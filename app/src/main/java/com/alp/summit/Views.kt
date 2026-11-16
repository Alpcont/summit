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
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
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
    val progress: Float get() = shown
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

// ---------- The moment the whole day is done ----------

/** A short full-screen celebration: a mountain is drawn, the flag goes up, sparks fly. Tap to skip. */
class CelebrationView(ctx: Context, private val count: Int) : View(ctx) {
    private class Spark(val dx: Float, val dy: Float, val color: Int, val size: Float)

    private var t = 0f
    private var anim: ValueAnimator? = null
    private val accent = ctx.getColor(R.color.accent)
    private val bg = ctx.getColor(R.color.bg)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ridge = Path()
    private val part = Path()
    private val flag = Path()
    private val measure = PathMeasure()
    private val overshoot = OvershootInterpolator(2.5f)
    private val sparks = ArrayList<Spark>()

    init {
        val colors = intArrayOf(accent, -1, 0xFFF5C451.toInt(), 0xFF5B9DFF.toInt(), 0xFF3DDC97.toInt(), 0xFFF283B6.toInt())
        val rnd = java.util.Random(11)
        for (i in 0 until 46) {
            val angle = Math.PI * (1.05 + 0.9 * rnd.nextDouble())      // upwards, fanned out
            val speed = 0.45f + rnd.nextFloat() * 0.75f
            sparks.add(
                Spark(
                    (Math.cos(angle) * speed).toFloat(), (Math.sin(angle) * speed).toFloat(),
                    colors[i % colors.size], 2f + rnd.nextFloat() * 3f
                )
            )
        }
        setOnClickListener { anim?.end() }
    }

    fun play(end: () -> Unit) {
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800
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
        val d = resources.displayMetrics.density
        val a = min(1f, t / 0.07f) * min(1f, (1f - t) / 0.14f)      // fade in, hold, fade out
        c.drawColor(withAlpha(bg, (238 * a).toInt()))
        val size = min(width * 0.62f, 300f * d)
        val cx = width / 2f
        val base = height * 0.47f
        val peakX = cx + size * 0.08f
        val peakY = base - size * 0.52f

        // The mountain draws itself.
        ridge.rewind()
        ridge.moveTo(cx - size / 2, base)
        ridge.lineTo(cx - size * 0.22f, base - size * 0.24f)
        ridge.lineTo(cx - size * 0.12f, base - size * 0.16f)
        ridge.lineTo(peakX, peakY)
        ridge.lineTo(cx + size * 0.26f, base - size * 0.22f)
        ridge.lineTo(cx + size * 0.34f, base - size * 0.30f)
        ridge.lineTo(cx + size / 2, base)
        measure.setPath(ridge, false)
        part.rewind()
        measure.getSegment(0f, measure.length * min(1f, t / 0.26f), part, true)
        line.strokeWidth = 3f * d
        line.alpha = (255 * a).toInt()
        c.drawPath(part, line)

        // The flag goes up.
        val f = ((t - 0.24f) / 0.14f).coerceIn(0f, 1f)
        if (f > 0f) {
            val k = overshoot.getInterpolation(f)
            val top = peakY - 30f * d * k
            c.drawLine(peakX, peakY, peakX, top, line)
            flag.rewind()
            flag.moveTo(peakX, top)
            flag.lineTo(peakX + 20f * d * k, top + 8f * d * k)
            flag.lineTo(peakX, top + 16f * d * k)
            flag.close()
            fill.color = accent
            fill.alpha = (255 * a).toInt()
            c.drawPath(flag, fill)
        }

        // Sparks from the peak.
        val life = ((t - 0.30f) / 0.50f).coerceIn(0f, 1f)
        if (life > 0f && life < 1f) {
            val reach = size * 0.9f
            val eased = 1f - (1f - life) * (1f - life)
            for (s in sparks) {
                val x = peakX + s.dx * reach * eased
                val y = peakY - 30f * d + s.dy * reach * eased + reach * 0.55f * life * life
                fill.color = s.color
                fill.alpha = (255 * (1f - life) * a).toInt()
                c.drawCircle(x, y, s.size * d, fill)
            }
        }

        val ta = ((t - 0.30f) / 0.12f).coerceIn(0f, 1f) * a
        text.color = withAlpha(context.getColor(R.color.text_primary), (255 * ta).toInt())
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = 30f * d
        c.drawText("Summit reached", cx, base + 64f * d, text)
        text.color = withAlpha(context.getColor(R.color.text_secondary), (255 * ta).toInt())
        text.typeface = Typeface.DEFAULT
        text.textSize = 15f * d
        val line2 = if (count == 1) "Today's task is done. Well climbed, Alp." else "All $count tasks done. Well climbed, Alp."
        c.drawText(line2, cx, base + 92f * d, text)
    }
}

// ---------- Week chart and timer ring ----------

/** Seven bars, Monday to Sunday: how much of each day's tasks got done. */
class BarsView(ctx: Context) : View(ctx) {
    var rates = FloatArray(7) { -1f }   // 0..1 per day; below 0 = no tasks that day
    var todayIndex = -1
    private val letters = arrayOf("M", "T", "W", "T", "F", "S", "S")
    private val accent = ctx.getColor(R.color.accent)
    private val track = ctx.getColor(R.color.surface2)
    private val dim = ctx.getColor(R.color.text_dim)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bar = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(150))
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cw = width / 7f
        val top = 8f * d
        val bottom = height - 28f * d
        val w = 20f * d
        for (i in 0 until 7) {
            val cx = cw * (i + 0.5f)
            shape.color = track
            bar.set(cx - w / 2, top, cx + w / 2, bottom)
            c.drawRoundRect(bar, w / 2, w / 2, shape)
            val rate = rates[i]
            if (rate > 0f) {
                shape.color = if (i == todayIndex || rate >= 1f) accent else withAlpha(accent, 0x8C)
                bar.set(cx - w / 2, bottom - max((bottom - top) * rate, w), cx + w / 2, bottom)
                c.drawRoundRect(bar, w / 2, w / 2, shape)
            }
            text.textSize = 12f * d
            text.color = if (i == todayIndex) accent else dim
            text.typeface = if (i == todayIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(letters[i], cx, height - 8f * d, text)
        }
    }
}

/** The ring of the focus timer, with the time in its middle. */
class RingView(ctx: Context) : View(ctx) {
    var fraction = 1f
    var label = ""
    var sub = ""
    private val accent = ctx.getColor(R.color.accent)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val stroke = 10f * d
        val size = min(width, height) - stroke
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        line.strokeWidth = stroke
        line.color = context.getColor(R.color.surface2)
        c.drawOval(oval, line)
        if (fraction > 0f) {
            line.color = accent
            c.drawArc(oval, -90f, 360f * fraction.coerceIn(0f, 1f), false, line)
        }
        text.color = context.getColor(R.color.text_primary)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = 52f * d
        c.drawText(label, cx, cy + 14f * d, text)
        text.color = context.getColor(R.color.text_secondary)
        text.typeface = Typeface.DEFAULT
        text.textSize = 14f * d
        c.drawText(sub, cx, cy + 44f * d, text)
    }
}

// ---------- Day timeline: tasks and calendar events as blocks by the hour ----------

/** A thin progress bar. */
class BarView(ctx: Context) : View(ctx) {
    var fraction = 0f
    var color: Int = ctx.getColor(R.color.accent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val track = ctx.getColor(R.color.surface2)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(6))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        paint.color = track
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, h / 2, h / 2, paint)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            paint.color = color
            r.set(0f, 0f, max(width * f, h), h)
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

/**
 * One day from 00:00 to 24:00. Tasks with a time are blocks as tall as their duration;
 * events from the phone calendar are shown too but cannot be changed.
 * Tap a block to edit, its box to tick, its round button to start or pause the timer.
 * Hold a block and drag to move it; hold its lower edge and drag to make it longer or shorter.
 */
class DayTimelineView(ctx: Context) : View(ctx) {
    private class Block(val task: Task?, val event: PhoneEvent?, val start: Int, val end: Int) {
        var col = 0
        var cols = 1
        val rect = RectF()
    }

    private companion object {
        const val MOVE = 1
        const val RESIZE = 2
        const val DAY = 24 * 60
    }

    var onOpen: ((Task) -> Unit)? = null
    var onTick: ((Task) -> Unit)? = null
    var onPlay: ((Task) -> Unit)? = null
    var onEmpty: ((Int) -> Unit)? = null                 // minutes of the tapped free slot
    var onChange: ((Task, Int, Int) -> Unit)? = null     // new start and duration after a drag
    var onDropTask: ((String, Int) -> Unit)? = null      // an untimed task was dropped at these minutes
    var onAutoScroll: ((Float) -> Int)? = null           // finger's screen y -> pixels the page scrolled

    var runningId = ""        // the task the timer is on
    var runningExtra = 0      // its minutes that are not logged yet
    var nowMinutes = -1       // where the "now" line goes; below 0 = no line

    private var day: LocalDate = LocalDate.now()
    private var isToday = false
    private var first = 0     // the timeline starts here (minutes); empty early hours are left out
    private val blocks = ArrayList<Block>()
    private val hourH = dp(60).toFloat()
    private val gutter = dp(50).toFloat()
    private val padTop = dp(10).toFloat()
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val accent = ctx.getColor(R.color.accent)
    private val cPrimary = ctx.getColor(R.color.text_primary)
    private val cSecondary = ctx.getColor(R.color.text_secondary)
    private val cDim = ctx.getColor(R.color.text_dim)
    private val cLine = ctx.getColor(R.color.surface2)
    private val cBg = ctx.getColor(R.color.bg)
    private val cOnAccent = ctx.getColor(R.color.on_accent)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    // A block being dragged.
    private var drag: Block? = null
    private var dragMode = 0
    private var dragStart = 0
    private var dragLength = 0
    private var pressed: Block? = null
    private var downX = 0f
    private var downY = 0f
    private var downRawY = 0f
    private var lastRawY = 0f
    private var scrolled = 0f
    private var moved = false
    private var dropAt = -1   // where a task dragged in from outside would land

    private val hold = Runnable { startDrag() }
    private val scroller = object : Runnable {
        override fun run() {
            if (drag == null) return
            val dy = onAutoScroll?.invoke(lastRawY) ?: 0
            if (dy != 0) {
                scrolled += dy
                updateDrag()
            }
            postDelayed(this, 16)
        }
    }

    fun setData(day: LocalDate, isToday: Boolean, tasks: List<Task>, events: List<PhoneEvent>, nowMin: Int) {
        this.day = day
        this.isToday = isToday
        blocks.clear()
        for (e in events) if (!e.allDay) blocks.add(Block(null, e, e.start, max(e.end, e.start + 15).coerceAtMost(DAY)))
        for (t in tasks) if (t.time >= 0) {
            blocks.add(Block(t, null, t.time, (t.time + (if (t.duration > 0) t.duration else 30)).coerceAtMost(DAY)))
        }
        // Blocks that overlap share the width, side by side.
        blocks.sortWith(compareBy({ it.start }, { -it.end }))
        val cluster = ArrayList<Block>()
        var clusterEnd = -1
        fun close() {
            val cols = (cluster.maxOfOrNull { it.col } ?: 0) + 1
            for (b in cluster) b.cols = cols
            cluster.clear()
        }
        for (b in blocks) {
            if (cluster.isNotEmpty() && b.start >= clusterEnd) close()
            var col = 0
            while (cluster.any { it.col == col && it.end > b.start }) col++
            b.col = col
            cluster.add(b)
            clusterEnd = max(clusterEnd, b.end)
        }
        close()
        // Start at 06:00, or earlier if something is planned (or it is) before that.
        var start = 6 * 60
        for (b in blocks) start = min(start, b.start)
        if (isToday) start = min(start, nowMin)
        first = start / 60 * 60
        place()
        requestLayout()
        invalidate()
    }

    private fun y(minutes: Int): Float = padTop + (minutes - first) / 60f * hourH

    private fun minutesAt(y: Float): Int = (first + (y - padTop) / hourH * 60f).toInt().coerceIn(first, DAY)

    private fun snap(minutes: Int, step: Int): Int = Math.round(minutes / step.toFloat()) * step

    private fun place() {
        val room = width - gutter - 4f * d
        if (room <= 0f) return
        for (b in blocks) {
            val w = room / b.cols
            val left = gutter + b.col * w
            val top = y(b.start)
            b.rect.set(left, top + d, left + w - 3f * d, max(y(b.end) - 1.5f * d, top + 24f * d))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (padTop + (DAY - first) / 60f * hourH + dp(14)).toInt())
    }

    // ----- Drawing -----

    override fun onDraw(c: Canvas) {
        text.typeface = Typeface.DEFAULT
        text.isStrikeThruText = false
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 11f * d
        text.color = cDim
        shape.style = Paint.Style.FILL
        for (h in first / 60..24) {
            val yy = y(h * 60)
            shape.color = cLine
            c.drawRect(gutter, yy, width.toFloat(), yy + d, shape)
            if (h < 24) c.drawText(String.format(java.util.Locale.ENGLISH, "%02d:00", h), gutter - 8f * d, yy + 4f * d, text)
        }
        text.textAlign = Paint.Align.LEFT
        for (b in blocks) if (b !== drag) drawBlock(c, b, b.rect, false)

        if (nowMinutes >= 0) {
            val yy = y(nowMinutes)
            shape.color = accent
            c.drawRect(gutter - 4f * d, yy - d, width.toFloat(), yy + d, shape)
            c.drawCircle(gutter - 4f * d, yy, 4.5f * d, shape)
        }
        if (dropAt >= 0) label(c, y(dropAt), fmtTime(dropAt))

        val g = drag
        if (g != null) {
            val top = y(dragStart)
            box.set(g.rect.left, top + d, g.rect.right, max(y(dragStart + dragLength) - 1.5f * d, top + 24f * d))
            drawBlock(c, g, box, true)
            label(c, if (dragMode == RESIZE) box.bottom else box.top, fmtTime(dragStart) + " – " + fmtTime((dragStart + dragLength) % DAY))
        }
    }

    /** A line across the day with a time on it (while dragging or dropping). */
    private fun label(c: Canvas, yy: Float, words: String) {
        shape.style = Paint.Style.FILL
        shape.color = accent
        c.drawRect(gutter, yy - d, width.toFloat(), yy + d, shape)
        text.textSize = 12f * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.isStrikeThruText = false
        val w = text.measureText(words) + 14f * d
        val top = max(yy - 22f * d, 0f)
        box.set(width - w - 6f * d, top, width - 6f * d, top + 20f * d)
        // `box` is reused here on purpose only when no dragged block is being drawn with it.
        c.drawRoundRect(box, 10f * d, 10f * d, shape)
        text.color = cOnAccent
        c.drawText(words, box.left + 7f * d, box.top + 14.5f * d, text)
        text.typeface = Typeface.DEFAULT
    }

    private fun drawBlock(c: Canvas, b: Block, r: RectF, lifted: Boolean) {
        val t = b.task
        val e = b.event
        val done = t != null && t.isDone(day)
        val color = when {
            t != null -> Store.category(t.categoryId)?.color ?: accent
            e != null -> e.color
            else -> accent
        }
        val radius = 9f * d
        shape.style = Paint.Style.FILL
        shape.color = cBg
        c.drawRoundRect(r, radius, radius, shape)
        shape.color = withAlpha(color, if (done) 0x1C else if (lifted) 0x66 else if (e != null) 0x2E else 0x3D)
        c.drawRoundRect(r, radius, radius, shape)

        // How far the work on this task has come, as a darker part from the left.
        if (t != null && !done && t.duration > 0) {
            val worked = t.worked(day) + (if (t.id == runningId) runningExtra else 0)
            val f = (worked.toFloat() / t.duration).coerceIn(0f, 1f)
            if (f > 0f) {
                c.save()
                c.clipRect(r.left, r.top, r.left + r.width() * f, r.bottom)
                shape.color = withAlpha(color, 0x55)
                c.drawRoundRect(r, radius, radius, shape)
                c.restore()
            }
        }
        // Coloured edge on the left.
        c.save()
        c.clipRect(r.left, r.top, r.left + 4f * d, r.bottom)
        shape.color = withAlpha(color, if (done) 0x80 else 0xFF)
        c.drawRoundRect(r, radius, radius, shape)
        c.restore()
        if (lifted || (t != null && t.id == runningId)) {
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1.6f * d
            shape.color = accent
            c.drawRoundRect(r, radius, radius, shape)
            shape.style = Paint.Style.FILL
        }

        val cy = r.top + min(r.height() / 2f, 20f * d)
        var left = r.left + 12f * d
        var right = r.right - 8f * d
        if (t != null) {
            // Tick box.
            val s = 16f * d
            val bx = r.left + 11f * d
            if (done) {
                shape.color = color
                c.drawRoundRect(bx, cy - s / 2, bx + s, cy + s / 2, 5f * d, 5f * d, shape)
                shape.style = Paint.Style.STROKE
                shape.strokeWidth = 2f * d
                shape.strokeCap = Paint.Cap.ROUND
                shape.color = cBg
                path.rewind()
                path.moveTo(bx + s * 0.27f, cy + s * 0.02f)
                path.lineTo(bx + s * 0.43f, cy + s * 0.18f)
                path.lineTo(bx + s * 0.74f, cy - s * 0.16f)
                c.drawPath(path, shape)
            } else {
                shape.style = Paint.Style.STROKE
                shape.strokeWidth = 1.5f * d
                shape.color = cSecondary
                c.drawRoundRect(bx, cy - s / 2, bx + s, cy + s / 2, 5f * d, 5f * d, shape)
            }
            shape.style = Paint.Style.FILL
            left = bx + s + 9f * d

            // Start / pause button, for open tasks of today.
            if (isToday && !done && r.width() > 120f * d) {
                val px = r.right - 20f * d
                val running = t.id == runningId
                shape.color = if (running) accent else withAlpha(accent, 0x33)
                c.drawCircle(px, cy, 13f * d, shape)
                shape.color = if (running) cOnAccent else accent
                if (running) {
                    c.drawRect(px - 4.5f * d, cy - 5f * d, px - 1.5f * d, cy + 5f * d, shape)
                    c.drawRect(px + 1.5f * d, cy - 5f * d, px + 4.5f * d, cy + 5f * d, shape)
                } else {
                    path.rewind()
                    path.moveTo(px - 3.5f * d, cy - 5.5f * d)
                    path.lineTo(px + 5.5f * d, cy)
                    path.lineTo(px - 3.5f * d, cy + 5.5f * d)
                    path.close()
                    c.drawPath(path, shape)
                }
                right = px - 18f * d
            }
        }

        val title = t?.title ?: e?.title ?: ""
        text.textSize = 14f * d
        text.color = if (done) cDim else if (e != null) cSecondary else cPrimary
        text.isStrikeThruText = done
        val room = right - left
        if (room > 12f * d) {
            c.drawText(TextUtils.ellipsize(title, text, room, TextUtils.TruncateAt.END).toString(), left, cy + 5f * d, text)
        }
        text.isStrikeThruText = false
        if (r.height() >= 44f * d && room > 12f * d) {
            val second = if (t != null) {
                val parts = ArrayList<String>()
                parts.add(fmtTime(b.start) + (if (t.duration > 0) " – " + fmtTime(b.end % DAY) else ""))
                if (t.duration > 0 && !done) {
                    val worked = t.worked(day) + (if (t.id == runningId) runningExtra else 0)
                    if (worked > 0) parts.add("${worked * 100 / t.duration}%")
                }
                parts.joinToString("  ·  ")
            } else {
                fmtTime(b.start) + " – " + fmtTime(b.end % DAY) + (if (e != null && e.place.isNotBlank()) "  ·  " + e.place else "")
            }
            text.textSize = 12f * d
            text.color = if (done) cDim else cSecondary
            c.drawText(TextUtils.ellipsize(second, text, room, TextUtils.TruncateAt.END).toString(), left, cy + 23f * d, text)
        }
    }

    // ----- Touch -----

    private fun blockAt(x: Float, y: Float): Block? = blocks.lastOrNull { it.rect.contains(x, y) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downRawY = e.rawY
                lastRawY = e.rawY
                scrolled = 0f
                moved = false
                val b = blockAt(e.x, e.y)
                pressed = if (b?.task != null && !b.task.isDone(day)) b else null
                if (pressed != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawY = e.rawY
                if (drag != null) {
                    updateDrag()
                } else if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true            // the page scrolls from here
                    removeCallbacks(hold)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                removeCallbacks(scroller)
                val g = drag
                if (g != null) {
                    drag = null
                    val t = g.task
                    val changed = dragStart != g.start || dragLength != g.end - g.start
                    if (t != null && changed) {
                        // Moving keeps the duration as it was (also "none"); only a resize sets one.
                        onChange?.invoke(t, dragStart, if (dragMode == RESIZE) dragLength else t.duration)
                    }
                    invalidate()
                } else if (!moved) {
                    performClick()
                    click(e.x, e.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                removeCallbacks(scroller)
                drag = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun click(x: Float, y: Float) {
        val b = blockAt(x, y)
        val t = b?.task
        if (b == null) {
            onEmpty?.invoke((minutesAt(y) / 30 * 30).coerceAtMost(DAY - 30))
        } else if (t != null) {
            when {
                x < b.rect.left + 36f * d -> onTick?.invoke(t)
                isToday && !t.isDone(day) && b.rect.width() > 120f * d && x > b.rect.right - 40f * d -> onPlay?.invoke(t)
                else -> onOpen?.invoke(t)
            }
        }
    }

    private fun startDrag() {
        val b = pressed ?: return
        if (moved) return
        drag = b
        dragMode = if (downY > b.rect.bottom - 16f * d && b.rect.height() >= 36f * d) RESIZE else MOVE
        dragStart = b.start
        dragLength = b.end - b.start
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        post(scroller)
        invalidate()
    }

    private fun updateDrag() {
        val b = drag ?: return
        val delta = Math.round((lastRawY - downRawY + scrolled) / hourH * 60f)
        val length = b.end - b.start
        if (dragMode == MOVE) {
            dragStart = snap(b.start + delta, 5).coerceIn(first, DAY - length)
            dragLength = length
        } else {
            dragStart = b.start
            dragLength = snap(length + delta, 5).coerceIn(5, DAY - b.start)
        }
        invalidate()
    }

    // ----- A task without a time is dragged in from the row above -----

    override fun onDragEvent(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return e.localState is String
            DragEvent.ACTION_DRAG_LOCATION -> {
                dropAt = snap(minutesAt(e.y), 15).coerceIn(first, DAY - 15)
                val at = IntArray(2)
                getLocationOnScreen(at)
                onAutoScroll?.invoke(at[1] + e.y)
                invalidate()
            }
            DragEvent.ACTION_DROP -> {
                val id = e.localState as? String
                val minutes = snap(minutesAt(e.y), 15).coerceIn(first, DAY - 15)
                dropAt = -1
                invalidate()
                if (id != null) onDropTask?.invoke(id, minutes)
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                dropAt = -1
                invalidate()
            }
        }
        return true
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
