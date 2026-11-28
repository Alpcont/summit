package com.alp.summit

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime

/** Starts a session; before the first one, asks once for Do Not Disturb and app blocking. */
fun Activity.beginFocus(t: Task?, d: LocalDate, minutes: Int, dayMode: Boolean, after: () -> Unit = {}) {
    val go = {
        Focus.start(this, t, d, minutes, dayMode)
        after()
    }
    Focus.askDndOnce(this) {
        if (!Blocking.enabled(this) && !prefs(this).getBoolean("askedBlocking", false)) Blocking.guide(this) { go() } else go()
    }
}

/** The task the focus card will start on: picked by hand, or the next open task of today. */
object FocusPick {
    var taskId: String? = null      // null = next open task, "" = no task

    fun task(today: LocalDate): Task? = when (val id = taskId) {
        null -> Store.queue(today).firstOrNull()
        "" -> null
        else -> Store.task(id)?.takeIf { !it.isDone(today) } ?: Store.queue(today).firstOrNull()
    }
}

/** A ring with a short text in its middle: today's focus against the goal. */
class GoalRing(ctx: Context) : View(ctx) {
    var fraction = 0f
    var label = ""
    var sub = ""
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val stroke = 6f * d
        val size = minOf(width, height) - stroke
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        line.strokeWidth = stroke
        line.color = context.getColor(R.color.surface2)
        c.drawOval(oval, line)
        if (fraction > 0f) {
            line.color = context.getColor(R.color.accent)
            c.drawArc(oval, -90f, 360f * fraction.coerceIn(0f, 1f), false, line)
        }
        text.typeface = Fonts.manrope(context, 800)
        text.textSize = size * 0.22f
        text.color = context.getColor(R.color.text_primary)
        c.drawText(label, cx, cy + (if (sub.isEmpty()) text.textSize * 0.35f else 0f), text)
        if (sub.isNotEmpty()) {
            text.typeface = Fonts.manrope(context, 500)
            text.textSize = size * 0.13f
            text.color = context.getColor(R.color.text_secondary)
            c.drawText(sub, cx, cy + size * 0.2f, text)
        }
    }
}

/**
 * The focus card on Today, under the mountain: the goal ring, a task (or none), a length, Start.
 * While a session runs it shows the timer, the task, Pause and Stop.
 */
fun Activity.focusCard(today: LocalDate, rerender: () -> Unit): View {
    val a = this
    val p = prefs(a)
    val card = LinearLayout(a)
    card.orientation = LinearLayout.VERTICAL
    card.background = rounded(getColor(R.color.surface), dp(18))
    card.setPadding(dp(16), dp(14), dp(16), dp(14))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(14)
    card.layoutParams = lp

    val top = LinearLayout(a)
    top.orientation = LinearLayout.HORIZONTAL
    top.gravity = Gravity.CENTER_VERTICAL
    card.addView(top)
    val ring = GoalRing(a)
    top.addView(ring, LinearLayout.LayoutParams(dp(68), dp(68)))
    val words = LinearLayout(a)
    words.orientation = LinearLayout.VERTICAL
    words.setPadding(dp(14), 0, 0, 0)
    top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val head = mtext("", 15f, 700, getColor(R.color.text_primary))
    words.addView(head)
    val big = mtext("", 30f, 700, getColor(R.color.text_primary))
    big.letterSpacing = -0.02f
    words.addView(big)
    val sub = mtext("", 13f, 500, getColor(R.color.text_secondary))
    sub.maxLines = 2
    sub.setPadding(0, dp(2), 0, 0)
    words.addView(sub)
    val controls = FlowLayout(a)
    controls.setPadding(0, dp(12), 0, 0)
    card.addView(controls)

    var shownActive: Boolean? = null
    var shownPhase = ""

    fun ringFill() {
        val goal = Focus.goal(a)
        val m = Focus.minutesOn(a, today)
        ring.fraction = m.toFloat() / maxOf(1, goal)
        ring.label = if (m > 0) fmtDuration(m) else "0m"
        ring.sub = "of " + fmtDuration(goal)
        ring.invalidate()
    }

    fun build() {
        controls.removeAllViews()
        val active = Focus.active(a)
        shownActive = active
        shownPhase = if (Focus.onBreak(a)) "break" else if (Focus.paused(a)) "paused" else "run"
        fun chip(text: String, on: Boolean, act: () -> Unit) {
            val c = a.chip(text, on)
            c.setOnClickListener { act() }
            controls.addView(c)
        }
        if (!active) {
            head.text = "Focus"
            big.visibility = View.GONE
            val t = FocusPick.task(today)
            sub.text = "Today " + fmtDuration(maxOf(0, Focus.minutesOn(a, today))).ifEmpty { "0m" } + " of " +
                fmtDuration(Focus.goal(a)) + " goal"
            // The task.
            chip("Task: " + (t?.title?.let { if (it.length > 24) it.take(23) + "…" else it } ?: "none") + "  ▾", t != null) {
                val open = Store.queue(today)
                val names = arrayOf("No task") + open.map { it.title }.toTypedArray()
                AlertDialog.Builder(a).setTitle("Focus on").setItems(names) { _, i ->
                    FocusPick.taskId = if (i == 0) "" else open[i - 1].id
                    build()
                }.show()
            }
            // The length.
            val len = p.getInt("focusLen", 50)
            for (m in intArrayOf(25, 50, 90)) chip("${m}m", len == m) {
                p.edit().putInt("focusLen", m).apply()
                build()
            }
            val custom = len != 0 && len != 25 && len != 50 && len != 90
            chip(if (custom) "${len}m" else "Custom", custom) {
                val input = EditText(a)
                input.inputType = InputType.TYPE_CLASS_NUMBER
                input.hint = "Minutes"
                input.setText(if (custom) len.toString() else "")
                val box = FrameLayout(a)
                box.setPadding(dp(24), dp(8), dp(24), 0)
                box.addView(input)
                AlertDialog.Builder(a).setTitle("Focus for how many minutes?").setView(box)
                    .setPositiveButton("Set") { _, _ ->
                        val n = input.text.toString().toIntOrNull()
                        if (n != null && n in 5..600) p.edit().putInt("focusLen", n).apply()
                        build()
                    }.setNegativeButton("Cancel", null).show()
            }
            chip("Until I stop", len == 0) {
                p.edit().putInt("focusLen", 0).apply()
                build()
            }
            val start = mtext("Start focus", 15f, 800, getColor(R.color.on_accent))
            start.gravity = Gravity.CENTER
            start.background = rippled(rounded(getColor(R.color.accent), dp(22)), getColor(R.color.on_accent))
            start.setPadding(dp(22), 0, dp(22), 0)
            start.setOnClickListener {
                start.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                a.beginFocus(FocusPick.task(today), today, p.getInt("focusLen", 50), false) {
                    startActivity(Intent(a, FocusActivity::class.java))
                    rerender()
                }
            }
            controls.addView(start, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        } else {
            big.visibility = View.VISIBLE
            head.text = when {
                Focus.onBreak(a) -> "Break · stand up and walk"
                Focus.paused(a) -> "Paused"
                Focus.dayMode(a) -> "My day · focus"
                else -> "Focus"
            }
            sub.text = Focus.task(a)?.title ?: "No task"
            if (Focus.onBreak(a)) chip("Skip break", false) { Focus.skipBreak(a) }
            else if (Focus.paused(a)) chip("Resume", true) { Focus.resume(a) }
            else chip("Pause", false) { Focus.pause(a) }
            chip("Stop", false) { confirmStop(a) }
            chip("Full screen", true) { startActivity(Intent(a, FocusActivity::class.java)) }
        }
        ringFill()
    }

    val ticker = object : Runnable {
        override fun run() {
            val phase = if (Focus.onBreak(a)) "break" else if (Focus.paused(a)) "paused" else "run"
            if (shownActive != Focus.active(a) || phase != shownPhase) build()
            if (Focus.active(a)) big.text = Focus.clock(a)
            ringFill()
            card.postDelayed(this, 1000)
        }
    }
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            card.removeCallbacks(ticker)
            card.post(ticker)
        }

        override fun onViewDetachedFromWindow(v: View) {
            card.removeCallbacks(ticker)
        }
    })
    build()
    if (Focus.active(a)) big.text = Focus.clock(a)
    return card
}

fun confirmStop(a: Activity, then: () -> Unit = {}) {
    AlertDialog.Builder(a)
        .setMessage("Stop this focus session? The minutes so far are kept.")
        .setPositiveButton("Stop") { _, _ ->
            Focus.stop(a)
            then()
            Focus.askRating(a)
        }
        .setNegativeButton("Keep going", null).show()
}

// ---------- Full-screen focus ----------

/**
 * Dark, a big timer, the task and its subtasks to tick; the screen stays on. Works sideways.
 * Opened without a running session it offers to start one (for the task it was opened with).
 */
class FocusActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var ring: RingView? = null
    private var shown = ""
    private var startLen = 50
    private val listener: () -> Unit = { handler.post { render() } }
    private val tick = object : Runnable {
        override fun run() {
            if (state() != shown) render()
            ring?.let {
                it.fraction = if (Focus.active(this@FocusActivity)) 1f - Focus.fraction(this@FocusActivity) else 1f
                if (Focus.active(this@FocusActivity)) it.label = Focus.clock(this@FocusActivity)
                it.invalidate()
            }
            handler.postDelayed(this, 500)
        }
    }

    // Always dark, whatever the app's theme.
    override fun attachBaseContext(newBase: Context) {
        val c = Configuration()
        c.uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        super.attachBaseContext(newBase.createConfigurationContext(c))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0B0C0E.toInt()
        window.navigationBarColor = 0xFF0B0C0E.toInt()
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0C0E.toInt())
        setContentView(root)
        fitSystem(root)
        startLen = prefs(this).getInt("focusLen", 50)
        intent.getStringExtra("id")?.let { FocusPick.taskId = it }
    }

    override fun onResume() {
        super.onResume()
        Focus.listeners.add(listener)
        render()
        handler.post(tick)
        Focus.askRating(this)
    }

    override fun onPause() {
        super.onPause()
        Focus.listeners.remove(listener)
        handler.removeCallbacks(tick)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun state(): String = when {
        !Focus.active(this) -> "idle"
        Focus.onBreak(this) -> "break"
        Focus.paused(this) -> "paused"
        else -> "run|" + (Focus.task(this)?.id ?: "")
    }

    private fun render() {
        shown = state()
        root.removeAllViews()
        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val body = LinearLayout(this)
        body.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER
        body.setPadding(dp(24), dp(56), dp(24), dp(24))
        scroll.addView(body)

        val close = ImageView(this)
        close.setImageResource(R.drawable.ic_close)
        close.setColorFilter(0xFF9096A0.toInt())
        close.scaleType = ImageView.ScaleType.CENTER
        close.contentDescription = "Close"
        close.setOnClickListener { finish() }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(6)
            marginStart = dp(6)
        })

        val today = LocalDate.now()
        val active = Focus.active(this)
        val r = RingView(this)
        ring = r
        r.label = if (active) Focus.clock(this) else "${if (startLen == 0) "∞" else "$startLen:00"}"
        r.sub = when {
            !active -> if (startLen == 0) "until you stop" else "minutes of focus"
            Focus.onBreak(this) -> "break"
            Focus.paused(this) -> "paused"
            Focus.target(this) > 0 -> "left"
            else -> "of focus"
        }
        r.fraction = if (active) 1f - Focus.fraction(this) else 1f
        val size = if (wide) dp(260) else dp(280)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        left.addView(r, LinearLayout.LayoutParams(size, size))
        body.addView(left, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.CENTER_HORIZONTAL
        right.setPadding(if (wide) dp(24) else 0, if (wide) 0 else dp(24), 0, 0)
        body.addView(right, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val t = if (active) Focus.task(this) else FocusPick.task(today)
        if (active && Focus.onBreak(this)) {
            val walk = mtext("Stand up and walk", 26f, 700, 0xFFF2F3F5.toInt())
            walk.gravity = Gravity.CENTER
            right.addView(walk)
            val s = mtext("Away from the screen for a few minutes: stretch, get water, look out of the window. " +
                "Apps are open during the break; focus continues after.", 15f, 500, 0xFF9096A0.toInt())
            s.gravity = Gravity.CENTER
            s.setLineSpacing(dp(3).toFloat(), 1f)
            s.setPadding(0, dp(10), 0, dp(20))
            right.addView(s)
        } else {
            val title = mtext(t?.title ?: "Focus without a task", 24f, 700, 0xFFF2F3F5.toInt())
            title.gravity = Gravity.CENTER
            right.addView(title)
            Store.courseOf(t ?: Task())?.let {
                val c = mtext(it.code, 14f, 700, it.color)
                c.setPadding(0, dp(6), 0, 0)
                right.addView(c)
            }
            if (t != null && t.subtasks.isNotEmpty()) subtasks(right, t, if (active) Focus.date(this) else today)
        }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bl.topMargin = dp(24)
        right.addView(buttons, bl)
        fun button(text: String, filled: Boolean, act: () -> Unit) {
            val b = mtext(text, 16f, 800, if (filled) 0xFF1A0D06.toInt() else 0xFFF2F3F5.toInt())
            b.gravity = Gravity.CENTER
            b.background = if (filled) rippled(rounded(0xFFFF8552.toInt(), dp(27)), 0xFF1A0D06.toInt())
            else rippled(rounded(0, dp(27), 0xFF3A3E46.toInt(), dp(2)), 0xFFF2F3F5.toInt())
            b.setOnClickListener {
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                act()
            }
            val l = LinearLayout.LayoutParams(0, dp(54), 1f)
            l.marginStart = dp(5)
            l.marginEnd = dp(5)
            buttons.addView(b, l)
        }
        if (!active) {
            // Choose a length, then start.
            val lens = FlowLayout(this)
            lens.setPadding(0, dp(16), 0, 0)
            for ((label, m) in listOf("25m" to 25, "50m" to 50, "90m" to 90, "Until I stop" to 0)) {
                val c = chip(label, startLen == m)
                c.setOnClickListener {
                    startLen = m
                    prefs(this).edit().putInt("focusLen", m).apply()
                    render()
                }
                lens.addView(c)
            }
            right.addView(lens, right.childCount - 1)
            button("Start focus", true) { beginFocus(FocusPick.task(today), today, startLen, false) { render() } }
        } else {
            when {
                Focus.onBreak(this) -> button("Skip break", false) { Focus.skipBreak(this) }
                Focus.paused(this) -> button("Resume", true) { Focus.resume(this) }
                else -> button("Pause", false) { Focus.pause(this) }
            }
            button("Stop", false) { confirmStop(this) { render() } }
            if (t != null && !Focus.onBreak(this)) {
                val done = mtext(if (Focus.dayMode(this)) "Task done · next task" else "Task done", 15f, 700, 0xFFFF8552.toInt())
                done.gravity = Gravity.CENTER
                done.setPadding(0, dp(18), 0, dp(8))
                done.setOnClickListener {
                    val d = Focus.date(this)
                    Store.setDone(t, d, true)
                    Store.save()
                    Reminders.taskTicked(this, t, d)
                    Reminders.schedule(this)
                    render()
                }
                right.addView(done)
            }
        }
    }

    private fun subtasks(into: LinearLayout, t: Task, d: LocalDate) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, 0)
        for (s in t.subtasks) {
            val key = t.subKey(d, s)
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val b = BoxView(this)
            b.color = 0xFFFF8552.toInt()
            b.setChecked(key in t.subDone, false)
            line.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)))
            val tv = TitleView(this)
            tv.text = s.title
            tv.textSize = 16f
            tv.normalColor = 0xFFF2F3F5.toInt()
            tv.struckColor = 0xFF5C616B.toInt()
            tv.setStruck(key in t.subDone, false)
            line.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.setOnClickListener {
                val on = key !in t.subDone
                if (on) t.subDone.add(key) else t.subDone.remove(key)
                t.updated = System.currentTimeMillis()
                Store.save()
                b.setChecked(on, true)
                tv.setStruck(on, true)
                line.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            box.addView(line)
        }
        into.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

// ---------- Focus widget ----------

/** One tap starts focus on the next open task; while a session runs it shows the timer. */
class FocusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, FocusWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            Store.load(ctx)
            val theme = Look.widgetTheme(ctx)
            val light = theme == "light"
            val cPrimary = if (light) 0xFF1F1A16.toInt() else 0xFFF2F3F5.toInt()
            val cSecondary = if (light) 0xFF6B6159.toInt() else 0xFF9096A0.toInt()
            val accent = if (light) 0xFFF47A45.toInt() else 0xFFFF8552.toInt()
            val v = RemoteViews(ctx.packageName, R.layout.widget_focus)
            v.setInt(R.id.wf_root, "setBackgroundResource",
                if (light) R.drawable.widget_bg_light else if (theme == "grey") R.drawable.widget_bg_grey else R.drawable.widget_bg)
            v.setTextColor(R.id.wf_title, cPrimary)
            v.setTextColor(R.id.wf_task, cSecondary)
            v.setTextColor(R.id.wf_clock, cPrimary)
            v.setTextColor(R.id.wf_action, accent)
            val today = LocalDate.now()
            val now = SystemClock.elapsedRealtime()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            if (Focus.active(ctx)) {
                val t = Focus.task(ctx)
                v.setTextViewText(R.id.wf_title, when {
                    Focus.onBreak(ctx) -> "Break"
                    Focus.paused(ctx) -> "Paused"
                    else -> "Focus"
                })
                v.setTextViewText(R.id.wf_task, t?.title ?: "No task")
                v.setViewVisibility(R.id.wf_clock, View.VISIBLE)
                val ms = System.currentTimeMillis()
                val down = Focus.onBreak(ctx) || Focus.target(ctx) > 0
                val base = when {
                    Focus.onBreak(ctx) -> now + (Focus.breakEnd(ctx) - ms)
                    Focus.target(ctx) > 0 -> now + (Focus.target(ctx) * 60_000L - Focus.focusMs(ctx, ms))
                    else -> now - Focus.focusMs(ctx, ms)
                }
                v.setChronometerCountDown(R.id.wf_clock, down)
                v.setChronometer(R.id.wf_clock, base, null, !Focus.paused(ctx))
                v.setTextViewText(R.id.wf_action, "Open")
                v.setOnClickPendingIntent(R.id.wf_root, PendingIntent.getActivity(ctx, 60,
                    Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            } else {
                val next = Store.queue(today).firstOrNull()
                val len = prefs(ctx).getInt("focusLen", 50)
                v.setTextViewText(R.id.wf_title, "Focus")
                v.setTextViewText(R.id.wf_task, if (next != null) "Next: " + next.title else "No open task today")
                v.setViewVisibility(R.id.wf_clock, View.GONE)
                v.setTextViewText(R.id.wf_action, "Tap to start · " + (if (len == 0) "until you stop" else "$len min"))
                v.setOnClickPendingIntent(R.id.wf_root, PendingIntent.getForegroundService(ctx, 61,
                    Intent(ctx, FocusService::class.java).setAction(Focus.ACTION_START_NEXT), flags))
            }
            manager.updateAppWidget(ids, v)
        }
    }
}

/** Minutes as "45m"; for screens that need a word when there is none. */
fun minutesText(m: Int): String = if (m <= 0) "0m" else fmtDuration(m)

/** Seconds into the day, for comparisons with exam times. */
fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
