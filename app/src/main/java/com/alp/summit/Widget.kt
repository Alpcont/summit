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

/**
 * Home-screen widgets: today's tasks (TodayWidget), or only the High and Medium ones
 * (SmallWidget, smaller). Each line can be ticked with a tap. Both follow the theme chosen in Summit.
 */
open class TodayWidget : AppWidgetProvider() {

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
                Store.save()            // saving also redraws the widgets
                if (t.isDone(d)) Reminders.taskTicked(ctx, t, d)
                Reminders.schedule(ctx)
            }
            return
        }
        if (intent.action == ACTION_LINK) {
            // A flame on the small widget: link (or unlink) that habit for today.
            Store.load(ctx)
            val h = Store.habits.get(intent.getStringExtra("habit"))
            val today = LocalDate.now()
            if (h != null && h.linkable(today, today)) {
                Store.linkHabit(h, today, !h.ticked(today))
                Store.save()
                Reminders.schedule(ctx)
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.alp.summit.action.WIDGET_TOGGLE"
        const val ACTION_LINK = "com.alp.summit.action.WIDGET_LINK"
        private val HAB_SLOTS = intArrayOf(R.id.w_hab0, R.id.w_hab1, R.id.w_hab2, R.id.w_hab3, R.id.w_hab4, R.id.w_hab5)
        private val HAB_ICONS = intArrayOf(R.id.w_habi0, R.id.w_habi1, R.id.w_habi2, R.id.w_habi3, R.id.w_habi4, R.id.w_habi5)
        private val HAB_NUMS = intArrayOf(R.id.w_habn0, R.id.w_habn1, R.id.w_habn2, R.id.w_habn3, R.id.w_habn4, R.id.w_habn5)

        /** Redraws every Summit widget on the home screen. Does nothing if there is none. */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            draw(ctx, manager, TodayWidget::class.java, false)
            draw(ctx, manager, SmallWidget::class.java, true)
            NotesWidget.refresh(ctx)    // notes change on the same save
        }

        private fun draw(ctx: Context, manager: AppWidgetManager, kind: Class<*>, important: Boolean) {
            val ids = manager.getAppWidgetIds(ComponentName(ctx, kind))
            if (ids == null || ids.isEmpty()) return
            Store.load(ctx)
            val today = LocalDate.now()
            val all = Store.on(today)
            val tasks = if (important) all.filter { it.priority >= 2 } else all
            val done = tasks.count { it.isDone(today) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val code = if (important) 20 else 0      // separate request codes, so the two widgets keep their own intents

            // Widgets are drawn by the home screen, so the colours are set here from the chosen theme.
            val theme = Look.widgetTheme(ctx)
            val light = theme == "light"
            val grey = theme == "grey"
            val cPrimary = if (light) 0xFF1F1A16.toInt() else if (grey) 0xFFECEDEF.toInt() else 0xFFF2F3F5.toInt()
            val cSecondary = if (light) 0xFF6B6159.toInt() else if (grey) 0xFFA3A9B2.toInt() else 0xFF9096A0.toInt()
            val cDim = if (light) 0xFFA0968C.toInt() else if (grey) 0xFF7A808A.toInt() else 0xFF5C616B.toInt()
            val cDanger = if (light) 0xFFD9443F.toInt() else 0xFFFF6B6B.toInt()

            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            v.setInt(
                R.id.w_root, "setBackgroundResource",
                if (light) R.drawable.widget_bg_light else if (grey) R.drawable.widget_bg_grey else R.drawable.widget_bg
            )
            v.setTextViewText(R.id.w_day, if (important) "Important" else weekdayName(today))
            v.setTextColor(R.id.w_day, cPrimary)
            v.setTextColor(R.id.w_score, cSecondary)
            v.setTextColor(R.id.w_empty, cDim)
            v.setTextViewText(
                R.id.w_score,
                when {
                    tasks.isEmpty() -> ""
                    done == tasks.size -> if (important) "All done" else "Summit reached"
                    else -> "$done of ${tasks.size}"
                }
            )
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.w_header, PendingIntent.getActivity(ctx, 5 + code, open, flags))
            val add = Intent(ctx, EditTaskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("date", today.toString())
            v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(ctx, 6 + code, add, flags))

            // Small widget: today's habits as flames under the tasks; a lit flame is linked.
            val habits = if (important) Store.habits.items
                .filter { it.planned(today) && !today.isBefore(it.start) }
                .sortedWith(compareBy({ !it.marked }, { it.created })).take(HAB_SLOTS.size) else emptyList()
            v.setViewVisibility(R.id.w_habits, if (habits.isEmpty()) View.GONE else View.VISIBLE)
            for (i in HAB_SLOTS.indices) {
                val h = habits.getOrNull(i)
                v.setViewVisibility(HAB_SLOTS[i], if (h == null) View.GONE else View.VISIBLE)
                if (h == null) continue
                val linked = h.ticked(today)
                val color = if (light) Look.deeper(h.color) else h.color
                v.setInt(HAB_ICONS[i], "setColorFilter", color)
                v.setInt(HAB_ICONS[i], "setImageAlpha", if (linked) 255 else 80)
                v.setTextViewText(HAB_NUMS[i], h.streak(today).toString())
                v.setTextColor(HAB_NUMS[i], if (linked) cPrimary else cDim)
                v.setContentDescription(HAB_SLOTS[i], h.title + if (linked) ", linked today" else ", tap to link")
                val link = Intent(ctx, kind).setAction(ACTION_LINK).putExtra("habit", h.id)
                v.setOnClickPendingIntent(HAB_SLOTS[i], PendingIntent.getBroadcast(ctx, 40 + i, link, flags))
            }

            if (Build.VERSION.SDK_INT >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (t in tasks) {
                    val isDone = t.isDone(today)
                    val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                    row.setTextViewText(R.id.w_title, t.title)
                    row.setTextColor(R.id.w_title, if (isDone) cDim else cPrimary)
                    row.setInt(
                        R.id.w_title, "setPaintFlags",
                        Paint.ANTI_ALIAS_FLAG or (if (isDone) Paint.STRIKE_THRU_TEXT_FLAG else 0)
                    )
                    row.setImageViewResource(R.id.w_box, if (isDone) R.drawable.ic_box_on else R.drawable.ic_box_off)
                    // The small widget marks High tasks; the big one shows the time.
                    if (important && t.priority == 3 && !isDone) {
                        row.setTextViewText(R.id.w_time, "High")
                        row.setTextColor(R.id.w_time, cDanger)
                    } else {
                        row.setTextViewText(R.id.w_time, if (t.time >= 0) fmtTime(t.time) else "")
                        row.setTextColor(R.id.w_time, cSecondary)
                    }
                    row.setOnClickFillInIntent(
                        R.id.w_row, Intent().putExtra("task", t.id).putExtra("date", today.toString())
                    )
                    items.addItem(t.id.hashCode().toLong(), row)
                }
                v.setRemoteAdapter(R.id.w_list, items.build())
                v.setEmptyView(R.id.w_list, R.id.w_empty)
                v.setTextViewText(
                    R.id.w_empty,
                    if (important) "No High or Medium tasks today." else "Nothing planned today.\nTap + to add a task."
                )
                // One tap on a line ticks it: the line's own data is filled into this intent.
                val toggle = Intent(ctx, kind).setAction(ACTION_TOGGLE)
                v.setPendingIntentTemplate(
                    R.id.w_list,
                    PendingIntent.getBroadcast(ctx, 7 + code, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
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

/** The compact widget: only today's High and Medium priority tasks. The list scrolls when it does not fit. */
class SmallWidget : TodayWidget()

/**
 * Notes widget: notes pinned to today first, then the latest ones. Tap a note to open it,
 * the header to open the Notes tab, + to write a new note.
 */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            Store.load(ctx)
            val today = LocalDate.now()
            val theme = Look.widgetTheme(ctx)
            val light = theme == "light"
            val grey = theme == "grey"
            val cPrimary = if (light) 0xFF1F1A16.toInt() else if (grey) 0xFFECEDEF.toInt() else 0xFFF2F3F5.toInt()
            val cSecondary = if (light) 0xFF6B6159.toInt() else if (grey) 0xFFA3A9B2.toInt() else 0xFF9096A0.toInt()
            val cDim = if (light) 0xFFA0968C.toInt() else if (grey) 0xFF7A808A.toInt() else 0xFF5C616B.toInt()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            // Productivity and course notes (developer notes stay in the app).
            val notes = Store.notes.items.filter { !it.developer }
            val pinned = notes.filter { it.pin == today }.sortedBy { it.created }
            val shown = (pinned + notes.filter { it !in pinned }.sortedByDescending { it.updated }).take(20)

            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            v.setInt(R.id.w_root, "setBackgroundResource",
                if (light) R.drawable.widget_bg_light else if (grey) R.drawable.widget_bg_grey else R.drawable.widget_bg)
            v.setImageViewResource(R.id.w_icon, R.drawable.ic_notes)
            v.setTextViewText(R.id.w_day, "Notes")
            v.setTextColor(R.id.w_day, cPrimary)
            v.setTextViewText(R.id.w_score, if (pinned.isNotEmpty()) "${pinned.size} for today" else "")
            v.setTextColor(R.id.w_score, cSecondary)
            v.setTextColor(R.id.w_empty, cDim)
            v.setViewVisibility(R.id.w_habits, View.GONE)
            val open = Intent(ctx, MainActivity::class.java).putExtra("notes", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setOnClickPendingIntent(R.id.w_header, PendingIntent.getActivity(ctx, 80, open, flags))
            val add = Intent(ctx, NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(ctx, 81, add, flags))

            if (Build.VERSION.SDK_INT >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                for (n in shown) {
                    val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                    row.setViewVisibility(R.id.w_box, View.GONE)
                    row.setTextViewText(R.id.w_title, n.title.ifBlank { n.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Untitled note" })
                    row.setTextColor(R.id.w_title, cPrimary)
                    val course = Store.courses.get(n.courseId)
                    row.setTextViewText(R.id.w_time, when {
                        n.pin == today -> "Today"
                        course != null -> course.code
                        else -> ""
                    })
                    row.setTextColor(R.id.w_time, cSecondary)
                    row.setOnClickFillInIntent(R.id.w_row, Intent().putExtra("id", n.id))
                    items.addItem(n.id.hashCode().toLong(), row)
                }
                v.setRemoteAdapter(R.id.w_list, items.build())
                v.setEmptyView(R.id.w_list, R.id.w_empty)
                v.setTextViewText(R.id.w_empty, "No notes yet.\nTap + to write one.")
                // A tap on a line opens that note: its id is filled into this intent.
                val note = Intent(ctx, NoteActivity::class.java)
                v.setPendingIntentTemplate(R.id.w_list,
                    PendingIntent.getActivity(ctx, 82, note, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            } else {
                v.setViewVisibility(R.id.w_list, View.GONE)
                v.setTextViewText(R.id.w_empty, "Tap to open your notes.")
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
