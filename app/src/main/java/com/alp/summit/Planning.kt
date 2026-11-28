package com.alp.summit

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// ---------- Auto-fill my day ----------

/** One untimed task given a place in the day. */
class Placement(val task: Task, val time: Int, val length: Int)

object Planner {
    private const val FROM = 9 * 60
    private const val TO = 22 * 60

    /**
     * Places the day's open tasks without a time into the free gaps between calendar events and
     * timed tasks, 09:00–22:00 (from now on, today). High priority first, then tasks for a course
     * with an exam within 7 days. No duration = 30 min. Repeating tasks are left alone (a time
     * would change every day of the series). Returns (placed, did not fit).
     */
    fun autofill(ctx: android.content.Context, d: LocalDate, today: LocalDate): Pair<List<Placement>, List<Task>> {
        val open = Store.on(d).filter { !it.isDone(d) && it.time < 0 && !it.repeating }
        val busy = ArrayList<Pair<Int, Int>>()
        for (t in Store.on(d)) if (t.time >= 0) busy.add(Pair(t.time, t.time + maxOf(t.duration, 30)))
        for (e in PhoneCalendar.on(ctx, d)) if (!e.allDay) busy.add(Pair(e.start, e.end))
        val now = LocalTime.now()
        val from = if (d == today) maxOf(FROM, (now.hour * 60 + now.minute + 14) / 15 * 15) else FROM
        fun examSoon(t: Task): Boolean {
            val c = Store.courseOf(t) ?: return false
            return c.exams.any { !it.date.isBefore(d) && ChronoUnit.DAYS.between(d, it.date) <= 7 }
        }
        val order = open.sortedWith(compareBy<Task>({ if (it.priority == 3) 0 else 1 }, { if (examSoon(it)) 0 else 1 },
            { -it.priority }, { if (it.rank >= 0) it.rank else 999.0 }, { it.created }))
        val placed = ArrayList<Placement>()
        val left = ArrayList<Task>()
        for (t in order) {
            val len = if (t.duration > 0) t.duration else 30
            var at = (from + 4) / 5 * 5
            var slot = -1
            while (at + len <= TO) {
                if (busy.none { it.first < at + len && it.second > at }) {
                    slot = at
                    break
                }
                at += 5
            }
            if (slot < 0) left.add(t) else {
                placed.add(Placement(t, slot, len))
                busy.add(Pair(slot, slot + len))
            }
        }
        return Pair(placed.sortedBy { it.time }, left)
    }

    /** Shows what auto-fill would do; `accept` runs only after "Accept". */
    fun preview(a: Activity, d: LocalDate, today: LocalDate, accept: (List<Placement>) -> Unit) {
        val (placed, left) = autofill(a, d, today)
        val repeating = Store.on(d).count { !it.isDone(d) && it.time < 0 && it.repeating }
        if (placed.isEmpty() && left.isEmpty()) {
            android.widget.Toast.makeText(a, "Every open task of " + relDayInline(d, today) + " already has a time.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val sb = StringBuilder()
        for (p in placed) sb.append(fmtTime(p.time)).append("–").append(fmtTime(p.time + p.length)).append("   ").append(p.task.title).append("\n")
        if (left.isNotEmpty()) sb.append("\nDoes not fit between 09:00 and 22:00 (stays without a time):\n")
            .append(left.joinToString("\n") { "•  " + it.title }).append("\n")
        if (repeating > 0) sb.append("\n" + plural(repeating, "repeating task") + " left as is (a time would change every day it repeats).")
        AlertDialog.Builder(a)
            .setTitle("Auto-fill " + relDayInline(d, today))
            .setMessage(sb.toString().trim())
            .setPositiveButton(if (placed.isEmpty()) "OK" else "Accept") { _, _ -> if (placed.isNotEmpty()) accept(placed) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Puts tasks a day was dragged into back to the normal order (time, then priority). Returns what changed. */
    fun sortTasks(d: LocalDate): List<Pair<Task, Double>> {
        val out = ArrayList<Pair<Task, Double>>()
        for (t in Store.on(d)) if (t.rank >= 0) {
            out.add(Pair(t, t.rank))
            t.rank = -1.0
            t.updated = System.currentTimeMillis()
        }
        return out
    }

    fun hasOwnOrder(d: LocalDate): Boolean = Store.on(d).any { it.rank >= 0 }
}

// ---------- Routines ----------

/** A task inside a routine. */
class RoutineItem(
    var title: String, var time: Int = -1, var duration: Int = 0, var categoryId: String = "", var priority: Int = 2,
    val subtasks: ArrayList<String> = ArrayList(), val reminds: ArrayList<Int> = ArrayList()
) {
    fun json(): JSONObject = JSONObject().put("title", title).put("time", time).put("duration", duration)
        .put("category", categoryId).put("priority", priority).put("subtasks", JSONArray(subtasks)).put("reminds", JSONArray(reminds))

    /** A new ordinary task on day `d`. */
    fun toTask(d: LocalDate): Task {
        val t = Task()
        t.title = title
        t.date = d
        t.time = time
        t.duration = duration
        t.categoryId = categoryId
        t.priority = priority
        for (s in subtasks) t.subtasks.add(Subtask(UUID.randomUUID().toString(), s))
        if (reminds.isEmpty()) t.remind = -1 else {
            val sorted = reminds.sorted()
            t.remind = sorted.first()
            t.moreReminds.addAll(sorted.drop(1))
        }
        return t
    }

    companion object {
        fun from(j: JSONObject): RoutineItem {
            val r = RoutineItem(j.optString("title"), j.optInt("time", -1), j.optInt("duration", 0), j.optString("category"), j.optInt("priority", 2))
            j.optJSONArray("subtasks")?.let { a -> for (i in 0 until a.length()) r.subtasks.add(a.getString(i)) }
            j.optJSONArray("reminds")?.let { a -> for (i in 0 until a.length()) r.reminds.add(a.optInt(i)) }
            return r
        }

        fun of(t: Task): RoutineItem {
            val r = RoutineItem(t.title, t.time, t.duration, t.categoryId, t.priority)
            r.subtasks.addAll(t.subtasks.map { it.title })
            r.reminds.addAll(t.reminders())
            return r
        }
    }
}

/** A named, saved set of tasks, e.g. "Morning routine". Applied to a day, it makes ordinary tasks. */
class Routine(id: String = UUID.randomUUID().toString()) : Synced(id) {
    var name = ""
    val items = ArrayList<RoutineItem>()
    var created = System.currentTimeMillis()

    override fun json(): JSONObject {
        val a = JSONArray()
        for (it in items) a.put(it.json())
        return JSONObject().put("id", id).put("name", name).put("items", a).put("created", created).put("updated", updated)
    }

    /** Adds the routine's tasks to day `d`; returns them (for Undo). */
    fun apply(d: LocalDate): List<Task> {
        val made = items.map { it.toTask(d) }
        Store.tasks.addAll(made)
        return made
    }

    companion object {
        fun from(j: JSONObject): Routine {
            val r = Routine(j.getString("id"))
            r.name = j.optString("name")
            j.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) r.items.add(RoutineItem.from(a.getJSONObject(i))) }
            r.created = j.optLong("created", r.created)
            return r
        }
    }
}

/** Asks for a name and saves the tasks of day `d` as a routine. */
fun Activity.saveDayAsRoutine(d: LocalDate) {
    val tasks = Store.on(d)
    if (tasks.isEmpty()) {
        android.widget.Toast.makeText(this, "No tasks on this day to save.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val input = EditText(this)
    input.hint = "Name, e.g. Gym day"
    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    val box = LinearLayout(this)
    box.setPadding(dp(24), dp(8), dp(24), 0)
    box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    AlertDialog.Builder(this).setTitle("Save " + plural(tasks.size, "task") + " as a routine").setView(box)
        .setPositiveButton("Save") { _, _ ->
            val r = Routine()
            r.name = input.text.toString().trim().ifEmpty { weekdayName(d) + " routine" }
            for (t in tasks) r.items.add(RoutineItem.of(t))
            Store.routines.add(r)
            Store.save()
            android.widget.Toast.makeText(this, "Saved \"${r.name}\". Apply it from any day's menu.", android.widget.Toast.LENGTH_LONG).show()
        }
        .setNegativeButton("Cancel", null).show()
}

/** Lets the user pick a routine and applies it to day `d` with one tap. `applied` gets the new tasks (for Undo). */
fun Activity.pickRoutine(d: LocalDate, applied: (Routine, List<Task>) -> Unit) {
    val list = Store.routines.items.sortedBy { it.name.lowercase() }
    if (list.isEmpty()) {
        AlertDialog.Builder(this).setMessage("No routines yet. Save a day as a routine from its menu, or make one in Settings → Routines.")
            .setPositiveButton("New routine") { _, _ -> startActivity(Intent(this, RoutineActivity::class.java)) }
            .setNegativeButton("Close", null).show()
        return
    }
    AlertDialog.Builder(this).setTitle("Apply a routine to " + relDayInline(d, LocalDate.now()))
        .setItems(list.map { it.name + "  (" + plural(it.items.size, "task") + ")" }.toTypedArray()) { _, i ->
            val made = list[i].apply(d)
            Store.save()
            Reminders.schedule(this)
            applied(list[i], made)
        }.show()
}

/** Make or change a routine: its name and its tasks. */
class RoutineActivity : SheetActivity() {
    private lateinit var routine: Routine
    private var isNew = false
    private lateinit var nameInput: EditText
    private lateinit var itemsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Store.routines.get(intent.getStringExtra("id"))
        isNew = found == null
        routine = found ?: Routine()
        setup(if (isNew) "New routine" else "Routine")
        nameInput = EditText(this)
        nameInput.hint = "Name, e.g. Morning routine"
        nameInput.textSize = 20f
        nameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        nameInput.setTextColor(getColor(R.color.text_primary))
        nameInput.setHintTextColor(getColor(R.color.text_dim))
        nameInput.background = null
        nameInput.setText(routine.name)
        body.addView(nameInput)
        label(body, "Tasks")
        itemsBox = LinearLayout(this)
        itemsBox.orientation = LinearLayout.VERTICAL
        body.addView(itemsBox)
        val add = chip("+ Add a task", true)
        add.setOnClickListener { editItem(null) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        body.addView(add, lp)
        if (!isNew) {
            val del = text(body, "Delete routine", 15f, R.color.danger, 26)
            del.setPadding(0, dp(26), 0, dp(10))
            del.setOnClickListener {
                AlertDialog.Builder(this).setMessage("Delete \"${routine.name}\"? Tasks it already made stay.")
                    .setPositiveButton("Delete") { _, _ ->
                        Store.routines.get(routine.id)?.let { Store.routines.remove(it) }
                        Store.save()
                        finish()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        action.visibility = View.VISIBLE
        action.text = "Save"
        action.setOnClickListener {
            routine.name = nameInput.text.toString().trim().ifEmpty { "Routine" }
            routine.touch()
            if (Store.routines.get(routine.id) == null) Store.routines.add(routine)
            Store.save()
            finish()
        }
        render()
    }

    private fun render() {
        itemsBox.removeAllViews()
        if (routine.items.isEmpty()) text(itemsBox, "No tasks yet.", 14f, R.color.text_dim)
        for (it in routine.items.sortedWith(compareBy({ if (it.time >= 0) it.time else 9999 }, { -it.priority }))) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = rippled(rounded(getColor(R.color.surface), dp(14)), getColor(R.color.text_primary))
            row.setPadding(dp(14), dp(10), dp(14), dp(10))
            text(row, it.title, 15.5f, R.color.text_primary)
            val parts = ArrayList<String>()
            if (it.time >= 0) parts.add(fmtTime(it.time))
            if (it.duration > 0) parts.add(fmtDuration(it.duration))
            if (it.priority == 3) parts.add("High") else if (it.priority == 1) parts.add("Low")
            Store.category(it.categoryId)?.let { c -> parts.add(c.name) }
            if (it.subtasks.isNotEmpty()) parts.add(plural(it.subtasks.size, "subtask"))
            if (it.reminds.isNotEmpty()) parts.add(plural(it.reminds.size, "reminder"))
            if (parts.isNotEmpty()) text(row, parts.joinToString("  ·  "), 13f, R.color.text_secondary, 2)
            val item = it
            row.setOnClickListener { editItem(item) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            itemsBox.addView(row, lp)
        }
    }

    /** One task of the routine: title, time, duration, priority, category, subtasks, reminders. */
    private fun editItem(item: RoutineItem?) {
        val work = RoutineItem(item?.title ?: "", item?.time ?: -1, item?.duration ?: 0, item?.categoryId ?: "", item?.priority ?: 2)
        item?.let { work.subtasks.addAll(it.subtasks); work.reminds.addAll(it.reminds) }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(8), dp(22), 0)
        val title = EditText(this)
        title.hint = "Task"
        title.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        title.setText(work.title)
        box.addView(title)
        val chips = FlowLayout(this)
        chips.setPadding(0, dp(10), 0, 0)
        box.addView(chips)
        val subs = EditText(this)
        subs.hint = "Subtasks, one per line"
        subs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        subs.setText(work.subtasks.joinToString("\n"))
        box.addView(subs)
        fun paint() {
            chips.removeAllViews()
            fun c(label: String, on: Boolean, tint: Int = getColor(R.color.accent), act: () -> Unit) {
                val v = chip(label, on, tint)
                v.setOnClickListener {
                    act()
                    paint()
                }
                chips.addView(v)
            }
            c(if (work.time >= 0) fmtTime(work.time) else "Add a time", work.time >= 0) {
                val start = if (work.time >= 0) work.time else 8 * 60
                TimePickerDialog(this, R.style.AppDialog, { _, h, m ->
                    work.time = h * 60 + m
                    paint()
                }, start / 60, start % 60, true).show()
            }
            if (work.time >= 0) c("No time", false) { work.time = -1 }
            for (m in intArrayOf(15, 30, 45, 60, 90)) c(fmtDuration(m), work.duration == m) { work.duration = if (work.duration == m) 0 else m }
            c("Low", work.priority == 1) { work.priority = 1 }
            c("Medium", work.priority == 2) { work.priority = 2 }
            c("High", work.priority == 3, getColor(R.color.danger)) { work.priority = 3 }
            for (cat in Store.categories) c(cat.name, work.categoryId == cat.id, Look.tone(cat.color)) {
                work.categoryId = if (work.categoryId == cat.id) "" else cat.id
            }
            if (work.time >= 0) {
                c("No reminder", work.reminds.isEmpty()) { work.reminds.clear() }
                for (m in intArrayOf(0, 10, 30, 60)) c(if (m == 0) "Remind at the time" else "$m min before", m in work.reminds) {
                    if (!work.reminds.remove(m)) work.reminds.add(m)
                }
            }
        }
        paint()
        val b = AlertDialog.Builder(this).setTitle(if (item == null) "Add a task" else "Task").setView(android.widget.ScrollView(this).also { it.addView(box) })
            .setPositiveButton("Save") { _, _ ->
                work.title = title.text.toString().trim()
                if (work.title.isEmpty()) return@setPositiveButton
                work.subtasks.clear()
                work.subtasks.addAll(subs.text.toString().lines().mapNotNull { noteLine(it) })
                if (item == null) routine.items.add(work) else {
                    item.title = work.title
                    item.time = work.time
                    item.duration = work.duration
                    item.priority = work.priority
                    item.categoryId = work.categoryId
                    item.subtasks.clear(); item.subtasks.addAll(work.subtasks)
                    item.reminds.clear(); item.reminds.addAll(work.reminds)
                }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (item != null) b.setNeutralButton("Remove") { _, _ ->
            routine.items.remove(item)
            render()
        }
        b.show()
    }
}

// ---------- Upcoming: the next 4 weeks ----------

/** Something coming up: an exam, an assignment session or deadline, homework, a High task. */
class Upcoming(val date: LocalDate, val time: Int, val title: String, val sub: String, val color: Int, val open: (Activity) -> Unit)

fun upcomingItems(ctx: android.content.Context, today: LocalDate): List<Upcoming> {
    val end = today.plusDays(27)
    val out = ArrayList<Upcoming>()
    fun inRange(d: LocalDate) = !d.isBefore(today) && !d.isAfter(end)
    // Exams, coloured by how close they are.
    for (c in Courses.active()) for (e in c.exams) if (inRange(e.date)) {
        val n = ChronoUnit.DAYS.between(today, e.date)
        val color = when {
            n > 7 -> Look.tone(c.color)
            n > 2 -> ctx.getColor(R.color.accent)
            else -> ctx.getColor(R.color.danger)
        }
        out.add(Upcoming(e.date, e.time, c.code + " " + e.title.ifBlank { "exam" }, "Exam  ·  " + Courses.countdown(e.date, today), color) {
            it.startActivity(Intent(it, CoursesActivity::class.java))
        })
    }
    // Assignments: deadlines and sessions.
    for (a in Store.assignments) {
        val course = Store.courses.get(a.courseId)
        val color = course?.let { Look.tone(it.color) } ?: ctx.getColor(R.color.accent)
        if (inRange(a.deadline)) out.add(Upcoming(a.deadline, -1, a.title, "Assignment due" + (course?.let { "  ·  " + it.code } ?: ""), color) {
            it.startActivity(Intent(it, AssignmentActivity::class.java).putExtra("id", a.id))
        })
    }
    // Homework due dates from course notebooks.
    for (n in Store.notes.items) if (n.kind == "homework") {
        val d = n.due ?: continue
        if (!inRange(d)) continue
        val course = Store.courses.get(n.courseId)
        out.add(Upcoming(d, -1, n.title.ifBlank { "Homework" }, "Homework due" + (course?.let { "  ·  " + it.code } ?: ""),
            course?.let { Look.tone(it.color) } ?: ctx.getColor(R.color.accent)) { it.openNote(n) })
    }
    // Assignment sessions and High tasks (a repeating High task only once, on its next day).
    for (t in Store.tasks) {
        val isSession = t.origin.startsWith("asg|")
        if (!isSession && t.priority != 3) continue
        val day: LocalDate? = if (!t.repeating) t.date?.takeIf { inRange(it) && !t.done } else {
            var d = today
            var found: LocalDate? = null
            while (!d.isAfter(end)) {
                if (t.occursOn(d) && !t.isDone(d)) {
                    found = d
                    break
                }
                d = d.plusDays(1)
            }
            found
        }
        if (day == null) continue
        out.add(Upcoming(day, t.time, t.title, (if (isSession) "Assignment session" else "High priority") +
            (if (t.repeating) "  ·  " + t.repeatText() else ""),
            Store.colorOf(t) ?: ctx.getColor(if (isSession) R.color.accent else R.color.danger)) {
            it.startActivity(Intent(it, EditTaskActivity::class.java).putExtra("id", t.id).putExtra("date", day.toString()))
        })
    }
    return out.sortedWith(compareBy({ it.date }, { if (it.time >= 0) it.time else 9999 }))
}

/** The Upcoming page under the calendar header: grouped by week. */
fun Activity.renderUpcoming(list: LinearLayout, today: LocalDate) {
    val items = upcomingItems(this, today)
    if (items.isEmpty()) {
        val none = TextView(this)
        none.text = "Nothing coming up in the next 4 weeks: no exams, assignments, homework or High tasks."
        none.textSize = 15f
        none.setTextColor(getColor(R.color.text_dim))
        none.setPadding(0, dp(20), 0, dp(10))
        list.addView(none)
        return
    }
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    var lastWeek = -1L
    for (u in items) {
        val week = ChronoUnit.WEEKS.between(monday, u.date.minusDays((u.date.dayOfWeek.value - 1).toLong()))
        if (week != lastWeek) {
            lastWeek = week
            val head = TextView(this)
            val wStart = monday.plusWeeks(week)
            head.text = (when (week) {
                0L -> "This week"
                1L -> "Next week"
                else -> "Week of " + dayMonth(wStart)
            }).uppercase()
            head.textSize = 11f
            head.letterSpacing = 0.14f
            head.setTextColor(getColor(R.color.text_dim))
            head.setPadding(0, dp(22), 0, dp(6))
            list.addView(head)
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rippled(rounded(getColor(R.color.surface), dp(14)), getColor(R.color.text_primary))
        row.setPadding(dp(12), dp(10), dp(14), dp(10))
        val when_ = LinearLayout(this)
        when_.orientation = LinearLayout.VERTICAL
        when_.gravity = Gravity.CENTER_HORIZONTAL
        val dname = TextView(this)
        dname.text = shortDay(u.date).substringBefore(' ')
        dname.textSize = 11f
        dname.setTextColor(getColor(R.color.text_secondary))
        when_.addView(dname)
        val dnum = TextView(this)
        dnum.text = u.date.dayOfMonth.toString()
        dnum.textSize = 18f
        dnum.setTextColor(if (u.date == today) getColor(R.color.accent) else getColor(R.color.text_primary))
        when_.addView(dnum)
        row.addView(when_, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = View(this)
        bar.background = rounded(u.color, dp(2))
        val bl = LinearLayout.LayoutParams(dp(4), dp(34))
        bl.marginStart = dp(8)
        bl.marginEnd = dp(12)
        row.addView(bar, bl)
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = u.title
        title.textSize = 15.5f
        title.maxLines = 2
        title.setTextColor(getColor(R.color.text_primary))
        words.addView(title)
        val sub = TextView(this)
        sub.text = (if (u.time >= 0) fmtTime(u.time) + "  ·  " else "") + u.sub
        sub.textSize = 13f
        sub.setTextColor(u.color)
        words.addView(sub)
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { u.open(this) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        list.addView(row, lp)
    }
}
