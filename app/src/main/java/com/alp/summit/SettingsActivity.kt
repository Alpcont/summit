package com.alp.summit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/** Morning nudge, categories and reminder help. */
class SettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Look.wrap(newBase))
    }

    private lateinit var body: LinearLayout
    private val palette = listOf(
        "#5B9DFF", "#3DDC97", "#B692F6", "#38C6D9", "#F5C451",
        "#F283B6", "#FF6B6B", "#FF8552", "#A3E635", "#9AA5B1"
    ).map { Color.parseColor(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetMotion()
        Store.load(this)
        setContentView(R.layout.activity_simple)
        fitSystem(findViewById(R.id.root))
        body = findViewById(R.id.simple_body)
        centerColumn(findViewById(R.id.root), body)
        findViewById<TextView>(R.id.simple_title).text = "Settings"
        findViewById<View>(R.id.simple_close).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val p = prefs(this)

        label("Appearance")
        val theme = Look.theme(this)
        row("Theme", theme.replaceFirstChar { it.uppercase() }, getColor(R.color.accent)) {
            val next = when (theme) {
                "dark" -> "grey"
                "grey" -> "light"
                else -> "dark"
            }
            p.edit().putString("theme", next).apply()
            TodayWidget.refresh(this)    // the widgets follow the theme too
            recreate()
        }
        note("Chosen on each device. Tap to switch: Dark → Grey → Light.")

        label("Morning")
        val nudge = p.getBoolean("nudge", true)
        row("Morning nudge", if (nudge) "On" else "Off", if (nudge) getColor(R.color.accent) else 0) {
            p.edit().putBoolean("nudge", !nudge).apply()
            Reminders.schedule(this)
            render()
        }
        val minutes = p.getInt("nudgeTime", 480)
        row("Time", fmtTime(minutes), 0) {
            TimePickerDialog(this, R.style.AppDialog, { _, h, m ->
                p.edit().putInt("nudgeTime", h * 60 + m).apply()
                Reminders.schedule(this)
                render()
            }, minutes / 60, minutes % 60, true).show()
        }
        note("A notification with your day, and the review of unfinished tasks. The review opens by itself from this time on.")

        label("Evening")
        val evening = p.getBoolean("evening", true)
        row("Evening planning", if (evening) "On" else "Off", if (evening) getColor(R.color.accent) else 0) {
            p.edit().putBoolean("evening", !evening).apply()
            Reminders.schedule(this)
            render()
        }
        val eveningMinutes = p.getInt("eveningTime", 21 * 60 + 30)
        row("Time", fmtTime(eveningMinutes), 0) {
            TimePickerDialog(this, R.style.AppDialog, { _, h, m ->
                p.edit().putInt("eveningTime", h * 60 + m).apply()
                Reminders.schedule(this)
                render()
            }, eveningMinutes / 60, eveningMinutes % 60, true).show()
        }
        row("Plan tomorrow now", "›", 0) { startActivity(Intent(this, PlanActivity::class.java)) }
        note("A notification in the evening that opens a short screen to set up tomorrow.")

        label("Focus")
        val goal = Focus.goal(this)
        row("Daily focus goal", fmtDuration(goal), getColor(R.color.accent)) {
            pickMinutes("Daily focus goal (minutes)", goal, 15, 16 * 60) { p.edit().putInt("focusGoal", it).apply() }
        }
        val breaks = p.getBoolean("breaksOn", true)
        row("Breaks", if (breaks) "On" else "Off", if (breaks) getColor(R.color.accent) else 0) {
            p.edit().putBoolean("breaksOn", !breaks).apply()
            render()
        }
        if (breaks) {
            row("Focus before a break", p.getInt("breakEvery", 50).toString() + " min", 0) {
                pickMinutes("Minutes of focus before a break", p.getInt("breakEvery", 50), 10, 180) { p.edit().putInt("breakEvery", it).apply() }
            }
            row("Break length", p.getInt("breakLen", 10).toString() + " min", 0) {
                pickMinutes("Break length (minutes)", p.getInt("breakLen", 10), 1, 60) { p.edit().putInt("breakLen", it).apply() }
            }
        }
        note("The focus chain in Chains links itself on days you reach the goal. During a break Summit reminds you to walk.")
        val dnd = p.getBoolean("focusDnd", true)
        val dndOk = Focus.dndAllowed(this)
        row("Do Not Disturb during focus", if (!dnd) "Off" else if (dndOk) "On" else "Needs access",
            if (!dnd) 0 else if (dndOk) getColor(R.color.accent) else getColor(R.color.danger)) {
            if (dnd && !dndOk) startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            else {
                p.edit().putBoolean("focusDnd", !dnd).apply()
                render()
            }
        }
        if (dnd && !dndOk) note("Tap, find Summit in the list and switch it on. Summit turns Do Not Disturb on when a session starts and sets it back after.")
        val blocking = Blocking.enabled(this)
        row("App blocking", if (blocking) "On" else "Off", if (blocking) getColor(R.color.accent) else getColor(R.color.danger)) {
            Blocking.guide(this)
        }
        row("Allowed apps during focus", "›", 0) { startActivity(Intent(this, AllowedAppsActivity::class.java)) }
        note(if (blocking) "During focus every other app shows Summit's block screen until the timer ends or you stop the session."
            else "Off: apps are not blocked. Tap App blocking for the steps to switch on Summit in Accessibility settings.")

        label("Chains")
        val habitRemind = p.getBoolean("habitRemind", true)
        row("Habit reminder", if (habitRemind) "On" else "Off", if (habitRemind) getColor(R.color.accent) else 0) {
            p.edit().putBoolean("habitRemind", !habitRemind).apply()
            Reminders.schedule(this)
            render()
        }
        note("A notification in the evening if a habit planned for today is not linked yet. Each habit has its own time (Chains → hold a habit → Edit).")

        label("Phone calendar")
        val events = PhoneCalendar.enabled(this)
        row("Show calendar events", if (events) "On" else "Off", if (events) getColor(R.color.accent) else 0) {
            p.edit().putBoolean("showEvents", !events).apply()
            if (!events && !PhoneCalendar.allowed(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
            } else {
                render()
            }
        }
        note("Classes and appointments from the phone's calendar appear above your tasks. Summit only reads them. Courses (cap icon on Today) are found here too.")

        label("Sync")
        if (!Sync.configured(this)) {
            note("Sync between phone and tablet is not set up yet. It switches on once the Firebase file is added to the project.")
        } else {
            val account = Sync.account(this)
            if (account == null) {
                row("Sign in with Google", "›", getColor(R.color.accent)) {
                    Sync.signIn(this) { problem ->
                        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                        render()
                    }
                }
                note("Sign in with the same Google account on phone and tablet to keep them the same.")
            } else {
                row(account, "On", getColor(R.color.accent)) {}
                row("Sign out on this device", "›", 0) {
                    Sync.signOut(this)
                    render()
                }
                note(
                    "Tasks, categories, focus time, habits, notes, courses and assignments are kept the same on every device " +
                        "signed in with this account. Theme and reminder times stay per device." +
                        (if (Sync.lastError.isNotEmpty()) " Last problem: " + Sync.lastError else "")
                )
            }
        }

        label("Categories")
        for (c in Store.categories) row(c.name, "●", Look.tone(c.color)) { editCategory(c) }
        row("Add a category", "+", getColor(R.color.accent)) { editCategory(null) }

        label("Reminders")
        if (!Reminders.canNotify(this)) {
            row("Allow notifications", "Off", getColor(R.color.danger)) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            note("If nothing happens, allow notifications for Summit in the phone's app settings below.")
        }
        row("Open phone settings for Summit", "›", 0) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        note("Samsung may delay reminders to save battery. If they arrive late: Battery → Unrestricted.")

        val about = TextView(this)
        about.text = "Summit · made for Alp"
        about.textSize = 13f
        about.gravity = Gravity.CENTER
        about.setTextColor(getColor(R.color.text_dim))
        about.setPadding(0, dp(36), 0, 0)
        body.addView(about)
    }

    /** A number of minutes, typed in. */
    private fun pickMinutes(title: String, current: Int, min: Int, max: Int, save: (Int) -> Unit) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(current.toString())
        input.setSelection(input.text.length)
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in min..max) {
                    save(n)
                    Reminders.schedule(this)
                    TodayWidget.refresh(this)
                }
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ---------- Building blocks ----------

    private fun label(text: String) {
        val tv = TextView(this)
        tv.text = text.uppercase()
        tv.textSize = 11f
        tv.letterSpacing = 0.14f
        tv.setTextColor(getColor(R.color.text_dim))
        tv.setPadding(0, dp(26), 0, dp(8))
        body.addView(tv)
    }

    private fun note(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setLineSpacing(dp(2).toFloat(), 1f)
        tv.setTextColor(getColor(R.color.text_dim))
        tv.setPadding(dp(2), dp(8), dp(2), 0)
        body.addView(tv)
    }

    private fun row(title: String, value: String, valueColor: Int, onClick: () -> Unit) {
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
        v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        v.setTextColor(if (valueColor != 0) valueColor else getColor(R.color.text_secondary))
        row.addView(v)
        row.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        body.addView(row, lp)
    }

    /** Name and colour of a category; `c == null` creates a new one. */
    private fun editCategory(c: Category?) {
        var color = c?.color ?: palette.first { col -> Store.categories.none { it.color == col } || col == palette.last() }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val name = EditText(this)
        name.hint = "Name"
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.maxLines = 1
        name.setText(c?.name ?: "")
        box.addView(name)
        val colors = FlowLayout(this)
        colors.setPadding(0, dp(14), 0, dp(4))
        box.addView(colors)
        fun paint() {
            colors.removeAllViews()
            for (col in palette) {
                val dot = View(this)
                dot.background =
                    if (col == color) rounded(col, dp(18), getColor(R.color.text_primary), dp(3))
                    else rounded(col, dp(18))
                dot.setOnClickListener {
                    color = col
                    paint()
                }
                colors.addView(dot, ViewGroup.LayoutParams(dp(36), dp(36)))
            }
        }
        paint()

        val b = AlertDialog.Builder(this)
            .setTitle(if (c == null) "New category" else "Edit category")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = name.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (c == null) Store.categories.add(Category(UUID.randomUUID().toString(), text, color))
                    else {
                        c.name = text
                        c.color = color
                    }
                    Store.save()
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
        if (c != null) b.setNeutralButton("Delete") { _, _ ->
            // Tasks keep existing; they just lose this category.
            for (t in Store.tasks) if (t.categoryId == c.id) t.categoryId = ""
            Store.categories.remove(c)
            Store.save()
            render()
        }
        b.show()
    }

    /** Esc on a keyboard goes back to the main screen. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
