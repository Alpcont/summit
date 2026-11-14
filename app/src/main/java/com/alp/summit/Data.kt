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
    var spent = 0                      // minutes of focus time logged on this task
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
    val focusLog = HashMap<String, Int>()   // day -> minutes of focus time
    private var file: File? = null
    private var app: Context? = null

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
        if (app == null) app = ctx.applicationContext
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
            focusLog.clear()
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
        val fs = JSONObject()
        for ((day, minutes) in focusLog) fs.put(day, minutes)
        o.put("focus", fs)
        val text = o.toString()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
        // Every change also reaches the home-screen widget.
        app?.let { TodayWidget.refresh(it) }
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
        o.put("rank", t.rank).put("spent", t.spent)
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
            t.spent = j.optInt("spent", 0)
            t.created = j.optLong("created", System.currentTimeMillis())
            t.updated = j.optLong("updated", t.created)
            tasks.add(t)
        }
        readFocus(o)
    }

    private fun readFocus(o: JSONObject) {
        val fs = o.optJSONObject("focus") ?: return
        for (day in fs.keys()) focusLog[day] = fs.optInt(day, 0)
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

    // ----- Streaks and statistics -----

    /**
     * Days in a row on which every planned task was ticked ("summit reached").
     * Days without tasks neither count nor break the streak; today only counts once it is complete.
     */
    fun streak(today: LocalDate): Int {
        var n = 0
        for (i in 0 until 730) {
            val d = today.minusDays(i.toLong())
            val (done, total) = progress(d)
            if (total == 0) continue
            if (done == total) n++ else if (i > 0) break
        }
        return n
    }

    fun bestStreak(today: LocalDate): Int {
        var best = 0
        var run = 0
        for (i in 729 downTo 0) {
            val d = today.minusDays(i.toLong())
            val (done, total) = progress(d)
            if (total == 0) continue
            if (done == total) run++ else if (i > 0) run = 0
            if (run > best) best = run
        }
        return best
    }

    /** (done, total) summed over `days` days ending on `last`. */
    fun totals(last: LocalDate, days: Int): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (i in 0 until days) {
            val p = progress(last.minusDays(i.toLong()))
            done += p.first
            total += p.second
        }
        return Pair(done, total)
    }

    fun focusMinutes(last: LocalDate, days: Int): Int {
        var sum = 0
        for (i in 0 until days) sum += focusLog[last.minusDays(i.toLong()).toString()] ?: 0
        return sum
    }

    fun logFocus(t: Task?, d: LocalDate, minutes: Int) {
        if (minutes <= 0) return
        if (t != null) t.spent += minutes
        focusLog[d.toString()] = (focusLog[d.toString()] ?: 0) + minutes
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

fun repeatLabel(mask: Int): String {
    if (mask == 127) return "Daily"
    if (mask == 31) return "Weekdays"
    val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString(" ") { names[it] }
}

// ---------- Smart quick-add: "gym tomorrow 18:00 45m" ----------

/** What was understood from a typed line. Fields left at their start value were not mentioned. */
class Parsed(var title: String) {
    var date: LocalDate? = null
    var hasDate = false
    var time = -1
    var duration = 0
    var priority = 0
    var categoryId = ""
    var repeatMask = 0

    /** "Tomorrow · 18:00 · 45m · Gym", or "" when nothing special was found. */
    fun summary(today: LocalDate): String {
        val parts = ArrayList<String>()
        val d = date
        if (hasDate && d != null) parts.add(relDay(d, today))
        if (time >= 0) parts.add(fmtTime(time))
        if (duration > 0) parts.add(fmtDuration(duration))
        if (repeatMask != 0) parts.add(repeatLabel(repeatMask))
        Store.category(categoryId)?.let { parts.add(it.name) }
        if (priority == 3) parts.add("High")
        if (priority == 1) parts.add("Low")
        return parts.joinToString("  ·  ")
    }
}

private val DAY_WORDS = mapOf(
    "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
    "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5
)
// "sat" and "sun" are also ordinary words, so they only count after "on", "next" or "every".
private val DAY_WORDS_LOOSE = mapOf("sat" to 6, "sun" to 7)
private val MONTH_WORDS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val MONTH_NAMES = listOf(
    "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"
)
private val R_TIME = Regex("^(\\d{1,2}):(\\d{2})$")
private val R_TIME_AMPM = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm)$")
private val R_TIME_BARE = Regex("^(\\d{1,2})(?::(\\d{2}))?$")
private val R_MINUTES = Regex("^(\\d+)(m|min|mins|minutes)$")
private val R_HOURS = Regex("^(\\d+(?:\\.\\d+)?)(h|hr|hrs|hour|hours)$")
private val R_HOURS_MIN = Regex("^(\\d+)h(\\d+)m?$")
private val R_NUMBER = Regex("^\\d+(?:\\.\\d+)?$")
private val R_DAY_MONTH = Regex("^(\\d{1,2})[./](\\d{1,2})$")

fun parseQuick(text: String, today: LocalDate, categories: List<Category>): Parsed {
    val clean = text.trim()
    val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val used = BooleanArray(words.size)
    val p = Parsed(clean)
    fun low(i: Int): String = if (i in words.indices) words[i].lowercase().trimEnd(',') else ""
    fun day(w: String, loose: Boolean): Int = DAY_WORDS[w] ?: (if (loose) DAY_WORDS_LOOSE[w] else null) ?: 0
    fun next(dow: Int): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek.value != dow) d = d.plusDays(1)
        return d
    }
    fun setDate(d: LocalDate) {
        p.date = d
        p.hasDate = true
    }
    fun ampm(h: Int, m: Int, half: String): Int {
        if (h < 1 || h > 12 || m > 59) return -1
        return ((h % 12) + (if (half == "pm") 12 else 0)) * 60 + m
    }
    // Each reader returns how many words it used (0 = not this).
    fun clock(i: Int): Int {
        val w = low(i)
        R_TIME.find(w)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            if (h < 24 && m < 60) {
                p.time = h * 60 + m
                return 1
            }
        }
        R_TIME_AMPM.find(w)?.let {
            val t = ampm(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0, it.groupValues[3])
            if (t >= 0) {
                p.time = t
                return 1
            }
        }
        val half = low(i + 1)
        if (half == "am" || half == "pm") R_TIME_BARE.find(w)?.let {
            val t = ampm(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0, half)
            if (t >= 0) {
                p.time = t
                return 2
            }
        }
        return 0
    }
    fun length(i: Int): Int {
        val w = low(i)
        var minutes = 0
        var took = 0
        R_MINUTES.find(w)?.let {
            minutes = it.groupValues[1].toInt()
            took = 1
        }
        if (took == 0) R_HOURS.find(w)?.let {
            minutes = Math.round(it.groupValues[1].toFloat() * 60)
            took = 1
        }
        if (took == 0) R_HOURS_MIN.find(w)?.let {
            minutes = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
            took = 1
        }
        if (took == 0 && R_NUMBER.matches(w)) {
            val unit = low(i + 1)
            if (unit in setOf("min", "mins", "minutes") && !w.contains('.')) {
                minutes = w.toInt()
                took = 2
            } else if (unit in setOf("h", "hr", "hrs", "hour", "hours")) {
                minutes = Math.round(w.toFloat() * 60)
                took = 2
            }
        }
        if (took == 0 || minutes < 1 || minutes > 24 * 60) return 0
        p.duration = minutes
        return took
    }
    fun calendar(i: Int): Int {
        val w = low(i)
        var dayNo = 0
        var monthNo = 0
        var took = 0
        R_DAY_MONTH.find(w)?.let {
            dayNo = it.groupValues[1].toInt()
            monthNo = it.groupValues[2].toInt()
            took = 1
        }
        if (took == 0 && w.length <= 2 && w.all { it.isDigit() } && w.isNotEmpty()) {
            val m = low(i + 1).trimEnd('.')
            val index = if (m.length == 3) MONTH_WORDS.indexOf(m) else MONTH_NAMES.indexOf(m)
            if (index >= 0) {
                dayNo = w.toInt()
                monthNo = index + 1
                took = 2
            }
        }
        if (took == 0) return 0
        val d = try {
            LocalDate.of(today.year, monthNo, dayNo)
        } catch (e: Exception) {
            return 0
        }
        setDate(if (d.isBefore(today)) d.plusYears(1) else d)
        return took
    }

    var i = 0
    while (i < words.size) {
        val w = low(i)
        val n = low(i + 1)
        var took = 0
        when {
            w == "today" -> {
                setDate(today)
                took = 1
            }
            w == "tomorrow" || w == "tmrw" -> {
                setDate(today.plusDays(1))
                took = 1
            }
            w == "daily" || (w == "every" && n == "day") -> {
                p.repeatMask = 127
                took = if (w == "daily") 1 else 2
            }
            w == "weekdays" || (w == "every" && (n == "weekday" || n == "weekdays")) -> {
                p.repeatMask = 31
                took = if (w == "weekdays") 1 else 2
            }
            w == "every" && day(n, true) != 0 -> {
                // "every mon wed fri", "every tue and thu"
                took = 1
                while (true) {
                    var j = i + took
                    if (low(j) == "and") j++
                    val dow = day(low(j), true)
                    if (dow == 0) break
                    p.repeatMask = p.repeatMask or (1 shl (dow - 1))
                    took = j - i + 1
                }
            }
            (w == "on" || w == "next") && day(n, true) != 0 -> {
                setDate(next(day(n, true)))
                took = 2
            }
            day(w, false) != 0 -> {
                setDate(next(day(w, false)))
                took = 1
            }
            w.length > 1 && w.startsWith("#") -> {
                val c = categories.firstOrNull { it.name.lowercase().startsWith(w.substring(1)) }
                if (c != null) {
                    p.categoryId = c.id
                    took = 1
                }
            }
            w == "!" || w == "!!" || w == "!high" || w == "!h" -> {
                p.priority = 3
                took = 1
            }
            w == "!med" || w == "!medium" -> {
                p.priority = 2
                took = 1
            }
            w == "!low" || w == "!l" -> {
                p.priority = 1
                took = 1
            }
            w == "at" && clock(i + 1) != 0 -> took = 1 + clock(i + 1)
            w == "for" && length(i + 1) != 0 -> took = 1 + length(i + 1)
            w == "on" && calendar(i + 1) != 0 -> took = 1 + calendar(i + 1)
            else -> {
                took = clock(i)
                if (took == 0) took = length(i)
                if (took == 0) took = calendar(i)
            }
        }
        for (k in i until minOf(i + took, words.size)) used[k] = true
        i += maxOf(took, 1)
    }

    val rest = words.filterIndexed { k, _ -> !used[k] }
    if (rest.isEmpty()) return Parsed(clean)     // only keywords: keep the line as it was typed
    p.title = rest.joinToString(" ")
    // A word that is itself a category name picks that category ("gym tomorrow" -> Gym).
    if (p.categoryId.isEmpty()) {
        val plain = rest.map { it.lowercase().trim(',', '.', '!', '?') }
        categories.firstOrNull { it.name.lowercase() in plain }?.let { p.categoryId = it.id }
    }
    return p
}
