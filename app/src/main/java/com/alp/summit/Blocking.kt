package com.alp.summit

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * App blocking during focus. Every app is blocked except the allowed ones; Summit itself, the
 * home screen, the keyboard, system screens and emergency calls always work. The list of allowed
 * apps is kept per device (installed apps differ between phone and tablet).
 */
object Blocking {
    // Always allowed, so nothing breaks.
    private val ALWAYS = setOf(
        "android", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.phone", "com.android.server.telecom",
        "com.android.emergency", "com.google.android.emergency", "com.samsung.android.emergency",
        "com.sec.android.emergencylauncher", "com.samsung.android.app.telephonyui", "com.android.incallui",
        "com.samsung.android.incallui", "com.android.dialer", "com.samsung.android.dialer", "com.google.android.dialer",
        "com.samsung.android.app.cocktailbarservice", "com.samsung.android.biometrics.app.setting",
        "com.android.settings.intelligence", "com.samsung.android.lool"
    )

    // Allowed unless switched off: phone and contacts, study apps, clock and calculator.
    private val DEFAULTS = listOf(
        "com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts",
        "com.sec.android.app.sbrowser", "com.android.chrome", "com.samsung.android.app.notes",
        "com.google.android.apps.docs", "com.adobe.reader", "com.google.android.apps.pdfviewer",
        "com.sec.android.app.clockpackage", "com.google.android.deskclock",
        "com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.samsung.android.calendar",
        "com.google.android.calendar"
    )

    /** Apps the user allowed (or the defaults, before the list was first changed). */
    fun allowed(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val saved = p.getStringSet("allowedApps", null)
        if (saved != null) return saved
        val out = HashSet(DEFAULTS)
        // Whatever this device opens web pages, PDFs and the dialler with also counts as a study or phone app.
        for (i in listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
            Intent(Intent.ACTION_VIEW).setType("application/pdf"),
            Intent(Intent.ACTION_DIAL),
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        )) {
            ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { out.add(it) }
        }
        return out
    }

    fun setAllowed(ctx: Context, set: Set<String>) {
        prefs(ctx).edit().putStringSet("allowedApps", HashSet(set)).apply()
    }

    private var homes: Set<String>? = null

    /** Home screens and keyboards of this device. */
    private fun systemSet(ctx: Context): Set<String> {
        homes?.let { return it }
        val out = HashSet<String>()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (r in ctx.packageManager.queryIntentActivities(home, 0)) out.add(r.activityInfo.packageName)
        ctx.getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { out.add(it.packageName) }
        homes = out
        return out
    }

    fun isBlocked(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in ALWAYS || pkg in systemSet(ctx)) return false
        return pkg !in allowed(ctx)
    }

    /** Whether the Accessibility service for blocking is switched on. */
    fun enabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, BlockService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Why Summit needs the Accessibility service, and how to switch it on. Shown from Settings, and once at the first session. */
    fun guide(a: Activity, then: (() -> Unit)? = null) {
        prefs(a).edit().putBoolean("askedBlocking", true).apply()
        AlertDialog.Builder(a)
            .setTitle("Block other apps during focus")
            .setMessage(
                "To block apps while you focus, Summit needs Android's Accessibility permission. Summit only " +
                    "uses it to see which app is opening, and only acts during a focus session. It reads nothing " +
                    "inside other apps and sends nothing anywhere.\n\n" +
                    "How to switch it on:\n" +
                    "1. Tap \"Open settings\" below.\n" +
                    "2. Tap \"Installed apps\" (or \"Downloaded apps\"), then Summit, and switch it on.\n\n" +
                    "If the switch is grey or says \"Restricted setting\": Android protects apps that were not " +
                    "installed from the Play Store. Go to Settings → Apps → Summit → ⋮ (top right) → " +
                    "\"Allow restricted settings\", then try step 2 again."
            )
            .setPositiveButton("Open settings") { _, _ ->
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App info") { _, _ ->
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.packageName)))
            }
            .setNegativeButton("Not now") { _, _ -> then?.invoke() }
            .setOnCancelListener { then?.invoke() }
            .show()
    }
}

/**
 * Sees which app comes to the front. During focus (not during a break), a blocked app is covered
 * by Summit's block screen right away. Nothing is read from inside other apps.
 */
class BlockService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Focus.blockingNow(this) || !Blocking.isBlocked(this, pkg)) return
        val now = System.currentTimeMillis()
        // One app opening sends several events: count it once.
        if (pkg != lastPkg || now - lastAt > 3000) Focus.countBlocked(this)
        lastPkg = pkg
        lastAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            startActivity(Intent(this, BlockActivity::class.java).putExtra("pkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }, 120)
    }

    override fun onInterrupt() {}
}

/** "This app is blocked while you focus": the time left and Back to focus. No way past it. */
class BlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var time: TextView
    private val tick = object : Runnable {
        override fun run() {
            if (!Focus.blockingNow(this@BlockActivity)) {
                finish()
                return
            }
            time.text = (if (Focus.onBreak(this@BlockActivity)) "Break: " else "") + Focus.clock(this@BlockActivity) +
                (if (Focus.target(this@BlockActivity) > 0) " left" else " of focus")
            handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Look.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(0xFF0B0C0E.toInt())
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        setContentView(root)
        fitSystem(root)
        val name = intent.getStringExtra("pkg")?.let { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                null
            }
        } ?: "This app"
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_peak)
        icon.setColorFilter(0xFFFF8552.toInt())
        root.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
        val t = mtext("$name is blocked while you focus", 24f, 700, 0xFFF2F3F5.toInt())
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(22), 0, dp(10))
        root.addView(t)
        time = mtext("", 46f, 300, 0xFFFF8552.toInt())
        time.gravity = Gravity.CENTER
        root.addView(time)
        val task = Focus.task(this)?.title
        val sub = mtext(if (task != null) "You're working on: $task" else "Stay with it. You've got this.", 15f, 500, 0xFF9096A0.toInt())
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, dp(12), 0, dp(36))
        root.addView(sub)
        val back = mtext("Back to focus", 16f, 800, 0xFF1A0D06.toInt())
        back.gravity = Gravity.CENTER
        back.background = rippled(rounded(0xFFFF8552.toInt(), dp(27)), 0xFF1A0D06.toInt())
        back.setOnClickListener {
            startActivity(Intent(this, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        root.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val note = mtext("The block ends when the timer ends, or when you stop the session in Summit.", 12f, 500, 0xFF5C616B.toInt())
        note.gravity = Gravity.CENTER
        note.setPadding(0, dp(16), 0, 0)
        root.addView(note)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back leads to the focus screen, never into the blocked app.
        startActivity(Intent(this, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** The installed apps with a switch each: which ones may open during focus. */
class AllowedAppsActivity : SheetActivity() {
    private class App(val pkg: String, val label: String, val system: Boolean)

    private lateinit var listBox: LinearLayout
    private var apps: List<App> = emptyList()
    private val allowed = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Allowed during focus")
        allowed.addAll(Blocking.allowed(this))
        text(body, "Every other app is blocked during a focus session (not during breaks). Summit, the home screen, " +
            "the keyboard, calls and system screens always work.", 14f, R.color.text_secondary)
        val search = EditText(this)
        search.hint = "Search apps"
        search.textSize = 15f
        search.setTextColor(getColor(R.color.text_primary))
        search.setHintTextColor(getColor(R.color.text_dim))
        search.background = rounded(getColor(R.color.surface), dp(14))
        search.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        body.addView(search, lp)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        body.addView(listBox)
        text(listBox, "Loading your apps…", 14f, R.color.text_dim, 16)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render(s?.toString() ?: "")
        })
        // Reading the app list takes a moment; do it off the main thread.
        Thread {
            val pm = packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launch, 0).map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { App(it.packageName, pm.getApplicationLabel(it).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = found
                render(search.text.toString())
            }
        }.start()
    }

    private fun render(query: String) {
        listBox.removeAllViews()
        val q = query.trim()
        val shown = apps.filter { q.isEmpty() || it.label.contains(q, true) }
        val on = shown.filter { it.pkg in allowed }
        val off = shown.filter { it.pkg !in allowed }
        if (on.isNotEmpty()) {
            label(listBox, "Allowed")
            for (a in on) line(a)
        }
        if (off.isNotEmpty()) {
            label(listBox, "Blocked during focus")
            for (a in off) line(a)
        }
    }

    private fun line(app: App) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.pkg))
        } catch (e: Exception) {
            // No icon.
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val name = TextView(this)
        name.text = app.label
        name.textSize = 15.5f
        name.setTextColor(getColor(R.color.text_primary))
        name.setPadding(dp(14), 0, dp(8), 0)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = app.pkg in allowed
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) allowed.add(app.pkg) else allowed.remove(app.pkg)
            Blocking.setAllowed(this, allowed)
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        listBox.addView(row)
    }
}
