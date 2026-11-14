package com.alp.summit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- Small UI helpers ----------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radius.toFloat()
    if (strokeWidth > 0) g.setStroke(strokeWidth, stroke)
    return g
}

/** Same colour with a different alpha (0–255). */
fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * Draws behind the system bars and pads the screen so nothing is hidden by the status bar,
 * the navigation bar or the keyboard. The padding follows the keyboard while it slides, so
 * the content moves with it instead of jumping. `covered` is a bottom bar the keyboard may
 * simply slide over (the tabs on the main screen).
 */
fun Activity.fitSystem(root: View, covered: View? = null) {
    if (Build.VERSION.SDK_INT < 30) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
            insets
        }
        return
    }
    window.setDecorFitsSystemWindows(false)
    var sliding = false
    fun pad(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom - (covered?.height ?: 0)
        root.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard))
    }
    root.setOnApplyWindowInsetsListener { _, insets ->
        if (!sliding) pad(insets)
        insets
    }
    root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
        override fun onPrepare(animation: WindowInsetsAnimation) {
            if (animation.typeMask and WindowInsets.Type.ime() != 0) sliding = true
        }

        override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
            if (sliding) pad(insets)
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimation) {
            if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                sliding = false
                root.rootWindowInsets?.let { pad(it) }
            }
        }
    })
}

/** On the tablet, keep a screen's content in a comfortable centred column. */
fun centerColumn(root: View, body: View) {
    val base = body.paddingLeft
    root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val w = r - l - root.paddingLeft - root.paddingRight
        val pad = base + max(0, (w - root.dp(720)) / 2)
        if (pad != body.paddingLeft) root.post { body.setPadding(pad, body.paddingTop, pad, body.paddingBottom) }
    }
}

/** Adds a touch ripple on top of a rounded background. */
fun rippled(background: GradientDrawable, tint: Int): Drawable =
    RippleDrawable(
        ColorStateList.valueOf(withAlpha(tint, 0x40)), background,
        rounded(-1, background.cornerRadius.toInt())   // the shape the ripple stays inside
    )

/** Rows glide in one after another when a page appears. */
fun enter(views: List<View>) {
    for ((i, v) in views.withIndex()) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = v.dp(14).toFloat()
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(min(i, 10) * 22L).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
}

/** A screen that rises in over the main page and sinks away when it is closed. */
fun Activity.sheetMotion() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.sheet_in, R.anim.stay)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.stay, R.anim.sheet_out)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.sheet_in, R.anim.stay)
    }
}

/** Finishes a running expand/collapse on this view at once. */
fun settle(v: View) {
    (v.getTag(R.id.root) as? Animator)?.cancel()
}

/** Grows a view from nothing to its natural height. `width` is the room it has. */
fun expand(v: View, width: Int) {
    settle(v)
    v.visibility = View.VISIBLE
    v.measure(
        View.MeasureSpec.makeMeasureSpec(max(width, 0), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    resize(v, 0, v.measuredHeight, 0f, 1f) {}
}

/** Shrinks a view away, then runs `end`. */
fun collapse(v: View, end: () -> Unit) {
    settle(v)
    if (v.visibility != View.VISIBLE || v.height == 0) {
        v.visibility = View.GONE
        end()
        return
    }
    resize(v, v.height, 0, 1f, 0f) {
        v.visibility = View.GONE
        end()
    }
}

private fun resize(v: View, from: Int, to: Int, alphaFrom: Float, alphaTo: Float, end: () -> Unit) {
    val lp = v.layoutParams
    lp.height = from
    v.layoutParams = lp
    v.alpha = alphaFrom
    val a = ValueAnimator.ofFloat(0f, 1f)
    a.duration = 220
    a.interpolator = DecelerateInterpolator(1.6f)
    a.addUpdateListener {
        val f = it.animatedValue as Float
        lp.height = (from + (to - from) * f).toInt()
        v.layoutParams = lp
        v.alpha = alphaFrom + (alphaTo - alphaFrom) * f
    }
    a.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            v.setTag(R.id.root, null)
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            v.layoutParams = lp
            v.alpha = 1f
            end()
        }
    })
    v.setTag(R.id.root, a)
    a.start()
}

/** A pill-shaped choice. Selected pills take the tint colour. */
fun Context.chip(text: CharSequence, selected: Boolean, tint: Int = getColor(R.color.accent)): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.textSize = 14f
    tv.setPadding(dp(14), dp(8), dp(14), dp(8))
    tv.minWidth = dp(40)
    tv.gravity = android.view.Gravity.CENTER
    if (selected) {
        tv.setTextColor(tint)
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.background = rippled(rounded(withAlpha(tint, 0x2E), dp(20), withAlpha(tint, 0x99), dp(1)), tint)
    } else {
        tv.setTextColor(getColor(R.color.text_secondary))
        tv.background = rippled(rounded(getColor(R.color.surface), dp(20)), tint)
    }
    return tv
}

// ---------- Tick box ----------

/** The box in front of every task. Fills with colour and shows a tick when checked. */
class BoxView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var color: Int = ctx.getColor(R.color.accent)
        set(v) {
            field = v
            invalidate()
        }
    var boxSize = 22f   // in dp; the view itself is larger so it is easy to tap
    private var checked = false
    private var p = 0f
    private var anim: ValueAnimator? = null
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val drawn = Path()
    private val measure = PathMeasure()
    private val pop = OvershootInterpolator(3f)
    private val rect = RectF()
    private val outlineColor = ctx.getColor(R.color.text_dim)
    private val tickColor = ctx.getColor(R.color.bg)

    fun setChecked(value: Boolean, animate: Boolean) {
        checked = value
        anim?.cancel()
        anim = null
        val target = if (value) 1f else 0f
        if (!animate || p == target) {
            p = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(p, target).apply {
            duration = if (value) 260 else 160
            addUpdateListener {
                p = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val s = boxSize * d
        val l = (width - s) / 2f
        val t = (height - s) / 2f
        val r = s * 0.3f
        rect.set(l, t, l + s, t + s)
        if (p < 1f) {
            outline.strokeWidth = 1.6f * d
            outline.color = outlineColor
            c.drawRoundRect(rect, r, r, outline)
        }
        if (p > 0f) {
            // The box pops in slightly past its size, then the tick is drawn stroke by stroke.
            val k = if (checked) 0.6f + 0.4f * pop.getInterpolation(p) else 0.8f + 0.2f * p
            c.save()
            c.scale(k, k, rect.centerX(), rect.centerY())
            fill.color = color
            fill.alpha = (255 * min(1f, p * 2f)).toInt()
            c.drawRoundRect(rect, r, r, fill)
            val t2 = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (t2 > 0f) {
                tick.color = tickColor
                tick.strokeWidth = s * 0.11f
                path.rewind()
                path.moveTo(l + s * 0.27f, t + s * 0.52f)
                path.lineTo(l + s * 0.43f, t + s * 0.68f)
                path.lineTo(l + s * 0.74f, t + s * 0.34f)
                measure.setPath(path, false)
                drawn.rewind()
                measure.getSegment(0f, measure.length * t2, drawn, true)
                c.drawPath(drawn, tick)
            }
            c.restore()
        }
    }
}

// ---------- Task title with a line that draws itself across ----------

class TitleView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : TextView(ctx, attrs) {
    var normalColor: Int = ctx.getColor(R.color.text_primary)
    var struckColor: Int = ctx.getColor(R.color.text_dim)
    private var strike = 0f
    private var anim: ValueAnimator? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun setStruck(on: Boolean, animate: Boolean) {
        val target = if (on) 1f else 0f
        anim?.cancel()
        anim = null
        if (!animate || strike == target) {
            show(target)
            return
        }
        anim = ValueAnimator.ofFloat(strike, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { show(it.animatedValue as Float) }
            start()
        }
    }

    private fun show(value: Float) {
        strike = value
        setTextColor(blend(normalColor, struckColor, value))
        invalidate()
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return ((x + (y - x) * f).toInt() and 0xFF) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = layout ?: return
        if (strike <= 0f) return
        line.color = currentTextColor
        line.strokeWidth = max(textSize * 0.07f, resources.displayMetrics.density * 1.4f)
        var total = 0f
        for (i in 0 until l.lineCount) total += l.getLineWidth(i)
        var left = total * strike
        for (i in 0 until l.lineCount) {
            if (left <= 0f) break
            val w = l.getLineWidth(i)
            val x = compoundPaddingLeft + l.getLineLeft(i)
            val y = extendedPaddingTop + l.getLineBaseline(i) - textSize * 0.32f
            val seg = min(w, left)
            c.drawLine(x, y, x + seg, y, line)
            left -= seg
        }
    }
}

// ---------- The day's climb ----------

/** A mountain ridge that lights up from the left as the day's tasks get done. */
class SummitView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    // x, y pairs from 0 to 1 (y grows downwards). The peak is the second-to-last point.
    private val pts = floatArrayOf(
        0f, 0.94f, 0.10f, 0.80f, 0.18f, 0.86f, 0.32f, 0.60f, 0.41f, 0.68f,
        0.56f, 0.38f, 0.65f, 0.47f, 0.84f, 0.12f, 1f, 0.50f
    )
    private val peakX = 0.84f
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private var burst = 1f                       // 0..1 while the summit celebration plays
    private var burstAnim: ValueAnimator? = null
    private val overshoot = OvershootInterpolator(2.5f)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accent = ctx.getColor(R.color.accent)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = ctx.getColor(R.color.line)
    }
    private val lit = Paint(base).apply { color = accent }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x33) }
    private val ridge = Path()
    private val area = Path()
    private val flag = Path()

    fun setProgress(value: Float, animate: Boolean) {
        val target = value.coerceIn(0f, 1f)
        anim?.cancel()
        anim = null
        burstAnim?.cancel()
        burstAnim = null
        burst = 1f
        if (!animate || target == shown) {
            shown = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (shown >= 1f) celebrate()
                }
            })
            start()
        }
    }

    /** The flag springs up and a ring spreads out from the peak. */
    private fun celebrate() {
        burst = 0f
        burstAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750
            addUpdateListener {
                burst = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun px(xn: Float): Float = dp(8) + (width - dp(16)) * xn

    private fun py(yn: Float): Float = dp(16) + (height - dp(22)) * yn

    private fun yAt(xn: Float): Float {
        var i = 0
        while (i < pts.size - 2) {
            val x0 = pts[i]
            val x1 = pts[i + 2]
            if (xn <= x1) {
                val f = if (x1 == x0) 0f else (xn - x0) / (x1 - x0)
                return pts[i + 1] + (pts[i + 3] - pts[i + 1]) * f
            }
            i += 2
        }
        return pts[pts.size - 1]
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ridge.rewind()
        area.rewind()
        var i = 0
        while (i < pts.size) {
            val x = px(pts[i])
            val y = py(pts[i + 1])
            if (i == 0) {
                ridge.moveTo(x, y)
                area.moveTo(x, h.toFloat())
                area.lineTo(x, y)
            } else {
                ridge.lineTo(x, y)
                area.lineTo(x, y)
            }
            i += 2
        }
        area.lineTo(px(1f), h.toFloat())
        area.close()
        glow.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), withAlpha(accent, 0x40), withAlpha(accent, 0), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        base.strokeWidth = 2f * d
        lit.strokeWidth = 2.5f * d
        c.drawPath(ridge, base)
        val xn = peakX * shown
        val x = px(xn)
        val y = py(yAt(xn))
        if (shown > 0f) {
            c.save()
            c.clipRect(0f, 0f, x, height.toFloat())
            c.drawPath(area, glow)
            c.drawPath(ridge, lit)
            c.restore()
        }
        if (shown >= 1f) {
            if (burst < 1f) {
                ring.color = withAlpha(accent, (200 * (1f - burst)).toInt())
                ring.strokeWidth = 2f * d
                c.drawCircle(x, y, (6f + 30f * burst) * d, ring)
            }
            // Flag on the summit.
            val k = if (burst < 1f) overshoot.getInterpolation(min(1f, burst * 2f)) else 1f
            val top = y - 13f * d * k
            c.drawLine(x, y, x, top, lit)
            flag.rewind()
            flag.moveTo(x, top)
            flag.lineTo(x + 9f * d * k, top + 3.5f * d * k)
            flag.lineTo(x, top + 7f * d * k)
            flag.close()
            c.drawPath(flag, dot)
        } else {
            c.drawCircle(x, y, 9f * d, halo)
            c.drawCircle(x, y, 4.5f * d, dot)
        }
    }
}

// ---------- Wrapping row of chips ----------

class FlowLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ViewGroup(ctx, attrs) {
    private val gap = dp(8)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            measureChild(ch, widthSpec, heightSpec)
            if (x > 0 && x + ch.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += ch.measuredWidth + gap
            rowH = max(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            ch.layout(
                paddingLeft + x, paddingTop + y,
                paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight
            )
            x += ch.measuredWidth + gap
            rowH = max(rowH, ch.measuredHeight)
        }
    }
}

// ---------- Calendar grid (a whole month, or one week) ----------

class MonthGridView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var weekMode = false
    var month: YearMonth = YearMonth.now()
    var selected: LocalDate = LocalDate.now()
    var today: LocalDate = LocalDate.now()
    var onSelect: ((LocalDate) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private val headerH = dp(26)
    private val cellH = dp(56)
    private val letters = arrayOf("M", "T", "W", "T", "F", "S", "S")
    private val accent = ctx.getColor(R.color.accent)
    private val cPrimary = ctx.getColor(R.color.text_primary)
    private val cDim = ctx.getColor(R.color.text_dim)
    private val cLine = ctx.getColor(R.color.line)
    private val cOnAccent = ctx.getColor(R.color.on_accent)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var downX = 0f
    private var downY = 0f
    private var pop = 1f

    /** Lets the circle of the selected day grow in. */
    fun popSelection() {
        ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 240
            interpolator = OvershootInterpolator(2.5f)
            addUpdateListener {
                pop = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** The Monday the grid starts on. */
    private fun first(): LocalDate {
        val d = if (weekMode) selected else month.atDay(1)
        return d.minusDays((d.dayOfWeek.value - 1).toLong())
    }

    private fun rows(): Int {
        if (weekMode) return 1
        val offset = month.atDay(1).dayOfWeek.value - 1
        return (offset + month.lengthOfMonth() + 6) / 7
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), headerH + rows() * cellH)
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cw = width / 7f
        text.typeface = Typeface.DEFAULT
        text.textSize = 12f * d
        text.color = cDim
        for (i in 0 until 7) c.drawText(letters[i], cw * (i + 0.5f), 15f * d, text)

        val start = first()
        for (r in 0 until rows()) {
            for (col in 0 until 7) {
                val date = start.plusDays((r * 7 + col).toLong())
                val cx = cw * (col + 0.5f)
                val cy = headerH + r * cellH + 21f * d
                val inMonth = weekMode || YearMonth.from(date) == month
                val isSel = date == selected
                val isToday = date == today
                if (isSel) {
                    shape.style = Paint.Style.FILL
                    shape.color = accent
                    c.drawCircle(cx, cy, 16f * d * pop, shape)
                } else if (isToday) {
                    shape.style = Paint.Style.STROKE
                    shape.strokeWidth = 1.5f * d
                    shape.color = accent
                    c.drawCircle(cx, cy, 16f * d, shape)
                }
                text.textSize = 15f * d
                text.typeface = if (isSel || isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                text.color = when {
                    isSel -> cOnAccent
                    isToday -> accent
                    inMonth -> cPrimary
                    else -> cDim
                }
                c.drawText(date.dayOfMonth.toString(), cx, cy + 5.5f * d, text)

                // Completion bar: how much of that day's tasks are ticked.
                val (done, total) = Store.progress(date)
                if (total > 0) {
                    val w = 20f * d
                    val top = cy + 23f * d
                    shape.style = Paint.Style.FILL
                    shape.color = cLine
                    bar.set(cx - w / 2, top, cx + w / 2, top + 3f * d)
                    c.drawRoundRect(bar, 2f * d, 2f * d, shape)
                    if (done > 0) {
                        shape.color = if (inMonth) accent else withAlpha(accent, 0x80)
                        bar.set(cx - w / 2, top, cx - w / 2 + w * done / total, top + 3f * d)
                        c.drawRoundRect(bar, 2f * d, 2f * d, shape)
                    }
                }
            }
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
                if (abs(dx) > dp(56) && abs(dx) > abs(e.y - downY) * 1.5f) {
                    onSwipe?.invoke(if (dx < 0) 1 else -1)
                    return true
                }
                if (e.y > headerH && width > 0) {
                    val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
                    val row = ((e.y - headerH) / cellH).toInt().coerceIn(0, rows() - 1)
                    val date = first().plusDays((row * 7 + col).toLong())
                    performClick()
                    onSelect?.invoke(date)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
