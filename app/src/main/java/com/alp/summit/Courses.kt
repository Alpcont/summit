package com.alp.summit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.UUID

/**
 * Course mode: courses come from the classes in the phone calendar ("ECON 101 Lecture"),
 * exams get a countdown, and Summit suggests study blocks, class reviews and assignment sessions.
 * Everything it suggests becomes an ordinary task once accepted (with `origin` saying what it was for).
 */
object Courses {
    val PALETTE = listOf(
        "#5B9DFF", "#3DDC97", "#B692F6", "#38C6D9", "#F5C451",
        "#F283B6", "#FF6B6B", "#FF8552", "#A3E635", "#9AA5B1"
    ).map { Color.parseColor(it) }

    // A course code at the start of an event title: "ECON 101", "PSYC100 Lecture", "MATH-102".
    private val CODE = Regex("^\\s*([A-Za-z]{2,5})\\s*-?\\s*(\\d{3,4}[A-Za-z]?)\\b")

    const val STUDY_DAYS = 5          // study blocks on each of the 5 days before an exam
    const val STUDY_MINUTES = 60      // one hour each
    private const val DAY_FROM = 9 * 60
    private const val DAY_TO = 21 * 60

    fun codeOf(title: String): String? =
        CODE.find(title)?.let { it.groupValues[1].uppercase() + " " + it.groupValues[2].uppercase() }

    fun byCode(code: String): Course? = Store.courses.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** Confirmed courses, by code. */
    fun active(): List<Course> = Store.courses.filter { !it.hidden }.sortedBy { it.code }

    /** Course codes in the phone calendar (2 weeks back, 4 weeks ahead) that are not confirmed or hidden yet. */
    fun found(ctx: android.content.Context, today: LocalDate): List<String> =
        PhoneCalendar.titles(ctx, today.minusDays(14), today.plusDays(28))
            .mapNotNull { codeOf(it) }.distinct().filter { byCode(it) == null }.sorted()

    fun upcomingExams(today: LocalDate): List<Pair<Course, Exam>> =
        active().flatMap { c -> c.exams.filter { !it.date.isBefore(today) }.map { Pair(c, it) } }
            .sortedBy { it.second.date }

    /** "today", "tomorrow", "in 5 days". */
    fun countdown(d: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, d)
        return when {
            n == 0L -> "today"
            n == 1L -> "tomorrow"
            n < 0L -> "${-n} days ago"
            else -> "in $n days"
        }
    }

    fun nextColor(): Int = PALETTE.firstOrNull { col -> Store.courses.none { it.color == col } } ?: PALETTE.first()

    // ---------- Suggestions ----------

    /** Something Summit offers to add as a task; one tap accepts or skips it. */
    class Suggestion(
        val course: Course, val key: String, val title: String, val date: LocalDate,
        val time: Int, val duration: Int, val origin: String, val why: String
    )

    /** Study blocks for day `d`: one hour on each of the 5 days before an exam, in a free gap between 09:00 and 21:00. */
    fun studyBlocks(ctx: android.content.Context, d: LocalDate, today: LocalDate): List<Suggestion> {
        if (d.isBefore(today)) return emptyList()
        val out = ArrayList<Suggestion>()
        var busy: MutableList<Pair<Int, Int>>? = null
        for ((c, e) in upcomingExams(today)) {
            val before = ChronoUnit.DAYS.between(d, e.date)
            if (before < 1 || before > STUDY_DAYS) continue
            val key = "study|${e.id}|$d"
            val origin = "study|${c.id}|${e.id}|$d"
            if (key in c.skipped || Store.tasks.any { it.origin == origin }) continue
            val taken = busy ?: busyTimes(ctx, d).also { busy = it }
            val now = LocalTime.now()
            val earliest = if (d == today) maxOf(DAY_FROM, (now.hour * 60 + now.minute + 14) / 15 * 15) else DAY_FROM
            val slot = freeSlot(taken, earliest, DAY_TO, STUDY_MINUTES) ?: continue
            taken.add(Pair(slot, slot + STUDY_MINUTES))
            val examName = e.title.ifBlank { "exam" }
            out.add(
                Suggestion(
                    c, key, "Study " + c.code, d, slot, STUDY_MINUTES, origin,
                    c.code + " " + examName + " " + countdown(e.date, d) + " (" + shortDay(e.date) + ")"
                )
            )
        }
        return out
    }

    /** One "review notes" task for each class held on `day`, to do the next day. */
    fun reviews(ctx: android.content.Context, day: LocalDate): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val seen = HashSet<String>()
        for (ev in PhoneCalendar.on(ctx, day)) {
            val c = byCode(codeOf(ev.title) ?: continue)?.takeIf { !it.hidden } ?: continue
            if (!seen.add(c.id)) continue
            val key = "review|$day"
            val origin = "review|${c.id}|$day"
            if (key in c.skipped || Store.tasks.any { it.origin == origin }) continue
            out.add(Suggestion(c, key, "Review " + c.code + " notes", day.plusDays(1), -1, 20, origin, "Class " + relDayInline(day, LocalDate.now())))
        }
        return out
    }

    /** Minutes of `d` already taken by timed tasks and calendar events. */
    private fun busyTimes(ctx: android.content.Context, d: LocalDate): MutableList<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (t in Store.on(d)) if (t.time >= 0) out.add(Pair(t.time, t.time + maxOf(t.duration, 30)))
        for (e in PhoneCalendar.on(ctx, d)) if (!e.allDay) out.add(Pair(e.start, e.end))
        return out
    }

    private fun freeSlot(busy: List<Pair<Int, Int>>, from: Int, to: Int, length: Int): Int? {
        var t = (from + 14) / 15 * 15
        while (t + length <= to) {
            if (busy.none { it.first < t + length && it.second > t }) return t
            t += 15
        }
        return null
    }

    fun accept(ctx: android.content.Context, s: Suggestion): Task {
        val t = Task()
        t.title = s.title
        t.date = s.date
        t.time = s.time
        t.duration = s.duration
        t.origin = s.origin
        Store.tasks.add(t)
        Store.save()
        Reminders.schedule(ctx)
        return t
    }

    fun skip(s: Suggestion) {
        s.course.skipped.add(s.key)
        s.course.touch()
        Store.save()
    }

    // ---------- Assignments ----------

    /** Spreads `n` sessions evenly over the picked days (several on one day only when there are more sessions than days). */
    fun spread(days: List<LocalDate>, n: Int): List<LocalDate> {
        val ds = days.sorted()
        if (ds.isEmpty() || n <= 0) return emptyList()
        if (n == 1) return listOf(ds[(ds.size - 1) / 2])
        if (n <= ds.size) return (0 until n).map { ds[Math.round(it * (ds.size - 1) / (n - 1).toFloat())] }
        return (0 until n).map { ds[it * ds.size / n] }
    }

    fun sessionsOf(a: Assignment): List<Task> =
        Store.tasks.filter { it.origin == "asg|" + a.id }.sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.created }))
}

// ---------- A small colour chooser shared by the course screens ----------

fun Activity.pickColor(title: String, current: Int, done: (Int) -> Unit) {
    var color = current
    val colors = FlowLayout(this)
    colors.setPadding(dp(24), dp(16), dp(24), dp(4))
    fun paint() {
        colors.removeAllViews()
        for (col in Courses.PALETTE) {
            val dot = View(this)
            val shown = Look.tone(col)
            dot.background = if (col == color) rounded(shown, dp(18), getColor(R.color.text_primary), dp(3)) else rounded(shown, dp(18))
            dot.setOnClickListener {
                color = col
                paint()
            }
            colors.addView(dot, ViewGroup.LayoutParams(dp(36), dp(36)))
        }
    }
    paint()
    AlertDialog.Builder(this).setTitle(title).setView(colors)
        .setPositiveButton("Save") { _, _ -> done(color) }
        .setNegativeButton("Cancel", null).show()
}

fun Activity.pickDay(start: LocalDate, done: (LocalDate) -> Unit) {
    val dialog = DatePickerDialog(this, R.style.AppDialog, { _, y, m, d -> done(LocalDate.of(y, m + 1, d)) },
        start.year, start.monthValue - 1, start.dayOfMonth)
    dialog.datePicker.firstDayOfWeek = Calendar.MONDAY
    dialog.show()
}

// ---------- Courses screen (cap button on Today) ----------

class CoursesActivity : SheetActivity() {
    private val today: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("Courses")
        action.visibility = View.VISIBLE
        action.text = "New assignment"
        action.setOnClickListener { startActivity(Intent(this, AssignmentActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        Sync.onRemoteChange = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        Sync.onRemoteChange = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun changed() {
        Store.save()
        Reminders.schedule(this)
        render()
    }

    private fun render() {
        body.removeAllViews()
        val p = prefs(this)

        // Classes in the phone calendar that look like courses.
        label(body, "From your calendar")
        if (!PhoneCalendar.enabled(this)) {
            text(body, "Summit finds your courses in the phone calendar (titles like \"ECON 101\"). " +
                "Turn on calendar events to let it look.", 14f, R.color.text_secondary)
            row(body, "Show calendar events", "Off", getColor(R.color.accent)) {
                p.edit().putBoolean("showEvents", true).apply()
                if (!PhoneCalendar.allowed(this)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2) else render()
            }
        } else {
            val found = Courses.found(this, today)
            if (found.isEmpty()) text(body, "No new course codes in the last 2 and next 4 weeks.", 14f, R.color.text_dim)
            for (code in found) {
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.CENTER_VERTICAL
                line.setPadding(0, dp(6), 0, dp(6))
                val name = TextView(this)
                name.text = code
                name.textSize = 16f
                name.setTextColor(getColor(R.color.text_primary))
                line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val add = chip("Add", true)
                add.setOnClickListener {
                    pickColor("Colour for $code", Courses.nextColor()) { col ->
                        val c = Course()
                        c.code = code
                        c.color = col
                        Store.courses.add(c)
                        changed()
                    }
                }
                line.addView(add)
                val no = chip("Not a course", false)
                no.setOnClickListener {
                    val c = Course()
                    c.code = code
                    c.hidden = true
                    Store.courses.add(c)
                    changed()
                }
                line.addView(no)
                (no.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                body.addView(line)
            }
        }

        label(body, "Your courses")
        val courses = Courses.active()
        if (courses.isEmpty()) text(body, "None yet. Add them from your calendar above, or by hand below.", 14f, R.color.text_dim)
        for (c in courses) courseCard(c)
        row(body, "Add a course by hand", "+", getColor(R.color.accent)) { addByHand() }

        val hidden = Store.courses.filter { it.hidden }
        if (hidden.isNotEmpty()) {
            text(body, "Marked as not a course (tap to undo):", 13f, R.color.text_dim, 14)
            val flow = FlowLayout(this)
            flow.setPadding(0, dp(8), 0, 0)
            for (c in hidden) {
                val ch = chip(c.code, false)
                ch.setOnClickListener {
                    Store.courses.remove(c)
                    changed()
                }
                flow.addView(ch)
            }
            body.addView(flow)
        }

        label(body, "Assignments")
        val list = Store.assignments.items.sortedBy { it.deadline }
        if (list.isEmpty()) text(body, "Split an assignment into work sessions with the button below.", 14f, R.color.text_dim)
        for (a in list) {
            val sessions = Courses.sessionsOf(a)
            val done = sessions.count { it.done }
            val course = Store.courses.get(a.courseId)
            val card = card(body)
            card.setOnClickListener { startActivity(Intent(this, AssignmentActivity::class.java).putExtra("id", a.id)) }
            val title = text(card, a.title, 16f, R.color.text_primary)
            if (course != null && !course.hidden) {
                title.text = course.code + "  ·  " + a.title
            }
            text(
                card, "Due " + shortDay(a.deadline) + ", " + Courses.countdown(a.deadline, today) + "  ·  " +
                    "$done of ${sessions.size} sessions done", 13f, R.color.text_secondary, 2
            )
            val bar = BarView(this)
            bar.fraction = if (sessions.isEmpty()) 0f else done.toFloat() / sessions.size
            course?.let { bar.color = Look.tone(it.color) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            card.addView(bar, lp)
        }
    }

    private fun courseCard(c: Course) {
        val card = card(body)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.background = rounded(Look.tone(c.color), dp(6))
        head.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)))
        val name = TextView(this)
        name.text = c.code
        name.textSize = 17f
        name.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        name.setTextColor(getColor(R.color.text_primary))
        name.setPadding(dp(10), 0, 0, 0)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)

        val exams = c.exams.sortedBy { it.date }
        for (e in exams) {
            val past = e.date.isBefore(today)
            val line = text(
                card, (e.title.ifBlank { "Exam" }) + "  ·  " + shortDay(e.date) + "  ·  " + Courses.countdown(e.date, today),
                15f, if (past) R.color.text_dim else R.color.text_primary, 8
            )
            line.setOnClickListener { editExam(c, e) }
        }
        if (exams.isEmpty()) text(card, "No exams yet.", 14f, R.color.text_dim, 6)

        val chips = FlowLayout(this)
        chips.setPadding(0, dp(12), 0, 0)
        val addExam = chip("Add exam", false, Look.tone(c.color))
        addExam.setOnClickListener { editExam(c, null) }
        chips.addView(addExam)
        val colour = chip("Colour", false)
        colour.setOnClickListener {
            pickColor("Colour for ${c.code}", c.color) { col ->
                c.color = col
                c.touch()
                changed()
            }
        }
        chips.addView(colour)
        val remove = chip("Remove", false, getColor(R.color.danger))
        remove.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Remove ${c.code} and its exams? Tasks you already accepted stay.")
                .setPositiveButton("Remove") { _, _ ->
                    Store.courses.remove(c)
                    changed()
                }
                .setNegativeButton("Cancel", null).show()
        }
        chips.addView(remove)
        card.addView(chips)
    }

    /** Add an exam (`e == null`) or change/delete one: pick the date, then name it. */
    private fun editExam(c: Course, e: Exam?) {
        pickDay(e?.date ?: today.plusDays(14)) { date ->
            val name = EditText(this)
            name.hint = "Midterm, final, quiz… (optional)"
            name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            name.setText(e?.title ?: "")
            val box = LinearLayout(this)
            box.setPadding(dp(24), dp(8), dp(24), 0)
            box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val b = AlertDialog.Builder(this)
                .setTitle(c.code + " exam on " + shortDay(date))
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    if (e == null) c.exams.add(Exam(UUID.randomUUID().toString(), date, -1, name.text.toString().trim()))
                    else {
                        e.date = date
                        e.title = name.text.toString().trim()
                    }
                    c.touch()
                    changed()
                }
                .setNegativeButton("Cancel", null)
            if (e != null) b.setNeutralButton("Delete exam") { _, _ ->
                c.exams.remove(e)
                c.touch()
                changed()
            }
            b.show()
        }
    }

    private fun addByHand() {
        val code = EditText(this)
        code.hint = "Course code, e.g. ECON 101"
        code.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this).setTitle("New course").setView(box)
            .setPositiveButton("Next") { _, _ ->
                val typed = code.text.toString().trim()
                if (typed.isEmpty()) return@setPositiveButton
                val normal = Courses.codeOf(typed) ?: typed.uppercase()
                Courses.byCode(normal)?.let { Store.courses.remove(it) }   // a hidden one becomes a course again
                pickColor("Colour for $normal", Courses.nextColor()) { col ->
                    val c = Course()
                    c.code = normal
                    c.color = col
                    Store.courses.add(c)
                    changed()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun card(into: LinearLayout): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rippled(rounded(getColor(R.color.surface), dp(16)), getColor(R.color.text_primary))
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        into.addView(card, lp)
        return card
    }
}

// ---------- Assignment breakdown ----------

/** A new assignment is split into sessions; an existing one shows its sessions (ordinary tasks, editable). */
class AssignmentActivity : SheetActivity() {
    private val today: LocalDate = LocalDate.now()
    private var existing: Assignment? = null
    private var courseId = ""
    private var deadline: LocalDate = today.plusDays(7)
    private var effort = 240
    private var sessions = 4
    private val days = HashSet<LocalDate>()
    private lateinit var titleInput: EditText
    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = Store.assignments.get(intent.getStringExtra("id"))
        setup(if (existing == null) "New assignment" else "Assignment")
        if (existing == null) {
            titleInput = EditText(this)
            titleInput.hint = "Assignment, e.g. Essay on game theory"
            titleInput.textSize = 18f
            titleInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            titleInput.setTextColor(getColor(R.color.text_primary))
            titleInput.setHintTextColor(getColor(R.color.text_dim))
            body.addView(titleInput)
            form = LinearLayout(this)
            form.orientation = LinearLayout.VERTICAL
            body.addView(form)
            pickDefaultDays()
            action.visibility = View.VISIBLE
            action.text = "Create sessions"
            action.setOnClickListener { create() }
            renderForm()
        }
    }

    override fun onResume() {
        super.onResume()
        if (existing != null) renderExisting()
    }

    /** Every day from today until the day before the deadline is picked at first. */
    private fun pickDefaultDays() {
        days.clear()
        var d = today
        while (d.isBefore(deadline)) {
            days.add(d)
            d = d.plusDays(1)
        }
        if (days.isEmpty()) days.add(deadline)
    }

    private fun renderForm() {
        form.removeAllViews()
        label(form, "Course")
        val courseRow = FlowLayout(this)
        fun pick(row: FlowLayout, text: String, on: Boolean, tint: Int = getColor(R.color.accent), act: () -> Unit) {
            val c = chip(text, on, tint)
            c.setOnClickListener {
                act()
                renderForm()
            }
            row.addView(c)
        }
        pick(courseRow, "None", courseId.isEmpty()) { courseId = "" }
        for (c in Courses.active()) pick(courseRow, c.code, courseId == c.id, Look.tone(c.color)) { courseId = c.id }
        form.addView(courseRow)

        label(form, "Deadline")
        val dl = FlowLayout(this)
        val dc = chip(shortDay(deadline) + "  ·  " + Courses.countdown(deadline, today), true)
        dc.setOnClickListener {
            pickDay(deadline) {
                deadline = if (it.isBefore(today)) today else it
                pickDefaultDays()
                renderForm()
            }
        }
        dl.addView(dc)
        form.addView(dl)

        label(form, "Total effort")
        val ef = FlowLayout(this)
        for (h in intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 15, 20)) pick(ef, "${h}h", effort == h * 60) { effort = h * 60 }
        form.addView(ef)

        label(form, "Sessions")
        val se = FlowLayout(this)
        for (n in 1..10) pick(se, n.toString(), sessions == n) { sessions = n }
        form.addView(se)

        label(form, "Days to work on it")
        val dayRow = FlowLayout(this)
        var d = today
        val last = maxOf(deadline, today)
        while (!d.isAfter(last) && d.isBefore(today.plusDays(60))) {
            val day = d
            pick(dayRow, shortDay(day).substringBeforeLast(' '), day in days) {
                if (!days.remove(day)) days.add(day)
            }
            d = d.plusDays(1)
        }
        form.addView(dayRow)

        // What will be created.
        val plan = Courses.spread(days.toList(), sessions)
        val each = maxOf(5, Math.round(effort / sessions.toFloat() / 5f) * 5)
        label(form, "Plan")
        if (plan.isEmpty()) text(form, "Pick at least one day.", 14f, R.color.danger)
        else text(
            form, plural(sessions, "session") + " of " + fmtDuration(each) + ": " +
                plan.joinToString(", ") { shortDay(it).substringBeforeLast(' ') } +
                ". You can change any of them afterwards like a normal task.", 14f, R.color.text_secondary
        )
    }

    private fun create() {
        val title = titleInput.text.toString().trim()
        val plan = Courses.spread(days.toList(), sessions)
        if (title.isEmpty() || plan.isEmpty()) {
            android.widget.Toast.makeText(this, if (title.isEmpty()) "Give the assignment a name." else "Pick at least one day.",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val a = Assignment()
        a.title = title
        a.courseId = courseId
        a.deadline = deadline
        a.effort = effort
        a.sessions = sessions
        Store.assignments.add(a)
        val each = maxOf(5, Math.round(effort / sessions.toFloat() / 5f) * 5)
        for ((i, day) in plan.withIndex()) {
            val t = Task()
            t.title = "$title (${i + 1}/$sessions)"
            t.date = day
            t.duration = each
            t.origin = "asg|" + a.id
            Store.tasks.add(t)
        }
        Store.save()
        Reminders.schedule(this)
        finish()
    }

    private fun renderExisting() {
        val a = Store.assignments.get(existing?.id) ?: run {
            finish()
            return
        }
        body.removeAllViews()
        val course = Store.courses.get(a.courseId)?.takeIf { !it.hidden }
        text(body, a.title, 24f, R.color.text_primary, 4)
        text(
            body, (if (course != null) course.code + "  ·  " else "") + "Due " + shortDay(a.deadline) + ", " +
                Courses.countdown(a.deadline, today) + "  ·  " + fmtDuration(a.effort) + " in total", 14f, R.color.text_secondary, 4
        )
        label(body, "Sessions")
        val list = Courses.sessionsOf(a)
        if (list.isEmpty()) text(body, "No sessions left.", 14f, R.color.text_dim)
        for (t in list) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(0, dp(4), 0, dp(4))
            val box = BoxView(this)
            box.color = Store.colorOf(t) ?: getColor(R.color.accent)
            box.setChecked(t.done, false)
            box.setOnClickListener {
                Store.setDone(t, t.date, !t.done)
                Store.save()
                Reminders.schedule(this)
                box.setChecked(t.done, true)
                box.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
            line.addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))
            val words = LinearLayout(this)
            words.orientation = LinearLayout.VERTICAL
            text(words, t.title, 15f, R.color.text_primary)
            text(words, (t.date?.let { shortDay(it) } ?: "Someday") + (if (t.duration > 0) "  ·  " + fmtDuration(t.duration) else ""),
                13f, R.color.text_secondary)
            line.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.setOnClickListener {
                val i = Intent(this, EditTaskActivity::class.java).putExtra("id", t.id)
                t.date?.let { i.putExtra("date", it.toString()) }
                startActivity(i)
            }
            body.addView(line)
        }
        text(body, "Tap a session to change its day, length or anything else.", 13f, R.color.text_dim, 10)

        action.visibility = View.VISIBLE
        action.text = "Delete assignment"
        action.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete \"${a.title}\"? Its open sessions are deleted too; ticked ones stay.")
                .setPositiveButton("Delete") { _, _ ->
                    Store.tasks.removeAll { it.origin == "asg|" + a.id && !it.done }
                    Store.assignments.remove(a)
                    Store.save()
                    Reminders.schedule(this)
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}

/** A tappable settings-style row, used on the smaller screens. */
fun Activity.row(into: LinearLayout, title: String, value: String, valueColor: Int, onClick: () -> Unit) {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.background = rippled(rounded(getColor(R.color.surface), dp(14)), getColor(R.color.text_primary))
    row.setPadding(dp(16), 0, dp(16), 0)
    row.minimumHeight = dp(52)
    val name = TextView(this)
    name.text = title
    name.textSize = 16f
    name.setTextColor(getColor(R.color.text_primary))
    row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val v = TextView(this)
    v.text = value
    v.textSize = 16f
    v.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    v.setTextColor(if (valueColor != 0) valueColor else getColor(R.color.text_secondary))
    row.addView(v)
    row.setOnClickListener { onClick() }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(6)
    into.addView(row, lp)
}
