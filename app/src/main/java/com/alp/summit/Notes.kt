package com.alp.summit

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.UUID

private val TYPES = listOf("bug" to "Bug", "idea" to "Idea", "change" to "Change")

fun typeName(type: String): String = TYPES.firstOrNull { it.first == type }?.second ?: "Idea"

/** Opens a note; `developer` decides the kind of a new one. */
fun Activity.openNote(note: Note?, developer: Boolean = false, pin: LocalDate? = null, course: String = "", kind: String = "plain") {
    val i = Intent(this, NoteActivity::class.java)
    if (note != null) i.putExtra("id", note.id) else {
        i.putExtra("developer", developer)
        pin?.let { i.putExtra("pin", it.toString()) }
        if (course.isNotEmpty()) i.putExtra("course", course).putExtra("kind", kind)
    }
    startActivity(i)
}

/** The kinds of a course note. */
val KINDS = listOf("homework" to "Homework", "lecture" to "Lecture notes", "exam" to "Exam topics", "plain" to "Note")

fun kindName(kind: String): String = KINDS.firstOrNull { it.first == kind }?.second ?: "Note"

/** The exam topics of one exam, from its "Exam topics" notes: titles, lines and checklist items. */
fun examTopics(courseId: String, examId: String): List<String> {
    val out = ArrayList<String>()
    for (n in Store.notes.items) if (n.courseId == courseId && n.kind == "exam" && n.examId == examId) {
        if (n.title.isNotBlank()) out.add(n.title.trim())
        for (line in n.text.lines()) noteLine(line)?.let { out.add(it) }
        for (c in n.items) if (!c.done) out.add(c.text)
    }
    return out.distinct()
}

/** A line of a note as a task title: without bullets, or null for an empty line. */
fun noteLine(line: String): String? = line.trim().trimStart('-', '•', '*', '·', '–', ' ').trim().takeIf { it.isNotEmpty() }

/** A small card for a note, used on the Notes tab, in the Day view and in evening planning. */
fun Activity.noteCard(n: Note, today: LocalDate, showPin: Boolean = true): View {
    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.background = rippled(rounded(getColor(R.color.surface), dp(16)), getColor(R.color.text_primary))
    card.setPadding(dp(16), dp(12), dp(16), dp(12))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(8)
    card.layoutParams = lp
    val title = TextView(this)
    title.text = n.title.ifBlank { n.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Untitled note" }
    title.textSize = 16f
    title.maxLines = 2
    title.ellipsize = android.text.TextUtils.TruncateAt.END
    title.setTextColor(getColor(if (n.developer && !n.open) R.color.text_dim else R.color.text_primary))
    if (n.developer && !n.open) title.paintFlags = title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
    card.addView(title)
    val body = n.text.trim().let { if (n.title.isBlank()) it.substringAfter('\n', "").trim() else it }
    if (body.isNotEmpty()) {
        val tv = TextView(this)
        tv.text = body
        tv.textSize = 14f
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setTextColor(getColor(R.color.text_secondary))
        tv.setPadding(0, dp(3), 0, 0)
        card.addView(tv)
    }
    val parts = ArrayList<String>()
    if (n.developer) {
        parts.add(typeName(n.type))
        parts.add(if (n.open) "Open" else "Done")
    }
    if (n.items.isNotEmpty()) parts.add("${n.items.count { it.done }}/${n.items.size} ticked")
    val pin = n.pin
    if (showPin && pin != null) parts.add("Pinned to " + relDayInline(pin, today))
    if (parts.isNotEmpty()) {
        val meta = TextView(this)
        meta.text = parts.joinToString("  ·  ")
        meta.textSize = 12f
        meta.setTextColor(getColor(if (n.developer && n.type == "bug" && n.open) R.color.danger else R.color.text_dim))
        meta.setPadding(0, dp(6), 0, 0)
        card.addView(meta)
    }
    card.setOnClickListener { openNote(n) }
    return card
}

/** All open developer notes as one tidy prompt for Claude, grouped by type, with the app version. */
fun claudePrompt(ctx: Context): Pair<String, Int> {
    val open = Store.notes.items.filter { it.developer && it.open }.sortedBy { it.created }
    val info = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    } catch (e: Exception) {
        null
    }
    val version = info?.versionName ?: "?"
    @Suppress("DEPRECATION")
    val build = if (info == null) "?" else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
    val sb = StringBuilder()
    sb.append("Update my Android app Summit (repo: Alpcont/summit). Follow the repo's CLAUDE.md.\n")
    sb.append("I'm on version $version (versionCode $build). These are my open notes about the app:\n")
    for ((type, name) in TYPES) {
        val group = open.filter { it.type == type }
        if (group.isEmpty()) continue
        sb.append("\n").append(if (name == "Change") "CHANGES" else name.uppercase() + "S").append("\n")
        for ((i, n) in group.withIndex()) {
            val head = n.title.trim().ifEmpty { n.text.trim().lineSequence().firstOrNull() ?: "" }
            sb.append(i + 1).append(". ").append(head).append("\n")
            val rest = if (n.title.isBlank()) n.text.trim().substringAfter('\n', "").trim() else n.text.trim()
            for (line in rest.lines()) if (line.isNotBlank()) sb.append("   ").append(line.trim()).append("\n")
            for (c in n.items) sb.append("   - ").append(if (c.done) "[done] " else "").append(c.text).append("\n")
        }
    }
    sb.append("\nKeep all existing features and saved data working. When done: the build must pass on GitHub Actions; ")
    sb.append("tell me what changed, the download link, and a short checklist to test on my phone.")
    return Pair(sb.toString(), open.size)
}

// ---------- The Notes tab ----------

class NotesPage(private val a: Activity, private val rerender: () -> Unit) {
    private var query = ""
    private var fab: View? = null
    private val light get() = Look.light
    private fun c(id: Int) = a.getColor(id)
    private fun dp(v: Int) = a.dp(v)

    /** A raised white card on the light theme, the next surface up on the dark ones. */
    private fun raised(): Int = if (light) 0xFFFFFFFF.toInt() else c(R.color.surface)

    /** The round "+" in the corner, only while the Notes tab is showing. */
    fun showFab(show: Boolean) {
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = fab
        if (!show) {
            if (existing != null) content.removeView(existing)
            fab = null
            return
        }
        if (existing != null) return
        val b = ImageView(a)
        b.setImageResource(R.drawable.ic_add)
        b.setColorFilter(c(R.color.on_accent))
        b.scaleType = ImageView.ScaleType.CENTER
        b.background = rippled(rounded(c(R.color.accent), dp(18)), c(R.color.on_accent))
        b.elevation = dp(6).toFloat()
        b.contentDescription = "New note"
        b.setOnClickListener { addNote() }
        val lp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        lp.marginEnd = dp(20)
        // Above the tab bar and the system navigation bar.
        val bottomInset = a.findViewById<View>(R.id.root)?.paddingBottom ?: 0
        lp.bottomMargin = dp(58) + bottomInset + dp(18)
        content.addView(b, lp)
        fab = b
    }

    private var notebook: String? = null      // the course notebook that is open, or null for the list

    /** "productivity", "courses" or "developer". */
    private fun mode(): String = prefs(a).getString("notesMode", null)
        ?: if (prefs(a).getBoolean("notesDeveloper", false)) "developer" else "productivity"

    /** The + button: a note of the kind that is showing. In Courses, ask for the course (if none is open) and the kind. */
    private fun addNote() {
        when (mode()) {
            "developer" -> a.openNote(null, true)
            "productivity" -> a.openNote(null, false)
            else -> {
                val courses = Courses.active()
                if (courses.isEmpty()) {
                    Toast.makeText(a, "Add a course first (cap icon on Today).", Toast.LENGTH_LONG).show()
                    return
                }
                fun kindFor(courseId: String) {
                    AlertDialog.Builder(a).setTitle("New entry").setItems(KINDS.map { it.second }.toTypedArray()) { _, i ->
                        a.openNote(null, course = courseId, kind = KINDS[i].first)
                    }.show()
                }
                val open = notebook
                if (open != null) kindFor(open)
                else AlertDialog.Builder(a).setTitle("Which course?").setItems(courses.map { it.code }.toTypedArray()) { _, i ->
                    kindFor(courses[i].id)
                }.show()
            }
        }
    }

    fun build(list: LinearLayout) {
        val p = prefs(a)
        val developer = mode() == "developer"
        val today = LocalDate.now()

        val title = a.mtext("Notes", 28f, 700, c(R.color.text_primary))
        title.letterSpacing = -0.02f
        list.addView(title)

        // Productivity / Courses / Developer.
        val seg = LinearLayout(a)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(if (light) 0xFFEFE9E0.toInt() else c(R.color.surface), dp(14))
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((label, value) in listOf("Productivity" to "productivity", "Courses" to "courses", "Developer" to "developer")) {
            val on = mode() == value
            val b = a.mtext(label, 13.5f, if (on) 700 else 600, c(if (on) R.color.text_primary else R.color.text_secondary))
            b.gravity = Gravity.CENTER
            if (on) {
                b.background = rounded(if (light) 0xFFFFFFFF.toInt() else c(R.color.line), dp(10))
                b.elevation = dp(1).toFloat()
            }
            b.setOnClickListener {
                p.edit().putString("notesMode", value).apply()
                query = ""
                notebook = null
                rerender()
            }
            seg.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        val segLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        segLp.topMargin = dp(14)
        list.addView(seg, segLp)

        // Search over all notes; the results update while typing without rebuilding the field.
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(raised(), dp(14))
        bar.elevation = if (light) dp(1).toFloat() else 0f
        bar.setPadding(dp(14), 0, dp(8), 0)
        val glass = ImageView(a)
        glass.setImageResource(R.drawable.ic_search)
        glass.setColorFilter(c(R.color.text_dim))
        bar.addView(glass, LinearLayout.LayoutParams(dp(18), dp(18)))
        val search = EditText(a)
        search.hint = "Search notes"
        search.textSize = 15f
        search.typeface = Fonts.manrope(a, 500)
        search.maxLines = 1
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.background = null
        search.setPadding(dp(10), 0, 0, 0)
        search.setTextColor(c(R.color.text_primary))
        search.setHintTextColor(c(R.color.text_dim))
        search.setText(query)
        bar.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        val sLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        sLp.topMargin = dp(14)
        list.addView(bar, sLp)

        val content = LinearLayout(a)
        content.orientation = LinearLayout.VERTICAL
        list.addView(content)
        fill(content, developer, today)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                fill(content, developer, today)
            }
        })
    }

    private fun empty(into: LinearLayout, text: String) {
        val tv = a.mtext(text, 14.5f, 500, c(R.color.text_dim))
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        tv.setPadding(0, dp(16), 0, dp(8))
        into.addView(tv)
    }

    private fun section(into: LinearLayout, text: String) {
        val tv = a.mtext(text.uppercase(), 11f, 700, c(R.color.text_dim))
        tv.letterSpacing = 0.08f
        tv.setPadding(0, dp(20), 0, dp(2))
        into.addView(tv)
    }

    private fun matches(n: Note, q: String): Boolean =
        n.title.contains(q, true) || n.text.contains(q, true) || n.items.any { it.text.contains(q, true) }

    private fun fill(content: LinearLayout, developer: Boolean, today: LocalDate) {
        content.removeAllViews()
        val q = query.trim()
        if (q.isNotEmpty()) {
            val found = Store.notes.items.filter { matches(it, q) }.sortedByDescending { it.updated }
            section(content, plural(found.size, "note") + " found")
            if (found.isEmpty()) empty(content, "Nothing matches \"$q\".")
            for (n in found) content.addView(card(n, today))
            return
        }

        if (mode() == "courses") {
            courses(content, today)
            return
        }
        if (!developer) {
            val notes = Store.notes.items.filter { !it.developer && !it.isCourse }
            val pinned = notes.filter { val p = it.pin; p != null && !p.isBefore(today) }.sortedBy { it.pin }
            val rest = notes.filter { it !in pinned }.sortedByDescending { it.updated }
            if (notes.isEmpty()) empty(content, "Ideas, lists, plans. Pin a note to a day to see it in that day's plan. Tap + to start one.")
            if (pinned.isNotEmpty()) {
                section(content, "Pinned to a day")
                for (n in pinned) content.addView(card(n, today))
            }
            if (rest.isNotEmpty()) {
                if (pinned.isNotEmpty()) section(content, "Other notes")
                for (n in rest) content.addView(card(n, today))
            }
            content.addView(View(a), LinearLayout.LayoutParams(1, dp(80)))   // room for the + button
            return
        }

        // Developer: the Copy for Claude bar, filter chips, then the notes.
        val notes = Store.notes.items.filter { it.developer }
        val open = notes.filter { it.open }
        val copyBar = LinearLayout(a)
        copyBar.orientation = LinearLayout.HORIZONTAL
        copyBar.gravity = Gravity.CENTER_VERTICAL
        // Dark ink on the light theme; on Dark and Grey a warm, slightly orange-tinted surface.
        val barBg = if (light) 0xFF1A1714.toInt() else mix(c(R.color.surface), c(R.color.accent), 0.16f)
        val barFg = if (light) 0xFFFAF7F2.toInt() else c(R.color.text_primary)
        copyBar.background = rounded(barBg, dp(18))
        copyBar.setPadding(dp(16), dp(14), dp(14), dp(14))
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        words.addView(a.mtext(plural(open.size, "open note"), 15f, 700, barFg))
        val counts = TYPES.mapNotNull { (key, name) ->
            val n = open.count { it.type == key }
            if (n == 0) null else "$n " + (if (n == 1) name.lowercase() else name.lowercase() + "s")
        }
        val version = try {
            a.packageManager.getPackageInfo(a.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val sub = a.mtext((counts + listOfNotNull(version?.let { "Summit $it" })).joinToString("  ·  "), 12.5f, 500, withAlpha(barFg, 0xB8))
        sub.setPadding(0, dp(3), 0, 0)
        words.addView(sub)
        copyBar.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val copy = LinearLayout(a)
        copy.orientation = LinearLayout.HORIZONTAL
        copy.gravity = Gravity.CENTER
        copy.background = rippled(rounded(0xFFFF8552.toInt(), dp(20)), 0xFF1A0D06.toInt())
        copy.setPadding(dp(14), 0, dp(14), 0)
        val ci = ImageView(a)
        ci.setImageResource(R.drawable.ic_copy)
        ci.setColorFilter(0xFF1A0D06.toInt())
        copy.addView(ci, LinearLayout.LayoutParams(dp(16), dp(16)))
        val ct = a.mtext("Copy for Claude", 13.5f, 800, 0xFF1A0D06.toInt())
        ct.setPadding(dp(6), 0, 0, 0)
        copy.addView(ct)
        copy.alpha = if (open.isEmpty()) 0.45f else 1f
        copy.setOnClickListener {
            if (open.isEmpty()) {
                Toast.makeText(a, "No open developer notes to copy.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (prompt, count) = claudePrompt(a)
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Summit notes for Claude", prompt))
            copy.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            Toast.makeText(a, "Copied ${plural(count, "open note")}. Paste it in Claude. Mark notes Done when they are fixed.",
                Toast.LENGTH_LONG).show()
        }
        copyBar.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        // What's new: every version and what changed, with my notes it solved.
        val news = a.mtext("What's new  ›", 13.5f, 700, c(R.color.accent))
        news.setPadding(0, dp(14), dp(16), 0)
        news.setOnClickListener { a.startActivity(Intent(a, WhatsNewActivity::class.java)) }
        val cbl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cbl.topMargin = dp(14)
        content.addView(copyBar, cbl)
        content.addView(news)

        // Filter chips: Open, Bug, Idea, Change, Done.
        val p = prefs(a)
        val filter = p.getString("notesFilter", "open") ?: "open"
        val chips = HorizontalScrollView(a)
        chips.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        chips.addView(row)
        for ((key, label) in listOf("open" to "Open", "bug" to "Bug", "idea" to "Idea", "change" to "Change", "done" to "Done")) {
            val on = filter == key
            val chip = a.mtext(label, 13f, if (on) 700 else 600, if (on) barFg else c(R.color.text_secondary))
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14), 0, dp(14), 0)
            chip.background = if (on) rounded(barBg, dp(17)) else rounded(0, dp(17), if (light) 0xFFE2DACE.toInt() else c(R.color.line), dp(1))
            chip.setOnClickListener {
                p.edit().putString("notesFilter", key).apply()
                fill(content, developer, today)
            }
            val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            l.marginEnd = dp(8)
            row.addView(chip, l)
        }
        val chl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chl.topMargin = dp(14)
        content.addView(chips, chl)

        val shown = when (filter) {
            "done" -> notes.filter { !it.open }.sortedByDescending { it.updated }
            "open" -> open.sortedBy { it.created }
            else -> open.filter { it.type == filter }.sortedBy { it.created }
        }
        if (notes.isEmpty()) empty(content, "Bugs, ideas and changes for Summit itself. \"Copy for Claude\" turns the open ones into one prompt. Tap + to add one.")
        else if (shown.isEmpty()) empty(content, if (filter == "done") "Nothing marked Done yet." else "No open notes here.")
        for (n in shown) content.addView(card(n, today))
        content.addView(View(a), LinearLayout.LayoutParams(1, dp(80)))
    }

    // ----- Course notebooks -----

    private fun courses(content: LinearLayout, today: LocalDate) {
        val list = Courses.active()
        val open = list.firstOrNull { it.id == notebook }
        if (open == null) {
            notebook = null
            if (list.isEmpty()) empty(content, "No courses yet. Add them with the cap icon on Today; each one gets a notebook here.")
            for (c in list) content.addView(notebookCard(c, today))
            content.addView(View(a), LinearLayout.LayoutParams(1, dp(80)))
            return
        }
        val color = Look.tone(open.color)
        val back = a.mtext("‹  All courses", 13.5f, 700, c(R.color.text_secondary))
        back.setPadding(0, dp(16), dp(16), dp(4))
        back.setOnClickListener {
            notebook = null
            rerender()
        }
        content.addView(back)
        val head = a.mtext(open.code, 24f, 800, color)
        head.setPadding(0, dp(4), 0, 0)
        content.addView(head)
        val notes = Store.notes.items.filter { it.courseId == open.id }
        val (week, total) = Store.studyMinutes(open.id, today)
        val sub = a.mtext(plural(notes.size, "entry").replace("entrys", "entries") + "  ·  studied " + minutesText(week) +
            " this week, " + minutesText(total) + " in total", 12.5f, 500, c(R.color.text_secondary))
        sub.setPadding(0, dp(4), 0, 0)
        content.addView(sub)

        // Filter by kind.
        val p = prefs(a)
        val kind = p.getString("notesKind", "all") ?: "all"
        val chips = HorizontalScrollView(a)
        chips.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        chips.addView(row)
        for ((key, label) in listOf("all" to "All") + KINDS) {
            val on = kind == key
            val chip = a.mtext(label, 13f, if (on) 700 else 600, if (on) c(R.color.text_primary) else c(R.color.text_secondary))
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14), 0, dp(14), 0)
            chip.background = if (on) rounded(withAlpha(color, 0x2E), dp(17), withAlpha(color, 0x99), dp(1))
            else rounded(0, dp(17), if (light) 0xFFE2DACE.toInt() else c(R.color.line), dp(1))
            chip.setOnClickListener {
                p.edit().putString("notesKind", key).apply()
                rerender()
            }
            val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            l.marginEnd = dp(8)
            row.addView(chip, l)
        }
        val chl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chl.topMargin = dp(14)
        content.addView(chips, chl)

        val shown = notes.filter { kind == "all" || it.kind == kind }
        if (shown.isEmpty()) empty(content, "Nothing here yet. Tap + to add homework, lecture notes, exam topics or a note.")
        // Homework first (by due date), then the rest, newest first.
        val homework = shown.filter { it.kind == "homework" }.sortedWith(compareBy({ it.due ?: LocalDate.MAX }, { it.created }))
        val rest = shown.filter { it.kind != "homework" }.sortedByDescending { it.updated }
        if (homework.isNotEmpty() && kind == "all") section(content, "Homework")
        for (n in homework) content.addView(card(n, today))
        if (rest.isNotEmpty() && kind == "all" && homework.isNotEmpty()) section(content, "Notes")
        for (n in rest) content.addView(card(n, today))
        content.addView(View(a), LinearLayout.LayoutParams(1, dp(80)))
    }

    private fun notebookCard(course: Course, today: LocalDate): View {
        val color = Look.tone(course.color)
        val card = LinearLayout(a)
        card.orientation = LinearLayout.HORIZONTAL
        card.background = rippled(rounded(mix(raised(), color, if (light) 0.08f else 0.10f), dp(18), withAlpha(color, 0x55), dp(1)), color)
        card.elevation = if (light) dp(1).toFloat() else 0f
        val bar = View(a)
        bar.background = rounded(color, dp(3))
        val bl = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
        bl.setMargins(dp(10), dp(12), 0, dp(12))
        card.addView(bar, bl)
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        words.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        words.addView(a.mtext(course.code, 18f, 800, color))
        val notes = Store.notes.items.filter { it.courseId == course.id }
        val counts = KINDS.mapNotNull { (key, name) ->
            val n = notes.count { it.kind == key }
            if (n == 0) null else "$n " + name.lowercase()
        }
        val l1 = a.mtext(if (counts.isEmpty()) "Empty notebook" else counts.joinToString("  ·  "), 13f, 600, c(R.color.text_primary))
        l1.setPadding(0, dp(4), 0, 0)
        words.addView(l1)
        val due = notes.filter { it.kind == "homework" && it.due != null && !it.due!!.isBefore(today) }.minByOrNull { it.due!! }
        val exam = course.exams.filter { !it.date.isBefore(today) }.minByOrNull { it.date }
        val parts = ArrayList<String>()
        if (due != null) parts.add("Homework due " + Courses.countdown(due.due!!, today))
        if (exam != null) parts.add(exam.title.ifBlank { "Exam" } + " " + Courses.countdown(exam.date, today))
        if (parts.isNotEmpty()) {
            val l2 = a.mtext(parts.joinToString("  ·  "), 12.5f, 500, c(R.color.text_secondary))
            l2.setPadding(0, dp(3), 0, 0)
            words.addView(l2)
        }
        card.setOnClickListener {
            notebook = course.id
            rerender()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp
        return card
    }

    /** "Today, 09:12", "Yesterday", "24 Nov". */
    private fun stamp(ms: Long, today: LocalDate): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        val d = t.toLocalDate()
        return when (d) {
            today -> "Today, " + fmtTime(t.hour * 60 + t.minute)
            today.minusDays(1) -> "Yesterday"
            else -> dayMonth(d).let { if (d.year != today.year) "$it ${d.year}" else it }.replace(Regex("^(\\d+) (\\w{3})\\w*"), "$1 $2")
        }
    }

    /** The tag colours: (text, background). */
    private fun tagColors(type: String): Pair<Int, Int> = when (type) {
        "bug" -> if (light) 0xFFB42E2E.toInt() to 0xFFFCE8E6.toInt() else 0xFFFF8A80.toInt() to 0xFF3A1E1E.toInt()
        "change" -> if (light) 0xFF6A3FB8.toInt() to 0xFFEFE8FB.toInt() else 0xFFC7A8FF.toInt() to 0xFF2B2140.toInt()
        else -> if (light) 0xFF2459B3.toInt() to 0xFFE5EEFC.toInt() else 0xFF8DB8FF.toInt() to 0xFF1B2A44.toInt()
    }

    private fun card(n: Note, today: LocalDate): View {
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.background = rippled(rounded(raised(), dp(16)), c(R.color.text_primary))
        card.elevation = if (light) dp(1).toFloat() else 0f
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp

        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        fun tag(text: String, fg: Int, bg: Int) {
            val t = a.mtext(text, 11f, 800, fg)
            t.letterSpacing = 0.05f
            t.background = rounded(bg, dp(7))
            t.setPadding(dp(8), dp(3), dp(8), dp(3))
            val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            l.marginEnd = dp(8)
            top.addView(t, l)
        }
        val course = Store.courses.get(n.courseId)
        if (n.isCourse && course != null) {
            val col = Look.tone(course.color)
            if (notebook == null) tag(course.code, col, withAlpha(col, 0x22))
            tag(kindName(n.kind).uppercase(), col, withAlpha(col, 0x22))
        } else if (n.kind == "reflection") {
            tag("REFLECTION", c(R.color.accent), c(R.color.accent_soft))
        } else if (n.developer) {
            val (fg, bg) = tagColors(n.type)
            tag(typeName(n.type).uppercase(), fg, bg)
            if (!n.open) tag(if (n.doneIn.isNotEmpty()) "DONE IN " + n.doneIn else "DONE", c(R.color.text_secondary), withAlpha(c(R.color.text_primary), 0x10))
        } else {
            val pin = n.pin
            if (pin != null) tag("PINNED  ·  " + relDay(pin, today).uppercase(), c(R.color.accent), c(R.color.accent_soft))
        }
        top.addView(a.mtext(stamp(n.created, today), 12f, 500, c(R.color.text_dim)))
        card.addView(top)

        val head = n.title.ifBlank { n.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Untitled note" }
        val title = a.mtext(head, 15f, 700, c(if (n.developer && !n.open) R.color.text_secondary else R.color.text_primary))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(0, dp(8), 0, 0)
        if (n.developer && !n.open) title.paintFlags = title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        card.addView(title)
        val body = n.text.trim().let { if (n.title.isBlank()) it.substringAfter('\n', "").trim() else it }
        if (body.isNotEmpty()) {
            val tv = a.mtext(body, 13f, 500, c(R.color.text_secondary))
            tv.maxLines = 3
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setLineSpacing(dp(2).toFloat(), 1f)
            tv.setPadding(0, dp(6), 0, 0)
            card.addView(tv)
        }
        val extra = ArrayList<String>()
        n.due?.let { if (n.kind == "homework") extra.add("Due " + shortDay(it) + ", " + Courses.countdown(it, today)) }
        if (n.kind == "exam") course?.exams?.firstOrNull { it.id == n.examId }?.let {
            extra.add("For " + it.title.ifBlank { "the exam" } + ", " + shortDay(it.date))
        }
        if (n.photos.isNotEmpty()) extra.add(plural(n.photos.size, "photo"))
        if (n.items.isNotEmpty()) extra.add("${n.items.count { it.done }}/${n.items.size} ticked")
        if (extra.isNotEmpty()) {
            val tv = a.mtext(extra.joinToString("  ·  "), 12f, 600, c(R.color.text_dim))
            tv.setPadding(0, dp(8), 0, 0)
            card.addView(tv)
        }
        card.setOnClickListener { a.openNote(n) }
        return card
    }
}

// ---------- One note ----------

/** Writing a note. It is saved by itself when the screen closes; an empty new note is not kept. */
class NoteActivity : SheetActivity() {
    private lateinit var note: Note
    private var isNew = false
    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var checklist: LinearLayout
    private lateinit var options: LinearLayout
    private lateinit var photosBox: LinearLayout
    private lateinit var linesBox: LinearLayout
    private var deleted = false
    private var savedHash = 0          // the note as it was last kept; only real changes are saved
    private val today: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Store.notes.get(intent.getStringExtra("id"))
        isNew = found == null
        note = found ?: Note().also {
            it.developer = intent.getBooleanExtra("developer", false)
            it.pin = intent.getStringExtra("pin")?.let { s -> LocalDate.parse(s) }
            it.courseId = intent.getStringExtra("course") ?: ""
            if (it.courseId.isNotEmpty()) it.kind = intent.getStringExtra("kind") ?: "plain"
            if (it.kind == "homework") it.due = today.plusDays(7)
        }
        val course = Store.courses.get(note.courseId)
        setup(if (note.developer) "Developer note" else if (course != null) course.code + "  ·  " + kindName(note.kind) else "Note")
        if (!isNew) savedHash = note.hash()

        titleInput = EditText(this)
        titleInput.hint = when {
            note.developer -> "What is it? e.g. Widget does not refresh"
            note.kind == "homework" -> "Homework, e.g. Problem set 4"
            note.kind == "lecture" -> "Lecture, e.g. Week 6: game theory"
            note.kind == "exam" -> "Topic, e.g. Nash equilibrium"
            else -> "Title"
        }
        titleInput.textSize = 20f
        titleInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        titleInput.setTextColor(getColor(R.color.text_primary))
        titleInput.setHintTextColor(getColor(R.color.text_dim))
        titleInput.background = null
        titleInput.setText(note.title)
        body.addView(titleInput)

        textInput = EditText(this)
        textInput.hint = if (note.developer) "Details: what happens, what you expected, where" else "Write anything"
        textInput.textSize = 16f
        textInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        textInput.minLines = 4
        textInput.gravity = Gravity.TOP or Gravity.START
        textInput.setTextColor(getColor(R.color.text_primary))
        textInput.setHintTextColor(getColor(R.color.text_dim))
        textInput.background = rounded(getColor(R.color.surface), dp(14))
        textInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        textInput.setText(note.text)
        val tLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.topMargin = dp(8)
        body.addView(textInput, tLp)

        // Photos (lecture notes and any course note that has some): slides, the whiteboard.
        photosBox = LinearLayout(this)
        photosBox.orientation = LinearLayout.VERTICAL
        body.addView(photosBox)
        renderPhotos()

        label(body, "Checklist")
        checklist = LinearLayout(this)
        checklist.orientation = LinearLayout.VERTICAL
        body.addView(checklist)
        addItemRow()

        options = LinearLayout(this)
        options.orientation = LinearLayout.VERTICAL
        body.addView(options)
        // Course notes: any line can become a task.
        linesBox = LinearLayout(this)
        linesBox.orientation = LinearLayout.VERTICAL
        body.addView(linesBox)
        renderChecklist()
        renderOptions()
        renderLines()
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                linesBox.removeCallbacks(linesLater)
                linesBox.postDelayed(linesLater, 600)
            }
        })

        if (!isNew) {
            val del = text(body, "Delete note", 15f, R.color.danger, 26)
            del.setPadding(0, dp(26), 0, dp(10))
            del.setOnClickListener {
                AlertDialog.Builder(this).setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleted = true
                        for (id in note.photos) Sync.deletePhoto(this, id)
                        Store.notes.get(note.id)?.let { Store.notes.remove(it) }
                        Store.save()
                        finish()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        action.visibility = View.VISIBLE
        action.text = "Done"
        action.setOnClickListener { finish() }
        if (isNew) titleInput.requestFocus()
    }

    private fun addItemRow() {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this)
        input.hint = "Add an item"
        input.textSize = 15f
        input.maxLines = 1
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(getColor(R.color.text_dim))
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val plus = ImageButton(this)
        plus.setImageResource(R.drawable.ic_add)
        plus.setColorFilter(getColor(R.color.accent))
        plus.background = null
        plus.contentDescription = "Add item"
        bar.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        fun add() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return
            note.items.add(CheckItem(UUID.randomUUID().toString(), text, false))
            input.setText("")
            renderChecklist()
            renderLines()
        }
        plus.setOnClickListener { add() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                add()
                true
            } else false
        }
        body.addView(bar)
    }

    private fun renderChecklist() {
        checklist.removeAllViews()
        for (item in note.items) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val box = BoxView(this)
            box.boxSize = 18f
            box.setChecked(item.done, false)
            line.addView(box, LinearLayout.LayoutParams(dp(40), dp(40)))
            val title = TitleView(this)
            title.text = item.text
            title.textSize = 15f
            title.setStruck(item.done, false)
            line.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = ImageButton(this)
            remove.setImageResource(R.drawable.ic_close)
            remove.setColorFilter(getColor(R.color.text_dim))
            remove.background = null
            remove.contentDescription = "Remove item"
            remove.setOnClickListener {
                note.items.remove(item)
                renderChecklist()
            }
            line.addView(remove, LinearLayout.LayoutParams(dp(40), dp(40)))
            line.setOnClickListener {
                item.done = !item.done
                box.setChecked(item.done, true)
                title.setStruck(item.done, true)
                box.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
            box.setOnClickListener { line.performClick() }
            checklist.addView(line)
        }
    }

    private fun renderOptions() {
        options.removeAllViews()
        fun choice(row: FlowLayout, text: String, on: Boolean, tint: Int = getColor(R.color.accent), act: () -> Unit) {
            val c = chip(text, on, tint)
            c.setOnClickListener {
                act()
                renderOptions()
            }
            row.addView(c)
        }
        if (note.isCourse) {
            courseOptions(::choice)
        } else if (note.developer) {
            label(options, "Type")
            val types = FlowLayout(this)
            for ((key, name) in TYPES) choice(types, name, note.type == key, if (key == "bug") getColor(R.color.danger) else getColor(R.color.accent)) { note.type = key }
            options.addView(types)
            label(options, "Status")
            val status = FlowLayout(this)
            choice(status, "Open", note.open) {
                note.open = true
                note.doneIn = ""
            }
            choice(status, "Done", !note.open) {
                // Remembered for the changelog: the version this was solved in.
                if (note.open) note.doneIn = appVersion(this)
                note.open = false
            }
            options.addView(status)
            text(options, "Open notes go into \"Copy for Claude\". Mark a note Done once the update has it.", 13f, R.color.text_dim, 10)
        } else {
            label(options, "Pin to a day")
            val pins = FlowLayout(this)
            val pin = note.pin
            val tomorrow = today.plusDays(1)
            choice(pins, "No day", pin == null) { note.pin = null }
            choice(pins, "Today", pin == today) { note.pin = today }
            choice(pins, "Tomorrow", pin == tomorrow) { note.pin = tomorrow }
            val other = pin != null && pin != today && pin != tomorrow
            val pick = chip(if (other && pin != null) shortDay(pin) else "Pick a date", other)
            pick.setOnClickListener {
                pickDay(pin ?: today) {
                    note.pin = it
                    renderOptions()
                }
            }
            pins.addView(pick)
            options.addView(pins)
            text(options, "A pinned note shows in that day's Day view and in the evening planning before it.", 13f, R.color.text_dim, 10)
        }
    }

    // ---------- Course notes ----------

    private val linesLater = Runnable { renderLines() }

    /** Kind, due date and "Make it a task" (homework), the exam (exam topics). */
    private fun courseOptions(choice: (FlowLayout, String, Boolean, Int, () -> Unit) -> Unit) {
        val course = Store.courses.get(note.courseId) ?: return
        val color = Look.tone(course.color)
        label(options, "Kind")
        val kinds = FlowLayout(this)
        for ((key, name) in KINDS) choice(kinds, name, note.kind == key, color) {
            note.kind = key
            if (key == "homework" && note.due == null) note.due = today.plusDays(7)
            renderPhotos()
        }
        options.addView(kinds)
        when (note.kind) {
            "homework" -> {
                label(options, "Due")
                val due = FlowLayout(this)
                val d = note.due
                val tomorrow = today.plusDays(1)
                choice(due, "Today", d == today, color) { note.due = today }
                choice(due, "Tomorrow", d == tomorrow, color) { note.due = tomorrow }
                val other = d != null && d != today && d != tomorrow
                val pick = chip(if (other && d != null) shortDay(d) else "Pick a date", other, color)
                pick.setOnClickListener {
                    pickDay(d ?: today) {
                        note.due = it
                        renderOptions()
                    }
                }
                due.addView(pick)
                options.addView(due)
                val origin = "note|" + note.id + "|hw"
                val made = Store.tasks.firstOrNull { it.origin == origin }
                val b = chip(if (made != null) "✓  Task made  ·  open it" else "Make it a task for " + course.code, made == null, color)
                b.setOnClickListener {
                    if (made != null) {
                        startActivity(Intent(this, EditTaskActivity::class.java).putExtra("id", made.id))
                        return@setOnClickListener
                    }
                    store(force = true)
                    val t = Task()
                    t.title = note.title.ifBlank { "Homework" }
                    t.date = note.due ?: today
                    t.courseId = course.id
                    t.notes = note.text.trim()
                    t.origin = origin
                    Store.tasks.add(t)
                    Store.save()
                    Reminders.schedule(this)
                    Toast.makeText(this, "Added for " + relDayInline(t.date ?: today, today), Toast.LENGTH_SHORT).show()
                    renderOptions()
                }
                val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                bl.topMargin = dp(14)
                options.addView(b, bl)
            }
            "exam" -> {
                label(options, "For which exam")
                val exams = FlowLayout(this)
                if (course.exams.isEmpty()) text(options, "This course has no exams yet. Add one on the Courses screen (cap icon on Today).",
                    13f, R.color.text_dim)
                for (e in course.exams.sortedBy { it.date }) {
                    choice(exams, e.title.ifBlank { "Exam" } + "  ·  " + shortDay(e.date), note.examId == e.id, color) { note.examId = e.id }
                }
                options.addView(exams)
                text(options, "These topics show inside the study blocks Summit suggests before that exam.", 13f, R.color.text_dim, 10)
            }
        }
    }

    /** Every line of the note and every checklist item, each with "+ Task". */
    private fun renderLines() {
        linesBox.removeAllViews()
        if (note.developer) return
        val course = Store.courses.get(note.courseId)
        val lines = (textInput.text.toString().lines().mapNotNull { noteLine(it) } + note.items.map { it.text }).distinct()
        if (lines.isEmpty()) return
        label(linesBox, "Turn a line into a task")
        for (line in lines) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(3), 0, dp(3))
            val tv = TextView(this)
            tv.text = line
            tv.textSize = 14.5f
            tv.maxLines = 2
            tv.setTextColor(getColor(R.color.text_primary))
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val done = line in note.tasked
            val c = chip(if (done) "✓ Task" else "+ Task", !done, course?.let { Look.tone(it.color) } ?: getColor(R.color.accent))
            if (!done) c.setOnClickListener {
                store(force = true)
                val t = Task()
                t.title = line
                t.date = if (note.kind == "homework") note.due ?: today else note.pin?.takeIf { !it.isBefore(today) } ?: today
                t.courseId = course?.id ?: ""
                t.origin = "note|" + note.id
                Store.tasks.add(t)
                note.tasked.add(line)
                note.touch()
                Store.save()
                Reminders.schedule(this)
                c.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                Toast.makeText(this, "Task added for " + relDayInline(t.date ?: today, today), Toast.LENGTH_SHORT).show()
                renderLines()
            }
            row.addView(c)
            linesBox.addView(row)
        }
    }

    // ---------- Photos ----------

    private fun renderPhotos() {
        photosBox.removeAllViews()
        // Productivity notes and lecture notes take photos; other course notes show the ones they have.
        if (note.developer || (note.isCourse && note.kind != "lecture" && note.photos.isEmpty())) return
        label(photosBox, "Photos")
        val grid = FlowLayout(this)
        for (id in note.photos) {
            val f = Sync.photoFile(this, id)
            if (f.exists()) {
                val img = ImageView(this)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.background = rounded(getColor(R.color.surface), dp(12))
                img.clipToOutline = true
                img.contentDescription = "Photo"
                Photos.thumb(f, dp(200))?.let { img.setImageBitmap(it) }
                img.setOnClickListener { showPhoto(id) }
                grid.addView(img, ViewGroup.LayoutParams(dp(96), dp(96)))
            } else {
                // Taken on the other device: fetch it once.
                val wait = TextView(this)
                wait.text = if (Sync.account(this) != null) "Loading…" else "On the other device"
                wait.textSize = 11f
                wait.gravity = Gravity.CENTER
                wait.setTextColor(getColor(R.color.text_dim))
                wait.background = rounded(getColor(R.color.surface), dp(12))
                grid.addView(wait, ViewGroup.LayoutParams(dp(96), dp(96)))
                Sync.fetchPhoto(this, id) { ok -> if (ok && !isFinishing) runOnUiThread { renderPhotos() } }
            }
        }
        photosBox.addView(grid)
        val buttons = FlowLayout(this)
        buttons.setPadding(0, dp(10), 0, 0)
        val take = chip("Take a photo", true)
        take.setOnClickListener { takePhoto() }
        buttons.addView(take)
        val pick = chip("From gallery", false)
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                startActivityForResult(i, 42)
            } catch (e: Exception) {
                Toast.makeText(this, "No gallery app found.", Toast.LENGTH_SHORT).show()
            }
        }
        buttons.addView(pick)
        photosBox.addView(buttons)
    }

    private fun takePhoto() {
        val uri = PhotoProvider.newShot(this)
        val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try {
            startActivityForResult(i, 41)
        } catch (e: Exception) {
            Toast.makeText(this, "No camera app found.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val sources = ArrayList<android.net.Uri>()
        if (requestCode == 41) sources.add(android.net.Uri.fromFile(PhotoProvider.shotFile(this)))
        if (requestCode == 42) {
            val clip = data?.clipData
            if (clip != null) for (k in 0 until clip.itemCount) sources.add(clip.getItemAt(k).uri)
            else data?.data?.let { sources.add(it) }
        }
        if (sources.isEmpty()) return
        Toast.makeText(this, "Adding " + plural(sources.size, "photo") + "…", Toast.LENGTH_SHORT).show()
        // Shrinking photos takes a moment: off the main thread.
        Thread {
            val ids = sources.mapNotNull { u ->
                Photos.store(this) {
                    if (u.scheme == "file") java.io.FileInputStream(u.path ?: "") else contentResolver.openInputStream(u)
                }
            }
            runOnUiThread {
                note.photos.addAll(ids)
                store(force = true)
                for (id in ids) Sync.uploadPhoto(this, id)
                renderPhotos()
            }
        }.start()
    }

    private fun showPhoto(id: String) {
        val img = ImageView(this)
        img.adjustViewBounds = true
        img.setImageBitmap(Photos.thumb(Sync.photoFile(this, id), resources.displayMetrics.widthPixels))
        AlertDialog.Builder(this).setView(img)
            .setPositiveButton("Close", null)
            .setNeutralButton("Delete photo") { _, _ ->
                note.photos.remove(id)
                Sync.deletePhoto(this, id)
                store(force = true)
                renderPhotos()
            }.show()
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) store()
    }

    /** Keeps what was typed. A brand-new note with nothing in it is not kept (unless `force`). */
    private fun store(force: Boolean = false) {
        note.title = titleInput.text.toString().trim()
        note.text = textInput.text.toString().trimEnd()
        val empty = !force && note.title.isEmpty() && note.text.isEmpty() && note.items.isEmpty() && note.photos.isEmpty()
        val current = Store.notes.get(note.id)
        if (current == null) {
            if (empty) return
            Store.notes.add(note)
            isNew = false
        } else if (current !== note) {
            // The note changed on the other device while it was open here: this edit wins.
            Store.notes.items[Store.notes.items.indexOf(current)] = note
        }
        val h = note.hash()
        if (current != null && current === note && h == savedHash) return
        savedHash = h
        note.touch()
        Store.save()
    }
}

// ---------- Photos of lecture notes ----------

object Photos {
    private const val MAX_SIDE = 1600

    /**
     * Reads a photo (camera or gallery), turns it upright, shrinks it to at most 1600 px and saves it
     * as a JPEG of under about 850 KB in photos/. Returns its new id, or null if it could not be read.
     */
    fun store(ctx: Context, open: () -> java.io.InputStream?): String? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null) null else {
                val turn = try {
                    open()?.use {
                        when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                            6 -> 90f
                            3 -> 180f
                            8 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                } catch (e: Exception) {
                    0f
                }
                val big = maxOf(bmp.width, bmp.height)
                val scale = if (big > MAX_SIDE) MAX_SIDE.toFloat() / big else 1f
                if (scale < 1f || turn != 0f) {
                    val m = android.graphics.Matrix()
                    m.postScale(scale, scale)
                    m.postRotate(turn)
                    bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                var quality = 82
                var bytes: ByteArray
                do {
                    val out = java.io.ByteArrayOutputStream()
                    bmp!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    bytes = out.toByteArray()
                    quality -= 12
                } while (bytes.size > 850_000 && quality > 30)
                val id = UUID.randomUUID().toString()
                Sync.photoFile(ctx, id).writeBytes(bytes)
                id
            }
        }
    } catch (e: Exception) {
        null
    }

    /** A smaller copy for the screen, about `size` px on its shorter side. */
    fun thumb(f: java.io.File, size: Int): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

/**
 * Hands the camera app a file to save its photo into (content://com.alp.summit.photos/shot.jpg),
 * without needing any storage permission.
 */
class PhotoProvider : android.content.ContentProvider() {
    companion object {
        private const val AUTHORITY = "com.alp.summit.photos"

        fun shotFile(ctx: Context): java.io.File = java.io.File(java.io.File(ctx.cacheDir, "camera").also { it.mkdirs() }, "shot.jpg")

        fun newShot(ctx: Context): android.net.Uri {
            shotFile(ctx).delete()
            return android.net.Uri.parse("content://$AUTHORITY/shot.jpg")
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        val f = shotFile(ctx)
        val m = if (mode.contains('w')) android.os.ParcelFileDescriptor.MODE_READ_WRITE or
            android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
        else android.os.ParcelFileDescriptor.MODE_READ_ONLY
        return android.os.ParcelFileDescriptor.open(f, m)
    }

    override fun getType(uri: android.net.Uri): String = "image/jpeg"
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
