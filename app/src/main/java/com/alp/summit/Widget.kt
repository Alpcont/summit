package com.alp.summit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/** Home-screen widget: today's tasks, each one tickable with a tap. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            Store.load(ctx)
            val t = Store.task(intent.getStringExtra("task"))
            val d = intent.getStringExtra("date")?.let { LocalDate.parse(it) }
            if (t != null && d != null) {
                Store.setDone(t, d, !t.isDone(d))
                Store.save()            // saving also redraws the widget
                Reminders.schedule(ctx)
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.alp.summit.action.WIDGET_TOGGLE"

        /** Redraws every Summit widget on the home screen. Does nothing if there is none. */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            Store.load(ctx)
            val today = LocalDate.now()
            val tasks = Store.on(today)
            val done = tasks.count { it.isDone(today) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            v.setTextViewText(R.id.w_day, weekdayName(today))
            v.setTextViewText(
                R.id.w_score,
                when {
                    tasks.isEmpty() -> ""
                    done == tasks.size -> "Summit reached"
                    else -> "$done of ${tasks.size}"
                }
            )
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.w_header, PendingIntent.getActivity(ctx, 5, open, flags))
            val add = Intent(ctx, EditTaskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("date", today.toString())
            v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(ctx, 6, add, flags))

            if (Build.VERSION.SDK_INT >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (t in tasks) {
                    val isDone = t.isDone(today)
                    val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                    row.setTextViewText(R.id.w_title, t.title)
                    row.setTextColor(R.id.w_title, ctx.getColor(if (isDone) R.color.text_dim else R.color.text_primary))
                    row.setInt(
                        R.id.w_title, "setPaintFlags",
                        Paint.ANTI_ALIAS_FLAG or (if (isDone) Paint.STRIKE_THRU_TEXT_FLAG else 0)
                    )
                    row.setImageViewResource(R.id.w_box, if (isDone) R.drawable.ic_box_on else R.drawable.ic_box_off)
                    row.setTextViewText(R.id.w_time, if (t.time >= 0) fmtTime(t.time) else "")
                    row.setOnClickFillInIntent(
                        R.id.w_row, Intent().putExtra("task", t.id).putExtra("date", today.toString())
                    )
                    items.addItem(t.id.hashCode().toLong(), row)
                }
                v.setRemoteAdapter(R.id.w_list, items.build())
                v.setEmptyView(R.id.w_list, R.id.w_empty)
                v.setTextViewText(R.id.w_empty, "Nothing planned today.\nTap + to add a task.")
                // One tap on a line ticks it: the line's own data is filled into this intent.
                val toggle = Intent(ctx, TodayWidget::class.java).setAction(ACTION_TOGGLE)
                v.setPendingIntentTemplate(
                    R.id.w_list,
                    PendingIntent.getBroadcast(ctx, 7, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                )
            } else {
                // Older Android versions: a summary that opens the app.
                v.setViewVisibility(R.id.w_list, View.GONE)
                v.setTextViewText(R.id.w_empty, if (tasks.isEmpty()) "Nothing planned today." else "Tap to open today's tasks.")
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
