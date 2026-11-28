package com.alp.summit

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale

/**
 * The one focus engine of the app. Only one session ever runs; the hold menu's focus timer,
 * "Start my day", the play buttons in the Day view, the focus card and the widget all use it.
 *
 * A session is stored as a few times in the device's settings, so it survives the app being
 * closed or the phone restarting: how much focus time is done, when the running stretch started,
 * whether it is on a break. Everything else (time left, next break) is worked out from those.
 * The minutes are logged on the task, its course and the day as they happen.
 */
object Focus {
    const val CH_RUN = "focus_session"
    const val CH_ALERT = "focus_alerts"
    const val NID = 77
    private const val NID_ALERT = 78

    const val ACTION_PAUSE = "com.alp.summit.focus.PAUSE"
    const val ACTION_RESUME = "com.alp.summit.focus.RESUME"
    const val ACTION_STOP = "com.alp.summit.focus.STOP"
    const val ACTION_SKIP_BREAK = "com.alp.summit.focus.SKIP_BREAK"
    const val ACTION_START_NEXT = "com.alp.summit.focus.START_NEXT"
    const val ACTION_REFRESH = "com.alp.summit.focus.REFRESH"

    private fun p(ctx: Context) = prefs(ctx)

    // ---------- State ----------

    fun active(ctx: Context): Boolean = p(ctx).getBoolean("f_active", false)
    fun paused(ctx: Context): Boolean = p(ctx).getBoolean("f_paused", false)
    fun onBreak(ctx: Context): Boolean = active(ctx) && p(ctx).getInt("f_phase", 0) == 1
    fun dayMode(ctx: Context): Boolean = p(ctx).getBoolean("f_day", false)

    /** The task the session is on, or null (also for a session without a task). */
    fun task(ctx: Context): Task? = if (active(ctx)) Store.task(p(ctx).getString("f_task", "")) else null

    fun date(ctx: Context): LocalDate = try {
        LocalDate.parse(p(ctx).getString("f_date", "") ?: "")
    } catch (e: Exception) {
        LocalDate.now()
    }

    /** Countdown length in minutes; 0 = "until I stop". */
    fun target(ctx: Context): Int = p(ctx).getInt("f_target", 0)

    fun focusMs(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val q = p(ctx)
        val seg = q.getLong("f_seg", 0L)
        return q.getLong("f_done", 0L) + (if (seg > 0L) now - seg else 0L)
    }

    private fun sinceBreakMs(ctx: Context, now: Long): Long {
        val q = p(ctx)
        val seg = q.getLong("f_seg", 0L)
        return q.getLong("f_since", 0L) + (if (seg > 0L) now - seg else 0L)
    }

    fun breakEnd(ctx: Context): Long = p(ctx).getLong("f_breakEnd", 0L)

    /** Whole focus minutes not yet written onto the task (for the Day view's live numbers). */
    fun unloggedMinutes(ctx: Context): Int =
        if (!active(ctx)) 0 else maxOf(0, (focusMs(ctx) / 60_000L).toInt() - p(ctx).getInt("f_logged", 0))

    /** Apps are blocked while a session runs, also when paused, but not during a break. */
    fun blockingNow(ctx: Context): Boolean = active(ctx) && !onBreak(ctx)

    fun goal(ctx: Context): Int = p(ctx).getInt("focusGoal", 180)
    fun breakEvery(ctx: Context): Int = if (p(ctx).getBoolean("breaksOn", true)) p(ctx).getInt("breakEvery", 50) else 0
    fun breakLen(ctx: Context): Int = p(ctx).getInt("breakLen", 10)

    /** Focus minutes of a day, including the running session. */
    fun minutesOn(ctx: Context, d: LocalDate): Int =
        (Store.focusLog[d.toString()] ?: 0) + (if (active(ctx) && date(ctx) == d) unloggedMinutes(ctx) else 0)

    /** "24:13" left (countdown), "1:02:40" done (open-ended), or the break's time left. */
    fun clock(ctx: Context): String {
        val now = System.currentTimeMillis()
        val ms = when {
            onBreak(ctx) -> maxOf(0L, breakEnd(ctx) - now)
            target(ctx) > 0 -> maxOf(0L, target(ctx) * 60_000L - focusMs(ctx, now))
            else -> focusMs(ctx, now)
        }
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format(Locale.ENGLISH, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.ENGLISH, "%d:%02d", s / 60, s % 60)
    }

    /** 0..1 of the countdown or of the break (open-ended: of the next 60 minutes). */
    fun fraction(ctx: Context): Float {
        val now = System.currentTimeMillis()
        return when {
            onBreak(ctx) -> 1f - (breakEnd(ctx) - now).toFloat() / maxOf(1, breakLen(ctx) * 60_000)
            target(ctx) > 0 -> focusMs(ctx, now).toFloat() / (target(ctx) * 60_000L)
            else -> (focusMs(ctx, now) % 3_600_000L) / 3_600_000f
        }.coerceIn(0f, 1f)
    }

    // ---------- Starting, pausing, stopping ----------

    /** Starts a session (a running one ends first). `minutes` 0 = until stopped; `dayMode` moves on to the next task. */
    fun start(ctx: Context, t: Task?, d: LocalDate, minutes: Int, dayMode: Boolean) {
        Store.load(ctx)
        if (active(ctx)) stop(ctx, ask = false)
        val now = System.currentTimeMillis()
        p(ctx).edit()
            // The timers of earlier versions are replaced by this one.
            .remove("focusEnd").remove("focusStart").remove("focusOpen").remove("focusOver").remove("dayRun")
            .putBoolean("f_active", true).putString("f_task", t?.id ?: "").putString("f_date", d.toString())
            .putInt("f_target", minutes).putLong("f_done", 0L).putLong("f_seg", now).putLong("f_since", 0L)
            .putInt("f_phase", 0).putLong("f_breakEnd", 0L).putBoolean("f_paused", false).putBoolean("f_day", dayMode)
            .putInt("f_blocked", 0).putInt("f_logged", 0).putLong("f_start", now)
            .putLong("f_partDone", 0L).putLong("f_partStart", now).putString("f_runIds", "")
            .commit()
        dndOn(ctx)
        service(ctx, ACTION_REFRESH)
        changed(ctx)
    }

    fun pause(ctx: Context) {
        if (!active(ctx) || paused(ctx) || onBreak(ctx)) return
        closeSegment(ctx, System.currentTimeMillis())
        p(ctx).edit().putBoolean("f_paused", true).commit()
        changed(ctx)
    }

    fun resume(ctx: Context) {
        if (!active(ctx) || !paused(ctx)) return
        p(ctx).edit().putBoolean("f_paused", false).putLong("f_seg", System.currentTimeMillis()).commit()
        changed(ctx)
    }

    fun skipBreak(ctx: Context) {
        if (!onBreak(ctx)) return
        p(ctx).edit().putInt("f_phase", 0).putLong("f_since", 0L).putLong("f_seg", System.currentTimeMillis())
            .putBoolean("f_paused", false).commit()
        changed(ctx)
    }

    /** Ends the session. With `ask`, Summit asks "How focused were you?" the next time it is open. */
    fun stop(ctx: Context, ask: Boolean = true) {
        if (!active(ctx)) return
        val now = System.currentTimeMillis()
        closeSegment(ctx, now)
        finishPart(ctx, now)
        val q = p(ctx)
        val ids = q.getString("f_runIds", "") ?: ""
        val e = q.edit()
        if (ask && ids.isNotEmpty()) e.putString("f_rate", ids).putInt("f_rateBlocked", q.getInt("f_blocked", 0))
        e.putBoolean("f_active", false).putLong("f_seg", 0L).putBoolean("f_paused", false).putInt("f_phase", 0).commit()
        dndOff(ctx)
        Store.save()
        service(ctx, ACTION_REFRESH)    // the service sees there is nothing left and ends itself
        changed(ctx)
    }

    /** A task was ticked: its minutes are logged; "Start my day" moves on to the next open task. */
    fun taskTicked(ctx: Context, t: Task, d: LocalDate?) {
        if (!active(ctx) || p(ctx).getString("f_task", "") != t.id) return
        val now = System.currentTimeMillis()
        val wasRunning = p(ctx).getLong("f_seg", 0L) > 0L
        closeSegment(ctx, now)
        finishPart(ctx, now)
        val day = d ?: date(ctx)
        val next = if (dayMode(ctx)) Store.queue(day).firstOrNull { it.id != t.id && !it.isDone(day) } else null
        if (next == null) {
            stop(ctx)
            return
        }
        p(ctx).edit().putString("f_task", next.id).putLong("f_partDone", p(ctx).getLong("f_done", 0L))
            .putLong("f_partStart", now).putLong("f_seg", if (wasRunning) now else 0L).commit()
        changed(ctx)
    }

    /** Someone tried to open a blocked app. */
    fun countBlocked(ctx: Context) {
        p(ctx).edit().putInt("f_blocked", p(ctx).getInt("f_blocked", 0) + 1).apply()
    }

    /** Ends the running stretch of focus: its time is added up and logged on the task. */
    private fun closeSegment(ctx: Context, now: Long) {
        val q = p(ctx)
        val seg = q.getLong("f_seg", 0L)
        if (seg <= 0L) return
        val add = maxOf(0L, now - seg)
        q.edit().putLong("f_done", q.getLong("f_done", 0L) + add).putLong("f_since", q.getLong("f_since", 0L) + add)
            .putLong("f_seg", 0L).commit()
        logNew(ctx)
    }

    private fun logNew(ctx: Context) {
        Store.load(ctx)
        val q = p(ctx)
        val total = (q.getLong("f_done", 0L) / 60_000L).toInt()
        val delta = total - q.getInt("f_logged", 0)
        if (delta <= 0) return
        Store.logFocus(Store.task(q.getString("f_task", "")), date(ctx), delta)
        q.edit().putInt("f_logged", total).commit()
        Store.save()
    }

    /** One record per task worked on (a "Start my day" run makes several). */
    private fun finishPart(ctx: Context, now: Long) {
        val q = p(ctx)
        val ms = q.getLong("f_done", 0L) - q.getLong("f_partDone", 0L)
        val minutes = Math.round(ms / 60_000.0).toInt()
        if (minutes < 1) return
        val t = Store.task(q.getString("f_task", ""))
        val s = FocusSession()
        s.start = q.getLong("f_partStart", now)
        s.end = now
        s.minutes = minutes
        s.day = date(ctx)
        s.taskId = t?.id ?: ""
        s.title = t?.title ?: "Focus"
        s.courseId = t?.let { Store.courseOf(it)?.id } ?: ""
        s.blocked = q.getInt("f_blocked", 0)
        Store.sessions.add(s)
        val ids = (q.getString("f_runIds", "") ?: "").split(',').filter { it.isNotEmpty() } + s.id
        q.edit().putString("f_runIds", ids.joinToString(",")).putInt("f_blocked", 0).commit()
        Store.save()
    }

    /**
     * Moves the session along by the clock: starts a break when it is due, ends a break, ends a
     * countdown. Returns when the next change is due (or null).
     */
    fun tick(ctx: Context): Long? {
        if (!active(ctx)) return null
        Store.load(ctx)
        val q = p(ctx)
        val now = System.currentTimeMillis()
        if (q.getInt("f_phase", 0) == 1) {
            val end = breakEnd(ctx)
            if (now < end) return end
            q.edit().putInt("f_phase", 0).putLong("f_since", 0L).putLong("f_seg", now).commit()
            alert(ctx, "Back to focus", task(ctx)?.title ?: "The break is over.")
            changed(ctx)
            return tick(ctx)
        }
        if (paused(ctx) || q.getLong("f_seg", 0L) == 0L) return null
        val targetMs = target(ctx) * 60_000L
        val fm = focusMs(ctx, now)
        if (targetMs > 0 && fm >= targetMs) {
            stop(ctx)
            alert(ctx, "Focus session done", plural(target(ctx), "minute") + " of focus. Well done.")
            return null
        }
        val every = breakEvery(ctx) * 60_000L
        val since = sinceBreakMs(ctx, now)
        // No break right before a countdown ends anyway.
        if (every > 0 && since >= every && (targetMs == 0L || targetMs - fm > 5 * 60_000L)) {
            closeSegment(ctx, now)
            q.edit().putInt("f_phase", 1).putLong("f_breakEnd", now + breakLen(ctx) * 60_000L).putLong("f_since", 0L).commit()
            alert(ctx, "Break: stand up and walk", "${breakLen(ctx)} minutes away from the screen. Focus continues after.")
            changed(ctx)
            return breakEnd(ctx)
        }
        var next = Long.MAX_VALUE
        if (targetMs > 0) next = minOf(next, now + (targetMs - fm))
        if (every > 0) next = minOf(next, now + (every - since))
        return if (next == Long.MAX_VALUE) null else next
    }

    /** Everything that shows the session is brought up to date. */
    private fun changed(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (active(ctx)) nm?.notify(NID, notification(ctx)) else nm?.cancel(NID)
        Reminders.schedule(ctx)
        TodayWidget.refresh(ctx)
        FocusWidget.refresh(ctx)
        listeners.forEach { it() }
    }

    /** Screens that show the session register here to redraw when it changes. */
    val listeners = ArrayList<() -> Unit>()

    private fun service(ctx: Context, action: String) {
        try {
            ctx.startForegroundService(Intent(ctx, FocusService::class.java).setAction(action))
        } catch (e: Exception) {
            // Not allowed from the background right now: the alarm and the notification still work.
        }
    }

    // ---------- Do Not Disturb ----------

    fun dndAllowed(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun dndOn(ctx: Context) {
        if (!p(ctx).getBoolean("focusDnd", true) || !dndAllowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            p(ctx).edit().putInt("f_dndPrev", nm.currentInterruptionFilter).commit()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (e: Exception) {
            p(ctx).edit().remove("f_dndPrev").commit()
        }
    }

    private fun dndOff(ctx: Context) {
        val prev = p(ctx).getInt("f_dndPrev", -1)
        p(ctx).edit().remove("f_dndPrev").commit()
        if (prev <= 0 || !dndAllowed(ctx)) return
        try {
            ctx.getSystemService(NotificationManager::class.java)?.setInterruptionFilter(prev)
        } catch (e: Exception) {
            // Left as it is.
        }
    }

    // ---------- The notification ----------

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Focus session", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Focus breaks and endings", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun action(ctx: Context, code: Int, act: String, label: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_stat), label,
            PendingIntent.getForegroundService(
                ctx, code, Intent(ctx, FocusService::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

    /** Always visible during a session: the time, Pause and Stop. */
    fun notification(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.let { channels(it) }
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            ctx, 9, Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = task(ctx)?.title ?: "Focus"
        val b = Notification.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ctx.getColor(R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(open)
        if (!active(ctx)) return b.setContentTitle("Focus").setContentText("Ending…").build()
        when {
            onBreak(ctx) -> {
                b.setContentTitle("Break: stand up and walk")
                    .setContentText("Focus continues on " + title)
                    .setUsesChronometer(true).setChronometerCountDown(true).setWhen(breakEnd(ctx))
                    .addAction(action(ctx, 31, ACTION_SKIP_BREAK, "Skip break"))
            }
            paused(ctx) -> {
                b.setContentTitle("Paused: $title")
                    .setContentText(fmtDuration(maxOf(1, (focusMs(ctx, now) / 60_000L).toInt())) + " of focus so far. Apps stay blocked.")
                    .setShowWhen(false)
                    .addAction(action(ctx, 32, ACTION_RESUME, "Resume"))
            }
            else -> {
                val t = target(ctx)
                b.setContentTitle(title)
                if (t > 0) {
                    b.setContentText("Focus · $t min").setUsesChronometer(true).setChronometerCountDown(true)
                        .setWhen(now + (t * 60_000L - focusMs(ctx, now)))
                } else {
                    b.setContentText("Focus · until you stop").setUsesChronometer(true).setWhen(now - focusMs(ctx, now))
                }
                b.addAction(action(ctx, 33, ACTION_PAUSE, "Pause"))
            }
        }
        b.addAction(action(ctx, 34, ACTION_STOP, "Stop"))
        return b.build()
    }

    /** A short buzz and a notification when a break starts or ends, or the countdown is done. */
    private fun alert(ctx: Context, title: String, text: String) {
        try {
            ctx.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120), intArrayOf(0, 220, 0, 220), -1)
            )
        } catch (e: Exception) {
            // No vibration.
        }
        if (!Reminders.canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(nm)
        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NID_ALERT, Notification.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
                .setColor(ctx.getColor(R.color.accent)).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(10 * 60_000L).build()
        )
    }

    // ---------- After a session ----------

    /** "How focused were you?" 1–5, with the number of tries to open a blocked app. Asked once. */
    fun askRating(a: Activity) {
        val q = p(a)
        val ids = (q.getString("f_rate", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (ids.isEmpty() || active(a)) return
        q.edit().remove("f_rate").apply()
        Store.load(a)
        val sessions = ids.mapNotNull { Store.sessions.get(it) }
        if (sessions.isEmpty()) return
        val minutes = sessions.sumOf { it.minutes }
        val blocked = q.getInt("f_rateBlocked", 0) + sessions.sumOf { it.blocked }
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(4), a.dp(24), 0)
        val sub = TextView(a)
        sub.text = fmtDuration(minutes) + " of focus" +
            (if (blocked > 0) "  ·  you tried to open a blocked app " + plural(blocked, "time") else "  ·  no blocked apps opened")
        sub.textSize = 14f
        sub.setTextColor(a.getColor(R.color.text_secondary))
        box.addView(sub)
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(0, a.dp(16), 0, a.dp(4))
        box.addView(row)
        val dialog = AlertDialog.Builder(a).setTitle("How focused were you?").setView(box)
            .setNegativeButton("Skip", null).create()
        for (n in 1..5) {
            val c = a.chip(n.toString(), false)
            c.textSize = 18f
            c.setOnClickListener {
                for (s in sessions) {
                    s.rating = n
                    s.blocked = if (s === sessions.last()) blocked else 0
                    s.touch()
                }
                Store.save()
                dialog.dismiss()
            }
            val lp = LinearLayout.LayoutParams(0, a.dp(48), 1f)
            lp.marginStart = a.dp(3)
            lp.marginEnd = a.dp(3)
            row.addView(c, lp)
        }
        val words = TextView(a)
        words.text = "1 = all over the place   ·   5 = deep focus"
        words.textSize = 12f
        words.gravity = Gravity.CENTER
        words.setTextColor(a.getColor(R.color.text_dim))
        box.addView(words)
        dialog.show()
    }

    /** Before the first session: why Summit wants Do Not Disturb access, then the system page. Asked once. */
    fun askDndOnce(a: Activity, then: () -> Unit) {
        val q = p(a)
        if (dndAllowed(a) || q.getBoolean("askedDnd", false) || !q.getBoolean("focusDnd", true)) return then()
        q.edit().putBoolean("askedDnd", true).apply()
        AlertDialog.Builder(a)
            .setTitle("Silence during focus")
            .setMessage(
                "Summit can turn on Do Not Disturb when a focus session starts and set it back to how it was " +
                    "when the session ends.\n\nOn the next screen, find Summit and switch it on. Summit only uses " +
                    "this during your focus sessions."
            )
            .setPositiveButton("Open settings") { _, _ ->
                a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton("Not now") { _, _ -> then() }
            .setOnCancelListener { then() }
            .show()
    }
}

/**
 * Keeps the session going with the screen off: a foreground service with the session's
 * notification. It wakes itself for the next break or the end of a countdown.
 */
class FocusService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val step = Runnable { advance() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.load(this)
        // Started as a foreground service, so it must show its notification right away.
        val n = Focus.notification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Focus.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Focus.NID, n)
        } catch (e: Exception) {
            // Could not become a foreground service; the alarm still moves the session on.
        }
        when (intent?.action) {
            Focus.ACTION_PAUSE -> Focus.pause(this)
            Focus.ACTION_RESUME -> Focus.resume(this)
            Focus.ACTION_STOP -> Focus.stop(this)
            Focus.ACTION_SKIP_BREAK -> Focus.skipBreak(this)
            Focus.ACTION_START_NEXT -> if (!Focus.active(this)) {
                // From the focus widget: the next open task of today, the usual length.
                val today = LocalDate.now()
                Focus.start(this, Store.queue(today).firstOrNull(), today, prefs(this).getInt("focusLen", 50), false)
            }
        }
        advance()
        return if (Focus.active(this)) START_STICKY else START_NOT_STICKY
    }

    private fun advance() {
        handler.removeCallbacks(step)
        val next = Focus.tick(this)
        if (!Focus.active(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(Focus.NID, Focus.notification(this))
        if (next != null) handler.postDelayed(step, maxOf(500L, next - System.currentTimeMillis() + 300L))
    }

    override fun onDestroy() {
        handler.removeCallbacks(step)
        super.onDestroy()
    }
}
