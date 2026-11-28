package com.alp.summit

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale

/** Shared frame of the smaller screens: top bar, scrolling body, optional button, Esc to close. */
abstract class SheetActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Look.wrap(newBase))
    }

    protected lateinit var body: LinearLayout
    protected lateinit var action: TextView

    protected fun setup(title: String) {
        sheetMotion()
        Store.load(this)
        setContentView(R.layout.activity_simple)
        val root = findViewById<View>(R.id.root)
        fitSystem(root)
        body = findViewById(R.id.simple_body)
        centerColumn(root, body)
        action = findViewById(R.id.simple_action)
        findViewById<TextView>(R.id.simple_title).text = title
        findViewById<View>(R.id.simple_close).setOnClickListener { finish() }
    }

    protected fun text(into: LinearLayout, s: CharSequence, size: Float, color: Int, top: Int = 0): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        tv.setTextColor(getColor(color))
        tv.setPadding(0, dp(top), 0, 0)
        into.addView(tv)
        return tv
    }

    protected fun label(into: LinearLayout, s: String) {
        val tv = text(into, s.uppercase(), 11f, R.color.text_dim, 28)
        tv.letterSpacing = 0.14f
        tv.setPadding(0, dp(28), 0, dp(8))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

// ---------- Streaks and statistics ----------

class StatsActivity : SheetActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Progress")
        val today = LocalDate.now()

        val streak = Store.streak(today)
        val big = text(body, streak.toString(), 64f, R.color.accent, 8)
        big.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text(body, if (streak == 1) "day in a row at the summit" else "days in a row at the summit", 16f, R.color.text_primary)
        text(
            body, "A day counts when every task planned for it is ticked. Best so far: " +
                plural(Store.bestStreak(today), "day") + ".", 13f, R.color.text_dim, 6
        )

        label(body, "This week")
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val bars = BarsView(this)
        var weekDone = 0
        var weekTotal = 0
        for (i in 0 until 7) {
            val (done, total) = Store.progress(monday.plusDays(i.toLong()))
            bars.rates[i] = if (total == 0) -1f else done.toFloat() / total
            weekDone += done
            weekTotal += total
        }
        bars.todayIndex = today.dayOfWeek.value - 1
        body.addView(bars)
        text(body, rateLine(weekDone, weekTotal), 15f, R.color.text_primary, 6)

        label(body, "Last 30 days")
        val (done30, total30) = Store.totals(today, 30)
        text(body, rateLine(done30, total30), 15f, R.color.text_primary)

        label(body, "Focus time")
        val week = Store.focusMinutes(today, today.dayOfWeek.value)
        val all = Store.focusLog.values.sum()
        text(
            body, "This week: " + (if (week > 0) fmtDuration(week) else "none yet") +
                "   ·   All time: " + (if (all > 0) fmtDuration(all) else "none yet"), 15f, R.color.text_primary
        )

        label(body, "Study time per course")
        val studied = Courses.active()
        if (studied.isEmpty()) text(body, "No courses yet (cap icon on Today).", 15f, R.color.text_dim)
        for (c in studied) {
            val (thisWeek, total) = Store.studyMinutes(c.id, today)
            val line = text(body, c.code + "  ·  " + minutesText(thisWeek) + " this week  ·  " + minutesText(total) + " total", 15f, R.color.text_primary, 6)
            line.setTextColor(Look.tone(c.color))
        }

        label(body, "Focus sessions this week")
        val weekSessions = Store.sessions.items.filter { !it.day.isBefore(monday) }
        if (weekSessions.isEmpty()) text(body, "None yet. Start one from the focus card on Today.", 15f, R.color.text_dim)
        else {
            val rated = weekSessions.filter { it.rating > 0 }
            val tries = weekSessions.sumOf { it.blocked }
            text(body, plural(weekSessions.size, "session") + "  ·  " + minutesText(weekSessions.sumOf { it.minutes }) +
                (if (rated.isNotEmpty()) "  ·  focus " + String.format(java.util.Locale.ENGLISH, "%.1f", rated.map { it.rating }.average()) + " / 5" else "") +
                "  ·  " + (if (tries == 1) "1 try" else "$tries tries") + " to open a blocked app", 15f, R.color.text_primary)
        }

        label(body, "Categories this week")
        var any = false
        for (c in Store.categories) {
            var done = 0
            var total = 0
            for (i in 0 until 7) {
                val d = monday.plusDays(i.toLong())
                for (t in Store.tasks) if (t.categoryId == c.id && t.occursOn(d)) {
                    total++
                    if (t.isDone(d)) done++
                }
            }
            if (total == 0) continue
            any = true
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(7), 0, dp(7))
            val name = TextView(this)
            name.text = c.name
            name.textSize = 15f
            name.setTextColor(Look.tone(c.color))
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val score = TextView(this)
            score.text = "$done of $total"
            score.textSize = 15f
            score.setTextColor(getColor(R.color.text_secondary))
            row.addView(score)
            body.addView(row)
        }
        if (!any) text(body, "No tasks with a category this week.", 15f, R.color.text_dim)
    }

    private fun rateLine(done: Int, total: Int): String =
        if (total == 0) "No tasks planned." else "$done of $total tasks done  ·  ${done * 100 / total}%"
}

// ---------- Evening planning ----------

/**
 * A calm look at tomorrow: add tasks, carry over what is still open, pull something from Someday,
 * review today's classes. On Sundays it starts with a look back at the week.
 */
class PlanActivity : SheetActivity() {
    private val today: LocalDate = LocalDate.now()
    private val tomorrow: LocalDate = today.plusDays(1)
    private lateinit var input: EditText
    private lateinit var lists: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sunday = today.dayOfWeek == java.time.DayOfWeek.SUNDAY
        setup(if (sunday) "Weekly review" else "Plan tomorrow")
        prefs(this).edit().putString("planShown", today.toString()).apply()
        if (sunday) weekReview()

        val (done, total) = Store.progress(today)
        text(
            body, weekdayName(tomorrow) + " " + dayMonth(tomorrow), 28f, R.color.text_primary, 4
        ).typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text(
            body, if (total == 0) "Nothing was planned for today." else "Today: $done of $total done.",
            15f, R.color.text_secondary, 2
        )

        // Add tasks for tomorrow; the same shortcuts as on the main page work here ("18:00", "45m", "#gym").
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(getColor(R.color.surface), dp(16))
        bar.setPadding(dp(18), 0, dp(7), 0)
        input = EditText(this)
        input.background = null
        input.hint = "Add a task for tomorrow"
        input.textSize = 16f
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(getColor(R.color.text_dim))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.maxLines = 1
        input.setPadding(0, dp(15), 0, dp(15))
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) {
                add()
                true
            } else false
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = ImageButton(this)
        mic.setImageResource(R.drawable.ic_mic)
        mic.setColorFilter(getColor(R.color.text_secondary))
        mic.background = null
        mic.contentDescription = "Speak a task"
        mic.setOnClickListener { listen(this, 52) }
        bar.addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)))
        val plus = ImageButton(this)
        plus.setImageResource(R.drawable.ic_up)
        plus.setColorFilter(getColor(R.color.on_accent))
        plus.setBackgroundResource(R.drawable.bg_accent_round)
        plus.contentDescription = "Add task"
        plus.setOnClickListener { add() }
        bar.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(18)
        body.addView(bar, lp)
        // What smart quick-add understood (also for spoken tasks), before anything is added.
        val preview = text(body, "", 13f, R.color.accent, 6)
        preview.visibility = View.GONE
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val sum = parseQuick(s?.toString() ?: "", today, Store.categories).summary(today)
                preview.text = sum
                preview.visibility = if (sum.isEmpty()) View.GONE else View.VISIBLE
            }
        })

        lists = LinearLayout(this)
        lists.orientation = LinearLayout.VERTICAL
        body.addView(lists)
        if (prefs(this).getBoolean("reflectOn", true)) reflection()

        action.visibility = View.VISIBLE
        action.text = "Done"
        action.setOnClickListener { finish() }
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 52 || resultCode != RESULT_OK) return
        val said = heard(data) ?: return
        input.setText(said)
        input.setSelection(input.text.length)
    }

    // ----- Evening reflection: two optional questions, kept as a note pinned to today -----

    private val Q1 = "What went well today?"
    private val Q2 = "What will I do differently tomorrow?"
    private var well: EditText? = null
    private var better: EditText? = null

    private fun reflectionNote(): Note? = Store.notes.items.firstOrNull { it.kind == "reflection" && it.pin == today }

    private fun reflection() {
        label(body, "Reflection  ·  optional")
        val existing = reflectionNote()?.text ?: ""
        fun answer(q: String): String {
            val i = existing.indexOf(q)
            if (i < 0) return ""
            val rest = existing.substring(i + q.length)
            val end = listOf(Q1, Q2).map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
            return rest.substring(0, end).trim()
        }
        fun field(q: String): EditText {
            text(body, q, 15f, R.color.text_primary, 8)
            val e = EditText(this)
            e.textSize = 15f
            e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            e.setTextColor(getColor(R.color.text_primary))
            e.setHintTextColor(getColor(R.color.text_dim))
            e.hint = "A sentence is enough (or skip it)"
            e.background = rounded(getColor(R.color.surface), dp(14))
            e.setPadding(dp(14), dp(12), dp(14), dp(12))
            e.setText(answer(q))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            body.addView(e, lp)
            return e
        }
        well = field(Q1)
        better = field(Q2)
        text(body, "Saved as a Productivity note pinned to today, marked Reflection.", 12f, R.color.text_dim, 6)
    }

    /** Keeps the answers (only if something was written). */
    private fun saveReflection() {
        val a = well?.text?.toString()?.trim() ?: return
        val b = better?.text?.toString()?.trim() ?: ""
        val n = reflectionNote()
        if (a.isEmpty() && b.isEmpty() && n == null) return
        val textNow = "$Q1\n$a\n\n$Q2\n$b"
        if (n != null && n.text == textNow) return
        val note = n ?: Note().also {
            it.kind = "reflection"
            it.pin = today
            it.title = "Reflection · " + shortDay(today)
            Store.notes.add(it)
        }
        note.text = textNow
        note.touch()
        Store.save()
    }

    override fun onPause() {
        super.onPause()
        saveReflection()
    }

    private fun add() {
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) return
        val p = parseQuick(typed, today, Store.categories)
        val t = Task()
        t.title = p.title
        t.date = if (p.hasDate) p.date else tomorrow
        t.time = p.time
        t.duration = p.duration
        if (p.priority != 0) t.priority = p.priority
        t.categoryId = p.categoryId
        t.repeatMask = p.repeatMask
        t.repeatRule = p.repeatRule
        Store.tasks.add(t)
        changed()
        input.setText("")
    }

    private fun changed() {
        Store.save()
        Reminders.schedule(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::lists.isInitialized) render()   // a note may have been changed in between
    }

    /** Sunday: what got done, what kept being moved, the task streak and the habit chains. */
    private fun weekReview() {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        text(body, "Your week", 28f, R.color.text_primary, 4).typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text(body, shortDay(monday) + " – " + shortDay(today), 15f, R.color.text_secondary, 2)

        label(body, "What got done")
        var done = 0
        var total = 0
        val doneTitles = ArrayList<String>()
        for (i in 0 until 7) {
            val d = monday.plusDays(i.toLong())
            for (t in Store.tasks) if (t.occursOn(d)) {
                total++
                if (t.isDone(d)) {
                    done++
                    if (!t.repeating) doneTitles.add(t.title)
                }
            }
        }
        text(body, if (total == 0) "No tasks this week." else "$done of $total tasks done  ·  ${done * 100 / total}%", 16f, R.color.text_primary)
        val focus = Store.focusMinutes(today, 7)
        if (focus > 0) text(body, fmtDuration(focus) + " of focus time", 14f, R.color.text_secondary, 2)
        if (doneTitles.isNotEmpty()) {
            text(body, doneTitles.take(10).joinToString("\n") { "✓  $it" } +
                (if (doneTitles.size > 10) "\n…and ${doneTitles.size - 10} more" else ""), 14f, R.color.text_secondary, 8)
                .setLineSpacing(dp(3).toFloat(), 1f)
        }

        val moved = Store.tasks.filter { it.moves >= 2 && !it.done && !it.repeating }.sortedByDescending { it.moves }
        if (moved.isNotEmpty()) {
            label(body, "Kept being moved")
            text(body, "Worth a decision: do it, give it a fixed time, split it, or drop it.", 13f, R.color.text_dim)
            for (t in moved.take(6)) text(body, t.title + "  ·  moved ${t.moves} times", 15f, R.color.text_primary, 6)
        }

        label(body, "Streaks")
        val streak = Store.streak(today)
        text(body, "Summit streak: " + plural(streak, "day") + "  ·  best " + plural(Store.bestStreak(today), "day"), 15f, R.color.text_primary)
        for (h in Store.habits.items.sortedByDescending { it.streak(today) }) {
            val n = h.streak(today)
            val tv = text(body, h.title + "  ·  " + plural(n, "day") + " chain", 15f, R.color.text_primary, 6)
            if (n > 0) tv.setTextColor(chainHeat(n))
        }
        if (Store.habits.items.isEmpty()) text(body, "No habits yet. Add some on the Chains tab.", 14f, R.color.text_dim, 4)

        label(body, "Now: plan Monday")
    }

    private fun render() {
        lists.removeAllViews()
        label(lists, "Tomorrow")
        val planned = Store.on(tomorrow)
        if (planned.isEmpty()) text(lists, "Nothing planned yet.", 15f, R.color.text_dim)
        for (t in planned) line(t, null) {}

        // Notes pinned to tomorrow.
        val pinned = Store.notes.items.filter { !it.developer && it.pin == tomorrow }
        if (pinned.isNotEmpty()) {
            label(lists, "Notes for tomorrow")
            for (n in pinned) lists.addView(noteCard(n, today, false))
        }

        // One "review notes" task for each class held today.
        val reviews = Courses.reviews(this, today)
        if (reviews.isNotEmpty()) {
            label(lists, "Today's classes")
            for (sg in reviews) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(7), 0, dp(7))
                val words = LinearLayout(this)
                words.orientation = LinearLayout.VERTICAL
                text(words, sg.title, 16f, R.color.text_primary)
                text(words, sg.why + "  ·  " + fmtDuration(sg.duration), 13f, R.color.text_secondary, 1)
                row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val add = chip("Add", true, Look.tone(sg.course.color))
                add.setOnClickListener {
                    Courses.accept(this, sg)
                    render()
                }
                row.addView(add)
                val skip = chip("Skip", false)
                skip.setOnClickListener {
                    Courses.skip(sg)
                    render()
                }
                row.addView(skip)
                (skip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                lists.addView(row)
            }
        }

        val open = Store.on(today).filter { !it.isDone(today) && !it.repeating }
        if (open.isNotEmpty()) {
            label(lists, "Still open today")
            for (t in open) line(t, "Move to tomorrow") {
                Store.move(t, tomorrow)
                changed()
            }
        }

        // Routines: one tap adds a routine's tasks to tomorrow.
        val routines = Store.routines.items.sortedBy { it.name.lowercase() }
        if (routines.isNotEmpty()) {
            label(lists, "Routines for tomorrow")
            val flow = FlowLayout(this)
            for (r in routines) {
                val c = chip(r.name, false)
                c.setOnClickListener {
                    r.apply(tomorrow)
                    changed()
                    android.widget.Toast.makeText(this, r.name + " added to tomorrow", android.widget.Toast.LENGTH_SHORT).show()
                }
                flow.addView(c)
            }
            lists.addView(flow)
        }

        val someday = Store.someday().filter { !it.done }.take(8)
        if (someday.isNotEmpty()) {
            label(lists, "From Someday")
            for (t in someday) line(t, "Add to tomorrow") {
                Store.move(t, tomorrow)
                changed()
            }
        }
    }

    /** A task line with an optional button on the right. */
    private fun line(t: Task, button: String?, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7), 0, dp(7))
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        text(words, t.title, 16f, R.color.text_primary)
        val parts = ArrayList<String>()
        if (t.time >= 0) parts.add(fmtTime(t.time))
        if (t.duration > 0) parts.add(fmtDuration(t.duration))
        Store.category(t.categoryId)?.let { parts.add(it.name) }
        if (t.repeating) parts.add(t.repeatText())
        if (parts.isNotEmpty()) text(words, parts.joinToString("  ·  "), 13f, R.color.text_secondary, 1)
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (button != null) {
            val c = chip(button, false)
            c.setOnClickListener { onClick() }
            row.addView(c)
        }
        lists.addView(row)
    }
}
