package com.alp.summit

import android.app.Activity
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
            name.setTextColor(c.color)
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

/** A calm look at tomorrow: add tasks, carry over what is still open, pull something from Someday. */
class PlanActivity : SheetActivity() {
    private val today: LocalDate = LocalDate.now()
    private val tomorrow: LocalDate = today.plusDays(1)
    private lateinit var input: EditText
    private lateinit var lists: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Plan tomorrow")
        prefs(this).edit().putString("planShown", today.toString()).apply()

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

        lists = LinearLayout(this)
        lists.orientation = LinearLayout.VERTICAL
        body.addView(lists)

        action.visibility = View.VISIBLE
        action.text = "Done"
        action.setOnClickListener { finish() }
        render()
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
        Store.tasks.add(t)
        changed()
        input.setText("")
    }

    private fun changed() {
        Store.save()
        Reminders.schedule(this)
        render()
    }

    private fun render() {
        lists.removeAllViews()
        label(lists, "Tomorrow")
        val planned = Store.on(tomorrow)
        if (planned.isEmpty()) text(lists, "Nothing planned yet.", 15f, R.color.text_dim)
        for (t in planned) line(t, null) {}

        val open = Store.on(today).filter { !it.isDone(today) && !it.repeating }
        if (open.isNotEmpty()) {
            label(lists, "Still open today")
            for (t in open) line(t, "Move to tomorrow") {
                Store.move(t, tomorrow)
                changed()
            }
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
        if (t.repeating) parts.add(repeatLabel(t.repeatMask))
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

// ---------- Focus timer ----------

/** A countdown for one task. The minutes are added to the task when the session ends. */
class FocusActivity : SheetActivity() {
    private companion object {
        const val READY = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }

    private var task: Task? = null
    private var date: LocalDate? = null
    private var length = 25
    private var state = -1
    private var logged = 0
    private lateinit var ring: RingView
    private lateinit var chips: LinearLayout
    private lateinit var cancel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Focus")
        val p = prefs(this)
        val running = p.getLong("focusEnd", 0L) > 0L
        // A session that is already running wins over the task this screen was opened for.
        task = Store.task(if (running) p.getString("focusTask", "") else intent.getStringExtra("id"))
        date = (if (running) p.getString("focusDate", "") else intent.getStringExtra("date"))
            ?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        length = p.getInt("focusLength", 25)

        body.gravity = Gravity.CENTER_HORIZONTAL
        val title = text(body, task?.title ?: "Focus session", 22f, R.color.text_primary, 12)
        title.gravity = Gravity.CENTER
        ring = RingView(this)
        val lp = LinearLayout.LayoutParams(dp(250), dp(250))
        lp.topMargin = dp(28)
        lp.bottomMargin = dp(24)
        body.addView(ring, lp)
        chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        body.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cancel = text(body, "Stop without logging", 15f, R.color.text_secondary, 10)
        cancel.gravity = Gravity.CENTER
        cancel.setPadding(dp(12), dp(12), dp(12), dp(12))
        cancel.setOnClickListener {
            Reminders.stopFocus(this, false)
            finish()
        }
        action.visibility = View.VISIBLE
        update()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun update() {
        val p = prefs(this)
        val now = System.currentTimeMillis()
        var end = p.getLong("focusEnd", 0L)
        if (end > 0L && now >= end) {
            // Ran out while this screen is open: log it here, no notification needed.
            Reminders.focusDone(this, false)
            logged = p.getInt("focusLength", length)
            end = 0L
            show(FINISHED)
        } else if (end > 0L) {
            show(RUNNING)
        } else if (state == RUNNING) {
            logged = p.getInt("focusLength", length)   // ended by its alarm a moment ago
            show(FINISHED)
        } else if (state != FINISHED) {
            show(READY)
        }

        when (state) {
            READY -> {
                ring.fraction = 1f
                ring.label = String.format(Locale.ENGLISH, "%d:00", length)
                ring.sub = "minutes of focus"
            }
            RUNNING -> {
                val start = p.getLong("focusStart", now)
                val left = (end - now).coerceAtLeast(0L)
                ring.fraction = if (end > start) left.toFloat() / (end - start) else 0f
                val seconds = (left + 999) / 1000
                ring.label = String.format(Locale.ENGLISH, "%d:%02d", seconds / 60, seconds % 60)
                val finish = java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                ring.sub = "until " + fmtTime(finish.hour * 60 + finish.minute)
            }
            else -> {
                ring.fraction = 0f
                ring.label = "Done"
                ring.sub = if (logged > 0) "$logged min logged" else "session ended"
            }
        }
        ring.invalidate()
    }

    /** Switches buttons and choices when the timer moves from one state to the next. */
    private fun show(next: Int) {
        if (next == state) return
        state = next
        chips.removeAllViews()
        chips.visibility = if (next == READY) View.VISIBLE else View.GONE
        cancel.visibility = if (next == RUNNING) View.VISIBLE else View.GONE
        val t = task
        when (next) {
            READY -> {
                for (m in intArrayOf(15, 25, 45, 60)) {
                    val c = chip("$m min", m == length)
                    c.setOnClickListener {
                        length = m
                        state = -1
                        update()
                    }
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.marginStart = dp(4)
                    lp.marginEnd = dp(4)
                    chips.addView(c, lp)
                }
                action.text = "Start"
                action.setOnClickListener {
                    Reminders.startFocus(this, t?.id ?: "", date?.toString() ?: "", length)
                    update()
                }
            }
            RUNNING -> {
                action.text = "Finish now"
                action.setOnClickListener {
                    logged = Reminders.stopFocus(this, true)
                    show(FINISHED)
                    update()
                }
            }
            else -> {
                if (t != null && !t.isDone(date)) {
                    action.text = "Mark task done"
                    action.setOnClickListener {
                        Store.setDone(t, date, true)
                        Store.save()
                        Reminders.schedule(this)
                        finish()
                    }
                } else {
                    action.text = "Close"
                    action.setOnClickListener { finish() }
                }
            }
        }
    }
}
