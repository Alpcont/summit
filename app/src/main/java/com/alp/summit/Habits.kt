package com.alp.summit

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The glow of a chain by its length alone (used in the weekly review): cool grey-blue to deep red-pink. */
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

/** How hot a chain is, 0..1 (full at 30 days). */
fun heatOf(streak: Int): Float = min(1f, streak / 30f)

/** A habit's own colour, warmer the longer the chain (and deeper on the light theme). */
fun habitGlow(h: Habit, streak: Int): Int = Look.tone(mix(h.color, 0xFFFF6A3D.toInt(), heatOf(streak) * 0.3f))

/** The flame, in a 24 × 24 box. */
fun flamePath(): Path {
    val p = Path()
    p.moveTo(12f, 2f)
    p.cubicTo(13.2f, 5.6f, 17.5f, 7.6f, 17.5f, 12.6f)
    p.cubicTo(17.5f, 15.6f, 15.04f, 22f, 12f, 22f)
    p.cubicTo(8.96f, 22f, 6.5f, 19.4f, 6.5f, 16.4f)
    p.cubicTo(6.5f, 14.0f, 7.7f, 12.5f, 9.2f, 11.3f)
    p.cubicTo(9.4f, 13.1f, 10.3f, 14.2f, 11.4f, 14.7f)
    p.cubicTo(10.6f, 11.6f, 11f, 5.5f, 12f, 2f)
    p.close()
    return p
}

/** A firm double click, like a link snapping shut. */
fun firmBuzz(v: View) {
    try {
        val vib = v.context.getSystemService(Vibrator::class.java)
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 22, 45, 34), intArrayOf(0, 255, 0, 200), -1))
            return
        }
    } catch (e: Exception) {
        // No vibrator access: the normal touch feedback below.
    }
    v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
}

private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val F_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
val HABIT_COLORS = listOf(
    "Orange" to 0xFFFF8552.toInt(), "Purple" to 0xFFB692F6.toInt(), "Blue" to 0xFF5B9DFF.toInt(), "Green" to 0xFF3DDC97.toInt(),
    "Yellow" to 0xFFF5C451.toInt(), "Pink" to 0xFFF283B6.toInt(), "Teal" to 0xFF38C6D9.toInt()
)

fun habitDays(mask: Int): String = when (mask) {
    127 -> "Every day"
    31 -> "Weekdays"
    96 -> "Weekends"
    else -> (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString(" · ") { DAY_NAMES[it] }
}

/** After the habit's reminder time (or 21:00), an unlinked planned day is close to breaking the chain. */
fun almostBroken(h: Habit, today: LocalDate): Boolean {
    if (!h.planned(today) || h.ticked(today) || today.isBefore(h.start)) return false
    val now = LocalTime.now()
    return now.hour * 60 + now.minute >= (if (h.remindAt >= 0) h.remindAt else 21 * 60)
}

// ---------- The Chains tab: flame cards ----------

class ChainsPage(private val a: Activity, private val rerender: () -> Unit) {
    private var page = 0                   // which card the One view shows
    private var month: YearMonth = YearMonth.now()
    private val light get() = Look.light
    private fun c(id: Int) = a.getColor(id)
    private fun dp(v: Int) = a.dp(v)
    private fun dpf(v: Float) = v * a.resources.displayMetrics.density

    fun build(list: LinearLayout) {
        val today = LocalDate.now()
        val p = prefs(a)
        val habits = Store.habits.items.sortedBy { it.created }
        val view = p.getInt("chainView", 0).coerceIn(0, 2)       // 0 One, 1 Marked, 2 All

        // Header: title, today's count, add button.
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        val title = a.mtext("Chains", 28f, 700, c(R.color.text_primary))
        title.letterSpacing = -0.02f
        words.addView(title)
        val plannedToday = habits.filter { it.planned(today) && !today.isBefore(it.start) }
        if (plannedToday.isNotEmpty()) {
            val sub = a.mtext("${plannedToday.count { it.ticked(today) }} of ${plannedToday.size} linked today", 13f, 500, c(R.color.text_secondary))
            sub.setPadding(0, dp(4), 0, 0)
            words.addView(sub)
        }
        head.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = ImageView(a)
        add.setImageResource(R.drawable.ic_add)
        add.setColorFilter(c(R.color.accent))
        add.scaleType = ImageView.ScaleType.CENTER
        add.background = rippled(rounded(c(R.color.surface), dp(22)), c(R.color.accent))
        add.contentDescription = "Add habit"
        add.setOnClickListener { edit(null) }
        head.addView(add, LinearLayout.LayoutParams(dp(44), dp(44)))
        list.addView(head)

        // One / Marked / All.
        val seg = LinearLayout(a)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(c(R.color.surface), dp(14))
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((i, label) in listOf("One", "Marked", "All").withIndex()) {
            val on = view == i
            val b = a.mtext(label, 13f, if (on) 700 else 600, c(if (on) R.color.text_primary else R.color.text_secondary))
            b.gravity = Gravity.CENTER
            if (on) b.background = rounded(if (light) 0xFFFFFFFF.toInt() else c(R.color.line), dp(10))
            b.setOnClickListener {
                p.edit().putInt("chainView", i).apply()
                rerender()
            }
            seg.addView(b, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        val segLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        segLp.topMargin = dp(14)
        list.addView(seg, segLp)

        if (habits.isEmpty()) {
            list.addView(emptyCard())
            return
        }

        when (view) {
            0 -> onePager(list, habits, today)
            else -> {
                val shown = if (view == 1) habits.filter { it.marked } else habits
                if (shown.isEmpty()) {
                    val none = a.mtext("No marked habits yet. Hold a habit and choose Mark, or switch Mark on when editing it.",
                        14f, 500, c(R.color.text_dim))
                    none.setLineSpacing(dpf(3f), 1f)
                    none.setPadding(0, dp(20), 0, dp(8))
                    list.addView(none)
                } else {
                    grid(list, shown, today)
                    monthCard(list, shown, today, if (view == 1) "marked habits" else "all habits")
                }
            }
        }

        // Yesterday can still be linked.
        for (h in habits) {
            val y = today.minusDays(1)
            if (h.linkable(y, today) && !h.ticked(y)) list.addView(forgotBox(h, y))
        }
        maybeCelebrate(list)
    }

    private fun emptyCard(): View {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.background = rounded(c(R.color.surface), dp(28))
        box.setPadding(dp(24), dp(28), dp(24), dp(24))
        val flame = FlameView(a)
        flame.set(c(R.color.accent), 0f, 0, linked = false, flicker = false)
        flame.showNumber = false
        box.addView(flame, LinearLayout.LayoutParams(dp(140), dp(120)))
        val t = a.mtext("Start your first chain", 20f, 800, c(R.color.text_primary))
        t.setPadding(0, dp(8), 0, dp(6))
        box.addView(t)
        val s = a.mtext("A habit for the days you choose. Link it each day; rest days never break the chain.", 14f, 500, c(R.color.text_secondary))
        s.gravity = Gravity.CENTER
        s.setLineSpacing(dpf(3f), 1f)
        box.addView(s)
        val b = bigButton("Add a habit", c(R.color.accent), filled = true)
        b.setOnClickListener { edit(null) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        lp.topMargin = dp(20)
        box.addView(b, lp)
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.topMargin = dp(18)
        box.layoutParams = lp2
        return box
    }

    // ----- One: a big card per habit, swiped sideways -----

    private fun onePager(list: LinearLayout, habits: List<Habit>, today: LocalDate) {
        val wanted = prefs(a).getString("chainHabit", "")
        habits.indexOfFirst { it.id == wanted }.takeIf { it >= 0 }?.let { page = it }
        page = page.coerceIn(0, habits.size - 1)

        val pager = PagerView(a)
        for (h in habits) pager.addPage(bigCard(h, today))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(16)
        list.addView(pager, lp)

        val dots = LinearLayout(a)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        dots.setPadding(0, dp(12), 0, dp(4))
        fun paintDots(current: Int) {
            dots.removeAllViews()
            if (habits.size < 2) return
            for (i in habits.indices) {
                val v = View(a)
                val on = i == current
                v.background = rounded(if (on) habitGlow(habits[i], habits[i].streak(today)) else c(R.color.line), dp(3))
                val dl = LinearLayout.LayoutParams(dp(if (on) 18 else 6), dp(6))
                dl.marginStart = dp(3)
                dl.marginEnd = dp(3)
                dots.addView(v, dl)
            }
        }
        paintDots(page)
        list.addView(dots)
        pager.onPage = {
            page = it
            prefs(a).edit().putString("chainHabit", habits[it].id).apply()
            paintDots(it)
        }
        pager.show(page)
    }

    private fun bigCard(h: Habit, today: LocalDate): View {
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(20), dp(20), dp(20), dp(20))

        val name = a.mtext((if (h.marked) "★  " else "") + h.title, 15f, 700, c(R.color.text_primary))
        name.gravity = Gravity.CENTER
        card.addView(name)
        val days = a.mtext(habitDays(h.mask), 12f, 500, c(R.color.text_secondary))
        days.setPadding(0, dp(4), 0, 0)
        card.addView(days)

        val flame = FlameView(a)
        card.addView(flame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))
        val status = a.mtext("", 13f, 600, c(R.color.text_primary))
        status.gravity = Gravity.CENTER
        card.addView(status)
        val warn = a.mtext("Link before midnight to keep it", 12f, 700, c(R.color.accent))
        warn.background = rounded(c(R.color.accent_soft), dp(12))
        warn.setPadding(dp(12), dp(6), dp(12), dp(6))
        val warnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        warnLp.topMargin = dp(10)
        card.addView(warn, warnLp)
        val freezeNote = a.mtext("", 12f, 600, c(R.color.text_secondary))
        freezeNote.setPadding(0, dp(8), 0, 0)
        card.addView(freezeNote)

        val weeks = WeeksView(a)
        val wl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        wl.topMargin = dp(14)
        card.addView(weeks, wl)

        val badges = LinearLayout(a)
        badges.orientation = LinearLayout.HORIZONTAL
        val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bl.topMargin = dp(14)
        card.addView(badges, bl)

        val button = TextView(a)
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        btnLp.topMargin = dp(16)
        card.addView(button, btnLp)

        fun fill() {
            val (streak, forgiven) = h.chain(today)
            val best = h.best(today)
            val glow = habitGlow(h, streak)
            val heat = heatOf(streak)
            val linked = h.ticked(today)
            val planned = h.planned(today) && !today.isBefore(h.start)

            // The longer the chain, the warmer and brighter the card.
            val surface = c(R.color.surface)
            val bg = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix(surface, glow, 0.10f + 0.22f * heat + (if (linked) 0.06f else 0f)), mix(surface, glow, 0.04f + 0.06f * heat), surface)
            )
            bg.cornerRadius = dpf(30f)
            bg.setStroke(dp(1), mix(surface, glow, 0.22f + 0.35f * heat))
            card.background = bg
            days.setTextColor(mix(c(R.color.text_secondary), glow, 0.35f))

            flame.set(glow, heat, streak, linked, almostBroken(h, today))
            status.setTextColor(if (light) Look.deeper(glow) else mix(c(R.color.text_primary), glow, 0.25f))
            status.text = when {
                streak == 0 && best > 0 -> "Best: $best  ·  a fresh start today"
                streak == 0 -> "Link today to start your chain"
                streak >= best && streak > 1 -> (if (streak == 1) "day" else "days") + " unbroken  ·  best ever"
                else -> (if (streak == 1) "day" else "days") + " unbroken  ·  best $best"
            }
            warn.visibility = if (planned && !linked && streak > 0) View.VISIBLE else View.GONE
            val lastFreeze = forgiven.firstOrNull()
            freezeNote.visibility = if (lastFreeze != null) View.VISIBLE else View.GONE
            if (lastFreeze != null) freezeNote.text = "Streak freeze used on " + lastFreeze.format(F_DAY)

            weeks.set(h, today, glow)
            badges.removeAllViews()
            for ((n, label) in listOf(7 to "Week", 30 to "Month", 100 to "Summit")) {
                val earned = n in h.badges
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.HORIZONTAL
                cell.gravity = Gravity.CENTER_VERTICAL
                cell.background = rounded(if (earned) withAlpha(glow, 0x22) else withAlpha(c(R.color.text_primary), 0x0A), dp(14))
                cell.setPadding(dp(6), dp(6), dp(6), dp(6))
                val hex = BadgeView(a)
                hex.set(n, if (earned) glow else 0, false)
                cell.addView(hex, LinearLayout.LayoutParams(dp(26), dp(29)))
                val col = LinearLayout(a)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(5), 0, 0, 0)
                col.addView(a.mtext(label, 11.5f, 700, c(R.color.text_primary)))
                col.addView(a.mtext(if (earned) "Earned" else "${max(0, n - streak)} to go", 10f, 500, c(R.color.text_secondary)))
                cell.addView(col)
                val cl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (n != 7) cl.marginStart = dp(6)
                badges.addView(cell, cl)
            }

            styleButton(button, glow, linked, planned, big = true)
        }
        fill()

        button.setOnClickListener {
            if (!(h.planned(today) && !today.isBefore(h.start))) return@setOnClickListener
            val before = h.streak(today)
            val on = !h.ticked(today)
            Store.linkHabit(h, today, on)
            Store.save()
            Reminders.schedule(a)
            fill()
            if (on) {
                firmBuzz(button)
                flame.flare(before)
                weeks.lightToday()
                card.animate().scaleX(1.015f).scaleY(1.015f).setDuration(140).withEndAction {
                    card.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
                card.postDelayed({ maybeCelebrate(card) }, 1100)
            } else {
                button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        card.setOnLongClickListener {
            menu(h, card)
            true
        }
        return card
    }

    private fun styleButton(b: TextView, glow: Int, linked: Boolean, planned: Boolean, big: Boolean) {
        b.gravity = Gravity.CENTER
        b.typeface = Fonts.manrope(a, 800)
        b.textSize = if (big) 16f else 13f
        val r = dp(if (big) 27 else 19)
        val onGlow = if (light) 0xFFFFFFFF.toInt() else 0xFF14100C.toInt()
        when {
            !planned -> {
                b.text = if (big) "Rest day  ·  not planned today" else "Rest day"
                b.setTextColor(c(R.color.text_dim))
                b.background = rounded(0, r, c(R.color.line), dp(2))
                b.elevation = 0f
            }
            linked -> {
                b.text = if (big) "✓  Linked today" else "Linked"
                b.setTextColor(onGlow)
                b.background = rippled(rounded(glow, r), onGlow)
                if (big) b.elevation = dpf(6f)
            }
            else -> {
                b.text = if (big) "✓  Link today" else "Link today"
                b.setTextColor(c(R.color.text_primary))
                b.background = rippled(rounded(withAlpha(glow, 0x14), r, glow, dp(2)), glow)
                b.elevation = 0f
            }
        }
    }

    private fun bigButton(text: String, color: Int, filled: Boolean): TextView {
        val b = a.mtext(text, 16f, 800, if (filled) c(R.color.on_accent) else c(R.color.text_primary))
        b.gravity = Gravity.CENTER
        b.background = if (filled) rippled(rounded(color, dp(27)), c(R.color.on_accent)) else rounded(0, dp(27), color, dp(2))
        return b
    }

    // ----- Marked and All: mini cards and the month -----

    private fun grid(list: LinearLayout, habits: List<Habit>, today: LocalDate) {
        var row: LinearLayout? = null
        for ((i, h) in habits.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(a)
                row.orientation = LinearLayout.HORIZONTAL
                val rl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rl.topMargin = dp(if (i == 0) 16 else 10)
                list.addView(row, rl)
            }
            val cl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i % 2 == 1) cl.marginStart = dp(10)
            row?.addView(miniCard(h, today, habits), cl)
        }
        if (habits.size % 2 == 1) row?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
    }

    private fun miniCard(h: Habit, today: LocalDate, all: List<Habit>): View {
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        val name = a.mtext((if (h.marked) "★ " else "") + h.title, 13.5f, 700, c(R.color.text_primary))
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        card.addView(name)
        val numRow = LinearLayout(a)
        numRow.orientation = LinearLayout.HORIZONTAL
        numRow.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(a)
        icon.setImageResource(R.drawable.ic_flame)
        numRow.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
        val num = a.mtext("", 40f, 800, c(R.color.text_primary))
        num.letterSpacing = -0.04f
        num.setPadding(dp(4), 0, 0, 0)
        numRow.addView(num)
        val nl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        card.addView(numRow, nl)
        val sub = a.mtext("", 11f, 600, c(R.color.text_secondary))
        card.addView(sub)
        val links = LinksStrip(a)
        val ll = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18))
        ll.topMargin = dp(8)
        card.addView(links, ll)
        val button = TextView(a)
        val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        bl.topMargin = dp(10)
        card.addView(button, bl)

        fun fill() {
            val streak = h.streak(today)
            val glow = habitGlow(h, streak)
            val heat = heatOf(streak)
            val linked = h.ticked(today)
            val surface = c(R.color.surface)
            val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix(surface, glow, 0.08f + 0.2f * heat), surface, surface))
            bg.cornerRadius = dpf(22f)
            bg.setStroke(dp(1), mix(surface, glow, 0.15f + 0.3f * heat))
            card.background = bg
            icon.setColorFilter(glow)
            icon.imageAlpha = (255 * (0.35f + 0.65f * heat)).toInt()
            num.text = streak.toString()
            val best = h.best(today)
            sub.text = when {
                streak == 0 && best > 0 -> "Best: $best · fresh start"
                almostBroken(h, today) -> "Link before midnight"
                else -> habitDays(h.mask)
            }
            sub.setTextColor(if (almostBroken(h, today)) c(R.color.accent) else c(R.color.text_secondary))
            links.set(h, today, glow)
            styleButton(button, glow, linked, h.planned(today) && !today.isBefore(h.start), big = false)
        }
        fill()
        button.setOnClickListener {
            if (!(h.planned(today) && !today.isBefore(h.start))) return@setOnClickListener
            val on = !h.ticked(today)
            Store.linkHabit(h, today, on)
            Store.save()
            Reminders.schedule(a)
            fill()
            if (on) {
                firmBuzz(button)
                links.lightToday()
                num.scaleX = 1.3f
                num.scaleY = 1.3f
                num.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
                card.postDelayed({ maybeCelebrate(card) }, 600)
            }
            // The month below shows the new state too.
            (card.parent?.parent as? ViewGroup)?.findViewWithTag<View>("chainMonth")?.invalidate()
        }
        card.setOnClickListener {
            // Open this habit's big card.
            prefs(a).edit().putInt("chainView", 0).putString("chainHabit", h.id).apply()
            rerender()
        }
        card.setOnLongClickListener {
            menu(h, card)
            true
        }
        return card
    }

    private fun monthCard(list: LinearLayout, habits: List<Habit>, today: LocalDate, what: String) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(c(R.color.surface), dp(20))
        box.setPadding(dp(14), dp(10), dp(8), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        list.addView(box, lp)
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        words.addView(a.mtext(monthName(month) + "  ·  " + what, 14f, 700, c(R.color.text_primary)))
        words.addView(a.mtext("a day with every planned habit linked glows", 11.5f, 500, c(R.color.text_secondary)))
        head.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((icon, dir) in listOf(R.drawable.ic_prev to -1L, R.drawable.ic_next to 1L)) {
            val b = ImageView(a)
            b.setImageResource(icon)
            b.setColorFilter(c(R.color.text_secondary))
            b.scaleType = ImageView.ScaleType.CENTER
            b.contentDescription = if (dir < 0) "Previous month" else "Next month"
            b.setOnClickListener {
                month = month.plusMonths(dir)
                rerender()
            }
            head.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        box.addView(head)
        val grid = MonthGlowView(a)
        grid.tag = "chainMonth"
        grid.month = month
        grid.habits = habits
        grid.onSwipe = {
            month = month.plusMonths(it.toLong())
            rerender()
        }
        val gl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gl.topMargin = dp(6)
        gl.marginEnd = dp(6)
        box.addView(grid, gl)
    }

    private fun monthName(m: YearMonth): String = m.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) +
        (if (m.year != LocalDate.now().year) " " + m.year else "")

    // ----- Forgot yesterday? -----

    private fun forgotBox(h: Habit, y: LocalDate): View {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = rounded(c(R.color.surface), dp(16))
        box.setPadding(dp(14), dp(12), dp(12), dp(12))
        val words = LinearLayout(a)
        words.orientation = LinearLayout.VERTICAL
        words.addView(a.mtext(h.title + ": forgot yesterday?", 13.5f, 700, c(R.color.text_primary)))
        val weekday = y.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
        val s = a.mtext("$weekday can still be linked until midnight.", 12f, 500, c(R.color.text_secondary))
        s.setPadding(0, dp(3), 0, 0)
        words.addView(s)
        box.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val glow = habitGlow(h, h.streak(LocalDate.now()))
        val b = a.mtext("Link " + DAY_NAMES[y.dayOfWeek.value - 1], 13f, 700, c(R.color.text_primary))
        b.gravity = Gravity.CENTER
        b.background = rippled(rounded(0, dp(18), glow, dp(1)), glow)
        b.setPadding(dp(14), 0, dp(14), 0)
        b.setOnClickListener {
            Store.linkHabit(h, y, true)
            Store.save()
            Reminders.schedule(a)
            firmBuzz(b)
            collapse(box) { rerender() }
        }
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        box.layoutParams = lp
        return box
    }

    // ----- Badges: the full-screen moment -----

    private fun maybeCelebrate(anchor: View) {
        val m = Store.milestone ?: return
        val h = Store.habits.get(m.first) ?: run {
            Store.milestone = null
            return
        }
        Store.milestone = null
        if (a.isFinishing || anchor.windowToken == null) return
        showMilestone(a, h, m.second)
    }

    // ----- Edit, mark, delete -----

    private fun menu(h: Habit, anchor: View) {
        val m = PopupMenu(a, anchor, Gravity.END)
        m.menu.add(0, 1, 0, "Edit")
        m.menu.add(0, 2, 0, if (h.marked) "Unmark" else "Mark")
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
                3 -> confirmDelete(h)
            }
            true
        }
        m.show()
    }

    private fun confirmDelete(h: Habit) {
        AlertDialog.Builder(a)
            .setMessage("Delete \"${h.title}\" and its whole chain? Tasks connected to it stay.")
            .setPositiveButton("Delete") { _, _ ->
                for (t in Store.tasks) if (t.habitId == h.id) {
                    t.habitId = ""
                    t.updated = System.currentTimeMillis()
                }
                Store.habits.remove(h)
                Store.save()
                Reminders.schedule(a)
                rerender()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** The habit form: name, days, colour, Mark, evening reminder, streak freeze. */
    private fun edit(h: Habit?) {
        var mask = h?.mask ?: 127
        var color = h?.color ?: HABIT_COLORS.firstOrNull { col -> Store.habits.items.none { it.color == col.second } }?.second
            ?: HABIT_COLORS[0].second
        var remindAt = h?.remindAt ?: 21 * 60
        val scroll = ScrollView(a)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(12), dp(22), dp(8))
        scroll.addView(box)

        fun label(text: String) {
            val l = a.mtext(text.uppercase(), 11f, 700, c(R.color.text_dim))
            l.letterSpacing = 0.08f
            l.setPadding(0, dp(18), 0, dp(8))
            box.addView(l)
        }
        label("Name")
        val name = EditText(a)
        name.hint = "e.g. Read 20 pages"
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.maxLines = 1
        name.typeface = Fonts.manrope(a, 600)
        name.textSize = 16f
        name.setTextColor(c(R.color.text_primary))
        name.setHintTextColor(c(R.color.text_dim))
        name.background = rounded(c(R.color.surface), dp(14))
        name.setPadding(dp(14), dp(14), dp(14), dp(14))
        name.setText(h?.title ?: "")
        box.addView(name)

        label("Days  ·  rest days don't break the chain")
        val dayRow = LinearLayout(a)
        dayRow.orientation = LinearLayout.HORIZONTAL
        box.addView(dayRow)
        label("Colour")
        val colorRow = LinearLayout(a)
        colorRow.orientation = LinearLayout.HORIZONTAL
        box.addView(colorRow)
        val info = a.mtext("", 12.5f, 500, c(R.color.text_secondary))
        info.setLineSpacing(dpf(2f), 1f)

        fun paint() {
            val shown = Look.tone(color)
            dayRow.removeAllViews()
            for (i in 0 until 7) {
                val on = mask and (1 shl i) != 0
                val d = a.mtext(DAY_NAMES[i], 12.5f, 700, if (on) (if (light) 0xFFFFFFFF.toInt() else 0xFF08200F.toInt()) else c(R.color.text_secondary))
                d.gravity = Gravity.CENTER
                d.background = if (on) rounded(shown, dp(12)) else rounded(0, dp(12), c(R.color.line), dp(1))
                d.setOnClickListener {
                    val next = mask xor (1 shl i)
                    if (next != 0) mask = next
                    paint()
                }
                val l = LinearLayout.LayoutParams(0, dp(44), 1f)
                if (i > 0) l.marginStart = dp(5)
                dayRow.addView(d, l)
            }
            colorRow.removeAllViews()
            for ((label, col) in HABIT_COLORS) {
                val dot = View(a)
                dot.contentDescription = label
                dot.background = if (col == color) rounded(Look.tone(col), dp(18), c(R.color.text_primary), dp(3)) else rounded(Look.tone(col), dp(18))
                dot.setOnClickListener {
                    color = col
                    paint()
                }
                val l = LinearLayout.LayoutParams(dp(34), dp(34))
                l.marginEnd = dp(10)
                colorRow.addView(dot, l)
            }
            val n = Integer.bitCount(mask)
            info.text = (if (n == 7) "Every day." else plural(n, "day") + " a week.") +
                " Your first badge comes after 7 linked " + (if (n == 7) "days." else "sessions.")
        }
        paint()

        // Switches and the reminder time.
        val opts = LinearLayout(a)
        opts.orientation = LinearLayout.VERTICAL
        opts.background = rounded(c(R.color.surface), dp(16))
        val ol = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ol.topMargin = dp(18)
        box.addView(opts, ol)
        fun option(title: String, sub: String, right: View): LinearLayout {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(12), dp(14), dp(12))
            val w = LinearLayout(a)
            w.orientation = LinearLayout.VERTICAL
            w.addView(a.mtext(title, 14.5f, 700, c(R.color.text_primary)))
            val s = a.mtext(sub, 12f, 500, c(R.color.text_secondary))
            s.setPadding(0, dp(3), 0, 0)
            w.addView(s)
            r.addView(w, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(right)
            opts.addView(r)
            return r
        }
        fun switch(on: Boolean): Switch {
            val s = Switch(a)
            s.isChecked = on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            s.thumbTintList = ColorStateList(states, intArrayOf(c(R.color.accent), c(R.color.text_secondary)))
            s.trackTintList = ColorStateList(states, intArrayOf(withAlpha(c(R.color.accent), 0x80), c(R.color.line)))
            return s
        }
        val mark = switch(h?.marked ?: false)
        option("Mark this habit", "Shows in the Marked view", mark)
        val time = a.mtext("", 15f, 800, c(R.color.accent))
        fun showTime() {
            time.text = if (remindAt >= 0) fmtTime(remindAt) else "Off"
        }
        showTime()
        val remindRow = option("Evening reminder", "Only if it isn't linked yet", time)
        remindRow.setOnClickListener {
            val start = if (remindAt >= 0) remindAt else 21 * 60
            val d = TimePickerDialog(a, R.style.AppDialog, { _, hh, mm ->
                remindAt = hh * 60 + mm
                showTime()
            }, start / 60, start % 60, true)
            d.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "No reminder") { _, _ ->
                remindAt = -1
                showTime()
            }
            d.show()
        }
        val freeze = switch(h?.freeze ?: false)
        option("Streak freeze", "One missed planned day a month won't break the chain", freeze)

        val infoBox = LinearLayout(a)
        infoBox.orientation = LinearLayout.HORIZONTAL
        infoBox.gravity = Gravity.CENTER_VERTICAL
        infoBox.background = rounded(withAlpha(Look.tone(color), 0x14), dp(16), withAlpha(Look.tone(color), 0x40), dp(1))
        infoBox.setPadding(dp(14), dp(12), dp(14), dp(12))
        val fi = ImageView(a)
        fi.setImageResource(R.drawable.ic_flame)
        fi.setColorFilter(Look.tone(color))
        fi.imageAlpha = 140
        infoBox.addView(fi, LinearLayout.LayoutParams(dp(26), dp(26)))
        info.setPadding(dp(12), 0, 0, 0)
        infoBox.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val il = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        il.topMargin = dp(14)
        box.addView(infoBox, il)

        val b = AlertDialog.Builder(a)
            .setTitle(if (h == null) "New habit" else "Edit habit")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val habit = h ?: Habit().also { Store.habits.add(it) }
                habit.title = text
                habit.mask = mask
                habit.color = color
                habit.marked = mark.isChecked
                habit.remindAt = remindAt
                habit.freeze = freeze.isChecked
                habit.touch()
                Store.save()
                Reminders.schedule(a)
                if (h == null) prefs(a).edit().putString("chainHabit", habit.id).putInt("chainView", 0).apply()
                rerender()
            }
            .setNegativeButton("Cancel", null)
        if (h != null) b.setNeutralButton("Delete") { _, _ -> confirmDelete(h) }
        b.show()
    }
}

// ---------- A sideways pager of cards ----------

/** Shows one page at a time; a swipe snaps to the next or previous page. */
class PagerView(ctx: Context) : HorizontalScrollView(ctx) {
    var onPage: ((Int) -> Unit)? = null
    private val row = LinearLayout(ctx)
    private var current = 0
    private var flung = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun addPage(v: View) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(4)
        lp.marginEnd = dp(4)
        row.addView(v, lp)
    }

    fun show(index: Int) {
        current = index
        post { scrollTo(index * width, 0) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw) return
        for (i in 0 until row.childCount) {
            val lp = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            lp.width = w - lp.marginStart - lp.marginEnd
            row.getChildAt(i).layoutParams = lp
        }
        post { scrollTo(current * w, 0) }
    }

    override fun fling(velocityX: Int) {
        flung = true
        val target = when {
            velocityX > 400 -> current + 1
            velocityX < -400 -> current - 1
            else -> Math.round(scrollX.toFloat() / max(1, width))
        }
        snap(target)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val r = super.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
            snap(Math.round(scrollX.toFloat() / max(1, width)))
        }
        return r
    }

    private fun snap(index: Int) {
        val i = index.coerceIn(0, max(0, row.childCount - 1))
        smoothScrollTo(i * width, 0)
        if (i != current) {
            current = i
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onPage?.invoke(i)
        }
    }
}

// ---------- The flame with the giant number ----------

/**
 * The heart of a card: a flame behind the streak number. Linked today, the flame burns brighter.
 * `flare` plays the moment of linking (flame grows, sparks and rings, the number rolls up, +1).
 * Close to breaking, the flame flickers dimmer.
 */
class FlameView(ctx: Context) : View(ctx) {
    var showNumber = true
    private var color = 0
    private var heat = 0f
    private var number = 0
    private var from = 0           // the number before a flare, for the roll
    private var linked = false
    private var flicker = false
    private var t = 1f             // flare progress
    private var f = 0f             // flicker phase
    private val d = resources.displayMetrics.density
    private val flame = flamePath()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.manrope(ctx, 800)
    }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.manrope(ctx, 800)
    }
    private var flareAnim: ValueAnimator? = null
    private var flickerAnim: ValueAnimator? = null
    private val m = android.graphics.Matrix()
    private val drawn = Path()

    fun set(color: Int, heat: Float, number: Int, linked: Boolean, flicker: Boolean) {
        this.color = color
        this.heat = heat
        this.number = number
        this.linked = linked
        if (flicker != this.flicker) {
            this.flicker = flicker
            if (isAttachedToWindow) updateFlicker()
        }
        invalidate()
    }

    fun flare(before: Int) {
        from = before
        flareAnim?.cancel()
        flareAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFlicker()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flickerAnim?.cancel()
        flareAnim?.cancel()
    }

    private fun updateFlicker() {
        flickerAnim?.cancel()
        flickerAnim = null
        f = 0f
        if (!flicker) return
        flickerAnim = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 1_000_000
            interpolator = LinearInterpolator()
            addUpdateListener {
                f = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val light = Look.light
        val cx = width / 2f
        val cy = height / 2f
        val size = min(height * 0.98f, 160f * d)
        // Flare: the flame swells and settles.
        val swell = if (t < 1f) 1f + 0.32f * sin(Math.PI * min(1f, t / 0.55f)).toFloat() else 1f
        var alpha = if (linked) 0.30f + 0.35f * heat else 0.13f + 0.10f * heat
        if (t < 1f) alpha = min(0.95f, alpha + 0.45f * (1f - t))
        if (flicker) {
            // An uneasy flame: dimmer, with an irregular flicker.
            val wobble = 0.5f + 0.25f * sin(f * 9.1f) + 0.25f * sin(f * 23.7f + 1.3f)
            alpha *= 0.35f + 0.45f * wobble
        }
        val s = size / 24f * swell
        m.reset()
        m.postScale(s, s)
        m.postTranslate(cx - 12f * s, cy - 12f * s - 4f * d)
        drawn.reset()
        flame.transform(m, drawn)
        val top = mix(color, 0xFFFFFFFF.toInt(), if (light) 0.45f else 0.7f)
        val deep = mix(color, 0xFF000000.toInt(), 0.3f)
        fill.shader = RadialGradient(cx, cy + size * 0.25f, size * 0.62f, intArrayOf(top, color, deep), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        fill.alpha = (255 * alpha).toInt()
        c.drawPath(drawn, fill)
        fill.shader = null

        if (t < 1f) {
            // Rings spreading out.
            for (k in 0..1) {
                val p = ((t - k * 0.12f) / 0.8f).coerceIn(0f, 1f)
                if (p <= 0f || p >= 1f) continue
                ring.color = withAlpha(mix(color, 0xFFFFFFFF.toInt(), 0.4f), (170 * (1f - p)).toInt())
                ring.strokeWidth = (2.2f - k * 0.6f) * d
                c.drawCircle(cx, cy, size * (0.45f + 0.55f * p + k * 0.12f), ring)
            }
            // Sparks flying.
            val life = (t / 0.85f).coerceIn(0f, 1f)
            if (life < 1f) for (k in 0 until 16) {
                val ang = k / 16.0 * Math.PI * 2 + (k % 3) * 0.17
                val r = size * (0.35f + (0.55f + (k % 4) * 0.12f) * (1f - (1f - life) * (1f - life)))
                fill.color = if (k % 2 == 0) mix(color, 0xFFFFFFFF.toInt(), 0.55f) else 0xFFFFB38A.toInt()
                fill.alpha = (230 * (1f - life)).toInt()
                c.drawCircle(cx + cos(ang).toFloat() * r, cy + sin(ang).toFloat() * r * 0.8f, (2.5f + (k % 3)) * d, fill)
            }
        }
        if (!showNumber) return

        // The number, glowing when linked. During a flare the old number rolls up and out.
        val textColor = if (light) Look.deeper(mix(color, 0xFF000000.toInt(), 0.2f)) else mix(color, 0xFFFFFFFF.toInt(), if (linked) 0.82f else 0.7f)
        num.textSize = 88f * d * (if (number >= 100) 0.82f else 1f)
        num.letterSpacing = -0.04f
        num.color = textColor
        if (linked && !light) num.setShadowLayer(30f * d, 0f, 0f, withAlpha(color, (150 + 80 * heat).toInt()))
        else num.clearShadowLayer()
        val base = cy + num.textSize * 0.36f
        val roll = if (t < 1f && from != number) (t / 0.45f).coerceIn(0f, 1f) else 1f
        if (roll < 1f) {
            val e = DecelerateInterpolator(1.6f).getInterpolation(roll)
            val shift = 40f * d
            num.alpha = (255 * (1f - e)).toInt()
            c.drawText(from.toString(), cx, base - shift * e, num)
            num.alpha = (255 * e).toInt()
            c.drawText(number.toString(), cx, base + shift * (1f - e), num)
            num.alpha = 255
        } else {
            c.drawText(number.toString(), cx, base, num)
        }
        // "+1" floating away.
        if (t < 1f && number > from) {
            val p = (t / 0.9f).coerceIn(0f, 1f)
            plus.textSize = 20f * d
            plus.color = if (light) Look.deeper(0xFFFF8552.toInt()) else 0xFFFFB38A.toInt()
            plus.alpha = (255 * (1f - p)).toInt()
            c.drawText("+" + (number - from), cx + 70f * d, cy - 30f * d - 36f * d * p, plus)
        }
    }
}

// ---------- Five weeks, the last one as chain links ----------

private const val S_FUTURE = 0
private const val S_REST = 1
private const val S_LINKED = 2
private const val S_MISSED = 3
private const val S_OPEN = 4
private const val S_FROZEN = 5

private fun stateOf(h: Habit, d: LocalDate, today: LocalDate, forgiven: List<LocalDate>): Int = when {
    d.isAfter(today) -> S_FUTURE
    h.ticked(d) -> S_LINKED
    d.isBefore(h.start) || !h.planned(d) -> S_REST
    d == today -> S_OPEN
    d in forgiven -> S_FROZEN
    else -> S_MISSED
}

/**
 * One chain link in `r`. Flat links and upright ones alternate, like a real chain. A linked one is
 * a bright ring (glowing when `glow`); an open one dashed; a missed one broken in the middle.
 */
private fun drawLink(c: Canvas, r: RectF, state: Int, color: Int, flat: Boolean, lit: Float, p: Paint, ctx: Context) {
    val d = ctx.resources.displayMetrics.density
    val line = ctx.getColor(R.color.line)
    val dim = ctx.getColor(R.color.text_dim)
    val box = RectF(r)
    if (!flat) box.inset(r.width() * 0.16f, r.height() * 0.2f)
    val rad = box.height() / 2f
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    p.pathEffect = null
    p.clearShadowLayer()
    when (state) {
        S_LINKED -> {
            if (lit > 0f) {
                p.color = withAlpha(color, (90 * lit).toInt())
                p.strokeWidth = 8f * d
                c.drawRoundRect(box, rad, rad, p)
            }
            p.color = if (lit > 0f && !Look.light) mix(color, 0xFFFFFFFF.toInt(), 0.6f * lit) else color
            p.strokeWidth = 3.2f * d
            c.drawRoundRect(box, rad, rad, p)
        }
        S_OPEN -> {
            p.color = color
            p.strokeWidth = 1.8f * d
            p.pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            c.drawRoundRect(box, rad, rad, p)
            p.pathEffect = null
        }
        S_FROZEN -> {
            p.color = withAlpha(color, 0x99)
            p.strokeWidth = 1.6f * d
            p.pathEffect = DashPathEffect(floatArrayOf(2f * d, 2.5f * d), 0f)
            c.drawRoundRect(box, rad, rad, p)
            p.pathEffect = null
        }
        S_MISSED -> {
            p.color = line
            p.strokeWidth = 2.4f * d
            c.save()
            c.clipRect(box.left - 4 * d, box.top - 4 * d, box.centerX() - 2.5f * d, box.bottom + 4 * d)
            c.drawRoundRect(box, rad, rad, p)
            c.restore()
            c.save()
            c.clipRect(box.centerX() + 2.5f * d, box.top - 4 * d, box.right + 4 * d, box.bottom + 4 * d)
            c.drawRoundRect(box, rad, rad, p)
            c.restore()
        }
        S_REST -> {
            p.color = withAlpha(dim, 0x55)
            p.strokeWidth = 1.2f * d
            box.inset(box.width() * 0.25f, box.height() * 0.2f)
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, p)
        }
        else -> {
            p.color = withAlpha(dim, 0x30)
            p.strokeWidth = 1.2f * d
            c.drawRoundRect(box, rad, rad, p)
        }
    }
}

class WeeksView(ctx: Context) : View(ctx) {
    private var states = IntArray(35)
    private var color = 0
    private var todayIndex = -1
    private var lit = 0f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(h: Habit, today: LocalDate, color: Int) {
        this.color = color
        val forgiven = h.chain(today).second
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(4)
        for (i in 0 until 35) {
            val day = monday.plusDays(i.toLong())
            states[i] = stateOf(h, day, today, forgiven)
            if (day == today) todayIndex = i
        }
        lit = if (h.ticked(today)) 0.6f else 0f
        invalidate()
    }

    /** Today's link lights up. */
    fun lightToday() {
        ValueAnimator.ofFloat(1f, 0.6f).apply {
            duration = 900
            addUpdateListener {
                lit = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (4 * 18f * d + 30f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / 7f
        val gap = 6f * d
        // Four earlier weeks as bars.
        for (i in 0 until 28) {
            val col = i % 7
            val row = i / 7
            val top = row * 18f * d
            r.set(col * cw + gap / 2, top, (col + 1) * cw - gap / 2, top + 12f * d)
            p.style = Paint.Style.FILL
            p.pathEffect = null
            val rad = 6f * d
            when (states[i]) {
                S_LINKED -> {
                    p.color = withAlpha(color, (90 + 165 * (i / 27f)).toInt())
                    c.drawRoundRect(r, rad, rad, p)
                }
                S_MISSED -> {
                    p.color = context.getColor(R.color.line)
                    c.drawRoundRect(r, rad, rad, p)
                }
                S_FROZEN -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.4f * d
                    p.color = withAlpha(color, 0x99)
                    p.pathEffect = DashPathEffect(floatArrayOf(2f * d, 2f * d), 0f)
                    c.drawRoundRect(r, rad, rad, p)
                }
                S_REST -> {
                    p.color = withAlpha(context.getColor(R.color.text_dim), 0x33)
                    c.drawCircle(r.centerX(), r.centerY(), 2.5f * d, p)
                }
                else -> {
                    p.color = withAlpha(context.getColor(R.color.text_dim), 0x18)
                    c.drawRoundRect(r, rad, rad, p)
                }
            }
        }
        // This week: interlocking links, overlapping their neighbours.
        val top = 4 * 18f * d + 2f * d
        val h = 22f * d
        for (i in 28 until 35) {
            val col = i - 28
            val cxL = col * cw + cw / 2
            val half = cw * 0.68f
            r.set(cxL - half, top + (if (col % 2 == 0) 0f else 3f * d), cxL + half, top + h - (if (col % 2 == 0) 0f else 3f * d))
            drawLink(c, r, states[i], color, col % 2 == 0, if (i == todayIndex) lit else 0.25f, p, context)
        }
        p.pathEffect = null
    }
}

/** The last 7 days as interlocking links (mini cards), today on the right. */
class LinksStrip(ctx: Context) : View(ctx) {
    private val states = IntArray(7)
    private var color = 0
    private var lit = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(h: Habit, today: LocalDate, color: Int) {
        this.color = color
        val forgiven = h.chain(today).second
        for (i in 0 until 7) states[i] = stateOf(h, today.minusDays((6 - i).toLong()), today, forgiven)
        lit = if (h.ticked(today)) 0.6f else 0f
        invalidate()
    }

    fun lightToday() {
        ValueAnimator.ofFloat(1f, 0.6f).apply {
            duration = 900
            addUpdateListener {
                lit = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cw = width / 7f
        for (i in 0 until 7) {
            val cx = i * cw + cw / 2
            val half = cw * 0.66f
            val inset = if (i % 2 == 0) 0f else 2.5f * d
            r.set(cx - half, inset + 1f * d, cx + half, height - inset - 1f * d)
            drawLink(c, r, states[i], color, i % 2 == 0, if (i == 6) lit else 0.2f, p, context)
        }
    }
}

// ---------- The month: a day glows when every planned habit was linked ----------

class MonthGlowView(ctx: Context) : View(ctx) {
    var month: YearMonth = YearMonth.now()
    var habits: List<Habit> = emptyList()
    var onSwipe: ((Int) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val head = dp(20)
    private val cell = dp(30)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.manrope(ctx, 700)
    }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var downX = 0f
    private var downY = 0f

    private fun rows() = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), head + rows() * cell)
    }

    override fun onDraw(c: Canvas) {
        val today = LocalDate.now()
        val accent = context.getColor(R.color.accent)
        val cw = width / 7f
        text.textSize = 10.5f * d
        text.color = context.getColor(R.color.text_dim)
        for ((i, l) in listOf("M", "T", "W", "T", "F", "S", "S").withIndex()) c.drawText(l, cw * (i + 0.5f), 12f * d, text)
        val glow = if (habits.size == 1) habitGlow(habits[0], habits[0].streak(today)) else accent
        val offset = month.atDay(1).dayOfWeek.value - 1
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val i = offset + day - 1
            val cx = cw * (i % 7 + 0.5f)
            val cy = head + (i / 7) * cell + cell / 2f
            r.set(cx - 15f * d, cy - 11f * d, cx + 15f * d, cy + 11f * d)
            var planned = 0
            var linked = 0
            for (h in habits) if (h.planned(date) && !date.isBefore(h.start)) {
                planned++
                if (h.ticked(date)) linked++
            }
            text.textSize = 12f * d
            shape.style = Paint.Style.FILL
            shape.clearShadowLayer()
            val future = date.isAfter(today)
            text.color = context.getColor(if (future) R.color.text_dim else R.color.text_primary)
            if (!future && planned > 0 && linked > 0) {
                val share = linked.toFloat() / planned
                if (share >= 1f) {
                    shape.color = glow
                    if (!Look.light) shape.setShadowLayer(8f * d, 0f, 0f, withAlpha(glow, 0x77))
                    c.drawRoundRect(r, 8f * d, 8f * d, shape)
                    shape.clearShadowLayer()
                    text.color = if (Look.light) 0xFFFFFFFF.toInt() else 0xFF1A0D06.toInt()
                } else {
                    shape.color = withAlpha(glow, (share * 0.4f * 255).toInt())
                    c.drawRoundRect(r, 8f * d, 8f * d, shape)
                }
            }
            if (date == today) {
                shape.style = Paint.Style.STROKE
                shape.strokeWidth = 1.5f * d
                shape.color = accent
                c.drawRoundRect(r, 8f * d, 8f * d, shape)
            }
            c.drawText(day.toString(), cx, cy + 4.2f * d, text)
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

// ---------- Badges ----------

/** A hexagon badge with the number of days; `color` 0 = not earned yet. */
class BadgeView(ctx: Context) : View(ctx) {
    private var n = 7
    private var color = 0
    private var big = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun set(n: Int, color: Int, big: Boolean) {
        this.n = n
        this.color = color
        this.big = big
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sx = w / 34f
        val sy = h / 38f
        path.reset()
        path.moveTo(17 * sx, 1 * sy)
        path.lineTo(33 * sx, 10 * sy)
        path.lineTo(33 * sx, 28 * sy)
        path.lineTo(17 * sx, 37 * sy)
        path.lineTo(1 * sx, 28 * sy)
        path.lineTo(1 * sx, 10 * sy)
        path.close()
        val earned = color != 0
        p.style = Paint.Style.FILL
        p.shader = if (earned && big) android.graphics.LinearGradient(0f, 0f, 0f, h,
            mix(color, 0xFFFFFFFF.toInt(), 0.6f), color, Shader.TileMode.CLAMP) else null
        p.color = if (earned) color else context.getColor(R.color.surface2)
        c.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (big) 2f else 1.2f) * resources.displayMetrics.density
        p.color = if (earned) mix(color, 0xFFFFFFFF.toInt(), 0.4f) else context.getColor(R.color.line)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Fonts.manrope(context, 800)
        p.color = if (earned) mix(color, 0xFF000000.toInt(), 0.75f) else context.getColor(R.color.text_dim)
        p.textSize = h * (if (big) 0.29f else 0.34f)
        c.drawText(n.toString(), w / 2, h * (if (big) 0.56f else 0.62f), p)
        if (big) {
            p.textSize = h * 0.07f
            p.letterSpacing = 0.15f
            c.drawText("DAYS", w / 2, h * 0.72f, p)
            p.letterSpacing = 0f
        }
    }
}

/** The full-screen moment a badge is earned. "Keep climbing" (or a tap anywhere) closes it. */
fun showMilestone(a: Activity, h: Habit, days: Int) {
    val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
    val today = LocalDate.now()
    val glow = habitGlow(h, days)
    val bg = a.getColor(R.color.bg)
    val root = FrameLayout(a)
    root.isClickable = true
    val sky = MilestoneSky(a, glow)
    root.addView(sky, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val col = LinearLayout(a)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    col.setPadding(a.dp(28), a.dp(70), a.dp(28), a.dp(40))
    root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val kicker = a.mtext("NEW BADGE", 13f, 800, if (Look.light) Look.deeper(glow) else mix(glow, 0xFFFFFFFF.toInt(), 0.5f))
    kicker.letterSpacing = 0.15f
    col.addView(kicker)
    val badge = BadgeView(a)
    badge.set(days, glow, true)
    val bl = LinearLayout.LayoutParams(a.dp(170), a.dp(190))
    bl.topMargin = a.dp(26)
    col.addView(badge, bl)
    val title = a.mtext(when (days) {
        7 -> "A week unbroken"
        30 -> "A month unbroken"
        else -> "100 days. Summit."
    }, 30f, 800, a.getColor(R.color.text_primary))
    title.gravity = Gravity.CENTER
    title.setPadding(0, a.dp(24), 0, a.dp(6))
    col.addView(title)
    val sub = a.mtext(h.title + "  ·  " + plural(h.streak(today), "day") + " in a row", 15f, 500, a.getColor(R.color.text_secondary))
    sub.gravity = Gravity.CENTER
    col.addView(sub)
    val stats = LinearLayout(a)
    stats.orientation = LinearLayout.HORIZONTAL
    stats.gravity = Gravity.CENTER
    val sl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    sl.topMargin = a.dp(22)
    col.addView(stats, sl)
    val next = intArrayOf(7, 30, 100).firstOrNull { it > days }
    for ((big, small) in listOf(
        "${h.badges.size} of 3" to "badges earned",
        (if (next != null) "${next - h.streak(today)}" else "All") to (if (next != null) "to the next" else "badges done"),
        "${h.best(today)}" to "best chain"
    )) {
        val chip = LinearLayout(a)
        chip.orientation = LinearLayout.VERTICAL
        chip.gravity = Gravity.CENTER
        chip.background = rounded(withAlpha(a.getColor(R.color.text_primary), 0x10), a.dp(14))
        chip.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        chip.addView(a.mtext(big, 18f, 800, a.getColor(R.color.text_primary)))
        chip.addView(a.mtext(small, 11f, 500, a.getColor(R.color.text_secondary)))
        val cl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cl.marginStart = a.dp(5)
        cl.marginEnd = a.dp(5)
        stats.addView(chip, cl)
    }
    val space = View(a)
    col.addView(space, LinearLayout.LayoutParams(1, 0, 1f))
    val keep = a.mtext("Keep climbing", 16f, 800, bg)
    keep.gravity = Gravity.CENTER
    keep.background = rippled(rounded(a.getColor(R.color.text_primary), a.dp(28)), bg)
    col.addView(keep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(56)))
    val hint = a.mtext("Tap anywhere to close", 12f, 500, a.getColor(R.color.text_dim))
    hint.setPadding(0, a.dp(10), 0, 0)
    col.addView(hint)

    fun close() {
        root.animate().alpha(0f).setDuration(220).withEndAction { content.removeView(root) }.start()
    }
    keep.setOnClickListener { close() }
    root.setOnClickListener { close() }
    content.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.alpha = 0f
    root.animate().alpha(1f).setDuration(200).start()
    badge.scaleX = 0.3f
    badge.scaleY = 0.3f
    badge.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(650).setInterpolator(OvershootInterpolator(2.6f)).start()
    firmBuzz(root)
    root.postDelayed({ root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }, 300)
    sky.play()
}

/** The glowing background of the badge moment: a radial glow, turning rays, sparks. */
private class MilestoneSky(ctx: Context, private val glow: Int) : View(ctx) {
    private var t = 0f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = ctx.getColor(R.color.bg)

    fun play() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = 190f * d
        p.shader = RadialGradient(cx, cy, max(width, height) * 0.75f,
            intArrayOf(mix(bg, glow, if (Look.light) 0.35f else 0.45f), mix(bg, glow, 0.12f), bg), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        // Rays.
        p.color = withAlpha(glow, 0x1E)
        c.save()
        c.rotate(t * 50f, cx, cy)
        for (i in 0 until 12) {
            c.rotate(30f, cx, cy)
            c.drawRect(cx - 5f * d, cy - 230f * d, cx + 5f * d, cy - 120f * d, p)
        }
        c.restore()
        // Sparks drifting outwards.
        for (k in 0 until 30) {
            val ang = k / 30.0 * Math.PI * 2 + (k % 3) * 0.2
            val r = (130f + (k % 5) * 22f) * d * (0.6f + 0.6f * min(1f, t * 1.6f))
            p.color = when (k % 3) {
                0 -> mix(glow, 0xFFFFFFFF.toInt(), 0.5f)
                1 -> 0xFFFFB38A.toInt()
                else -> glow
            }
            p.alpha = (200 * (1f - t * 0.7f)).toInt()
            c.drawCircle(cx + cos(ang).toFloat() * r, cy + sin(ang).toFloat() * r, (2.5f + k % 4) * d, p)
        }
    }
}
