# Summit

Alp's daily task planner for Android. Add the day's tasks, tick the box and the line is
crossed out; a small mountain on the main page lights up as the day gets done. Tasks can be
one-off, repeating, timed or parked in "Someday", and carry a colour category, priority,
subtasks, notes and an estimated duration. A calendar (month and week) shows how each day
went, a morning review asks what to do with anything left unfinished, and timed tasks send
reminders. Everything is stored on the device; the app uses no internet.

## Download
https://github.com/Alpcont/summit/releases/latest/download/Summit.apk

## Phone setup (once)
- Allow installing apps from your browser / file manager when Android asks.
- Allow notifications when Summit asks on first start (needed for reminders).
- If reminders arrive late: Settings → Apps → Summit → Battery → **Unrestricted**
  (there is a shortcut in Summit's own settings).

## How it works
- **Today**: the list for today. Open tasks come first (by time, then priority), ticked ones
  sink to the bottom. Type in the bar at the bottom to add a task; the pencil opens the full form.
- **On a task**: tap to edit. Swipe right to tick it, swipe left to move it to tomorrow (a
  repeating task is skipped for that day, a Someday task moves to today). Hold for the menu;
  hold and drag to put it where you want. Tasks you placed by hand stay on top in your order.
- **Undo**: after deleting or moving a task, an Undo bar shows for five seconds.
- **Keyboard**: Esc always goes back to the Today page.
- **Calendar**: switch between Month and Week. The bar under each day shows how much of it
  was completed. Tap a day to see and add its tasks; swipe sideways to change month or week.
- **Someday**: tasks without a date.
- **Morning review**: opens once a day (from 08:00, changeable) when earlier tasks are still
  unticked. For each one choose Today, Another day or Drop.
- **Settings** (top right on Today): morning and evening times, categories and their colours.
- **Quick-add shortcuts**: type details in the same line and they are understood, with a
  preview above the bar. Dates: `today`, `tomorrow`, `mon`…`fri`, `on sat`, `17.11`, `17 nov`.
  Times: `18:00`, `6pm`. Duration: `45m`, `1.5h`. Repeats: `daily`, `weekdays`, `every mon wed`.
  Category: `#gym`, or a word that is a category name. Priority: `!high`, `!low`.
- **Widget**: hold an empty spot on the home screen → Widgets → Summit. It lists today's
  tasks; tap one to tick it, tap + to add.
- **Progress** (bars icon on Today): streak, this week's chart, last 30 days, focus time.
  A streak day is a day on which every planned task was ticked; days without tasks are skipped.
- **Focus timer**: hold a task → Start focus timer. The minutes are logged on the task when
  the session ends, also if the app is closed in between.
- **Evening planning**: a nudge at 21:30 (changeable) opens a screen to set up tomorrow:
  add tasks, carry over what is still open, pull tasks from Someday.

## Plan
1. **Done**: tasks, calendar, morning review, reminders, categories.
2. **Done**: home-screen widget, streaks and stats, focus timer, smart quick-add, evening planning.
3. Next: sync between phone and tablet (Firebase + Google sign-in), phone calendar events
   shown next to tasks.

## Ideas for later
- A "day starts at" hour, so tasks finished after midnight still count for the day before.
- Backup to a file and restore.
- Light theme.
- Turkish words in quick-add ("yarın", "bugün").
- A second, smaller widget; a running focus timer shown in the notification bar.
- A "sort automatically again" button after tasks were dragged by hand.
- Rename a subtask in place.
