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
- **Today**: the list for today, sorted by time and then priority. Type in the bar at the
  bottom to add a task; the pencil opens the full form. Tap a task to edit it, hold it for
  quick actions (move to tomorrow, Someday, delete).
- **Calendar**: switch between Month and Week. The bar under each day shows how much of it
  was completed. Tap a day to see and add its tasks; swipe sideways to change month or week.
- **Someday**: tasks without a date.
- **Morning review**: opens once a day (from 08:00, changeable) when earlier tasks are still
  unticked. For each one choose Today, Another day or Drop.
- **Settings** (top right on Today): morning nudge time, categories and their colours.

## Plan
1. **Done**: tasks, calendar, morning review, reminders, categories.
2. Next: home-screen widget, streaks and stats, focus timer on a task, smart quick-add
   ("gym tomorrow 18:00"), evening planning screen.
3. Later: sync between phone and tablet (Firebase + Google sign-in), phone calendar events
   shown next to tasks.

## Ideas for later
- A "day starts at" hour, so tasks finished after midnight still count for the day before.
- Backup to a file and restore.
- Light theme.
- Undo after deleting a task.
- Rename a subtask in place.
