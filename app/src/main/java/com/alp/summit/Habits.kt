package com.alp.summit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The glow of a chain: cool grey-blue at the start, through gold and orange, to a deep red-pink at 100 days. */
fun chainHeat(streak: Int): Int {
    val stops = intArrayOf(0, 3, 7, 30, 100)
    val colors = intArrayOf(0xFF8FA3B8.toInt(), 0xFFF5C451.toInt(), 0xFFFF8552.toInt(), 0xFFFF5A3C.toInt(), 0xFFFF2E63.toInt())
    if (streak >= stops.last()) return Look.tone(colors.last())
    var i = 0
    while (streak >= stops[i + 1]) i++
    val f = (streak - stops[i]).toFloat() / (stops[i + 1] - stops[i])
    return Look.tone(mix(colors[i], colors[i + 1], f))
}

fun mix(a: Int, b: Int, f: Float): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return ((x + (y - x) * f).toInt() and 0xFF) shl shift
    }
    return ch(24) or ch(16) or ch(8) or ch(0)
}

private val MILESTONES = intArrayOf(7, 30, 100)
private val DAY_LETTERS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

// ---------- The Chains tab ----------

/** Builds the "Don't break the chain" page into the main screen's list. */
class ChainsPage(private val a: Activity, private val rerender: () -> Unit) {
    private var month: YearMonth = YearMonth.now()
    private var calendar: HabitMonthView? = null

    fun build(list: LinearLayout) {
        val today = LocalDate.now()
        val title = TextView(a)
        title.text = "Chains"
        title.textSize = 36f
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        title.setTextColor(a.getColor(R.color.text_primary))
        list.addView(title)
        val sub = TextView(a)
        sub.text = "Don't break the chain. Tick today or yesterday; days a habit is not planned never break it."
        sub.textSize = 15f
        sub.setTextColor(a.getColor(R.color.text_secondary))
        sub.setPadding(0, a.dp(2), 0, a.dp(10))
        list.addView(sub)

        val habits = Store.habits.items.sortedBy { it.created }
        if (habits.isEmpty()) {
            val empty = TextView(a)
            empty.text = "No habits yet.\nAdd one below: reading, gym, a language, anything you want to do on certain days."
            empty.textSize = 15f
            empty.setTextColor(a.getColor(R.color.text_dim))
            empty.setLineSpacing(a.dp(3).toFloat(), 1f)
            empty.setPadding(0, a.dp(18), 0, a.dp(12))
            list.addView(empty)
        }
        for (h in habits) list.addView(card(h, today))

        val add = a.chip("+  Add a habit", true)
        add.setPadding(a.dp(18), a.dp(11), a.dp(18), a.dp(11))
        add.setOnClickListener { edit(null) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = a.dp(14)
        list.addView(add, lp)

        if (habits.isNotEmpty()) calendarSection(list, habits)
    }

    // ----- One habit -----

    private fun card(h: Habit, today: LocalDate): View {
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.background = rippled(rounded(a.getColor(R.color.surface), a.dp(18)), a.getColor(R.color.text_primary))
        card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = a.dp(10)
        card.layoutParams = lp

        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        card.addView(top)
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val name = TextView(a)
        name.text = (if (h.marked) "★  " else "") + h.title
        name.textSize = 17f
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        name.setTextColor(a.getColor(R.color.text_primary))
        words.addView(name)
        val meta = TextView(a)
        meta.textSize = 13f
        meta.setTextColor(a.getColor(R.color.text_secondary))
        meta.setPadding(0, a.dp(2), 0, 0)
        words.addView(meta)

        val numberBox = LinearLayout(a)
        numberBox.orientation = LinearLayout.VERTICAL
        numberBox.gravity = Gravity.CENTER_HORIZONTAL
        top.addView(numberBox)
        val number = TextView(a)
        number.textSize = 34f
        number.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        number.gravity = Gravity.CENTER
        numberBox.addView(number)
        val unit = TextView(a)
        unit.textSize = 11f
        unit.setTextColor(a.getColor(R.color.text_dim))
        unit.gravity = Gravity.CENTER
        numberBox.addView(unit)

        val chain = ChainView(a)
        val chainLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(44))
        chainLp.topMargin = a.dp(10)
        card.addView(chain, chainLp)

        val buttons = LinearLayout(a)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        val bLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bLp.topMargin = a.dp(10)
        card.addView(buttons, bLp)

        var shownStreak = h.streak(today)

        fun fill(animateNumber: Boolean) {
            val streak = h.streak(today)
            val heat = chainHeat(streak)
            meta.text = weekdays(h.mask) + "  ·  best " + h.best(today) +
                (if (h.badges.isNotEmpty()) "  ·  badges " + h.badges.sorted().joinToString(" ") { "$it" } else "")
            unit.text = if (streak == 1) "day" else "days"
            number.setTextColor(if (streak == 0) a.getColor(R.color.text_dim) else heat)
            if (animateNumber && streak != shownStreak) {
                // The streak counts up (or down) to its new value.
                val from = shownStreak
                ValueAnimator.ofInt(from, streak).apply {
                    duration = (220L + 40L * abs(streak - from)).coerceAtMost(900L)
                    interpolator = DecelerateInterpolator(1.4f)
                    addUpdateListener { number.text = (it.animatedValue as Int).toString() }
                    start()
                }
                if (streak > from) {
                    number.scaleX = 1.35f
                    number.scaleY = 1.35f
                    number.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
                }
            } else {
                number.text = streak.toString()
            }
            shownStreak = streak
            chain.setHabit(h, today, streak)

            buttons.removeAllViews()
            for (d in listOf(today.minusDays(1), today)) {
                val label = if (d == today) "Today" else "Yesterday"
                val c = when {
                    !h.planned(d) -> a.chip("$label: not planned", false).also { it.alpha = 0.5f }
                    h.ticked(d) -> a.chip("✓  $label", true, heat)
                    else -> a.chip(label, false, heat)
                }
                if (h.planned(d)) c.setOnClickListener { tick(h, d, chain) { fill(true) } }
                buttons.addView(c)
                (c.layoutParams as LinearLayout.LayoutParams).marginEnd = a.dp(8)
            }
        }
        fill(false)

        card.setOnClickListener { edit(h) }
        card.setOnLongClickListener {
            val m = PopupMenu(a, card, Gravity.END)
            m.menu.add(0, 1, 0, "Edit")
            m.menu.add(0, 2, 0, if (h.marked) "Unmark" else "Mark (for the calendar)")
            m.menu.add(0, 3, 0, "Delete")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> edit(h)
                    2 -> {
                        h.marked = !h.marked
                        h.touch()
                        Store.save()
                        rerender()
                    }
                    3 -> AlertDialog.Builder(a)
                        .setMessage("Delete \"${h.title}\" and its whole chain?")
                        .setPositiveButton("Delete") { _, _ ->
                            Store.habits.remove(h)
                            Store.save()
                            rerender()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
            m.show()
            true
        }
        return card
    }

    /** Ticks or unticks one day. A new link snaps onto the chain; milestones get a celebration. */
    private fun tick(h: Habit, d: LocalDate, chain: ChainView, refresh: () -> Unit) {
        val on = !h.ticked(d)
        if (on) h.ticks.add(d.toString()) else h.ticks.remove(d.toString())
        h.touch()
        val today = LocalDate.now()
        val streak = h.streak(today)
        var milestone = 0
        if (on) for (m in MILESTONES) if (streak >= m && m !in h.badges) {
            h.badges.add(m)
            milestone = m
        }
        Store.save()
        refresh()
        calendar?.invalidate()
        if (on) {
            chain.snap()
            chain.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            )
            if (milestone > 0) chain.postDelayed({ celebrate(h, milestone) }, 450)
        } else {
            chain.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun celebrate(h: Habit, days: Int) {
        if (a.isFinishing) return
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val v = MilestoneView(a, days, h.title)
        content.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.postDelayed({ v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }, 260)
        v.play { content.removeView(v) }
    }

    // ----- Mini calendar -----

    private fun calendarSection(list: LinearLayout, habits: List<Habit>) {
        val p = prefs(a)
        val label = TextView(a)
        label.text = "CALENDAR"
        label.textSize = 11f
        label.letterSpacing = 0.14f
        label.setTextColor(a.getColor(R.color.text_dim))
        label.setPadding(0, a.dp(30), 0, a.dp(10))
        list.addView(label)

        val view = p.getInt("chainView", 2)                 // 0 one habit, 1 marked, 2 all
        val modes = LinearLayout(a)
        modes.orientation = LinearLayout.HORIZONTAL
        fun mode(text: String, value: Int) {
            val c = a.chip(text, view == value)
            c.setOnClickListener {
                p.edit().putInt("chainView", value).apply()
                rerender()
            }
            modes.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).marginEnd = a.dp(8)
        }
        mode("One habit", 0)
        mode("Marked", 1)
        mode("All", 2)
        list.addView(modes)

        val chosen = habits.firstOrNull { it.id == p.getString("chainHabit", "") } ?: habits.first()
        val shownHabits = when (view) {
            0 -> listOf(chosen)
            1 -> habits.filter { it.marked }
            else -> habits
        }
        if (view == 0) {
            val scroller = HorizontalScrollView(a)
            scroller.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(a)
            scroller.addView(row)
            for (h in habits) {
                val c = a.chip(h.title, h === chosen, chainHeat(h.streak(LocalDate.now())))
                c.setOnClickListener {
                    p.edit().putString("chainHabit", h.id).apply()
                    rerender()
                }
                row.addView(c)
                (c.layoutParams as LinearLayout.LayoutParams).marginEnd = a.dp(8)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = a.dp(10)
            list.addView(scroller, lp)
        }

        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, a.dp(14), 0, a.dp(4))
        val monthName = TextView(a)
        monthName.text = monthYear(month.atDay(1))
        monthName.textSize = 17f
        monthName.setTextColor(a.getColor(R.color.text_primary))
        head.addView(monthName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun arrow(icon: Int, desc: Int, dir: Long) {
            val b = android.widget.ImageButton(a, null, 0, 0)
            b.setImageResource(icon)
            b.contentDescription = a.getString(desc)
            b.background = null
            b.setColorFilter(a.getColor(R.color.text_secondary))
            b.setOnClickListener {
                month = month.plusMonths(dir)
                rerender()
            }
            head.addView(b, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        }
        arrow(R.drawable.ic_prev, R.string.previous, -1)
        arrow(R.drawable.ic_next, R.string.next, 1)
        list.addView(head)

        if (shownHabits.isEmpty()) {
            val none = TextView(a)
            none.text = "No marked habits yet. Hold a habit and choose Mark."
            none.textSize = 14f
            none.setTextColor(a.getColor(R.color.text_dim))
            none.setPadding(0, a.dp(8), 0, a.dp(8))
            list.addView(none)
            calendar = null
            return
        }
        val grid = HabitMonthView(a)
        grid.month = month
        grid.habits = shownHabits
        grid.onSwipe = {
            month = month.plusMonths(it.toLong())
            rerender()
        }
        list.addView(grid)
        calendar = grid
        val legend = TextView(a)
        legend.text = if (shownHabits.size == 1) "Filled: ticked  ·  ring: planned but missed  ·  dot: not planned"
        else "The fuller the circle, the more of that day's planned habits were ticked."
        legend.textSize = 12f
        legend.setTextColor(a.getColor(R.color.text_dim))
        legend.setPadding(0, a.dp(6), 0, 0)
        list.addView(legend)
    }

    // ----- Adding and changing a habit -----

    private fun edit(h: Habit?) {
        var mask = h?.mask ?: 127
        var marked = h?.marked ?: false
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val name = EditText(a)
        name.hint = "Habit, e.g. Read 20 pages"
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.maxLines = 1
        name.setText(h?.title ?: "")
        box.addView(name)
        val note = TextView(a)
        note.text = "On these days:"
        note.textSize = 13f
        note.setTextColor(a.getColor(R.color.text_secondary))
        note.setPadding(0, a.dp(14), 0, a.dp(6))
        box.addView(note)
        val days = FlowLayout(a)
        box.addView(days)
        val extra = FlowLayout(a)
        extra.setPadding(0, a.dp(12), 0, a.dp(4))
        box.addView(extra)
        fun paint() {
            days.removeAllViews()
            for (i in 0 until 7) {
                val c = a.chip(DAY_LETTERS[i], mask and (1 shl i) != 0)
                c.setOnClickListener {
                    val next = mask xor (1 shl i)
                    if (next != 0) mask = next
                    paint()
                }
                days.addView(c)
            }
            extra.removeAllViews()
            for ((label, value) in listOf("Every day" to 127, "Weekdays" to 31)) {
                val c = a.chip(label, mask == value)
                c.setOnClickListener {
                    mask = value
                    paint()
                }
                extra.addView(c)
            }
            val star = a.chip(if (marked) "★  Marked" else "☆  Mark", marked)
            star.setOnClickListener {
                marked = !marked
                paint()
            }
            extra.addView(star)
        }
        paint()
        val b = AlertDialog.Builder(a)
            .setTitle(if (h == null) "New habit" else "Edit habit")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val habit = h ?: Habit().also { Store.habits.add(it) }
                habit.title = text
                habit.mask = mask
                habit.marked = marked
                habit.touch()
                Store.save()
                Reminders.schedule(a)
                rerender()
            }
            .setNegativeButton("Cancel", null)
        if (h != null) b.setNeutralButton("Delete") { _, _ ->
            Store.habits.remove(h)
            Store.save()
            rerender()
        }
        b.show()
    }

    private fun weekdays(mask: Int): String = when (mask) {
        127 -> "Every day"
        31 -> "Weekdays"
        96 -> "Weekends"
        else -> (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString(" ") { DAY_LETTERS[it] }
    }
}

// ---------- The chain itself ----------

/**
 * The last planned days of a habit as chain links, oldest on the left, today on the right.
 * Ticked links are joined and glow (warmer the longer the chain); a missed day is a broken link.
 */
class ChainView(ctx: Context) : View(ctx) {
    private var states = IntArray(0)       // 1 ticked, 0 open (today), -1 missed
    private var heat = 0
    private var streak = 0
    private var snapT = 1f                  // 0..1 while the newest link snaps on
    private val d = resources.displayMetrics.density
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dim = ctx.getColor(R.color.text_dim)
    private val line = ctx.getColor(R.color.line)
    private val r = RectF()

    private fun count(): Int = max(5, ((width - 8f * d) / (22f * d)).toInt())

    fun setHabit(h: Habit, today: LocalDate, streak: Int) {
        this.streak = streak
        heat = chainHeat(streak)
        val out = ArrayList<Int>()
        var day = today
        var guard = 0
        // Enough planned days to fill the widest screen.
        while (out.size < 40 && guard < 400 && !day.isBefore(h.start)) {
            if (h.planned(day)) out.add(if (h.ticked(day)) 1 else if (day == today) 0 else -1)
            day = day.minusDays(1)
            guard++
        }
        states = out.reversed().toIntArray()
        invalidate()
    }

    /** The newest link snaps onto the chain. */
    fun snap() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                snapT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val n = count()
        val shown = if (states.size > n) states.copyOfRange(states.size - n, states.size) else states
        val linkW = 26f * d
        val step = 22f * d
        val h = 14f * d
        val cy = height / 2f
        // Right-aligned so today is always at the end.
        var x = width - 4f * d - linkW - (shown.size - 1) * step
        val glowStrength = min(1f, streak / 30f)
        val newest = shown.indexOfLast { it == 1 }
        for (i in shown.indices) {
            val s = shown[i]
            // Alternate links lie flat and upright, like a real chain.
            val flat = i % 2 == 0
            val hh = if (flat) h else h * 0.62f
            r.set(x, cy - hh, x + linkW, cy + hh)
            var scale = 1f
            var alpha = 1f
            if (i == newest && snapT < 1f) {
                val k = OvershootInterpolator(3.2f).getInterpolation(snapT)
                scale = 1.7f - 0.7f * k
                alpha = min(1f, snapT * 2.5f)
            }
            c.save()
            c.scale(scale, scale, r.centerX(), r.centerY())
            when (s) {
                1 -> {
                    if (glowStrength > 0f) {
                        glow.color = withAlpha(heat, (40 + 70 * glowStrength).toInt())
                        glow.strokeWidth = (6f + 6f * glowStrength) * d
                        c.drawRoundRect(r, hh, hh, glow)
                    }
                    link.color = withAlpha(heat, (255 * alpha).toInt())
                    link.strokeWidth = 3.4f * d
                    c.drawRoundRect(r, hh, hh, link)
                }
                0 -> {
                    link.color = dim
                    link.strokeWidth = 1.6f * d
                    link.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * d, 3.5f * d), 0f)
                    c.drawRoundRect(r, hh, hh, link)
                    link.pathEffect = null
                }
                else -> {
                    // A broken link: two halves with a gap.
                    link.color = line
                    link.strokeWidth = 2.4f * d
                    c.save()
                    c.clipRect(r.left - 4 * d, r.top - 4 * d, r.centerX() - 3f * d, r.bottom + 4 * d)
                    c.drawRoundRect(r, hh, hh, link)
                    c.restore()
                    c.save()
                    c.clipRect(r.centerX() + 3f * d, r.top - 4 * d, r.right + 4 * d, r.bottom + 4 * d)
                    c.drawRoundRect(r, hh, hh, link)
                    c.restore()
                }
            }
            c.restore()
            if (i == newest && snapT < 1f) {
                // A flash ring where the link snapped on.
                glow.color = withAlpha(heat, (180 * (1f - snapT)).toInt())
                glow.strokeWidth = 2f * d
                c.drawCircle(r.centerX(), r.centerY(), (10f + 22f * snapT) * d, glow)
            }
            x += step
        }
    }
}

// ---------- Mini calendar of one or more habits ----------

class HabitMonthView(ctx: Context) : View(ctx) {
    var month: YearMonth = YearMonth.now()
    var habits: List<Habit> = emptyList()
    var onSwipe: ((Int) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val headerH = dp(24)
    private val cellH = dp(42)
    private val letters = arrayOf("M", "T", "W", "T", "F", "S", "S")
    private val accent = ctx.getColor(R.color.accent)
    private val cPrimary = ctx.getColor(R.color.text_primary)
    private val cDim = ctx.getColor(R.color.text_dim)
    private val cLine = ctx.getColor(R.color.line)
    private val cBg = ctx.getColor(R.color.bg)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var downX = 0f
    private var downY = 0f

    private fun rows(): Int = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), headerH + rows() * cellH)
    }

    override fun onDraw(c: Canvas) {
        val today = LocalDate.now()
        val cw = width / 7f
        text.textSize = 11f * d
        text.typeface = Typeface.DEFAULT
        text.color = cDim
        for (i in 0 until 7) c.drawText(letters[i], cw * (i + 0.5f), 14f * d, text)
        val offset = month.atDay(1).dayOfWeek.value - 1
        val heat = if (habits.size == 1) chainHeat(habits[0].streak(today)) else accent
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val i = offset + day - 1
            val cx = cw * (i % 7 + 0.5f)
            val cy = headerH + (i / 7) * cellH + cellH / 2f
            val rad = 15f * d
            var planned = 0
            var ticked = 0
            for (h in habits) {
                if (date.isBefore(h.start) || !h.planned(date)) {
                    if (h.ticked(date)) ticked++   // ticked although not planned: still shows
                    continue
                }
                planned++
                if (h.ticked(date)) ticked++
            }
            val future = date.isAfter(today)
            shape.style = Paint.Style.FILL
            text.typeface = Typeface.DEFAULT
            text.textSize = 13f * d
            text.color = if (future) cDim else cPrimary
            when {
                ticked > 0 -> {
                    val f = if (planned == 0) 1f else min(1f, ticked.toFloat() / planned)
                    shape.color = withAlpha(heat, (70 + 185 * f).toInt())
                    if (f >= 1f) {
                        c.drawCircle(cx, cy, rad, shape)
                    } else {
                        // Partly done: the circle fills from the bottom.
                        c.save()
                        c.clipRect(cx - rad, cy + rad - 2 * rad * f, cx + rad, cy + rad)
                        c.drawCircle(cx, cy, rad, shape)
                        c.restore()
                        shape.style = Paint.Style.STROKE
                        shape.strokeWidth = 1.4f * d
                        shape.color = withAlpha(heat, 0x99)
                        c.drawCircle(cx, cy, rad, shape)
                    }
                    if (f >= 0.5f) text.color = cBg
                }
                planned > 0 && !future && date != today -> {
                    shape.style = Paint.Style.STROKE
                    shape.strokeWidth = 1.4f * d
                    shape.color = cLine
                    c.drawCircle(cx, cy, rad, shape)
                }
                planned == 0 -> {
                    shape.color = cLine
                    c.drawCircle(cx, cy + 11f * d, 1.6f * d, shape)
                    text.color = cDim
                }
            }
            if (date == today) {
                shape.style = Paint.Style.STROKE
                shape.strokeWidth = 1.8f * d
                shape.color = accent
                oval.set(cx - rad - 3 * d, cy - rad - 3 * d, cx + rad + 3 * d, cy + rad + 3 * d)
                c.drawOval(oval, shape)
                text.typeface = Typeface.DEFAULT_BOLD
            }
            c.drawText(day.toString(), cx, cy + 4.5f * d, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                if (abs(dx) > dp(56) && abs(dx) > abs(e.y - downY) * 1.5f) onSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

// ---------- 7, 30 and 100 days: a badge ----------

/** A full-screen moment: a medal pops in with the number of days, sparks fly. Tap to skip. */
class MilestoneView(ctx: Context, private val days: Int, private val habit: String) : View(ctx) {
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val bg = ctx.getColor(R.color.bg)
    private val heat = chainHeat(days)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pop = OvershootInterpolator(2.6f)
    private class Spark(val angle: Double, val speed: Float, val size: Float, val color: Int)
    private val sparks = ArrayList<Spark>()

    init {
        val colors = intArrayOf(heat, 0xFFF5C451.toInt(), ctx.getColor(R.color.accent), 0xFFFF2E63.toInt(), ctx.getColor(R.color.text_primary))
        val rnd = java.util.Random(days.toLong())
        val count = when (days) {
            100 -> 90
            30 -> 64
            else -> 42
        }
        for (i in 0 until count) {
            sparks.add(Spark(rnd.nextDouble() * Math.PI * 2, 0.4f + rnd.nextFloat() * 0.8f, 2f + rnd.nextFloat() * 3.5f, colors[i % colors.size]))
        }
        setOnClickListener { anim?.end() }
    }

    fun play(end: () -> Unit) {
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val a = min(1f, t / 0.07f) * min(1f, (1f - t) / 0.14f)
        c.drawColor(withAlpha(bg, (240 * a).toInt()))
        val cx = width / 2f
        val cy = height * 0.42f
        val k = pop.getInterpolation(((t - 0.04f) / 0.22f).coerceIn(0f, 1f))
        val rad = 70f * d * k

        // Light rays turning slowly behind the medal.
        if (k > 0f) {
            fill.color = withAlpha(heat, (36 * a).toInt())
            c.save()
            c.rotate(t * 60f, cx, cy)
            for (i in 0 until 12) {
                c.rotate(30f, cx, cy)
                c.drawRect(cx - 5f * d, cy - rad * 2.2f, cx + 5f * d, cy - rad * 1.15f, fill)
            }
            c.restore()
        }
        // The medal.
        fill.color = withAlpha(heat, (255 * a).toInt())
        c.drawCircle(cx, cy, rad, fill)
        ring.strokeWidth = 4f * d
        ring.color = withAlpha(bg, (160 * a).toInt())
        c.drawCircle(cx, cy, rad * 0.82f, ring)
        // A ring spreading out when it lands.
        val burst = ((t - 0.2f) / 0.35f).coerceIn(0f, 1f)
        if (burst in 0.001f..0.999f) {
            ring.color = withAlpha(heat, (200 * (1f - burst) * a).toInt())
            ring.strokeWidth = 3f * d
            c.drawCircle(cx, cy, rad + 70f * d * burst, ring)
        }
        if (k > 0.3f) {
            text.color = withAlpha(bg, (255 * a).toInt())
            text.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            text.textSize = 54f * d * k
            c.drawText(days.toString(), cx, cy + 19f * d * k, text)
        }
        // Sparks in every direction.
        val life = ((t - 0.22f) / 0.6f).coerceIn(0f, 1f)
        if (life > 0f && life < 1f) {
            val eased = 1f - (1f - life) * (1f - life)
            val reach = min(width, height) * 0.48f
            for (s in sparks) {
                val x = cx + Math.cos(s.angle).toFloat() * s.speed * reach * eased
                val y = cy + Math.sin(s.angle).toFloat() * s.speed * reach * eased + reach * 0.35f * life * life
                fill.color = withAlpha(s.color, (255 * (1f - life) * a).toInt())
                c.drawCircle(x, y, s.size * d, fill)
            }
        }
        val ta = ((t - 0.28f) / 0.12f).coerceIn(0f, 1f) * a
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = 28f * d
        text.color = withAlpha(context.getColor(R.color.text_primary), (255 * ta).toInt())
        c.drawText("$days-day chain", cx, cy + rad + 64f * d, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 15f * d
        text.color = withAlpha(context.getColor(R.color.text_secondary), (255 * ta).toInt())
        val line2 = when (days) {
            7 -> "A full week of $habit. Badge earned."
            30 -> "A month of $habit without a break. Badge earned."
            else -> "100 days of $habit. Legendary."
        }
        c.drawText(line2, cx, cy + rad + 92f * d, text)
    }
}
