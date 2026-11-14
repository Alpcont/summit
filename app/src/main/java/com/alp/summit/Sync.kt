package com.alp.summit

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Sync between phone and tablet through Firebase (the only file that talks to Firebase).
 *
 * The app keeps working from its own file on the device. This layer only copies changes:
 * - before every save, tasks whose content changed are sent up (users/<account>/tasks/<task id>);
 * - changes made on another device arrive through listeners and are merged in (see Store.applyRemote…).
 * If both devices changed the same task, the later change wins. Without a google-services.json
 * in the project, or when nobody is signed in, everything here quietly does nothing.
 */
object Sync {
    /** Called on the main thread after something arrived from another device. */
    var onRemoteChange: (() -> Unit)? = null
    var lastError = ""
        private set

    private val listeners = ArrayList<ListenerRegistration>()
    private var listeningFor: String? = null
    private var heardCategories = false   // the cloud's category list has been seen (or is known to be empty)

    /** True when the app was built with the Firebase project file. */
    fun configured(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun uid(ctx: Context): String? =
        if (configured(ctx)) FirebaseAuth.getInstance().currentUser?.uid else null

    /** The signed-in account, or null. */
    fun account(ctx: Context): String? {
        if (!configured(ctx)) return null
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return user.email ?: "Google account"
    }

    private fun userDoc(uid: String): DocumentReference =
        FirebaseFirestore.getInstance().collection("users").document(uid)

    // ---------- Receiving ----------

    /** Starts listening for changes from other devices. Safe to call often. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val uid = uid(app) ?: return
        if (listeningFor == uid) return
        stop()
        listeningFor = uid
        Store.load(app)
        val p = prefs(app)
        if (p.getString("syncUid", "") != uid) {
            // Another account than last time: start the exchange from scratch.
            Store.resetSync()
            p.edit().putString("syncUid", uid).apply()
        }
        val user = userDoc(uid)

        listeners.add(user.collection("tasks").addSnapshotListener { snap, e ->
            if (e != null) {
                lastError = e.message ?: "Sync problem"
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            lastError = ""
            var changed = false
            for (c in snap.documentChanges) {
                val doc = c.document
                if (doc.metadata.hasPendingWrites()) continue     // our own change on its way up
                if (Store.applyRemoteTask(doc.id, JSONObject(doc.data as Map<*, *>))) changed = true
            }
            if (changed) arrived(app)
        })

        listeners.add(user.collection("meta").document("categories").addSnapshotListener { doc, e ->
            if (e != null || doc == null || doc.metadata.hasPendingWrites()) return@addSnapshotListener
            val data = doc.data
            if (data == null) {
                // Nothing in the cloud yet. Only trust that when it comes from the server itself.
                if (!doc.metadata.isFromCache && !heardCategories) {
                    heardCategories = true
                    Store.save()
                }
                return@addSnapshotListener
            }
            val first = !heardCategories
            heardCategories = true
            if (Store.applyRemoteCategories(JSONObject(data as Map<*, *>)) || first) arrived(app)
        })

        listeners.add(user.collection("meta").document("focus").addSnapshotListener { doc, e ->
            if (e != null || doc == null || doc.metadata.hasPendingWrites()) return@addSnapshotListener
            val data = doc.data ?: return@addSnapshotListener
            if (Store.applyRemoteFocus(JSONObject(data as Map<*, *>))) arrived(app)
        })

        Store.save()   // sends whatever this device has that the cloud does not know yet
    }

    private fun stop() {
        for (l in listeners) l.remove()
        listeners.clear()
        listeningFor = null
        heardCategories = false
    }

    private fun arrived(app: Context) {
        Store.save()               // keeps it on the device; also sends back anything the merge changed
        Reminders.schedule(app)
        onRemoteChange?.invoke()
    }

    // ---------- Sending ----------

    /** Called by Store.save(): hands everything that changed since the last save to the cloud. */
    fun beforeSave(ctx: Context) {
        val uid = uid(ctx) ?: return
        try {
            val user = userDoc(uid)
            val tasks = user.collection("tasks")
            val now = System.currentTimeMillis()
            val alive = HashSet<String>()
            for (t in Store.tasks) {
                alive.add(t.id)
                val h = Store.contentHash(t)
                if (h == t.syncHash) continue
                if (t.syncHash != 0) t.updated = now      // changed since the last exchange
                t.syncHash = h
                Store.syncedIds.add(t.id)
                tasks.document(t.id).set(toMap(Store.taskJson(t).put("deleted", false)))
            }
            // A task the cloud knows but this device no longer has was deleted here.
            for (id in Store.syncedIds.filter { it !in alive }) {
                tasks.document(id).set(mapOf("deleted" to true, "updated" to now))
                Store.syncedIds.remove(id)
            }

            val catsHash = Store.categoriesHash()
            if (catsHash != Store.catsSyncHash && (Store.catsSyncHash != 0 || heardCategories)) {
                Store.catsUpdated = now
                Store.catsSyncHash = catsHash
                val items = ArrayList<Map<String, Any>>()
                for (c in Store.categories) items.add(mapOf("id" to c.id, "name" to c.name, "color" to c.color))
                user.collection("meta").document("categories").set(mapOf("items" to items, "updated" to now))
            }

            val focusHash = Store.focusHash()
            if (focusHash != Store.focusSyncHash) {
                Store.focusSyncHash = focusHash
                user.collection("meta").document("focus").set(mapOf("days" to HashMap(Store.focusLog), "updated" to now))
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Sync problem"
        }
    }

    /** JSON as written to the app's own file -> the plain maps and lists Firestore stores. */
    private fun toMap(j: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (key in j.keys()) out[key] = plain(j.get(key))
        return out
    }

    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    // ---------- Account ----------

    /** Shows Google's account chooser. `done` gets null on success, otherwise a message to show. */
    fun signIn(activity: Activity, done: (String?) -> Unit) {
        val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (!configured(activity)) {
            done("Sync is not set up yet.")
            return
        }
        if (id == 0) {
            // The Firebase file was downloaded before Google sign-in was switched on.
            done("Google sign-in is missing from the Firebase file. Enable Google under Authentication, download google-services.json again and replace it.")
            return
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(activity.getString(id)).build())
            .build()
        val main = Handler(Looper.getMainLooper())
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, CancellationSignal(), Executor { main.post(it) },
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val token = GoogleIdTokenCredential.createFrom(c.data).idToken
                        FirebaseAuth.getInstance()
                            .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    start(activity)
                                    done(null)
                                } else {
                                    done(task.exception?.message ?: "Sign-in failed.")
                                }
                            }
                    } else {
                        done("Google answered in an unexpected way.")
                    }
                }

                override fun onError(e: GetCredentialException) {
                    done("Sign-in did not finish: " + (e.message ?: e.type))
                }
            }
        )
    }

    /** Stops syncing on this device. The tasks stay on the device and in the cloud. */
    fun signOut(ctx: Context) {
        stop()
        if (configured(ctx)) FirebaseAuth.getInstance().signOut()
    }
}
