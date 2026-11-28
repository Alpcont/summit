package com.alp.summit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate

// ---------- Voice add ----------

/**
 * Opens the phone's speech input (English). The words come back in onActivityResult; see `heard`.
 * The listening itself is done by the phone's voice input (Google or Samsung), not by Summit.
 */
fun listen(a: Activity, code: Int) {
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task, e.g. \"gym tomorrow at 6pm for 45 minutes\"")
    try {
        a.startActivityForResult(i, code)
    } catch (e: Exception) {
        Toast.makeText(a, "No voice input on this device. Settings → General management → Keyboard → Voice input.", Toast.LENGTH_LONG).show()
    }
}

/** The best guess of what was said, or null. */
fun heard(data: Intent?): String? =
    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

// ---------- Share into Summit ----------

/**
 * Summit in Android's share menu for text, links and pictures. A small sheet: a new task (the
 * text through smart quick-add, the link kept in the notes) or a new note (Productivity or a
 * course notebook; pictures become the note's photos).
 */
class ShareActivity : SheetActivity() {
    private var text = ""
    private val images = ArrayList<Uri>()
    private val today: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Add to Summit")
        val i = intent
        text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
            .filter { it.isNotBlank() }.distinct().joinToString("\n").trim()
        @Suppress("DEPRECATION")
        if (i.action == Intent.ACTION_SEND) (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { images.add(it) }
        @Suppress("DEPRECATION")
        if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { images.addAll(it) }
        if (text.isEmpty() && images.isEmpty()) {
            finish()
            return
        }

        if (text.isNotEmpty()) {
            val shown = text(body, text, 15f, R.color.text_primary, 4)
            shown.maxLines = 6
            shown.background = rounded(getColor(R.color.surface), dp(14))
            shown.setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        if (images.isNotEmpty()) text(body, plural(images.size, "picture"), 14f, R.color.text_secondary, 10)

        // New task: the text through smart quick-add.
        val first = text.lineSequence().firstOrNull { it.isNotBlank() && !isLink(it) } ?: ""
        val parsed = parseQuick(first, today, Store.categories)
        if (text.isNotEmpty()) {
            label(body, "New task")
            text(body, parsed.title.ifBlank { linkTitle() }, 16f, R.color.text_primary)
            val summary = parsed.summary(today)
            text(body, if (summary.isNotEmpty()) summary else "Today", 13f, R.color.accent, 2)
            val add = chip("Add as a task", true)
            add.setOnClickListener { addTask(parsed) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            body.addView(add, lp)
        }

        // New note: Productivity or a course notebook.
        label(body, "New note")
        val where = FlowLayout(this)
        val prod = chip("Productivity", false)
        prod.setOnClickListener { addNote("") }
        where.addView(prod)
        for (c in Courses.active()) {
            val ch = chip(c.code, false, Look.tone(c.color))
            ch.setOnClickListener { addNote(c.id) }
            where.addView(ch)
        }
        body.addView(where)
    }

    private fun isLink(s: String) = s.trim().startsWith("http://") || s.trim().startsWith("https://")

    private fun linkTitle(): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.let {
        if (isLink(it)) "Look at " + (Uri.parse(it.trim()).host ?: "link") else it
    } ?: "Shared"

    private fun addTask(p: Parsed) {
        val t = Task()
        t.title = p.title.ifBlank { linkTitle() }
        t.date = if (p.hasDate) p.date else today
        t.time = p.time
        t.duration = p.duration
        if (p.priority != 0) t.priority = p.priority
        t.categoryId = p.categoryId
        t.repeatMask = p.repeatMask
        t.repeatRule = p.repeatRule
        // The whole shared text (with any link) stays in the task's notes.
        if (text.trim() != t.title) t.notes = text
        Store.tasks.add(t)
        Store.save()
        Reminders.schedule(this)
        Toast.makeText(this, "Task added for " + relDayInline(t.date ?: today, today), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun addNote(courseId: String) {
        val n = Note()
        n.courseId = courseId
        if (courseId.isNotEmpty()) n.kind = if (images.isNotEmpty()) "lecture" else "plain"
        val lines = text.lines()
        val head = lines.firstOrNull { it.isNotBlank() } ?: ""
        n.title = if (isLink(head) || head.length > 80) "" else head.trim()
        n.text = if (n.title.isEmpty()) text else lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
        Store.notes.add(n)
        Store.save()
        if (images.isEmpty()) {
            openNote(n)
            finish()
            return
        }
        Toast.makeText(this, "Adding " + plural(images.size, "picture") + "…", Toast.LENGTH_SHORT).show()
        action.visibility = View.GONE
        Thread {
            val ids = images.mapNotNull { u -> Photos.store(this) { contentResolver.openInputStream(u) } }
            runOnUiThread {
                n.photos.addAll(ids)
                n.touch()
                Store.save()
                for (id in ids) Sync.uploadPhoto(this, id)
                openNote(n)
                finish()
            }
        }.start()
    }
}
