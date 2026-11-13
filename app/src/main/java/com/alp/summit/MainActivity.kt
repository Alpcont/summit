package com.alp.summit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** The main screen with its three pages: Today, Calendar and Someday. */
class MainActivity : Activity() {
    private lateinit var root: View
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var tabViews: List<TextView>

    private var tab = 0                              // 0 Today, 1 Calendar, 2 Someday
    private var today: LocalDate = LocalDate.now()
    private var selected: LocalDate = LocalDate.now()   // the day picked in the calendar
    private var month: YearMonth = YearMonth.now()      // the month the calendar shows
    private var weekMode = false
    private var filter = ""                          // category shown on Today, "" = all
    private var refreshHeader: () -> Unit = {}
    private var forceReview = false
    private val scores = HashMap<LocalDate, TextView>()   // "2 of 5" labels in the calendar
    private var side = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        scroll = findViewById(R.id.scroll)
        list = findViewById(R.id.list)
        input = findViewById(R.id.quick_input)
        val tabs = findViewById<View>(R.id.tabs)
        fitSystem(root) { keyboard -> tabs.visibility = if (keyboard) View.GONE else View.VISIBLE }

        weekMode = prefs(this).getBoolean("weekMode", false)
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab")
            savedInstanceState.getString("selected")?.let { selected = LocalDate.parse(it) }
            month = YearMonth.from(selected)
        }

        tabViews = listOf(findViewById(R.id.tab_today), findViewById(R.id.tab_calendar), findViewById(R.id.tab_someday))
        for (i in tabViews.indices) tabViews[i].setOnClickListener {
            if (tab != i) {
                tab = i
                render()
                scroll.scrollTo(0, 0)
            }
        }

        findViewById<View>(R.id.quick_add).setOnClickListener { quickAdd() }
        findViewById<View>(R.id.quick_more).setOnClickListener { openEditor(null, input.text.toString().trim()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                quickAdd()
                true
            } else false
        }

        // On the tablet, keep the content in a comfortable centred column.
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l - root.paddingLeft - root.paddingRight
            val s = maxOf(0, (w - dp(720)) / 2)
            if (s != side) {
                side = s
                root.post { applySide() }
            }
        }

        val p = prefs(this)
        if (!Reminders.canNotify(this) && !p.getBoolean("askedNotifications", false) && Build.VERSION.SDK_INT >= 33) {
            p.edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        forceReview = intent?.getBooleanExtra("review", false) == true
    }

    private fun applySide() {
        val s = maxOf(side, 0)
        list.setPadding(dp(20) + s, list.paddingTop, dp(20) + s, list.paddingBottom)
        val bar = findViewById<View>(R.id.quick_bar)
        val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
        lp.marginStart = dp(16) + s
        lp.marginEnd = dp(16) + s
        bar.layoutParams = lp
        findViewById<View>(R.id.tabs).setPadding(s, 0, s, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("review", false)) forceReview = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putString("selected", selected.toString())
    }

    override fun onResume() {
        super.onResume()
        val now = LocalDate.now()
        if (now != today) {
            if (selected == today) {
                selected = now
                month = YearMonth.from(now)
            }
            today = now
        }
        render()
        Reminders.schedule(this)
        maybeReview()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) {
            tab = 0
            render()
            scroll.scrollTo(0, 0)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Opens the morning review once a day (from the nudge time on), or when asked for. */
    private fun maybeReview() {
        val force = forceReview
        forceReview = false
        if (Store.missed(today).isEmpty()) return
        val p = prefs(this)
        if (!force) {
            if (p.getString("reviewShown", "") == today.toString()) return
            val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute
            if (nowMinutes < p.getInt("nudgeTime", 480)) return
        }
        p.edit().putString("reviewShown", today.toString()).apply()
        startActivity(Intent(this, ReviewActivity::class.java))
    }

    // ---------- Pages ----------

    private fun render() {
        list.removeAllViews()
        scores.clear()
        refreshHeader = {}
        when (tab) {
            0 -> renderToday()
            1 -> renderCalendar()
            else -> renderSomeday()
        }
        for (i in tabViews.indices) {
            val c = getColor(if (i == tab) R.color.accent else R.color.text_dim)
            tabViews[i].setTextColor(c)
            tabViews[i].compoundDrawableTintList = ColorStateList.valueOf(c)
        }
        input.hint = when (tab) {
            0 -> "Add a task for today"
            1 -> "Add a task for " + relDayInline(selected, today)
            else -> "Add a task for someday"
        }
    }

    private fun renderToday() {
        val h = layoutInflater.inflate(R.layout.header_today, list, false)
        list.addView(h)
        val hour = LocalTime.now().hour
        h.findViewById<TextView>(R.id.today_greeting).text = when {
            hour < 5 -> "Still up, Alp"
            hour < 12 -> "Good morning, Alp"
            hour < 18 -> "Good afternoon, Alp"
            else -> "Good evening, Alp"
        }
        h.findViewById<TextView>(R.id.today_title).text = weekdayName(today)
        h.findViewById<TextView>(R.id.today_sub).text = dayMonth(today)
        h.findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val summit = h.findViewById<SummitView>(R.id.summit)
        val progress = h.findViewById<TextView>(R.id.today_progress)
        val planned = h.findViewById<TextView>(R.id.today_planned)
        var first = true
        refreshHeader = {
            val all = Store.on(today)
            val done = all.count { it.isDone(today) }
            summit.setProgress(if (all.isEmpty()) 0f else done.toFloat() / all.size, !first)
            first = false
            progress.text = when {
                all.isEmpty() -> "No tasks yet"
                done == all.size -> "Summit reached · all ${all.size} done"
                else -> "$done of ${all.size} done"
            }
            progress.setTextColor(getColor(if (all.isNotEmpty() && done == all.size) R.color.accent else R.color.text_primary))
            val left = all.filter { !it.isDone(today) }.sumOf { it.duration }
            planned.text = if (left > 0) fmtDuration(left) + " left" else ""
        }
        refreshHeader()

        val missed = Store.missed(today).size
        val banner = h.findViewById<TextView>(R.id.review_banner)
        if (missed > 0) {
            banner.visibility = View.VISIBLE
            banner.text = plural(missed, "unfinished task") + " from earlier days  ·  Review"
            banner.setOnClickListener {
                forceReview = true
                maybeReview()
            }
        }

        val all = Store.on(today)
        val used = Store.categories.filter { c -> all.any { it.categoryId == c.id } }
        if (filter.isNotEmpty() && used.none { it.id == filter }) filter = ""
        if (used.size >= 2 || filter.isNotEmpty()) {
            h.findViewById<HorizontalScrollView>(R.id.filter_scroll).visibility = View.VISIBLE
            val row = h.findViewById<LinearLayout>(R.id.filter_row)
            fun add(label: String, id: String, tint: Int) {
                val c = chip(label, filter == id, tint)
                c.setOnClickListener {
                    filter = id
                    render()
                }
                row.addView(c)
                (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            }
            add("All", "", getColor(R.color.accent))
            for (c in used) add(c.name, c.id, c.color)
        }

        val shown = all.filter { filter.isEmpty() || it.categoryId == filter }
        if (shown.isEmpty()) list.addView(emptyText("A clear day.\nAdd your first task below."))
        for (t in shown) list.addView(taskRow(t, today))
    }

    private fun renderCalendar() {
        val h = layoutInflater.inflate(R.layout.header_calendar, list, false)
        list.addView(h)
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        h.findViewById<TextView>(R.id.cal_title).text =
            if (weekMode) weekTitle(monday) else monthYear(month.atDay(1))
        h.findViewById<View>(R.id.cal_prev).setOnClickListener { shift(-1) }
        h.findViewById<View>(R.id.cal_next).setOnClickListener { shift(1) }

        val modes = h.findViewById<LinearLayout>(R.id.cal_modes)
        fun mode(label: String, on: Boolean, action: () -> Unit) {
            val c = chip(label, on)
            c.setOnClickListener { action() }
            modes.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        }
        mode("Month", !weekMode) { setWeekMode(false) }
        mode("Week", weekMode) { setWeekMode(true) }
        val spacer = View(this)
        modes.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        if (selected != today || YearMonth.from(today) != month) {
            mode("Today", false) {
                selected = today
                month = YearMonth.from(today)
                render()
            }
        }

        val grid = h.findViewById<MonthGridView>(R.id.cal_grid)
        grid.weekMode = weekMode
        grid.month = month
        grid.selected = selected
        grid.today = today
        grid.onSwipe = { shift(it) }
        grid.onSelect = {
            selected = it
            month = YearMonth.from(it)
            render()
        }
        refreshHeader = {
            grid.invalidate()
            for ((d, tv) in scores) tv.text = scoreText(d)
        }

        if (!weekMode) {
            list.addView(dayHeading(selected, true))
            val tasks = Store.on(selected)
            if (tasks.isEmpty()) list.addView(emptyText("Nothing planned for this day."))
            for (t in tasks) list.addView(taskRow(t, selected))
        } else {
            // Week view: the seven days one under another, each with its tasks.
            for (i in 0 until 7) {
                val d = monday.plusDays(i.toLong())
                val heading = dayHeading(d, d == selected)
                heading.setOnClickListener {
                    selected = d
                    month = YearMonth.from(d)
                    render()
                }
                list.addView(heading)
                val tasks = Store.on(d)
                if (tasks.isEmpty()) {
                    val none = TextView(this)
                    none.text = "—"
                    none.setTextColor(getColor(R.color.text_dim))
                    none.setPadding(0, dp(2), 0, dp(6))
                    list.addView(none)
                }
                for (t in tasks) list.addView(taskRow(t, d))
            }
        }
    }

    private fun weekTitle(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return if (monday.month == sunday.month) "${monday.dayOfMonth} – ${dayMonth(sunday)}"
        else "${shortDay(monday).substringAfter(' ')} – ${shortDay(sunday).substringAfter(' ')}"
    }

    private fun setWeekMode(on: Boolean) {
        if (weekMode == on) return
        weekMode = on
        prefs(this).edit().putBoolean("weekMode", on).apply()
        month = YearMonth.from(selected)
        render()
    }

    private fun shift(dir: Int) {
        if (weekMode) {
            selected = selected.plusWeeks(dir.toLong())
        } else {
            val m = month.plusMonths(dir.toLong())
            selected = if (YearMonth.from(today) == m) today else m.atDay(1)
        }
        month = YearMonth.from(selected)
        render()
    }

    private fun renderSomeday() {
        val title = TextView(this)
        title.text = "Someday"
        title.textSize = 36f
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        title.setTextColor(getColor(R.color.text_primary))
        list.addView(title)
        val sub = TextView(this)
        sub.text = "Tasks without a date. Hold a task to move it into a day."
        sub.textSize = 15f
        sub.setTextColor(getColor(R.color.text_secondary))
        sub.setPadding(0, dp(2), 0, dp(18))
        list.addView(sub)

        val tasks = Store.someday()
        if (tasks.isEmpty()) list.addView(emptyText("Nothing waiting.\nPark ideas here until you pick a day."))
        for (t in tasks) list.addView(taskRow(t, null))
        if (tasks.any { it.done }) {
            val clear = TextView(this)
            clear.text = "Clear completed"
            clear.textSize = 14f
            clear.setTextColor(getColor(R.color.text_secondary))
            clear.setPadding(0, dp(18), 0, dp(10))
            clear.setOnClickListener {
                Store.tasks.removeAll { it.date == null && it.done }
                Store.save()
                render()
            }
            list.addView(clear)
        }
    }

    // ---------- Building blocks ----------

    private fun emptyText(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        tv.setTextColor(getColor(R.color.text_dim))
        tv.setPadding(0, dp(22), 0, dp(12))
        return tv
    }

    /** "Tuesday 17 November" with that day's score on the right. */
    private fun dayHeading(d: LocalDate, highlight: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(18), 0, dp(6))
        val name = TextView(this)
        name.text = if (d == today) "Today · " + dayMonth(d) else weekdayName(d) + " " + dayMonth(d)
        name.textSize = 15f
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        name.setTextColor(getColor(if (highlight) R.color.accent else R.color.text_secondary))
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val score = TextView(this)
        score.text = scoreText(d)
        score.textSize = 14f
        score.setTextColor(getColor(R.color.text_dim))
        row.addView(score)
        scores[d] = score
        return row
    }

    private fun scoreText(d: LocalDate): String {
        val (done, total) = Store.progress(d)
        return if (total > 0) "$done of $total" else ""
    }

    private fun taskRow(t: Task, d: LocalDate?): View {
        val row = layoutInflater.inflate(R.layout.row_task, list, false)
        row.tag = t.id
        bindRow(row, t, d, false)
        return row
    }

    private fun bindRow(row: View, t: Task, d: LocalDate?, animate: Boolean) {
        val done = t.isDone(d)
        val cat = Store.category(t.categoryId)

        val box = row.findViewById<BoxView>(R.id.box)
        box.color = cat?.color ?: getColor(R.color.accent)
        box.setChecked(done, animate)
        box.contentDescription = if (done) "Done" else "Not done"
        box.setOnClickListener {
            Store.setDone(t, d, !t.isDone(d))
            Store.save()
            bindRow(row, t, d, true)
            refreshHeader()
            Reminders.schedule(this)
        }

        // Ticking the box crosses the line out.
        val title = row.findViewById<TextView>(R.id.title)
        title.text = t.title
        title.paintFlags =
            if (done) title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        title.setTextColor(getColor(if (done) R.color.text_dim else R.color.text_primary))

        val meta = row.findViewById<TextView>(R.id.meta)
        val sb = SpannableStringBuilder()
        fun part(text: String, color: Int = 0) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            val start = sb.length
            sb.append(text)
            if (color != 0) sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }
        if (t.priority == 3) part("High", getColor(R.color.danger))
        if (t.time >= 0) part(fmtTime(t.time), getColor(R.color.text_primary))
        if (t.duration > 0) part(fmtDuration(t.duration))
        if (cat != null) part(cat.name, cat.color)
        if (t.repeating) part(repeatLabel(t.repeatMask))
        if (t.subtasks.isNotEmpty()) {
            val ticked = t.subtasks.count { t.subKey(d, it) in t.subDone }
            part("$ticked/${t.subtasks.size}")
        }
        if (t.priority == 1) part("Low")
        if (t.notes.isNotBlank()) part("Note")
        meta.text = sb
        meta.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        meta.alpha = if (done) 0.45f else 1f

        row.setOnClickListener { openEditor(t, "", d) }
        row.setOnLongClickListener {
            rowMenu(row, t, d)
            true
        }

        // Subtasks can be ticked right in the list. They fold away once the task is done.
        val subs = row.findViewById<LinearLayout>(R.id.subs)
        subs.removeAllViews()
        subs.visibility = if (t.subtasks.isEmpty() || done) View.GONE else View.VISIBLE
        if (!done) for (s in t.subtasks) {
            val key = t.subKey(d, s)
            val on = key in t.subDone
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val sbox = BoxView(this)
            sbox.boxSize = 16f
            sbox.color = getColor(R.color.text_secondary)
            sbox.setChecked(on, false)
            line.addView(sbox, LinearLayout.LayoutParams(dp(34), dp(34)))
            val st = TextView(this)
            st.text = s.title
            st.textSize = 14f
            st.setTextColor(getColor(if (on) R.color.text_dim else R.color.text_secondary))
            if (on) st.paintFlags = st.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            line.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.setOnClickListener {
                if (on) t.subDone.remove(key) else t.subDone.add(key)
                t.updated = System.currentTimeMillis()
                Store.save()
                bindRow(row, t, d, false)
            }
            subs.addView(line)
        }
    }

    private fun repeatLabel(mask: Int): String {
        if (mask == 127) return "Daily"
        if (mask == 31) return "Weekdays"
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString(" ") { names[it] }
    }

    private fun rowMenu(anchor: View, t: Task, d: LocalDate?) {
        val m = PopupMenu(this, anchor, Gravity.END)
        m.menu.add(0, 1, 0, "Edit")
        if (t.repeating && d != null) {
            m.menu.add(0, 5, 0, "Skip this day")
            m.menu.add(0, 6, 0, "Delete all repeats")
        } else {
            if (t.date != today) m.menu.add(0, 2, 0, "Move to today")
            if (t.date != today.plusDays(1)) m.menu.add(0, 3, 0, "Move to tomorrow")
            if (t.date != null) m.menu.add(0, 4, 0, "Move to Someday")
            m.menu.add(0, 7, 0, "Delete")
        }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openEditor(t, "", d)
                2 -> changed { Store.move(t, today) }
                3 -> changed { Store.move(t, today.plusDays(1)) }
                4 -> changed { Store.move(t, null) }
                5 -> changed {
                    if (d != null) t.skipDates.add(d.toString())
                    t.updated = System.currentTimeMillis()
                }
                6 -> AlertDialog.Builder(this)
                    .setMessage("Delete \"${t.title}\" on every day it repeats?")
                    .setPositiveButton("Delete") { _, _ -> changed { Store.tasks.remove(t) } }
                    .setNegativeButton("Cancel", null)
                    .show()
                7 -> changed { Store.tasks.remove(t) }
            }
            true
        }
        m.show()
    }

    private fun changed(action: () -> Unit) {
        action()
        Store.save()
        render()
        Reminders.schedule(this)
    }

    // ---------- Adding ----------

    /** The day new tasks go to on the current page (null on Someday). */
    private fun targetDate(): LocalDate? = when (tab) {
        0 -> today
        1 -> selected
        else -> null
    }

    private fun quickAdd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            openEditor(null, "")
            return
        }
        val t = Task()
        t.title = text
        t.date = targetDate()
        if (tab == 0) t.categoryId = filter
        Store.tasks.add(t)
        Store.save()
        input.setText("")
        render()
        scroll.post {
            val row = list.findViewWithTag<View>(t.id)
            if (row != null) scroll.smoothScrollTo(0, maxOf(0, row.top - dp(120)))
        }
    }

    private fun openEditor(t: Task?, title: String, d: LocalDate? = null) {
        val i = Intent(this, EditTaskActivity::class.java)
        if (t != null) {
            i.putExtra("id", t.id)
            if (d != null) i.putExtra("date", d.toString())
        } else {
            i.putExtra("title", title)
            targetDate()?.let { i.putExtra("date", it.toString()) }
            if (tab == 0) i.putExtra("category", filter)
            input.setText("")
        }
        startActivity(i)
    }
}
