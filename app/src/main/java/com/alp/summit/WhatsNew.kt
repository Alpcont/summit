package com.alp.summit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** The version of Summit that is installed, e.g. "5.2". */
fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

/** Every Summit version and what changed in it, newest first. */
val CHANGELOG: List<Pair<String, List<String>>> = listOf(
    "5.2" to listOf(
        "Auto-fill my day: untimed tasks go into the free time between 09:00 and 22:00, with a preview and Undo",
        "Sort automatically: a day back to the normal order (hold menu and the day's ⋮ menu)",
        "Routines: save a day as a routine, apply one to any day, manage them in Settings",
        "More repeats: every N days, every N weeks, monthly on a date or on a weekday (\"last Friday\")",
        "Upcoming in the Calendar: the next 4 weeks of exams, assignments, homework and High tasks",
        "Voice add: the microphone in the quick-add bar and in the evening planning",
        "Share into Summit: text, links and pictures from any app become a task or a note",
        "Productivity notes get photos and \"line → task\"",
        "Evening reflection: two optional questions, kept as a note",
        "What's new (this page), with your developer notes under the version that solved them",
        "Rename a subtask by tapping its text in the task form"
    ),
    "5.1" to listOf(
        "Notes widget",
        "A separate theme for the widgets (Settings → Widgets)",
        "Exam chips sorted by days left"
    ),
    "5.0" to listOf(
        "Course notebooks: homework, lecture notes with photos, exam topics, any line to a task",
        "One focus engine: focus card, full screen, breaks, Do Not Disturb, app blocking, focus widget",
        "Rating after each session, the Focus chain, study time per course",
        "Exams get a start time; countdown chips follow the day you swipe to"
    ),
    "4.3" to listOf(
        "Fix: Chains One view was empty",
        "Fix: Copy for Claude bar too white in dark themes",
        "Several reminders per task, and custom ones"
    ),
    "4.2" to listOf(
        "Chains as flame cards, with badges, a month view and streak freeze",
        "Notes redesign with the Manrope font",
        "Tasks connected to habits",
        "Habit flames on the small widget"
    ),
    "4.1" to listOf(
        "Pinned notes and study blocks on the Today page",
        "Study blocks even when there is no free hour",
        "Grey theme"
    ),
    "4.0" to listOf(
        "Swipe between days on Today",
        "Small widget, light theme",
        "Notes tab and Chains tab",
        "Courses, exam countdown, study blocks, assignments, class reviews, Sunday review"
    ),
    "3.2" to listOf("Sync between phone and tablet switched on"),
    "3.1" to listOf("Day timeline with Start my day"),
    "3.0" to listOf("Phone calendar events next to tasks", "Sync between devices (Firebase)"),
    "2.0" to listOf("Home-screen widget", "Streaks and statistics", "Focus timer", "Smart quick-add", "Evening planning"),
    "1.2" to listOf("Esc to Today, swipe, drag, Undo, vibration, celebration"),
    "1.1" to listOf("Animations and smoother motion"),
    "1.0" to listOf("Tasks, calendar, morning review, reminders, categories")
)

/** Opens "What's new" once after an update. */
fun showWhatsNewOnce(a: Activity) {
    val p = prefs(a)
    val v = appVersion(a)
    if (v.isEmpty() || p.getString("seenVersion", "") == v) return
    val first = !p.contains("seenVersion") && Store.tasks.isEmpty()
    p.edit().putString("seenVersion", v).apply()
    if (!first) a.startActivity(Intent(a, WhatsNewActivity::class.java).putExtra("fresh", true))
}

/** Each version and what changed, with the developer notes marked Done in that version. */
class WhatsNewActivity : SheetActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = appVersion(this)
        setup(if (intent.getBooleanExtra("fresh", false)) "What's new in $current" else "What's new")
        for ((version, changes) in CHANGELOG) {
            val head = text(body, "Summit $version" + (if (version == current) "  ·  installed" else ""), 20f,
                if (version == current) R.color.accent else R.color.text_primary, 22)
            head.typeface = Fonts.manrope(this, 800)
            for (c in changes) text(body, "•  $c", 15f, R.color.text_primary, 6).setLineSpacing(dp(2).toFloat(), 1f)
            val mine = Store.notes.items.filter { it.developer && !it.open && it.doneIn == version }.sortedBy { it.created }
            if (mine.isNotEmpty()) {
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                box.background = rounded(getColor(R.color.surface), dp(14))
                box.setPadding(dp(14), dp(10), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(10)
                body.addView(box, lp)
                text(box, "YOUR NOTES SOLVED HERE", 11f, R.color.text_dim).letterSpacing = 0.12f
                for (n in mine) {
                    val t = text(box, typeName(n.type) + ":  " + n.title.ifBlank { n.text.lineSequence().firstOrNull() ?: "" }, 14.5f, R.color.text_primary, 6)
                    t.setOnClickListener { openNote(n) }
                }
            }
        }
        val older = Store.notes.items.filter { it.developer && !it.open && it.doneIn.isEmpty() }
        if (older.isNotEmpty()) text(body, plural(older.size, "developer note") + " marked Done before 5.2 (no version recorded).",
            13f, R.color.text_dim, 22)
        action.visibility = android.view.View.VISIBLE
        action.text = "Close"
        action.setOnClickListener { finish() }
    }
}
