package com.alp.summit

import android.Manifest
import android.app.Activity
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
import java.util.UUID

/** Morning nudge, categories and reminder help. */
class SettingsActivity : Activity() {
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

        label("Categories")
        for (c in Store.categories) row(c.name, "●", c.color) { editCategory(c) }
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
