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
    var spent = 0                      // minutes of work logged on this task, all days together
    val spentDay = HashMap<String, Int>()  // the same minutes per day (a repeating task starts fresh every day)
    var origin = ""                    // made by Summit for something: "study|course|exam|day", "asg|id", "review|course|day"
    var moves = 0                      // how often it was moved to another day (for the weekly review)
    var created = System.currentTimeMillis()
    var updated = created
    var syncHash = 0                   // fingerprint of the content last exchanged with the cloud; 0 = never

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

    /** Minutes worked that count towards this task's planned time on day `d`. */
    fun worked(d: LocalDate?): Int = if (repeating && d != null) spentDay[d.toString()] ?: 0 else spent

    fun subKey(d: LocalDate?, s: Subtask): String =
        (if (repeating && d != null) d.toString() else "") + "|" + s.id

    /** Takes over everything from another copy of the same task (one that arrived from another device). */
    fun assign(o: Task) {
        title = o.title; notes = o.notes; date = o.date; time = o.time; duration = o.duration
        priority = o.priority; categoryId = o.categoryId; repeatMask = o.repeatMask; endDate = o.endDate
        remind = o.remind; done = o.done; rank = o.rank; spent = o.spent; created = o.created; updated = o.updated
        origin = o.origin; moves = o.moves
        doneDates.clear(); doneDates.addAll(o.doneDates)
        skipDates.clear(); skipDates.addAll(o.skipDates)
        subDone.clear(); subDone.addAll(o.subDone)
        subtasks.clear(); subtasks.addAll(o.subtasks)
        spentDay.clear(); spentDay.putAll(o.spentDay)
    }

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
    val habits = Shelf("habits") { Habit.from(it) }
    val notes = Shelf("notes") { Note.from(it) }
    val courses = Shelf("courses") { Course.from(it) }
    val assignments = Shelf("assignments") { Assignment.from(it) }
    /** Everything besides tasks that is kept on the device and synced the same way. */
    val shelves: List<Shelf<*>> = listOf(habits, notes, courses, assignments)

    // Bookkeeping for sync between devices (see Sync.kt).
    val syncedIds = HashSet<String>()       // tasks the cloud knows about; one missing here was deleted
    var catsUpdated = 0L
    var catsSyncHash = 0
    var focusSyncHash = 0
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
            for (sh in shelves) sh.clear()
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
        // Changes since the last save are handed to the cloud first (does nothing when not signed in).
        app?.let { Sync.beforeSave(it) }
        val o = JSONObject()
        o.put("version", 1)
        val cs = JSONArray()
        for (c in categories) cs.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color))
        o.put("categories", cs)
        val ts = JSONArray()
        for (t in tasks) ts.put(taskJson(t).put("syncHash", t.syncHash))
        o.put("tasks", ts)
        o.put("syncedIds", JSONArray(syncedIds.toList()))
        o.put("catsUpdated", catsUpdated).put("catsSyncHash", catsSyncHash).put("focusSyncHash", focusSyncHash)
        val fs = JSONObject()
        for ((day, minutes) in focusLog) fs.put(day, minutes)
        o.put("focus", fs)
        for (sh in shelves) sh.write(o)
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

    fun taskJson(t: Task): JSONObject {
        val o = JSONObject()
        o.put("id", t.id).put("title", t.title).put("notes", t.notes)
        o.put("date", t.date?.toString() ?: "")
        o.put("time", t.time).put("duration", t.duration).put("priority", t.priority)
        o.put("category", t.categoryId).put("repeat", t.repeatMask)
        o.put("end", t.endDate?.toString() ?: "")
        o.put("remind", t.remind).put("done", t.done)
        o.put("doneDates", JSONArray(t.doneDates.sorted()))
        o.put("skipDates", JSONArray(t.skipDates.sorted()))
        val ss = JSONArray()
        for (s in t.subtasks) ss.put(JSONObject().put("id", s.id).put("title", s.title))
        o.put("subtasks", ss)
        o.put("subDone", JSONArray(t.subDone.sorted()))
        o.put("rank", t.rank).put("spent", t.spent)
        val sd = JSONObject()
        for ((day, minutes) in t.spentDay.toSortedMap()) sd.put(day, minutes)
        o.put("spentDay", sd)
        o.put("created", t.created).put("updated", t.updated)
        // Newer fields are only written when used, so older tasks keep the same fingerprint.
        if (t.origin.isNotEmpty()) o.put("origin", t.origin)
        if (t.moves > 0) o.put("moves", t.moves)
        return o
    }

    private fun read(o: JSONObject) {
        val cs = o.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            categories.add(Category(c.getString("id"), c.getString("name"), c.getInt("color")))
        }
        val ts = o.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until ts.length()) tasks.add(taskFromJson(ts.getJSONObject(i)))
        readFocus(o)
        strings(o.optJSONArray("syncedIds"), syncedIds)
        catsUpdated = o.optLong("catsUpdated", 0L)
        catsSyncHash = o.optInt("catsSyncHash", 0)
        focusSyncHash = o.optInt("focusSyncHash", 0)
        for (sh in shelves) sh.read(o)
    }

    fun taskFromJson(j: JSONObject): Task {
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
        j.optJSONObject("spentDay")?.let { sd -> for (day in sd.keys()) t.spentDay[day] = sd.optInt(day, 0) }
        t.created = j.optLong("created", System.currentTimeMillis())
        t.updated = j.optLong("updated", t.created)
        t.syncHash = j.optInt("syncHash", 0)
        t.origin = j.optString("origin")
        t.moves = j.optInt("moves", 0)
        return t
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

    /** The course a task was made for (a study block, an assignment session or a class review), or null. */
    fun courseOf(t: Task): Course? {
        if (t.origin.isEmpty()) return null
        val p = t.origin.split('|')
        val c = when (p[0]) {
            "study", "review" -> courses.get(p.getOrNull(1))
            "asg" -> courses.get(assignments.get(p.getOrNull(1))?.courseId)
            else -> null
        }
        return c?.takeIf { !it.hidden }
    }

    /** The colour a task is shown in: its course's, else its category's (deeper on the light theme). */
    fun colorOf(t: Task): Int? = (courseOf(t)?.color ?: category(t.categoryId)?.color)?.let { Look.tone(it) }

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

    // ----- The day as planned time -----

    /** Open tasks of a day in the order they are worked through: by clock time, then the ones without a time. */
    fun queue(d: LocalDate): List<Task> {
        val open = on(d).filter { !it.isDone(d) }
        return open.filter { it.time >= 0 }.sortedBy { it.time } + open.filter { it.time < 0 }
    }

    /**
     * (minutes done, minutes planned) for a day, counting only tasks that have a duration.
     * A ticked task counts in full; an open one counts the minutes worked on it, at most its
     * duration. `running` and `extra` add the minutes of a timer that has not been logged yet.
     */
    fun dayTime(d: LocalDate, running: Task? = null, extra: Int = 0): Pair<Int, Int> {
        var done = 0
        var planned = 0
        for (t in tasks) {
            if (!t.occursOn(d) || t.duration <= 0) continue
            planned += t.duration
            done += if (t.isDone(d)) t.duration
            else minOf(t.worked(d) + (if (t === running) extra else 0), t.duration)
        }
        return Pair(done, planned)
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
        if (t != null) {
            t.spent += minutes
            t.spentDay[d.toString()] = (t.spentDay[d.toString()] ?: 0) + minutes
        }
        focusLog[d.toString()] = (focusLog[d.toString()] ?: 0) + minutes
    }

    // ----- Sync between devices: what changed here, and taking in what changed elsewhere -----

    /** A fingerprint of everything in a task except its timestamps. */
    fun contentHash(t: Task): Int {
        val j = taskJson(t)
        j.remove("updated")
        val h = j.toString().hashCode()
        return if (h == 0) 1 else h
    }

    fun categoriesHash(list: List<Category> = categories): Int {
        val h = list.joinToString(";") { it.id + "|" + it.name + "|" + it.color }.hashCode()
        return if (h == 0) 1 else h
    }

    fun focusHash(): Int {
        val h = focusLog.toSortedMap().toString().hashCode()
        return if (h == 0) 1 else h
    }

    private fun clean(t: Task): Boolean = t.syncHash != 0 && contentHash(t) == t.syncHash

    /** A task as another device saved it. Returns true if something here changed. */
    fun applyRemoteTask(id: String, j: JSONObject): Boolean {
        val local = task(id)
        val remoteUpdated = j.optLong("updated", 0L)
        if (j.optBoolean("deleted", false)) {
            syncedIds.remove(id)
            if (local == null) return false
            // Edited here after it was deleted elsewhere: the edit wins and goes back up.
            if (!clean(local) && local.updated > remoteUpdated) {
                local.syncHash = 0
                return false
            }
            tasks.remove(local)
            return true
        }
        j.put("id", id)
        val remote = taskFromJson(j)
        val h = contentHash(remote)
        syncedIds.add(id)
        if (local == null) {
            remote.syncHash = h
            tasks.add(remote)
            return true
        }
        if (contentHash(local) == h) {
            local.syncHash = h
            return false
        }
        // Both sides changed: the later change wins.
        if (!clean(local) && local.updated >= remoteUpdated) return false
        local.assign(remote)
        local.syncHash = h
        return true
    }

    /** The category list as another device saved it. */
    fun applyRemoteCategories(j: JSONObject): Boolean {
        val items = j.optJSONArray("items") ?: return false
        val remote = ArrayList<Category>()
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i)
            remote.add(Category(c.getString("id"), c.optString("name"), c.optInt("color")))
        }
        val remoteHash = categoriesHash(remote)
        if (catsSyncHash == 0) {
            // First contact: join both lists. Same name = same category, the cloud's copy is kept.
            val merged = ArrayList(remote)
            for (c in categories) {
                if (merged.any { it.id == c.id }) continue
                val same = merged.firstOrNull { it.name.equals(c.name, ignoreCase = true) }
                if (same == null) merged.add(c)
                else for (t in tasks) if (t.categoryId == c.id) t.categoryId = same.id
            }
            categories.clear()
            categories.addAll(merged)
            catsSyncHash = remoteHash
            catsUpdated = j.optLong("updated", 0L)
            return true
        }
        val localHash = categoriesHash()
        if (remoteHash == localHash) {
            catsSyncHash = localHash
            return false
        }
        if (localHash != catsSyncHash && catsUpdated >= j.optLong("updated", 0L)) return false
        categories.clear()
        categories.addAll(remote)
        catsSyncHash = remoteHash
        catsUpdated = j.optLong("updated", 0L)
        return true
    }

    /** Focus minutes per day from another device: the larger number wins, so nothing is ever lost. */
    fun applyRemoteFocus(j: JSONObject): Boolean {
        val days = j.optJSONObject("days") ?: return false
        var changed = false
        for (day in days.keys()) {
            val minutes = days.optInt(day, 0)
            if (minutes > (focusLog[day] ?: 0)) {
                focusLog[day] = minutes
                changed = true
            }
        }
        return changed
    }

    /** Forget what was exchanged, e.g. when another account signs in. */
    fun resetSync() {
        for (t in tasks) t.syncHash = 0
        syncedIds.clear()
        catsSyncHash = 0
        catsUpdated = 0L
        focusSyncHash = 0
        for (sh in shelves) sh.resetSync()
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
        if (t.date != null && d != t.date) t.moves++
        t.date = d
        t.rank = -1.0
        if (d == null) {
            t.repeatMask = 0
            t.endDate = null
        }
        t.updated = System.currentTimeMillis()
    }
}

// ---------- Habits, notes, courses and assignments ----------

/** Something besides a task that is stored on the device and synced between devices. */
abstract class Synced(val id: String) {
    var updated = System.currentTimeMillis()
    var syncHash = 0       // fingerprint of the content last exchanged with the cloud; 0 = never

    /** Everything about it, as stored on the device and in the cloud ("id" and "updated" included). */
    abstract fun json(): JSONObject

    fun hash(): Int {
        val j = json()
        j.remove("updated")
        val h = j.toString().hashCode()
        return if (h == 0) 1 else h
    }

    /** Call after every change, so the later change wins when both devices changed it. */
    fun touch() {
        updated = System.currentTimeMillis()
    }
}

/** One list of synced things, with the bookkeeping sync needs. Stored in the app's file under `name`. */
class Shelf<T : Synced>(val name: String, private val make: (JSONObject) -> T) : Iterable<T> {
    val items = ArrayList<T>()
    val syncedIds = HashSet<String>()   // ids the cloud knows; one missing from `items` was deleted here

    override fun iterator(): Iterator<T> = items.iterator()
    fun get(id: String?): T? = items.firstOrNull { it.id == id }
    fun add(item: T) = items.add(item)
    fun remove(item: T) = items.remove(item)
    fun clear() = items.clear()

    fun write(o: JSONObject) {
        val a = JSONArray()
        for (it in items) a.put(it.json().put("syncHash", it.syncHash))
        o.put(name, a)
        o.put(name + "Synced", JSONArray(syncedIds.toList()))
    }

    fun read(o: JSONObject) {
        val a = o.optJSONArray(name) ?: JSONArray()
        for (i in 0 until a.length()) {
            try {
                val j = a.getJSONObject(i)
                val item = make(j)
                item.updated = j.optLong("updated", item.updated)
                item.syncHash = j.optInt("syncHash", 0)
                items.add(item)
            } catch (e: Exception) {
                // One unreadable entry should not take the others with it.
            }
        }
        val ids = o.optJSONArray(name + "Synced") ?: JSONArray()
        for (i in 0 until ids.length()) syncedIds.add(ids.getString(i))
    }

    /** Builds an item from what another device saved (also keeps its "updated"). */
    fun fromRemote(id: String, j: JSONObject): T {
        j.put("id", id)
        val item = make(j)
        item.updated = j.optLong("updated", 0L)
        return item
    }

    fun resetSync() {
        for (it in items) it.syncHash = 0
        syncedIds.clear()
    }

    /**
     * What changed here since the last exchange: (items to send, ids deleted here).
     * Marks them as exchanged. Works like the tasks do (see Sync.beforeSave).
     */
    fun outgoing(now: Long): Pair<List<JSONObject>, List<String>> {
        val up = ArrayList<JSONObject>()
        val alive = HashSet<String>()
        for (it in items) {
            alive.add(it.id)
            val h = it.hash()
            if (h == it.syncHash) continue
            if (it.syncHash != 0) it.updated = now      // changed since the last exchange
            it.syncHash = h
            syncedIds.add(it.id)
            up.add(it.json().put("deleted", false))
        }
        val gone = syncedIds.filter { it !in alive }
        syncedIds.removeAll(gone.toSet())
        return Pair(up, gone)
    }

    /** One item as another device saved it. Returns true if something here changed. Later change wins. */
    fun applyRemote(id: String, j: JSONObject): Boolean {
        val local = get(id)
        val remoteUpdated = j.optLong("updated", 0L)
        val localClean = local != null && local.syncHash != 0 && local.hash() == local.syncHash
        if (j.optBoolean("deleted", false)) {
            syncedIds.remove(id)
            if (local == null) return false
            // Changed here after it was deleted elsewhere: the change wins and goes back up.
            if (!localClean && local.updated > remoteUpdated) {
                local.syncHash = 0
                return false
            }
            items.remove(local)
            return true
        }
        val remote = fromRemote(id, j)
        val h = remote.hash()
        syncedIds.add(id)
        if (local == null) {
            remote.syncHash = h
            items.add(remote)
            return true
        }
        if (local.hash() == h) {
            local.syncHash = h
            return false
        }
        if (!localClean && local.updated >= remoteUpdated) return false
        remote.syncHash = h
        items[items.indexOf(local)] = remote
        return true
    }
}

private fun JSONObject.dateOrNull(key: String): LocalDate? =
    optString(key).takeIf { it.isNotEmpty() }?.let { try { LocalDate.parse(it) } catch (e: Exception) { null } }

private fun stringSet(a: JSONArray?): HashSet<String> {
    val out = HashSet<String>()
    if (a != null) for (i in 0 until a.length()) out.add(a.getString(i))
    return out
}

/** A habit for the "Don't break the chain" tab. */
class Habit(id: String = UUID.randomUUID().toString()) : Synced(id) {
    var title = ""
    var mask = 127                       // weekdays it is planned: bit 0 = Monday ... bit 6 = Sunday
    var marked = false                   // starred, for the "Marked" calendar view
    var start: LocalDate = LocalDate.now().minusDays(1)   // days before this never break the chain
    val ticks = HashSet<String>()        // days it was done
    val badges = HashSet<Int>()          // 7, 30 and 100-day badges already earned
    var created = System.currentTimeMillis()

    fun planned(d: LocalDate): Boolean = mask and (1 shl (d.dayOfWeek.value - 1)) != 0
    fun ticked(d: LocalDate): Boolean = d.toString() in ticks

    /** Planned days in a row that were ticked. Unplanned days are skipped; an unticked today does not break it yet. */
    fun streak(today: LocalDate): Int {
        var n = 0
        var d = today
        while (!d.isBefore(start)) {
            if (planned(d)) {
                if (ticked(d)) n++ else if (d != today) break
            }
            d = d.minusDays(1)
        }
        return n
    }

    fun best(today: LocalDate): Int {
        var best = 0
        var run = 0
        var d = start
        while (!d.isAfter(today)) {
            if (planned(d)) {
                if (ticked(d)) run++ else if (d != today) run = 0
                best = maxOf(best, run)
            }
            d = d.plusDays(1)
        }
        return best
    }

    override fun json(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("mask", mask).put("marked", marked)
        .put("start", start.toString()).put("ticks", JSONArray(ticks.sorted()))
        .put("badges", JSONArray(badges.sorted())).put("created", created).put("updated", updated)

    companion object {
        fun from(j: JSONObject): Habit {
            val h = Habit(j.getString("id"))
            h.title = j.optString("title")
            h.mask = j.optInt("mask", 127)
            h.marked = j.optBoolean("marked", false)
            j.dateOrNull("start")?.let { h.start = it }
            h.ticks.addAll(stringSet(j.optJSONArray("ticks")))
            val b = j.optJSONArray("badges")
            if (b != null) for (i in 0 until b.length()) h.badges.add(b.optInt(i))
            h.created = j.optLong("created", h.created)
            return h
        }
    }
}

class CheckItem(val id: String, var text: String, var done: Boolean)

/** A note. Productivity notes can be pinned to a day; developer notes are about Summit itself. */
class Note(id: String = UUID.randomUUID().toString()) : Synced(id) {
    var developer = false
    var title = ""
    var text = ""
    val items = ArrayList<CheckItem>()   // checklist inside the note
    var pin: LocalDate? = null           // productivity: shown on this day
    var type = "idea"                    // developer: "bug", "idea" or "change"
    var open = true                      // developer: Open or Done
    var created = System.currentTimeMillis()

    override fun json(): JSONObject {
        val a = JSONArray()
        for (c in items) a.put(JSONObject().put("id", c.id).put("text", c.text).put("done", c.done))
        return JSONObject()
            .put("id", id).put("developer", developer).put("title", title).put("text", text).put("items", a)
            .put("pin", pin?.toString() ?: "").put("type", type).put("open", open)
            .put("created", created).put("updated", updated)
    }

    companion object {
        fun from(j: JSONObject): Note {
            val n = Note(j.getString("id"))
            n.developer = j.optBoolean("developer", false)
            n.title = j.optString("title")
            n.text = j.optString("text")
            val a = j.optJSONArray("items")
            if (a != null) for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                n.items.add(CheckItem(c.optString("id", UUID.randomUUID().toString()), c.optString("text"), c.optBoolean("done")))
            }
            n.pin = j.dateOrNull("pin")
            n.type = j.optString("type", "idea").ifEmpty { "idea" }
            n.open = j.optBoolean("open", true)
            n.created = j.optLong("created", n.created)
            return n
        }
    }
}

class Exam(val id: String, var date: LocalDate, var time: Int, var title: String)

/** A course found in the phone calendar (e.g. "ECON 101"), with its colour and exams. */
class Course(id: String = UUID.randomUUID().toString()) : Synced(id) {
    var code = ""                        // "ECON 101"
    var color = Color.parseColor("#5B9DFF")
    var hidden = false                   // "not a course": not suggested again
    val exams = ArrayList<Exam>()
    val skipped = HashSet<String>()      // suggestions turned down ("study|exam|day", "review|day")

    override fun json(): JSONObject {
        val a = JSONArray()
        for (e in exams) a.put(JSONObject().put("id", e.id).put("date", e.date.toString()).put("time", e.time).put("title", e.title))
        return JSONObject()
            .put("id", id).put("code", code).put("color", color).put("hidden", hidden).put("exams", a)
            .put("skipped", JSONArray(skipped.sorted())).put("updated", updated)
    }

    companion object {
        fun from(j: JSONObject): Course {
            val c = Course(j.getString("id"))
            c.code = j.optString("code")
            c.color = j.optInt("color", c.color)
            c.hidden = j.optBoolean("hidden", false)
            val a = j.optJSONArray("exams")
            if (a != null) for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val d = e.dateOrNull("date") ?: continue
                c.exams.add(Exam(e.optString("id", UUID.randomUUID().toString()), d, e.optInt("time", -1), e.optString("title")))
            }
            c.skipped.addAll(stringSet(j.optJSONArray("skipped")))
            return c
        }
    }
}

/** An assignment split into work sessions. The sessions are ordinary tasks with origin "asg|<id>". */
class Assignment(id: String = UUID.randomUUID().toString()) : Synced(id) {
    var title = ""
    var courseId = ""
    var deadline: LocalDate = LocalDate.now().plusDays(7)
    var effort = 240                     // minutes in total
    var sessions = 4
    var created = System.currentTimeMillis()

    override fun json(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("course", courseId).put("deadline", deadline.toString())
        .put("effort", effort).put("sessions", sessions).put("created", created).put("updated", updated)

    companion object {
        fun from(j: JSONObject): Assignment {
            val a = Assignment(j.getString("id"))
            a.title = j.optString("title")
            a.courseId = j.optString("course")
            j.dateOrNull("deadline")?.let { a.deadline = it }
            a.effort = j.optInt("effort", 240)
            a.sessions = j.optInt("sessions", 4)
            a.created = j.optLong("created", a.created)
            return a
        }
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
