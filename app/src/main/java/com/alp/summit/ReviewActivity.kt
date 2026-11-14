package com.alp.summit

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.util.Calendar

/**
 * Morning review: every task left unticked on an earlier day gets a decision:
 * do it today, move it to another day, or drop it.
 */
class ReviewActivity : Activity() {
    private class Choice(val missed: Missed) {
        var action = 0                 // 0 today, 1 another day, 2 drop
        var date: LocalDate? = null    // the day picked for "another day"
    }

    private val today: LocalDate = LocalDate.now()
    private val choices = ArrayList<Choice>()
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sheetMotion()
        Store.load(this)
        setContentView(R.layout.activity_simple)
        fitSystem(findViewById(R.id.root))
        body = findViewById(R.id.simple_body)
        centerColumn(findViewById(R.id.root), body)
        findViewById<TextView>(R.id.simple_title).text = "Morning review"
        findViewById<View>(R.id.simple_close).setOnClickListener { finish() }
        for (m in Store.missed(today)) choices.add(Choice(m))
        if (choices.isEmpty()) {
            finish()
            return
        }
        val apply = findViewById<TextView>(R.id.simple_action)
        apply.visibility = View.VISIBLE
        apply.text = "Apply"
        apply.setOnClickListener { apply() }
        render()
    }

    private fun render() {
        body.removeAllViews()
        val intro = TextView(this)
        intro.text = plural(choices.size, "task") + " left unticked. Choose what happens to each one."
        intro.textSize = 15f
        intro.setTextColor(getColor(R.color.text_secondary))
        intro.setPadding(0, 0, 0, dp(8))
        body.addView(intro)

        for (c in choices) {
            val t = c.missed.task
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = rounded(getColor(R.color.surface), dp(16))
            card.setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            body.addView(card, lp)

            val title = TextView(this)
            title.text = t.title
            title.textSize = 16f
            title.setTextColor(getColor(R.color.text_primary))
            card.addView(title)

            val from = TextView(this)
            val cat = Store.category(t.categoryId)
            from.text = "From " + relDayInline(c.missed.date, today) + (if (cat != null) "  ·  " + cat.name else "") +
                (if (t.repeating) "  ·  repeats" else "")
            from.textSize = 13f
            from.setTextColor(getColor(R.color.text_secondary))
            from.setPadding(0, dp(2), 0, dp(12))
            card.addView(from)

            val row = FlowLayout(this)
            card.addView(row)
            val doToday = chip("Today", c.action == 0)
            doToday.setOnClickListener {
                c.action = 0
                render()
            }
            row.addView(doToday)
            val picked = c.date
            val later = chip(if (c.action == 1 && picked != null) shortDay(picked) else "Another day", c.action == 1)
            later.setOnClickListener { pickDate(c) }
            row.addView(later)
            val drop = chip("Drop", c.action == 2, getColor(R.color.danger))
            drop.setOnClickListener {
                c.action = 2
                render()
            }
            row.addView(drop)
        }
    }

    private fun pickDate(c: Choice) {
        val start = c.date ?: today.plusDays(1)
        val dialog = DatePickerDialog(this, R.style.AppDialog, { _, y, m, d ->
            c.date = LocalDate.of(y, m + 1, d)
            c.action = 1
            render()
        }, start.year, start.monthValue - 1, start.dayOfMonth)
        dialog.datePicker.firstDayOfWeek = Calendar.MONDAY
        dialog.show()
    }

    private fun apply() {
        val now = System.currentTimeMillis()
        for (c in choices) {
            val t = c.missed.task
            val target = if (c.action == 1) c.date ?: today else today
            if (!t.repeating) {
                if (c.action == 2) Store.tasks.remove(t) else Store.move(t, target)
            } else {
                // A repeating task keeps its rhythm: the missed day leaves the series,
                // and "today" or "another day" adds a one-off copy there.
                t.skipDates.add(c.missed.date.toString())
                t.updated = now
                if (c.action != 2) Store.tasks.add(t.copyTo(target))
            }
        }
        Store.save()
        Reminders.schedule(this)
        finish()
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
