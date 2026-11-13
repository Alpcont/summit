package com.alp.summit

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Calendar
import java.util.UUID

/** Create a task or change one. Nothing is stored until Save is tapped. */
class EditTaskActivity : Activity() {
    private var task: Task? = null
    private var occurrence: LocalDate? = null   // the day the task was opened from
    private val today: LocalDate = LocalDate.now()

    // Working copy of the fields.
    private var date: LocalDate? = null
    private var time = -1
    private var remind = 0
    private var mask = 0
    private var duration = 0
    private var priority = 2
    private var category = ""
    private val subs = ArrayList<Subtask>()

    private lateinit var titleInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var subInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContentView(R.layout.activity_edit)
        fitSystem(findViewById(R.id.root))
        centerColumn(findViewById(R.id.root), findViewById(R.id.edit_body))
        titleInput = findViewById(R.id.edit_title)
        notesInput = findViewById(R.id.edit_notes)
        subInput = findViewById(R.id.sub_input)

        val day = intent.getStringExtra("date")?.let { LocalDate.parse(it) }
        val t = Store.task(intent.getStringExtra("id"))
        task = t
        if (t != null) {
            occurrence = day
            date = t.date
            time = t.time
            remind = t.remind
            mask = t.repeatMask
            duration = t.duration
            priority = t.priority
            category = t.categoryId
            for (s in t.subtasks) subs.add(Subtask(s.id, s.title))
            titleInput.setText(t.title)
            notesInput.setText(t.notes)
        } else {
            date = day
            category = intent.getStringExtra("category") ?: ""
            titleInput.setText(intent.getStringExtra("title") ?: "")
            titleInput.setSelection(titleInput.text.length)
            titleInput.requestFocus()
            // A brand-new task starts with the keyboard open on the title.
            if (titleInput.text.isEmpty()) {
                window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
        }

        findViewById<TextView>(R.id.edit_heading).text = if (t == null) "New task" else "Edit task"
        findViewById<View>(R.id.edit_close).setOnClickListener { finish() }
        findViewById<View>(R.id.edit_save).setOnClickListener { save() }
        findViewById<View>(R.id.sub_add).setOnClickListener { addSubtask() }
        subInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                addSubtask()
                true
            } else false
        }
        val delete = findViewById<View>(R.id.edit_delete)
        if (t != null) {
            delete.visibility = View.VISIBLE
            delete.setOnClickListener { delete(t) }
        }
        render()
    }

    // ---------- Choices ----------

    private fun render() {
        val repeats = mask != 0 && date != null
        findViewById<TextView>(R.id.label_when).text = if (repeats) "Starts" else "When"

        val whenRow = findViewById<FlowLayout>(R.id.when_chips)
        whenRow.removeAllViews()
        val tomorrow = today.plusDays(1)
        val other = date != null && date != today && date != tomorrow
        add(whenRow, "Today", date == today) { date = today }
        add(whenRow, "Tomorrow", date == tomorrow) { date = tomorrow }
        val picked = date
        val pick = chip(if (other && picked != null) shortDay(picked) else "Pick a date", other)
        pick.setOnClickListener { pickDate() }
        whenRow.addView(pick)
        add(whenRow, "Someday", date == null) { date = null }

        val timeRow = findViewById<FlowLayout>(R.id.time_chips)
        timeRow.removeAllViews()
        val timeChip = chip(if (time >= 0) fmtTime(time) else "Add a time", time >= 0)
        timeChip.setOnClickListener { pickTime() }
        timeRow.addView(timeChip)
        if (time >= 0) add(timeRow, "No time", false) { time = -1 }

        val remindRow = findViewById<FlowLayout>(R.id.remind_chips)
        remindRow.removeAllViews()
        val showRemind = time >= 0 && date != null
        findViewById<View>(R.id.label_remind).visibility = if (showRemind) View.VISIBLE else View.GONE
        remindRow.visibility = if (showRemind) View.VISIBLE else View.GONE
        add(remindRow, "Off", remind == -1) { remind = -1 }
        add(remindRow, "At the time", remind == 0) { remind = 0 }
        add(remindRow, "10 min before", remind == 10) { remind = 10 }
        add(remindRow, "30 min before", remind == 30) { remind = 30 }
        add(remindRow, "1 hour before", remind == 60) { remind = 60 }

        val repeatRow = findViewById<FlowLayout>(R.id.repeat_chips)
        repeatRow.removeAllViews()
        val showRepeat = date != null
        findViewById<View>(R.id.label_repeat).visibility = if (showRepeat) View.VISIBLE else View.GONE
        repeatRow.visibility = if (showRepeat) View.VISIBLE else View.GONE
        val letters = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        for (i in 0 until 7) {
            val bit = 1 shl i
            add(repeatRow, letters[i], mask and bit != 0) { mask = mask xor bit }
        }
        add(repeatRow, "Every day", mask == 127) { mask = if (mask == 127) 0 else 127 }

        val durationRow = findViewById<FlowLayout>(R.id.duration_chips)
        durationRow.removeAllViews()
        for (m in intArrayOf(15, 30, 45, 60, 90, 120, 180)) {
            add(durationRow, fmtDuration(m), duration == m) { duration = if (duration == m) 0 else m }
        }

        val priorityRow = findViewById<FlowLayout>(R.id.priority_chips)
        priorityRow.removeAllViews()
        add(priorityRow, "Low", priority == 1) { priority = 1 }
        add(priorityRow, "Medium", priority == 2) { priority = 2 }
        add(priorityRow, "High", priority == 3, getColor(R.color.danger)) { priority = 3 }

        val categoryRow = findViewById<FlowLayout>(R.id.category_chips)
        categoryRow.removeAllViews()
        for (c in Store.categories) {
            add(categoryRow, c.name, category == c.id, c.color) { category = if (category == c.id) "" else c.id }
        }

        val subList = findViewById<LinearLayout>(R.id.sub_list)
        subList.removeAllViews()
        for (s in subs) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val name = TextView(this)
            name.text = s.title
            name.textSize = 15f
            name.setTextColor(getColor(R.color.text_primary))
            line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = ImageButton(this)
            remove.setImageResource(R.drawable.ic_close)
            remove.setColorFilter(getColor(R.color.text_dim))
            remove.background = null
            remove.contentDescription = "Remove subtask"
            remove.setOnClickListener {
                subs.remove(s)
                render()
            }
            line.addView(remove, LinearLayout.LayoutParams(dp(44), dp(44)))
            subList.addView(line)
        }
    }

    private fun add(row: FlowLayout, label: String, on: Boolean, tint: Int = getColor(R.color.accent), action: () -> Unit) {
        val c = chip(label, on, tint)
        c.setOnClickListener {
            action()
            render()
        }
        row.addView(c)
    }

    private fun pickDate() {
        val start = date ?: today
        val dialog = DatePickerDialog(this, R.style.AppDialog, { _, y, m, d ->
            date = LocalDate.of(y, m + 1, d)
            render()
        }, start.year, start.monthValue - 1, start.dayOfMonth)
        dialog.datePicker.firstDayOfWeek = Calendar.MONDAY
        dialog.show()
    }

    private fun pickTime() {
        val start = if (time >= 0) time else 9 * 60
        TimePickerDialog(this, R.style.AppDialog, { _, h, m ->
            time = h * 60 + m
            render()
        }, start / 60, start % 60, true).show()
    }

    private fun addSubtask() {
        val text = subInput.text.toString().trim()
        if (text.isEmpty()) return
        subs.add(Subtask(UUID.randomUUID().toString(), text))
        subInput.setText("")
        render()
    }

    // ---------- Save and delete ----------

    private fun save() {
        val title = titleInput.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, "Give the task a title first", Toast.LENGTH_SHORT).show()
            titleInput.requestFocus()
            return
        }
        addSubtask()   // keep a subtask that was typed but not added yet
        val t = task ?: Task().also { Store.tasks.add(it) }
        t.title = title
        t.notes = notesInput.text.toString().trim()
        t.date = date
        t.time = time
        t.remind = remind
        t.repeatMask = if (date == null) 0 else mask
        if (!t.repeating) t.endDate = null
        t.duration = duration
        t.priority = priority
        t.categoryId = category
        t.subtasks.clear()
        t.subtasks.addAll(subs)
        val ids = subs.map { it.id }
        t.subDone.retainAll { key -> ids.any { key.endsWith("|$it") } }
        t.updated = System.currentTimeMillis()
        Store.save()
        Reminders.schedule(this)
        finish()
    }

    private fun delete(t: Task) {
        val day = occurrence
        if (t.repeating && day != null) {
            val options = arrayOf("Only " + relDayInline(day, today), "This day and all later ones", "Every day it repeats")
            AlertDialog.Builder(this)
                .setTitle("Delete repeating task")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> t.skipDates.add(day.toString())
                        1 -> {
                            val start = t.date
                            if (start != null && !day.isAfter(start)) Store.tasks.remove(t)
                            else t.endDate = day.minusDays(1)
                        }
                        else -> Store.tasks.remove(t)
                    }
                    t.updated = System.currentTimeMillis()
                    done()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setMessage("Delete this task?")
                .setPositiveButton("Delete") { _, _ ->
                    Store.tasks.remove(t)
                    done()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun done() {
        Store.save()
        Reminders.schedule(this)
        finish()
    }
}
