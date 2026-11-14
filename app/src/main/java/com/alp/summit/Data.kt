package com.alp.summit

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// ---------- Model ----------

class Subtask(val id: String, var title: String)

class Category(val id: String, var name: String, var color: Int)

class Task(val id: String = UUID.randomUUID().toString()) {
    var title = ""
    var notes = ""
    var date: LocalDate? = null        // null = Someday. For a repeating task: its first day.
    var time = -1                      // minutes after midnight, -1 = no time
    var duration = 0                   // estimated minutes, 0 = not set
    var priority = 2                   // 1 low, 2 medium, 3 high
    var categoryId = ""
    var repeatMask = 0                 // bit 0 = Monday ... bit 6 = Sunday, 0 = does not repeat
    var endDate: LocalDate? = null     // last day of a repeating task, null = no end
    var remind = 0                     // minutes before `time` to notify, -1 = no reminder
    var done = false                   // tick state of a non-repeating task
    val doneDates = HashSet<String>()  // repeating task: the days it was ticked
    val skipDates = HashSet<String>()  // repeating task: days removed from the series
    val subtasks = ArrayList<Subtask>()
    val subDone = HashSet<String>()    // ticked subtasks, see subKey()
    var rank = -1.0                    // position after dragging; below 0 = automatic order
    var created = System.currentTimeMillis()
    var updated = created

    val repeating: Boolean get() = repeatMask != 0 && date != null

    fun occursOn(d: LocalDate): Boolean {
        val start = date ?: return false
        if (!repeating) return d == start
        if (d.isBefore(start)) return false
        val end = endDate
        if (end != null && d.isAfter(end)) return false
        if (repeatMask and (1 shl (d.dayOfWeek.value - 1)) == 0) return false
        return d.toString() !in skipDates
    }

    fun isDone(d: LocalDate?): Boolean =
        if (repeating && d != null) d.toString() in doneDates else done

    fun subKey(d: LocalDate?, s: Subtask): String =
        (if (repeating && d != null) d.toString() else "") + "|" + s.id

    /** A one-off copy of this task on another day (used when a missed repeat is moved). */
    fun copyTo(d: LocalDate): Task {
        val c = Task()
        c.title = title; c.notes = notes; c.date = d; c.time = time; c.duration = duration
        c.priority = priority; c.categoryId = categoryId; c.remind = remind
        for (s in subtasks) c.subtasks.add(Subtask(UUID.randomUUID().toString(), s.title))
        return c
    }
}

/** A task that was left unticked on an earlier day. */
class Missed(val task: Task, val date: LocalDate)

// ---------- Storage (one JSON file inside the app's private folder) ----------

object Store {
    val tasks = ArrayList<Task>()
    val categories = ArrayList<Category>()
    private var file: File? = null

    // Tasks placed by hand come first in that order; the rest follow by time, then priority.
    private val order = compareBy<Task>(
        { if (it.rank >= 0) 0 else 1 },
        { if (it.rank >= 0) it.rank else 0.0 },
        { if (it.time >= 0) 0 else 1 },
        { if (it.time >= 0) it.time else 0 },
        { -it.priority },
        { it.created }
    )

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "summit.json")
        file = f
        if (!f.exists()) {
            seed()
            save()
            return
        }
        try {
            read(JSONObject(f.readText()))
        } catch (e: Exception) {
            // Keep the unreadable file so nothing is lost silently, then start clean.
            try { f.copyTo(File(f.parentFile, "summit.damaged.json"), true) } catch (_: Exception) {}
            tasks.clear()
            categories.clear()
            seed()
        }
    }

    private fun seed() {
        val names = listOf("University", "Gym", "Reading", "Projects", "Investing", "Personal")
        val colors = listOf("#5B9DFF", "#3DDC97", "#B692F6", "#38C6D9", "#F5C451", "#F283B6")
        for (i in names.indices) {
            categories.add(Category(UUID.randomUUID().toString(), names[i], Color.parseColor(colors[i])))
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("version", 1)
        val cs = JSONArray()
        for (c in categories) cs.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color))
        o.put("categories", cs)
        val ts = JSONArray()
        for (t in tasks) ts.put(toJson(t))
        o.put("tasks", ts)
        val text = o.toString()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    private fun toJson(t: Task): JSONObject {
        val o = JSONObject()
        o.put("id", t.id).put("title", t.title).put("notes", t.notes)
        o.put("date", t.date?.toString() ?: "")
        o.put("time", t.time).put("duration", t.duration).put("priority", t.priority)
        o.put("category", t.categoryId).put("repeat", t.repeatMask)
        o.put("end", t.endDate?.toString() ?: "")
        o.put("remind", t.remind).put("done", t.done)
        o.put("doneDates", JSONArray(t.doneDates.toList()))
        o.put("skipDates", JSONArray(t.skipDates.toList()))
        val ss = JSONArray()
        for (s in t.subtasks) ss.put(JSONObject().put("id", s.id).put("title", s.title))
        o.put("subtasks", ss)
        o.put("subDone", JSONArray(t.subDone.toList()))
        o.put("rank", t.rank)
        o.put("created", t.created).put("updated", t.updated)
        return o
    }

    private fun read(o: JSONObject) {
        val cs = o.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            categories.add(Category(c.getString("id"), c.getString("name"), c.getInt("color")))
        }
        val ts = o.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until ts.length()) {
            val j = ts.getJSONObject(i)
            val t = Task(j.getString("id"))
            t.title = j.optString("title")
            t.notes = j.optString("notes")
            t.date = j.optString("date").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
            t.time = j.optInt("time", -1)
            t.duration = j.optInt("duration", 0)
            t.priority = j.optInt("priority", 2)
            t.categoryId = j.optString("category")
            t.repeatMask = j.optInt("repeat", 0)
            t.endDate = j.optString("end").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
            t.remind = j.optInt("remind", 0)
            t.done = j.optBoolean("done", false)
            strings(j.optJSONArray("doneDates"), t.doneDates)
            strings(j.optJSONArray("skipDates"), t.skipDates)
            strings(j.optJSONArray("subDone"), t.subDone)
            val ss = j.optJSONArray("subtasks") ?: JSONArray()
            for (k in 0 until ss.length()) {
                val s = ss.getJSONObject(k)
                t.subtasks.add(Subtask(s.getString("id"), s.optString("title")))
            }
            t.rank = j.optDouble("rank", -1.0)
            t.created = j.optLong("created", System.currentTimeMillis())
            t.updated = j.optLong("updated", t.created)
            tasks.add(t)
        }
    }

    private fun strings(a: JSONArray?, into: MutableSet<String>) {
        if (a == null) return
        for (i in 0 until a.length()) into.add(a.getString(i))
    }

    // ----- Queries -----

    fun task(id: String?): Task? = tasks.firstOrNull { it.id == id }

    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    /** Tasks of one day. Open tasks first (hand-placed, then by time and priority), ticked ones below. */
    fun on(d: LocalDate): List<Task> =
        tasks.filter { it.occursOn(d) }.sortedWith(compareBy<Task> { it.isDone(d) }.then(order))

    fun someday(): List<Task> =
        tasks.filter { it.date == null }.sortedWith(compareBy<Task> { it.done }.then(order))

    /** (done, total) for one day. */
    fun progress(d: LocalDate): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (t in tasks) if (t.occursOn(d)) {
            total++
            if (t.isDone(d)) done++
        }
        return Pair(done, total)
    }

    /**
     * Unfinished tasks from before today. One-off tasks stay here until handled.
     * Repeating tasks count only for the last 7 days, and only if they are not on today anyway.
     */
    fun missed(today: LocalDate): List<Missed> {
        val out = ArrayList<Missed>()
        for (t in tasks) {
            val start = t.date ?: continue
            if (!t.repeating) {
                if (start.isBefore(today) && !t.done) out.add(Missed(t, start))
            } else if (!t.occursOn(today)) {
                for (i in 1..7) {
                    val d = today.minusDays(i.toLong())
                    if (t.occursOn(d) && !t.isDone(d)) out.add(Missed(t, d))
                }
            }
        }
        return out.sortedBy { it.date }
    }

    // ----- Changes -----

    fun setDone(t: Task, d: LocalDate?, value: Boolean) {
        if (t.repeating && d != null) {
            if (value) t.doneDates.add(d.toString()) else t.doneDates.remove(d.toString())
        } else {
            t.done = value
        }
        t.updated = System.currentTimeMillis()
    }

    fun move(t: Task, d: LocalDate?) {
        t.date = d
        t.rank = -1.0
        if (d == null) {
            t.repeatMask = 0
            t.endDate = null
        }
        t.updated = System.currentTimeMillis()
    }
}

// ---------- Small helpers ----------

fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("summit", Context.MODE_PRIVATE)

private val F_WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
private val F_DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
private val F_SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val F_MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

fun weekdayName(d: LocalDate): String = d.format(F_WEEKDAY)
fun dayMonth(d: LocalDate): String = d.format(F_DAY_MONTH)
fun shortDay(d: LocalDate): String = d.format(F_SHORT)
fun monthYear(d: LocalDate): String = d.format(F_MONTH_YEAR)

/** "Today", "Tomorrow", "Yesterday" or "Tue 17 Nov". */
fun relDay(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> shortDay(d)
}

/** Like relDay, but for the middle of a sentence: "today", "tomorrow", "Tue 17 Nov". */
fun relDayInline(d: LocalDate, today: LocalDate): String {
    val r = relDay(d, today)
    return if (r.contains(' ')) r else r.lowercase()
}

fun fmtTime(m: Int): String = String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60)

fun fmtDuration(m: Int): String = when {
    m <= 0 -> ""
    m < 60 -> "${m}m"
    m % 60 == 0 -> "${m / 60}h"
    else -> "${m / 60}h ${m % 60}m"
}

fun plural(n: Int, word: String): String = if (n == 1) "$n $word" else "$n ${word}s"
