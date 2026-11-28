package com.alp.summit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlin.math.abs
import kotlin.math.min

/** The main screen with its five pages: Today, Calendar, Chains, Notes and Someday. */
class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Look.wrap(newBase))
        themeApplied = Look.current
    }

    private companion object {
        const val NONE = 0
        const val PAGE = 1           // the whole page glides in
        const val BELOW_HEADER = 2   // only the rows under the header glide in

        // Calendar page modes.
        const val MONTH = 0
        const val WEEK = 1
        const val DAY = 2

        // What a finger on a task line is doing.
        const val IDLE = 0
        const val DRAG = 2
    }

    private lateinit var root: View
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var tabViews: List<TextView>

    private var tab = 0                              // 0 Today, 1 Calendar, 2 Chains, 3 Notes, 4 Someday
    private var today: LocalDate = LocalDate.now()
    private var shown: LocalDate = LocalDate.now()      // the day on the Today page (swipe to change it)
    private var themeApplied = ""                    // the theme this screen was built with
    private var swipeX = 0f                          // where a finger went down, for swiping between days
    private var swipeY = 0f
    private var swipeOk = false
    private val chains by lazy { ChainsPage(this) { render() } }
    private val notesPage by lazy { NotesPage(this) { render() } }
    private var selected: LocalDate = LocalDate.now()   // the day picked in the calendar
    private var month: YearMonth = YearMonth.now()      // the month the calendar shows
    private var calMode = MONTH                      // how the Calendar page shows time: month, week or one day
    private var dayTick: (() -> Unit)? = null        // refreshes the numbers of the Day view
    private val ticker = object : Runnable {
        override fun run() {
            val tick = dayTick ?: return
            tick()
            // Every second while a timer runs (for its clock), otherwise just often enough for the "now" line.
            list.postDelayed(this, if (Focus.active(this@MainActivity)) 1000L else 20000L)
        }
    }
    private var filter = ""                          // category shown on Today, "" = all
    private var refreshHeader: () -> Unit = {}
    private var forceReview = false
    private var firstShow = true
    private var entering = false                     // true while a page is built to glide in
    private val scores = HashMap<LocalDate, TextView>()   // "2 of 5" labels in the calendar
    private val rows = ArrayList<Row>()              // the task lines currently on screen, top to bottom
    private var summitCarry: Float? = null           // where the ridge stood before the page was rebuilt
    private var gestureActive = false                // a swipe or drag is in progress
    private var settleToken = 0
    private var undo: (() -> Unit)? = null
    private val hideUndo = Runnable { dismissUndo() }
    private var openPlan = false
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
        fitSystem(root, tabs)

        calMode = prefs(this).getInt("calMode", if (prefs(this).getBoolean("weekMode", false)) WEEK else MONTH)
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab")
            savedInstanceState.getString("selected")?.let { selected = LocalDate.parse(it) }
            savedInstanceState.getString("shown")?.let { shown = LocalDate.parse(it) }
            month = YearMonth.from(selected)
        }

        tabViews = listOf(
            findViewById(R.id.tab_today), findViewById(R.id.tab_calendar), findViewById(R.id.tab_chains),
            findViewById(R.id.tab_notes), findViewById(R.id.tab_someday)
        )
        for (i in tabViews.indices) tabViews[i].setOnClickListener {
            if (tab != i) {
                tab = i
                render(PAGE)
                scroll.scrollTo(0, 0)
                it.scaleX = 0.88f
                it.scaleY = 0.88f
                it.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }

        findViewById<View>(R.id.undo_button).setOnClickListener {
            val restore = undo ?: return@setOnClickListener
            undo = null
            restore()
            Store.save()
            renderMoving()
            Reminders.schedule(this)
            dismissUndo()
        }
        // Show what the quick-add understands while typing: "gym tomorrow 18:00" -> "Tomorrow · 18:00 · Gym".
        val preview = findViewById<TextView>(R.id.quick_preview)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val summary = parseQuick(s?.toString() ?: "", today, Store.categories).summary(today)
                preview.text = summary
                preview.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
            }
        })
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
        Sync.start(this)
        forceReview = intent?.getBooleanExtra("review", false) == true
        openPlan = intent?.getBooleanExtra("plan", false) == true
        if (intent?.getBooleanExtra("day", false) == true) showDay()
        if (intent?.getBooleanExtra("chains", false) == true) tab = 2
        if (intent?.getBooleanExtra("notes", false) == true) tab = 3
    }

    private fun applySide() {
        val s = maxOf(side, 0)
        list.setPadding(dp(20) + s, list.paddingTop, dp(20) + s, list.paddingBottom)
        for (id in intArrayOf(R.id.quick_bar, R.id.undo_bar)) {
            val bar = findViewById<View>(id)
            val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
            lp.marginStart = dp(16) + s
            lp.marginEnd = dp(16) + s
            bar.layoutParams = lp
        }
        findViewById<View>(R.id.tabs).setPadding(s, 0, s, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("review", false)) forceReview = true
        if (intent.getBooleanExtra("plan", false)) openPlan = true
        if (intent.getBooleanExtra("day", false)) showDay()
        if (intent.getBooleanExtra("chains", false)) tab = 2
        if (intent.getBooleanExtra("notes", false)) tab = 3
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putString("selected", selected.toString())
        outState.putString("shown", shown.toString())
    }

    override fun onResume() {
        super.onResume()
        // The theme was switched in Settings: build the screen again in the new colours.
        if (Look.theme(this) != themeApplied) {
            recreate()
            return
        }
        val now = LocalDate.now()
        if (now != today) {
            if (selected == today) {
                selected = now
                month = YearMonth.from(now)
            }
            if (shown == today) shown = now
            today = now
        }
        render(if (firstShow) PAGE else NONE)
        firstShow = false
        // Changes from the other device slide into place while the app is open.
        Sync.onRemoteChange = { if (!gestureActive) renderMoving() }
        celebrateBadge()
        Focus.listeners.remove(focusListener)
        Focus.listeners.add(focusListener)
        Focus.askRating(this)
        Reminders.schedule(this)
        if (openPlan) {
            openPlan = false
            startActivity(Intent(this, PlanActivity::class.java))
        } else {
            maybeReview()
        }
    }

    /** Esc on a keyboard always leads back to the Today page. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) {
                input.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                if (tab != 0 || shown != today) {
                    tab = 0
                    shown = today
                    render(PAGE)
                    scroll.scrollTo(0, 0)
                } else {
                    scroll.smoothScrollTo(0, 0)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Swiping left or right anywhere on the Today page shows the next or previous day.
     * Watched here, before the page's own views, so it works over task lines too. Rows of
     * chips that scroll sideways keep their own swipe.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (tab == 0) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = e.rawX
                swipeY = e.rawY
                swipeOk = startsOnPage(e.rawX.toInt(), e.rawY.toInt())
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swipeOk = false
            MotionEvent.ACTION_UP -> {
                val dx = e.rawX - swipeX
                val dy = e.rawY - swipeY
                if (swipeOk && !gestureActive && abs(dx) > dp(70) && abs(dx) > abs(dy) * 1.6f) {
                    swipeOk = false
                    val handled = super.dispatchTouchEvent(e)
                    goDay(if (dx < 0) 1 else -1)
                    return handled
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun startsOnPage(x: Int, y: Int): Boolean {
        val r = android.graphics.Rect()
        if (!scroll.getGlobalVisibleRect(r) || !r.contains(x, y)) return false
        for (id in intArrayOf(R.id.filter_scroll, R.id.today_chips_scroll)) {
            val v = list.findViewById<View>(id) ?: continue
            if (v.visibility == View.VISIBLE && v.getGlobalVisibleRect(r) && r.contains(x, y)) return false
        }
        return true
    }

    /** The next (1) or previous (-1) day on the Today page. */
    private fun goDay(dir: Int) = goTo(shown.plusDays(dir.toLong()))

    /** Shows another day on the Today page; the page glides in from that side. */
    private fun goTo(target: LocalDate) {
        if (target == shown) return
        val dir = if (target.isAfter(shown)) 1 else -1
        shown = target
        render()
        scroll.scrollTo(0, 0)
        list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            v.alpha = 0f
            v.translationX = dir * dp(48).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(min(i, 8) * 14L).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    // Redraw when the focus session changes elsewhere (notification, widget, full screen).
    private val focusListener: () -> Unit = { list.post { if (!gestureActive) render() } }

    override fun onPause() {
        super.onPause()
        Focus.listeners.remove(focusListener)
        Sync.onRemoteChange = null
        list.removeCallbacks(ticker)
    }

    /** Jump to today's timeline in the Calendar (used by the timer's notification and banner). */
    private fun showDay() {
        tab = 1
        calMode = DAY
        selected = today
        month = YearMonth.from(today)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0 || shown != today) {
            tab = 0
            shown = today
            render(PAGE)
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

    /** Rebuilds the current page. `motion` says what should glide in: nothing, the whole page, or the part under the header. */
    private fun render(motion: Int = NONE) {
        summitCarry = list.findViewById<SummitView>(R.id.summit)?.progress
        list.removeCallbacks(ticker)
        dayTick = null
        list.removeAllViews()
        scores.clear()
        rows.clear()
        refreshHeader = {}
        entering = motion == PAGE
        notesPage.showFab(tab == 3)
        when (tab) {
            0 -> renderToday()
            1 -> renderCalendar()
            2 -> chains.build(list)
            3 -> notesPage.build(list)
            else -> renderSomeday()
        }
        entering = false
        if (motion != NONE) {
            val from = if (motion == BELOW_HEADER) 1 else 0
            enter((from until list.childCount).map { list.getChildAt(it) })
        }
        for (i in tabViews.indices) {
            val c = getColor(if (i == tab) R.color.accent else R.color.text_dim)
            tabViews[i].setTextColor(c)
            tabViews[i].compoundDrawableTintList = ColorStateList.valueOf(c)
        }
        input.hint = when (tab) {
            0 -> "Add a task for " + relDayInline(shown, today)
            1 -> "Add a task for " + relDayInline(selected, today)
            else -> "Add a task for someday"
        }
        // Chains and Notes have their own buttons for adding; the task bar is not needed there.
        val tasksPage = tab != 2 && tab != 3
        findViewById<View>(R.id.quick_bar).visibility = if (tasksPage) View.VISIBLE else View.GONE
        if (!tasksPage) {
            findViewById<View>(R.id.quick_preview).visibility = View.GONE
            input.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun renderToday() {
        val day = shown
        val isToday = day == today
        val h = layoutInflater.inflate(R.layout.header_today, list, false)
        list.addView(h)
        val hour = LocalTime.now().hour
        val away = java.time.temporal.ChronoUnit.DAYS.between(today, day)
        h.findViewById<TextView>(R.id.today_greeting).text = when {
            away == 1L -> "Tomorrow"
            away == -1L -> "Yesterday"
            away > 1L -> "In $away days"
            away < -1L -> "${-away} days ago"
            hour < 5 -> "Still up, Alp"
            hour < 12 -> "Good morning, Alp"
            hour < 18 -> "Good afternoon, Alp"
            else -> "Good evening, Alp"
        } + (if (isToday) Store.streak(today).let { if (it >= 2) "  ·  $it-day streak" else "" } else "")
        h.findViewById<View>(R.id.btn_stats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        h.findViewById<View>(R.id.btn_courses).setOnClickListener {
            startActivity(Intent(this, CoursesActivity::class.java))
        }
        h.findViewById<TextView>(R.id.today_title).text = weekdayName(day)
        h.findViewById<TextView>(R.id.today_sub).text = dayMonth(day) + (if (day.year != today.year) " " + day.year else "")
        h.findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // "Back to today" on other days, and how many days are left until each exam.
        val chipRow = h.findViewById<LinearLayout>(R.id.today_chips)
        fun topChip(c: TextView, onClick: () -> Unit) {
            c.setOnClickListener { onClick() }
            chipRow.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        }
        if (!isToday) topChip(chip("Back to today", true)) { goTo(today) }
        // Exams count from the day shown, and get more urgent as they come closer.
        for ((course, text, urgency) in Courses.examChips(day, today)) {
            val tint = when (urgency) {
                0 -> Look.tone(course.color)
                1 -> getColor(R.color.accent)
                else -> getColor(R.color.danger)
            }
            topChip(chip(text, urgency > 0, tint)) {
                startActivity(Intent(this, CoursesActivity::class.java))
            }
        }
        if (chipRow.childCount > 0) h.findViewById<View>(R.id.today_chips_scroll).visibility = View.VISIBLE

        val summit = h.findViewById<SummitView>(R.id.summit)
        val progress = h.findViewById<TextView>(R.id.today_progress)
        val planned = h.findViewById<TextView>(R.id.today_planned)
        val carry = summitCarry
        if (carry != null && !entering) summit.setProgress(carry, false)
        var first = true
        refreshHeader = {
            val all = Store.on(day)
            val done = all.count { it.isDone(day) }
            summit.setProgress(if (all.isEmpty()) 0f else done.toFloat() / all.size, !first || entering || carry != null)
            first = false
            progress.text = when {
                all.isEmpty() -> "No tasks yet"
                done == all.size -> "Summit reached · all ${all.size} done"
                else -> "$done of ${all.size} done"
            }
            progress.setTextColor(getColor(if (all.isNotEmpty() && done == all.size) R.color.accent else R.color.text_primary))
            val left = all.filter { !it.isDone(day) }.sumOf { it.duration }
            planned.text = if (left > 0) fmtDuration(left) + " left" else ""
        }
        refreshHeader()

        // Banners about today only show on today.
        val missed = if (isToday) Store.missed(today).size else 0
        val banner = h.findViewById<TextView>(R.id.review_banner)
        if (missed > 0) {
            banner.visibility = View.VISIBLE
            banner.text = plural(missed, "unfinished task") + " from earlier days  ·  Review"
            banner.setOnClickListener {
                forceReview = true
                maybeReview()
            }
        }

        val p = prefs(this)
        // The focus card, under the mountain (today only).
        if (isToday) {
            val hl = h as LinearLayout
            hl.addView(focusCard(today) { render() }, hl.indexOfChild(h.findViewById(R.id.focus_banner)))
        }
        val nowMinutes = LocalTime.now().hour * 60 + LocalTime.now().minute
        if (isToday && p.getBoolean("evening", true) && nowMinutes >= p.getInt("eveningTime", 21 * 60 + 30) &&
            p.getString("planShown", "") != today.toString()
        ) {
            val plan = h.findViewById<TextView>(R.id.plan_banner)
            plan.visibility = View.VISIBLE
            plan.text = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) "Sunday: weekly review and plan Monday  ·  Open"
            else "Evening: set up tomorrow  ·  Plan"
            plan.setOnClickListener { startActivity(Intent(this, PlanActivity::class.java)) }
        }

        val all = Store.on(day)
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
            for (c in used) add(c.name, c.id, Look.tone(c.color))
        }

        addEvents(day)
        // Notes pinned to this day, and study blocks suggested before an exam (also in Calendar → Day).
        for (n in Store.notes.items.filter { !it.developer && it.pin == day }) list.addView(noteCard(n, today))
        for (sg in Courses.studyBlocks(this, day, today)) list.addView(suggestionCard(sg))
        val visible = all.filter { filter.isEmpty() || it.categoryId == filter }
        if (visible.isEmpty()) list.addView(emptyText(if (isToday) "A clear day.\nAdd your first task below." else "Nothing planned for this day."))
        for (t in visible) list.addView(taskRow(t, day))
    }

    private fun renderCalendar() {
        val h = layoutInflater.inflate(R.layout.header_calendar, list, false)
        list.addView(h)
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        h.findViewById<TextView>(R.id.cal_title).text = when (calMode) {
            WEEK -> weekTitle(monday)
            DAY -> if (selected == today) "Today" else shortDay(selected)
            else -> monthYear(month.atDay(1))
        }
        h.findViewById<View>(R.id.cal_prev).setOnClickListener { shift(-1) }
        h.findViewById<View>(R.id.cal_next).setOnClickListener { shift(1) }

        val modes = h.findViewById<LinearLayout>(R.id.cal_modes)
        fun mode(label: String, on: Boolean, action: () -> Unit) {
            val c = chip(label, on)
            c.setOnClickListener { action() }
            modes.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        }
        mode("Month", calMode == MONTH) { setMode(MONTH) }
        mode("Week", calMode == WEEK) { setMode(WEEK) }
        mode("Day", calMode == DAY) { setMode(DAY) }
        val spacer = View(this)
        modes.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        if (selected != today || YearMonth.from(today) != month) {
            mode("Today", false) {
                selected = today
                month = YearMonth.from(today)
                        render(BELOW_HEADER)
                slideIn(list.findViewById(R.id.cal_grid), 0)
            }
        }

        val grid = h.findViewById<MonthGridView>(R.id.cal_grid)
        grid.weekMode = calMode != MONTH
        grid.month = month
        grid.selected = selected
        grid.today = today
        grid.onSwipe = { shift(if (calMode == DAY) it * 7 else it) }
        grid.onSelect = {
            val sameMonth = calMode != MONTH || YearMonth.from(it) == month
            selected = it
            month = YearMonth.from(it)
                render(BELOW_HEADER)
            val fresh = list.findViewById<MonthGridView>(R.id.cal_grid)
            if (sameMonth) fresh?.popSelection() else slideIn(fresh, 0)
        }
        refreshHeader = {
            grid.invalidate()
            for ((d, tv) in scores) tv.text = scoreText(d)
        }

        if (calMode == DAY) {
            renderDay()
        } else if (calMode == MONTH) {
            list.addView(dayHeading(selected, true))
            addEvents(selected)
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
                    list.findViewById<MonthGridView>(R.id.cal_grid)?.popSelection()
                }
                list.addView(heading)
                val events = addEvents(d)
                val tasks = Store.on(d)
                if (tasks.isEmpty() && events == 0) {
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

    private fun setMode(mode: Int) {
        if (calMode == mode) return
        calMode = mode
        prefs(this).edit().putInt("calMode", mode).apply()
        month = YearMonth.from(selected)
        render(BELOW_HEADER)
        slideIn(list.findViewById(R.id.cal_grid), 0)
    }

    /** Arrows and swipes: one month, one week or one day (a swipe on the week strip passes 7 days). */
    private fun shift(dir: Int) {
        when (calMode) {
            WEEK -> selected = selected.plusWeeks(dir.toLong())
            DAY -> selected = selected.plusDays(dir.toLong())
            else -> {
                val m = month.plusMonths(dir.toLong())
                selected = if (YearMonth.from(today) == m) today else m.atDay(1)
            }
        }
        month = YearMonth.from(selected)
        render(BELOW_HEADER)
        slideIn(list.findViewById(R.id.cal_grid), if (dir > 0) 1 else -1)
    }

    // ---------- Day view: the day by the hour, with "Start my day" ----------

    private fun renderDay() {
        val d = selected
        val isToday = d == today
        val p = prefs(this)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Progress card: start button, the task the timer is on, and how far the day is.
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(getColor(R.color.surface), dp(18))
        card.setPadding(dp(16), dp(14), dp(16), dp(16))
        val cardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(14)
        list.addView(card, cardLp)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        card.addView(top)
        val start = android.widget.ImageButton(this)
        start.setBackgroundResource(R.drawable.bg_accent_round)
        start.setColorFilter(getColor(R.color.on_accent))
        if (isToday) {
            val lp = LinearLayout.LayoutParams(dp(52), dp(52))
            lp.marginEnd = dp(14)
            top.addView(start, lp)
        }
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val nowTitle = TextView(this)
        nowTitle.textSize = 16f
        nowTitle.typeface = medium
        nowTitle.maxLines = 1
        nowTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        nowTitle.setTextColor(getColor(R.color.text_primary))
        words.addView(nowTitle)
        val nowSub = TextView(this)
        nowSub.textSize = 13f
        nowSub.setPadding(0, dp(2), 0, 0)
        words.addView(nowSub)
        val finish = chip("Finish", true)
        top.addView(finish)
        (finish.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)

        val taskBar = BarView(this)
        val taskBarLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        taskBarLp.topMargin = dp(12)
        card.addView(taskBar, taskBarLp)

        val dayText = TextView(this)
        dayText.textSize = 13f
        dayText.setTextColor(getColor(R.color.text_secondary))
        dayText.setPadding(0, dp(14), 0, dp(6))
        card.addView(dayText)
        val dayBar = BarView(this)
        card.addView(dayBar)
        val hint = TextView(this)
        hint.textSize = 13f
        hint.setTextColor(getColor(R.color.danger))
        hint.setPadding(0, dp(10), 0, 0)
        card.addView(hint)

        // Events that last all day stay as lines; timed ones go into the timeline.
        val events = PhoneCalendar.on(this, d)
        addEvents(d, true)

        // Notes pinned to this day, and study blocks Summit suggests before an exam.
        for (n in Store.notes.items.filter { !it.developer && it.pin == d }) list.addView(noteCard(n, today))
        for (sg in Courses.studyBlocks(this, d, today)) list.addView(suggestionCard(sg))

        // Tasks without a time: a row on top. Hold one and drag it into the day to give it a time.
        val all = Store.on(d)
        val loose = all.filter { it.time < 0 }
        if (loose.isNotEmpty()) {
            val label = TextView(this)
            label.text = "No time yet  ·  hold one and drag it into the day"
            label.textSize = 12f
            label.setTextColor(getColor(R.color.text_dim))
            label.setPadding(0, dp(16), 0, dp(8))
            list.addView(label)
            val strip = HorizontalScrollView(this)
            strip.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this)
            strip.addView(row)
            for (t in loose) {
                val done = t.isDone(d)
                val c = chip(t.title + (if (t.duration > 0) "  ·  " + fmtDuration(t.duration) else ""), false,
                    Store.colorOf(t) ?: getColor(R.color.accent))
                c.maxWidth = dp(240)
                c.maxLines = 1
                c.ellipsize = android.text.TextUtils.TruncateAt.END
                if (done) {
                    c.paintFlags = c.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    c.alpha = 0.5f
                } else {
                    c.setTextColor(getColor(R.color.text_primary))
                    c.setOnLongClickListener { v ->
                        v.startDragAndDrop(
                            android.content.ClipData.newPlainText("task", t.title), View.DragShadowBuilder(v), t.id, 0
                        )
                        true
                    }
                }
                c.setOnClickListener { openEditor(t, "", d) }
                row.addView(c)
                (c.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
            }
            list.addView(strip)
        }

        val timeline = DayTimelineView(this)
        val timelineLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        timelineLp.topMargin = dp(10)
        list.addView(timeline, timelineLp)
        timeline.setData(d, isToday, all, events, LocalTime.now().hour * 60 + LocalTime.now().minute)
        timeline.onOpen = { openEditor(it, "", d) }
        timeline.onTick = { setDone(it, d, !it.isDone(d)) }
        timeline.onEmpty = { minutes -> openEditor(null, "", null, minutes) }
        timeline.onChange = { t, time, duration ->
            t.time = time
            t.duration = duration
            t.updated = System.currentTimeMillis()
            Store.save()
            Reminders.schedule(this)
            render()
        }
        timeline.onDropTask = { id, minutes ->
            Store.task(id)?.let { t ->
                t.time = minutes
                t.updated = System.currentTimeMillis()
                Store.save()
                Reminders.schedule(this)
                render()
            }
        }
        // While something is dragged near the top or bottom edge, the page scrolls along.
        timeline.onAutoScroll = { rawY ->
            val at = IntArray(2)
            scroll.getLocationOnScreen(at)
            val step = when {
                rawY < at[1] + dp(70) -> -dp(11)
                rawY > at[1] + scroll.height - dp(70) -> dp(11)
                else -> 0
            }
            val before = scroll.scrollY
            if (step != 0) scroll.scrollBy(0, step)
            scroll.scrollY - before
        }
        // The play buttons and "Start my day" use the one focus engine (Focus.kt).
        timeline.onPlay = { t ->
            if (Focus.task(this) === t) {
                if (Focus.paused(this)) Focus.resume(this) else Focus.pause(this)
            } else {
                beginFocus(t, today, 0, true) { render() }
            }
            render()
        }
        start.setOnClickListener {
            when {
                Focus.active(this) && Focus.paused(this) -> Focus.resume(this)
                Focus.active(this) && !Focus.onBreak(this) -> Focus.pause(this)   // the minutes so far are kept
                !Focus.active(this) -> {
                    val next = Store.queue(today).firstOrNull() ?: return@setOnClickListener
                    beginFocus(next, today, 0, true) { render() }
                }
            }
            start.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            render()
        }
        finish.setOnClickListener {
            val t = Reminders.runningTask(this) ?: return@setOnClickListener
            setDone(t, Focus.date(this), true)
        }

        // Numbers that move: refreshed by the ticker, every second while a timer runs.
        dayTick = {
            val now = LocalTime.now()
            val nowMin = now.hour * 60 + now.minute
            val running = Reminders.runningTask(this)
            val extra = Reminders.runningMinutes(this)
            timeline.runningId = running?.id ?: ""
            timeline.runningExtra = extra
            timeline.nowMinutes = if (isToday) nowMin else -1
            timeline.invalidate()

            start.setImageResource(if (running != null) R.drawable.ic_pause else R.drawable.ic_play)
            start.contentDescription = if (running != null) "Pause" else "Start my day"
            finish.visibility = if (isToday && running != null) View.VISIBLE else View.GONE
            taskBar.visibility = if (isToday && running != null && running.duration > 0) View.VISIBLE else View.GONE
            nowSub.setTextColor(getColor(R.color.text_secondary))
            if (!isToday) {
                nowTitle.text = weekdayName(d) + " " + dayMonth(d)
                nowSub.text = plural(all.size, "task") + "  ·  " + all.count { it.time >= 0 } + " with a time"
            } else if (running != null) {
                val clock = (if (Focus.onBreak(this)) "Break " else if (Focus.paused(this)) "Paused " else "") + Focus.clock(this)
                val worked = running.worked(today) + extra
                nowTitle.text = "Now: " + running.title
                if (running.duration > 0) {
                    val left = running.duration - worked
                    val percent = worked * 100 / running.duration
                    taskBar.fraction = worked.toFloat() / running.duration
                    taskBar.color = getColor(if (left < 0) R.color.danger else R.color.accent)
                    taskBar.invalidate()
                    if (left < 0) {
                        nowSub.setTextColor(getColor(R.color.danger))
                        nowSub.text = "$clock  ·  $percent%  ·  over by " + fmtDuration(-left)
                    } else {
                        nowSub.text = "$clock  ·  $percent% of " + fmtDuration(running.duration) + "  ·  " +
                            (if (left == 0) "time is up" else fmtDuration(left) + " left")
                    }
                } else {
                    nowSub.text = "$clock  ·  no duration set for this task"
                }
            } else {
                val next = Store.queue(today).firstOrNull()
                nowTitle.text = when {
                    next != null -> "Next: " + next.title
                    all.isEmpty() -> "Nothing planned today"
                    else -> "Everything is done"
                }
                nowSub.text = if (next != null) "Press start. When you finish a task, the next one begins." else ""
                start.alpha = if (next != null) 1f else 0.35f
            }

            val (doneMin, plannedMin) = Store.dayTime(d, if (isToday) running else null, if (isToday) extra else 0)
            val without = all.count { it.duration <= 0 }
            if (plannedMin > 0) {
                dayText.text = "Day ${doneMin * 100 / plannedMin}%  ·  " + (if (doneMin > 0) fmtDuration(doneMin) else "0m") +
                    " of " + fmtDuration(plannedMin) + " planned" +
                    (if (without > 0) "  ·  " + plural(without, "task") + " without a duration" else "")
                dayBar.fraction = doneMin.toFloat() / plannedMin
            } else {
                dayText.text = "Give your tasks a duration to see how far the day is."
                dayBar.fraction = 0f
            }
            dayBar.invalidate()

            // Running late: open tasks whose planned slot is already over.
            val late = if (isToday) all.count {
                !it.isDone(d) && it.time >= 0 && it !== running && it.time + maxOf(it.duration, 0) < nowMin
            } else 0
            hint.visibility = if (late > 0) View.VISIBLE else View.GONE
            hint.text = if (late == 1) "1 task is behind schedule." else "$late tasks are behind schedule."
        }
        dayTick?.invoke()
        list.postDelayed(ticker, 1000L)
    }

    /** "Study ECON 101 · 14:00–15:00" with Accept and Skip. Accepting makes it a task in the timeline. */
    private fun suggestionCard(sg: Courses.Suggestion): View {
        val color = Look.tone(sg.course.color)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(withAlpha(color, 0x1F), dp(16), withAlpha(color, 0x66), dp(1))
        card.setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp
        val title = TextView(this)
        title.text = "Suggested: " + sg.title + "  ·  " +
            (if (sg.time >= 0) fmtTime(sg.time) + " – " + fmtTime(sg.time + sg.duration) else fmtDuration(sg.duration) + ", no free hour 09–21")
        title.textSize = 15f
        title.setTextColor(getColor(R.color.text_primary))
        card.addView(title)
        val why = TextView(this)
        why.text = sg.why
        why.textSize = 13f
        why.setTextColor(getColor(R.color.text_secondary))
        why.setPadding(0, dp(2), 0, dp(10))
        card.addView(why)
        // What to study: the exam's topics from the course notebook.
        if (sg.topics.isNotEmpty()) {
            val topics = TextView(this)
            topics.text = "To study:\n" + sg.topics.take(8).joinToString("\n") { "•  $it" } +
                (if (sg.topics.size > 8) "\n…and ${sg.topics.size - 8} more" else "")
            topics.textSize = 14f
            topics.setLineSpacing(dp(2).toFloat(), 1f)
            topics.setTextColor(getColor(R.color.text_primary))
            topics.setPadding(0, 0, 0, dp(10))
            card.addView(topics)
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val accept = chip("Accept", true, color)
        accept.setOnClickListener {
            Courses.accept(this, sg)
            card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            render()
        }
        row.addView(accept)
        val skip = chip("Skip", false)
        skip.setOnClickListener {
            collapse(card) {
                Courses.skip(sg)
                render()
            }
        }
        row.addView(skip)
        (skip.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        card.addView(row)
        return card
    }

    /** Tick or untick from the Day view (also the "Finish" button of the running task). */
    private fun setDone(t: Task, d: LocalDate, value: Boolean) {
        Store.setDone(t, d, value)
        Store.save()
        if (value) Reminders.taskTicked(this, t, d)   // logs the timer and moves on to the next task
        Reminders.schedule(this)
        val (done, total) = Store.progress(today)
        val dayDone = value && d == today && done == total
        list.performHapticFeedback(
            when {
                dayDone -> HapticFeedbackConstants.LONG_PRESS
                !value -> HapticFeedbackConstants.CLOCK_TICK
                Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
        if (dayDone) list.postDelayed({ celebrate() }, 400)
        if (Store.milestone != null) list.postDelayed({ celebrateBadge() }, if (dayDone) 3400 else 500)
        render()
    }

    /** The calendar grid slides in from the side it was moved towards. */
    private fun slideIn(grid: View?, dir: Int) {
        if (grid == null) return
        grid.alpha = 0f
        grid.translationX = dir * dp(56).toFloat()
        grid.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun renderSomeday() {
        val title = TextView(this)
        title.text = "Someday"
        title.textSize = 36f
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        title.setTextColor(getColor(R.color.text_primary))
        list.addView(title)
        val sub = TextView(this)
        sub.text = "Tasks without a date. Hold one to give it a day."
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
                val gone = Store.tasks.filter { it.date == null && it.done }
                act("Cleared " + plural(gone.size, "completed task"), { Store.tasks.addAll(gone) }) {
                    Store.tasks.removeAll(gone.toSet())
                }
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

    /** Lines for the phone calendar's events of a day (if switched on in Settings). Returns how many. */
    private fun addEvents(d: LocalDate, allDayOnly: Boolean = false): Int {
        val events = PhoneCalendar.on(this, d).filter { !allDayOnly || it.allDay }
        for (e in events) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(7), 0, dp(7))
            val bar = View(this)
            bar.background = rounded(Look.tone(e.color), dp(2))
            val lp = LinearLayout.LayoutParams(dp(3), dp(32))
            lp.marginStart = dp(10)
            lp.marginEnd = dp(24)
            row.addView(bar, lp)
            val words = LinearLayout(this)
            words.orientation = LinearLayout.VERTICAL
            val title = TextView(this)
            title.text = e.title
            title.textSize = 15f
            title.setTextColor(getColor(R.color.text_primary))
            words.addView(title)
            val sub = TextView(this)
            sub.text = (if (e.allDay) "All day" else fmtTime(e.start) + " – " + fmtTime(e.end % (24 * 60))) +
                (if (e.place.isNotBlank()) "  ·  " + e.place else "")
            sub.textSize = 13f
            sub.maxLines = 1
            sub.setTextColor(getColor(R.color.text_secondary))
            words.addView(sub)
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        return events.size
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

    /** One task line on screen: `view` is the outer frame, `inner` the line itself. */
    private class Row(val view: FrameLayout, val inner: View, val task: Task, val date: LocalDate?)

    private fun key(t: Task, d: LocalDate?): String = t.id + "|" + (d?.toString() ?: "")

    private fun taskRow(t: Task, d: LocalDate?): View {
        val frame = FrameLayout(this)
        val inner = layoutInflater.inflate(R.layout.row_task, frame, false)
        frame.addView(inner)
        val r = Row(frame, inner, t, d)
        rows.add(r)
        bindRow(r, false)
        inner.setOnTouchListener(RowTouch(r))
        return frame
    }

    /** Tick or untick a task, with a light vibration. Ticked tasks sink to the bottom a moment later. */
    private fun toggle(r: Row) {
        val value = !r.task.isDone(r.date)
        Store.setDone(r.task, r.date, value)
        Store.save()
        if (value) Reminders.taskTicked(this, r.task, r.date)   // logs a running timer, moves "my day" on
        bindRow(r, true)
        refreshHeader()
        Reminders.schedule(this)
        val (done, total) = Store.progress(today)
        val dayDone = value && r.date == today && done == total
        r.view.performHapticFeedback(
            when {
                dayDone -> HapticFeedbackConstants.LONG_PRESS
                !value -> HapticFeedbackConstants.CLOCK_TICK
                Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
        if (dayDone) list.postDelayed({ celebrate() }, 500)
        if (Store.milestone != null) list.postDelayed({ celebrateBadge() }, if (dayDone) 3400 else 500)
        val token = ++settleToken
        list.postDelayed({ if (token == settleToken && !gestureActive) renderMoving() }, 430)
    }

    /** A task connected to a habit (or the widget) earned a chain badge: show its moment once. */
    private fun celebrateBadge() {
        val m = Store.milestone ?: return
        if (isFinishing) return
        Store.milestone = null
        Store.habits.get(m.first)?.let { showMilestone(this, it, m.second) }
    }

    /** The full-screen moment when every task of today is done. */
    private fun celebrate() {
        val (done, total) = Store.progress(today)
        if (total == 0 || done != total || isFinishing) return
        val content = findViewById<ViewGroup>(android.R.id.content)
        val v = CelebrationView(this, total)
        content.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.play { content.removeView(v) }
    }

    /** Rebuilds the page and lets every line slide from where it was to where it belongs now. */
    private fun renderMoving() {
        val old = HashMap<String, Float>()
        for (r in rows) old[key(r.task, r.date)] = r.view.top + r.view.translationY
        render()
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                for (r in rows) {
                    val was = old[key(r.task, r.date)]
                    if (was == null) {
                        r.view.alpha = 0f
                        r.view.animate().alpha(1f).setDuration(240).start()
                    } else if (was != r.view.top.toFloat()) {
                        r.view.translationY = was - r.view.top
                        r.view.animate().translationY(0f).setDuration(300)
                            .setInterpolator(DecelerateInterpolator(1.6f)).start()
                    }
                }
                return true
            }
        })
    }

    private fun bindRow(r: Row, animate: Boolean) {
        val row = r.inner
        val t = r.task
        val d = r.date
        val done = t.isDone(d)

        val box = row.findViewById<BoxView>(R.id.box)
        box.color = Store.colorOf(t) ?: getColor(R.color.accent)
        box.setChecked(done, animate)
        box.contentDescription = if (done) "Done" else "Not done"
        box.setOnClickListener { toggle(r) }

        // Ticking the box draws a line through the title.
        val title = row.findViewById<TitleView>(R.id.title)
        title.text = t.title
        title.setStruck(done, animate)
        setMeta(row, t, d, animate)

        row.setOnClickListener { openEditor(t, "", d) }

        // Subtasks can be ticked right in the list. They fold away once the task is done.
        val subs = row.findViewById<LinearLayout>(R.id.subs)
        settle(subs)
        val show = t.subtasks.isNotEmpty() && !done
        if (animate && show != (subs.visibility == View.VISIBLE)) {
            if (show) {
                fillSubs(row, subs, t, d)
                expand(subs, row.width)
            } else {
                collapse(subs) {}
            }
        } else {
            subs.visibility = if (show) View.VISIBLE else View.GONE
            if (show) fillSubs(row, subs, t, d) else subs.removeAllViews()
        }
    }

    /** The small grey line under a title: time, duration, category and so on. */
    private fun setMeta(row: View, t: Task, d: LocalDate?, animate: Boolean) {
        val done = t.isDone(d)
        val cat = Store.category(t.categoryId)
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
        Store.courseOf(t)?.let { part(it.code, Look.tone(it.color)) }
        if (cat != null) part(cat.name, Look.tone(cat.color))
        Store.habits.get(t.habitId)?.let { part("Chain: " + it.title, Look.tone(it.color)) }
        if (t.repeating) part(repeatLabel(t.repeatMask))
        if (t.subtasks.isNotEmpty()) {
            val ticked = t.subtasks.count { t.subKey(d, it) in t.subDone }
            part("$ticked/${t.subtasks.size}")
        }
        if (t.spent > 0) part(fmtDuration(t.spent) + " focused")
        if (t.priority == 1) part("Low")
        if (t.notes.isNotBlank()) part("Note")
        meta.text = sb
        meta.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        val alpha = if (done) 0.45f else 1f
        meta.animate().cancel()
        if (animate) meta.animate().alpha(alpha).setDuration(220).start() else meta.alpha = alpha
    }

    private fun fillSubs(row: View, subs: LinearLayout, t: Task, d: LocalDate?) {
        subs.removeAllViews()
        for (s in t.subtasks) {
            val key = t.subKey(d, s)
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val sbox = BoxView(this)
            sbox.boxSize = 16f
            sbox.color = getColor(R.color.text_secondary)
            sbox.setChecked(key in t.subDone, false)
            line.addView(sbox, LinearLayout.LayoutParams(dp(34), dp(34)))
            val st = TitleView(this)
            st.normalColor = getColor(R.color.text_secondary)
            st.struckColor = getColor(R.color.text_dim)
            st.text = s.title
            st.textSize = 14f
            st.setStruck(key in t.subDone, false)
            line.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.setOnClickListener {
                val on = key !in t.subDone
                if (on) t.subDone.add(key) else t.subDone.remove(key)
                t.updated = System.currentTimeMillis()
                Store.save()
                sbox.setChecked(on, true)
                st.setStruck(on, true)
                setMeta(row, t, d, false)
            }
            subs.addView(line)
        }
    }

    private fun rowMenu(r: Row) {
        val t = r.task
        val d = r.date
        val m = PopupMenu(this, r.inner, Gravity.END)
        m.menu.add(0, 1, 0, "Edit")
        if (!t.isDone(d)) m.menu.add(0, 8, 0, "Start focus timer")
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
                2 -> collapse(r.view) { moveTo(t, today) }
                3 -> collapse(r.view) { moveTo(t, today.plusDays(1)) }
                4 -> collapse(r.view) { moveTo(t, null) }
                5 -> collapse(r.view) { skip(t, d) }
                6 -> AlertDialog.Builder(this)
                    .setMessage("Delete \"${t.title}\" on every day it repeats?")
                    .setPositiveButton("Delete") { _, _ -> collapse(r.view) { delete(t) } }
                    .setNegativeButton("Cancel", null)
                    .show()
                7 -> collapse(r.view) { delete(t) }
                8 -> startActivity(
                    Intent(this, FocusActivity::class.java).putExtra("id", t.id).putExtra("date", d?.toString() ?: "")
                )
            }
            true
        }
        m.show()
    }

    // ---------- Changes that can be undone ----------

    private fun moveTo(t: Task, target: LocalDate?) {
        val date = t.date
        val mask = t.repeatMask
        val end = t.endDate
        val rank = t.rank
        val where = if (target == null) "Someday" else relDayInline(target, today)
        act("Moved to $where", {
            t.date = date
            t.repeatMask = mask
            t.endDate = end
            t.rank = rank
        }) { Store.move(t, target) }
    }

    private fun skip(t: Task, d: LocalDate?) {
        if (d == null) return
        act("Skipped for this day", { t.skipDates.remove(d.toString()) }) {
            t.skipDates.add(d.toString())
            t.updated = System.currentTimeMillis()
        }
    }

    private fun delete(t: Task) {
        val index = Store.tasks.indexOf(t)
        act("Task deleted", { Store.tasks.add(index.coerceIn(0, Store.tasks.size), t) }) { Store.tasks.remove(t) }
    }

    /** Does `action`, then offers "Undo" for a few seconds; `restore` puts things back. */
    private fun act(message: String, restore: () -> Unit, action: () -> Unit) {
        action()
        Store.save()
        renderMoving()
        Reminders.schedule(this)
        offerUndo(message, restore)
    }

    private fun offerUndo(message: String, restore: () -> Unit) {
        undo = restore
        findViewById<TextView>(R.id.undo_text).text = message
        val bar = findViewById<View>(R.id.undo_bar)
        settle(bar)
        if (bar.visibility != View.VISIBLE) {
            val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
            expand(bar, root.width - root.paddingLeft - root.paddingRight - lp.marginStart - lp.marginEnd)
        }
        bar.removeCallbacks(hideUndo)
        bar.postDelayed(hideUndo, 5000)
    }

    private fun dismissUndo() {
        undo = null
        val bar = findViewById<View>(R.id.undo_bar)
        bar.removeCallbacks(hideUndo)
        collapse(bar) {}
    }

    // ---------- Holding and dragging a task ----------

    /**
     * Touch handling of one task line:
     * tap = edit, hold = menu (move, skip, delete), hold and drag = put the task somewhere else
     * in its list. Ticking is done with the box. A sideways swipe is left to the page (other day).
     */
    private inner class RowTouch(val r: Row) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = IDLE
        private var moved = false
        private var group: List<Row> = emptyList()
        private var from = 0
        private var to = 0
        private val holding = Runnable { startDrag() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    mode = IDLE
                    moved = false
                    v.drawableHotspotChanged(e.x, e.y)
                    v.isPressed = true
                    v.postDelayed(holding, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    when (mode) {
                        IDLE -> if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            // The page scrolls (or swipes to another day) and takes the touch from here.
                            release(v)
                            moved = true
                        }
                        DRAG -> dragTo(dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val was = mode
                    release(v)
                    mode = IDLE
                    when (was) {
                        IDLE -> if (!moved) v.performClick()
                        DRAG -> endDrag(true)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    val was = mode
                    release(v)
                    mode = IDLE
                    if (was == DRAG) endDrag(false)
                }
            }
            return true
        }

        private fun release(v: View) {
            v.removeCallbacks(holding)
            v.isPressed = false
        }

        /** Keeps the page from scrolling while a drag is going on. */
        private fun claim(v: View) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
            gestureActive = true
        }

        /** The finger rested on the line: lift it. Open tasks can then be dragged within their list. */
        private fun startDrag() {
            if (mode != IDLE || moved) return
            mode = DRAG
            r.inner.isPressed = false
            claim(r.inner)
            r.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val can = !r.task.isDone(r.date) && (tab != 0 || filter.isEmpty())
            group = if (can) rows.filter { it.date == r.date && !it.task.isDone(it.date) } else listOf(r)
            from = group.indexOf(r)
            to = from
            r.view.background = rounded(getColor(R.color.surface2), dp(12))
            r.view.translationZ = dp(8).toFloat()
            r.view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
        }

        private fun dragTo(dy: Float) {
            if (abs(dy) > slop) moved = true
            if (group.size < 2) return
            val lowest = (group.first().view.top - r.view.top - dp(10)).toFloat()
            val highest = (group.last().view.bottom - r.view.bottom + dp(10)).toFloat()
            val y = dy.coerceIn(lowest, highest)
            r.view.translationY = y
            val center = r.view.top + r.view.height / 2f + y
            var index = 0
            for (g in group) if (g !== r && g.view.top + g.view.height / 2f < center) index++
            if (index == to) return
            to = index
            r.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            // The lines in between make room.
            val h = r.view.height.toFloat()
            for ((i, g) in group.withIndex()) {
                if (g === r) continue
                val target = when {
                    from < to && i in (from + 1)..to -> -h
                    to < from && i in to until from -> h
                    else -> 0f
                }
                g.view.animate().translationY(target).setDuration(170).start()
            }
        }

        private fun endDrag(released: Boolean) {
            gestureActive = false
            r.view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            r.view.translationZ = 0f
            r.view.background = null
            if (!moved) {
                // Held without moving: the menu.
                r.view.translationY = 0f
                if (released) rowMenu(r)
                return
            }
            if (to == from || group.size < 2) {
                r.view.animate().translationY(0f).setDuration(200).start()
                return
            }
            val ordered = group.toMutableList()
            ordered.removeAt(from)
            ordered.add(to, r)
            for ((i, g) in ordered.withIndex()) g.task.rank = i.toDouble()
            Store.save()
            renderMoving()
        }
    }

    // ---------- Adding ----------

    /** The day new tasks go to on the current page (null on Someday). */
    private fun targetDate(): LocalDate? = when (tab) {
        0 -> shown
        1 -> selected
        else -> null
    }

    private fun quickAdd() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            openEditor(null, "")
            return
        }
        // "gym tomorrow 18:00 45m" -> title "gym", tomorrow, 18:00, 45 minutes, category Gym.
        val p = parseQuick(text, today, Store.categories)
        val t = Task()
        t.title = p.title
        t.date = if (p.hasDate) p.date else targetDate()
        if ((p.time >= 0 || p.repeatMask != 0) && t.date == null) t.date = today
        t.time = p.time
        t.duration = p.duration
        if (p.priority != 0) t.priority = p.priority
        t.categoryId = if (p.categoryId.isNotEmpty()) p.categoryId else if (tab == 0) filter else ""
        t.repeatMask = p.repeatMask
        Store.tasks.add(t)
        Store.save()
        Reminders.schedule(this)
        input.setText("")
        render()
        val row = rows.firstOrNull { it.task === t }?.view
        if (row == null) {
            // It went to another day or list, so say where, with a way back.
            val where = t.date?.let { relDayInline(it, today) } ?: "Someday"
            offerUndo("Added for $where") { Store.tasks.remove(t) }
            return
        }
        // The new task grows into the list, and the list scrolls to it.
        expand(row, list.width - list.paddingLeft - list.paddingRight)
        scroll.post { scroll.smoothScrollTo(0, maxOf(0, row.top - dp(120))) }
    }

    private fun openEditor(t: Task?, title: String, d: LocalDate? = null, time: Int = -1) {
        val i = Intent(this, EditTaskActivity::class.java)
        if (t != null) {
            i.putExtra("id", t.id)
            if (d != null) i.putExtra("date", d.toString())
        } else {
            i.putExtra("title", title)
            if (time >= 0) i.putExtra("time", time)
            targetDate()?.let { i.putExtra("date", it.toString()) }
            if (tab == 0) i.putExtra("category", filter)
            input.setText("")
        }
        startActivity(i)
    }
}
