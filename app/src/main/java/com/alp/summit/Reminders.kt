package com.alp.summit

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reminders without any background service: one alarm is always set for the next thing
 * that needs a notification (a timed task or the morning nudge). When it fires we show
 * what is due and set the alarm for the next one.
 */
object Reminders {
    private const val CH_TASK = "task_reminders"
    private const val CH_DAILY = "daily_plan"
    const val ACTION_FIRE = "com.alp.summit.action.FIRE"
    const val ACTION_DONE = "com.alp.summit.action.DONE"
    private const val STALE = 3 * 60 * 60_000L   // do not show reminders more than 3 hours late

    private class Event(val at: Long, val task: Task?, val date: LocalDate)

    private fun millis(d: LocalDate, minutes: Int): Long =
        d.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

    /** Everything that should notify in the window (from, to]. */
    private fun events(ctx: Context, from: Long, to: Long): List<Event> {
        val zone = ZoneId.systemDefault()
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1)
        val p = prefs(ctx)
        val out = ArrayList<Event>()
        var d = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        while (!d.isAfter(last)) {
            if (p.getBoolean("nudge", true)) {
                val a = millis(d, p.getInt("nudgeTime", 480))
                if (a > from && a <= to) out.add(Event(a, null, d))
            }
            for (t in Store.tasks) {
                if (t.time < 0 || t.remind < 0 || !t.occursOn(d) || t.isDone(d)) continue
                val a = millis(d, t.time) - t.remind * 60_000L
                // a > t.updated: a task edited after its reminder moment does not notify late.
                if (a > from && a <= to && a > t.updated) out.add(Event(a, t, d))
            }
            d = d.plusDays(1)
        }
        return out.sortedBy { it.at }
    }

    /** Set the alarm for the next reminder. Safe to call as often as needed. */
    fun schedule(ctx: Context) {
        Store.load(ctx)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        if (!p.contains("lastFire")) p.edit().putLong("lastFire", now).apply()
        // Always wake shortly after midnight too, so the next day's reminders get planned.
        val midnight = millis(LocalDate.now().plusDays(1), 1)
        val next = events(ctx, now, midnight).firstOrNull()?.at ?: midnight
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** Show what became due since the last time, then plan the next alarm. */
    fun fire(ctx: Context) {
        Store.load(ctx)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val from = maxOf(p.getLong("lastFire", now - 60_000L), now - STALE)
        val due = events(ctx, from, now)
        p.edit().putLong("lastFire", now).apply()
        for (e in due) show(ctx, e)
        schedule(ctx)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun show(ctx: Context, e: Event) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TASK, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, "Morning nudge", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val t = e.task
        if (t == null) {
            val today = LocalDate.now()
            val missed = Store.missed(today).size
            val total = Store.progress(today).second
            open.putExtra("review", missed > 0)
            val text = when {
                missed > 0 -> plural(missed, "unfinished task") + " from earlier. Decide what happens to each."
                total == 0 -> "Nothing planned yet. Add today's tasks."
                else -> plural(total, "task") + " on today's climb."
            }
            val n = Notification.Builder(ctx, CH_DAILY)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(if (missed > 0) "Morning review" else "Good morning, Alp")
                .setContentText(text)
                .setColor(ctx.getColor(R.color.accent))
                .setContentIntent(PendingIntent.getActivity(ctx, 2, open, flags))
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
            return
        }
        val id = (t.id + e.date).hashCode()
        val done = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DONE)
            .putExtra("task", t.id).putExtra("date", e.date.toString()).putExtra("nid", id)
        val parts = ArrayList<String>()
        parts.add(fmtTime(t.time))
        if (t.duration > 0) parts.add(fmtDuration(t.duration))
        Store.category(t.categoryId)?.let { parts.add(it.name) }
        val n = Notification.Builder(ctx, CH_TASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t.title)
            .setContentText(parts.joinToString(" · "))
            .setColor(ctx.getColor(R.color.accent))
            .setWhen(millis(e.date, t.time))
            .setShowWhen(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id, open, flags))
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_stat), "Mark done",
                    PendingIntent.getBroadcast(ctx, id, done, flags)
                ).build()
            )
            .build()
        nm.notify(id, n)
    }
}

/** Receives the app's own alarm and the "Mark done" button of a reminder. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Reminders.ACTION_DONE) {
            Store.load(ctx)
            val t = Store.task(intent.getStringExtra("task"))
            val d = intent.getStringExtra("date")?.let { LocalDate.parse(it) }
            if (t != null) {
                Store.setDone(t, d, true)
                Store.save()
            }
            ctx.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("nid", 0))
        } else {
            Reminders.fire(ctx)
        }
    }
}

/** Alarms are lost on restart, app update and clock changes, so plan them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.fire(ctx)
    }
}
