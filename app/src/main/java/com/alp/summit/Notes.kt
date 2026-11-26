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

    fun build(list: LinearLayout) {
        val p = prefs(a)
        val developer = p.getBoolean("notesDeveloper", false)
        val today = LocalDate.now()

        val title = TextView(a)
        title.text = "Notes"
        title.textSize = 36f
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        title.setTextColor(a.getColor(R.color.text_primary))
        list.addView(title)

        // The switch at the top.
        val modes = LinearLayout(a)
        modes.orientation = LinearLayout.HORIZONTAL
        modes.setPadding(0, a.dp(10), 0, a.dp(4))
        for ((label, value) in listOf("Productivity" to false, "Developer" to true)) {
            val c = a.chip(label, developer == value)
            c.setOnClickListener {
                p.edit().putBoolean("notesDeveloper", value).apply()
                query = ""
                rerender()
            }
            modes.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = a.dp(8)
        }
        list.addView(modes)

        // Search over all notes; the results update while typing without rebuilding the field.
        val search = EditText(a)
        search.hint = "Search all notes"
        search.textSize = 15f
        search.maxLines = 1
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.background = rounded(a.getColor(R.color.surface), a.dp(14))
        search.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
        search.setTextColor(a.getColor(R.color.text_primary))
        search.setHintTextColor(a.getColor(R.color.text_dim))
        search.setText(query)
        val sLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sLp.topMargin = a.dp(10)
        list.addView(search, sLp)

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

    private fun label(into: LinearLayout, text: String) {
        val tv = TextView(a)
        tv.text = text.uppercase()
        tv.textSize = 11f
        tv.letterSpacing = 0.14f
        tv.setTextColor(a.getColor(R.color.text_dim))
        tv.setPadding(0, a.dp(22), 0, a.dp(4))
        into.addView(tv)
    }

    private fun empty(into: LinearLayout, text: String) {
        val tv = TextView(a)
        tv.text = text
        tv.textSize = 15f
        tv.setLineSpacing(a.dp(3).toFloat(), 1f)
        tv.setTextColor(a.getColor(R.color.text_dim))
        tv.setPadding(0, a.dp(14), 0, a.dp(8))
        into.addView(tv)
    }

    private fun matches(n: Note, q: String): Boolean =
        n.title.contains(q, true) || n.text.contains(q, true) || n.items.any { it.text.contains(q, true) }

    private fun fill(content: LinearLayout, developer: Boolean, today: LocalDate) {
        content.removeAllViews()
        val q = query.trim()
        if (q.isNotEmpty()) {
            val found = Store.notes.items.filter { matches(it, q) }.sortedByDescending { it.updated }
            label(content, plural(found.size, "note") + " found")
            if (found.isEmpty()) empty(content, "Nothing matches \"$q\".")
            for (n in found) content.addView(a.noteCard(n, today))
            return
        }

        val add = a.chip(if (developer) "+  New developer note" else "+  New note", true)
        add.setPadding(a.dp(18), a.dp(11), a.dp(18), a.dp(11))
        add.setOnClickListener { a.openNote(null, developer) }
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, a.dp(14), 0, 0)
        row.addView(add)
        content.addView(row)

        if (!developer) {
            val notes = Store.notes.items.filter { !it.developer }
            val pinned = notes.filter { val p = it.pin; p != null && !p.isBefore(today) }.sortedBy { it.pin }
            val rest = notes.filter { it !in pinned }.sortedByDescending { it.updated }
            if (notes.isEmpty()) empty(content, "Ideas, lists, plans. Pin a note to a day to see it in that day's plan.")
            if (pinned.isNotEmpty()) {
                label(content, "Pinned to a day")
                for (n in pinned) content.addView(a.noteCard(n, today))
            }
            if (rest.isNotEmpty()) {
                if (pinned.isNotEmpty()) label(content, "Other notes")
                for (n in rest) content.addView(a.noteCard(n, today))
            }
            return
        }

        // Developer notes: open ones by type, then the done ones.
        val notes = Store.notes.items.filter { it.developer }
        val open = notes.filter { it.open }
        val copy = a.chip("Copy for Claude  (" + open.size + ")", open.isNotEmpty())
        copy.setPadding(a.dp(18), a.dp(11), a.dp(18), a.dp(11))
        copy.setOnClickListener {
            if (open.isEmpty()) {
                Toast.makeText(a, "No open developer notes to copy.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (prompt, count) = claudePrompt(a)
            val cm = a.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Summit notes for Claude", prompt))
            Toast.makeText(a, "Copied ${plural(count, "open note")}. Paste it in Claude. Mark notes Done when they are fixed.",
                Toast.LENGTH_LONG).show()
        }
        row.addView(copy)
        (copy.layoutParams as LinearLayout.LayoutParams).marginStart = a.dp(8)

        if (notes.isEmpty()) empty(content, "Bugs, ideas and changes for Summit itself. \"Copy for Claude\" turns the open ones into one prompt.")
        for ((type, name) in TYPES) {
            val group = open.filter { it.type == type }.sortedBy { it.created }
            if (group.isEmpty()) continue
            label(content, name + "s  ·  open")
            for (n in group) content.addView(a.noteCard(n, today))
        }
        val done = notes.filter { !it.open }.sortedByDescending { it.updated }
        if (done.isNotEmpty()) {
            label(content, "Done")
            for (n in done) content.addView(a.noteCard(n, today))
        }
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
