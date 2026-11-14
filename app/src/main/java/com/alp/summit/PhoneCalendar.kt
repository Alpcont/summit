package com.alp.summit

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

/** An appointment from the phone's own calendar (Google, Samsung, …). Shown next to tasks, never changed. */
class PhoneEvent(val title: String, val start: Int, val end: Int, val allDay: Boolean, val color: Int, val place: String)

object PhoneCalendar {
    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Switched on in Settings and permitted by the phone. */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("showEvents", false) && allowed(ctx)

    /** The events of one day, all-day ones first, then by start time. `start`/`end` are minutes of that day. */
    fun on(ctx: Context, d: LocalDate): List<PhoneEvent> {
        if (!enabled(ctx)) return emptyList()
        val out = ArrayList<PhoneEvent>()
        try {
            val zone = ZoneId.systemDefault()
            val from = d.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .let { ContentUris.appendId(it, from) }.let { ContentUris.appendId(it, to) }.build()
            val columns = arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.START_DAY,
                CalendarContract.Instances.END_DAY, CalendarContract.Instances.START_MINUTE,
                CalendarContract.Instances.END_MINUTE, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION
            )
            val julian = (d.toEpochDay() + 2440588L).toInt()   // the calendar counts days this way
            ctx.contentResolver.query(
                uri, columns, CalendarContract.Instances.VISIBLE + " = 1", null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val startDay = c.getInt(1)
                    val endDay = c.getInt(2)
                    if (julian < startDay || julian > endDay) continue
                    val allDay = c.getInt(5) != 0
                    val start = if (startDay == julian) c.getInt(3) else 0
                    val end = if (endDay == julian) c.getInt(4) else 24 * 60
                    // An event that ends exactly at midnight belongs to the day before only.
                    if (!allDay && endDay == julian && startDay < julian && end == 0) continue
                    out.add(
                        PhoneEvent(
                            c.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)", start, end,
                            allDay, c.getInt(6) or (0xFF shl 24), c.getString(7) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // No calendar on the device, or permission just taken away: simply show none.
        }
        return out.sortedWith(compareBy({ !it.allDay }, { it.start }))
    }
}
