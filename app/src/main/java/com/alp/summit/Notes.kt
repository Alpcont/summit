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
fun Activity.openNote(note: Note?, developer: Boolean = false, pin: LocalDate? = null) {
    val i = Intent(this, NoteActivity::class.java)
    if (note != null) i.putExtra("id", note.id) else {
        i.putExtra("developer", developer)
        pin?.let { i.putExtra("pin", it.toString()) }
    }
    startActivity(i)
}

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
        b.setOnClickListener { a.openNote(null, prefs(a).getBoolean("notesDeveloper", false)) }
        val lp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        lp.marginEnd = dp(20)
        // Above the tab bar and the system navigation bar.
        val bottomInset = a.findViewById<View>(R.id.root)?.paddingBottom ?: 0
        lp.bottomMargin = dp(58) + bottomInset + dp(18)
        content.addView(b, lp)
        fab = b
    }

    fun build(list: LinearLayout) {
        val p = prefs(a)
        val developer = p.getBoolean("notesDeveloper", false)
        val today = LocalDate.now()

        val title = a.mtext("Notes", 28f, 700, c(R.color.text_primary))
        title.letterSpacing = -0.02f
        list.addView(title)

        // Productivity / Developer.
        val seg = LinearLayout(a)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(if (light) 0xFFEFE9E0.toInt() else c(R.color.surface), dp(14))
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((label, value) in listOf("Productivity" to false, "Developer" to true)) {
            val on = developer == value
            val b = a.mtext(label, 13.5f, if (on) 700 else 600, c(if (on) R.color.text_primary else R.color.text_secondary))
            b.gravity = Gravity.CENTER
            if (on) {
                b.background = rounded(if (light) 0xFFFFFFFF.toInt() else c(R.color.line), dp(10))
                b.elevation = dp(1).toFloat()
            }
            b.setOnClickListener {
                p.edit().putBoolean("notesDeveloper", value).apply()
                query = ""
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

        if (!developer) {
            val notes = Store.notes.items.filter { !it.developer }
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
        val cbl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cbl.topMargin = dp(14)
        content.addView(copyBar, cbl)

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
        if (n.developer) {
            val (fg, bg) = tagColors(n.type)
            tag(typeName(n.type).uppercase(), fg, bg)
            if (!n.open) tag("DONE", c(R.color.text_secondary), withAlpha(c(R.color.text_primary), 0x10))
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
        if (n.items.isNotEmpty()) {
            val done = n.items.count { it.done }
            val tv = a.mtext("$done/${n.items.size} ticked", 12f, 600, c(R.color.text_dim))
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
        }
        setup(if (note.developer) "Developer note" else "Note")
        if (!isNew) savedHash = note.hash()

        titleInput = EditText(this)
        titleInput.hint = if (note.developer) "What is it? e.g. Widget does not refresh" else "Title"
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

        label(body, "Checklist")
        checklist = LinearLayout(this)
        checklist.orientation = LinearLayout.VERTICAL
        body.addView(checklist)
        addItemRow()

        options = LinearLayout(this)
        options.orientation = LinearLayout.VERTICAL
        body.addView(options)
        renderChecklist()
        renderOptions()

        if (!isNew) {
            val del = text(body, "Delete note", 15f, R.color.danger, 26)
            del.setPadding(0, dp(26), 0, dp(10))
            del.setOnClickListener {
                AlertDialog.Builder(this).setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleted = true
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
        if (note.developer) {
            label(options, "Type")
            val types = FlowLayout(this)
            for ((key, name) in TYPES) choice(types, name, note.type == key, if (key == "bug") getColor(R.color.danger) else getColor(R.color.accent)) { note.type = key }
            options.addView(types)
            label(options, "Status")
            val status = FlowLayout(this)
            choice(status, "Open", note.open) { note.open = true }
            choice(status, "Done", !note.open) { note.open = false }
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

    override fun onPause() {
        super.onPause()
        if (!deleted) store()
    }

    /** Keeps what was typed. A brand-new note with nothing in it is not kept. */
    private fun store() {
        note.title = titleInput.text.toString().trim()
        note.text = textInput.text.toString().trimEnd()
        val empty = note.title.isEmpty() && note.text.isEmpty() && note.items.isEmpty()
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
