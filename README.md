# Summit

Alp's daily task planner for Android. Add the day's tasks, tick the box and the line is
crossed out; a small mountain on the main page lights up as the day gets done. Tasks can be
one-off, repeating, timed or parked in "Someday", and carry a colour category, priority,
subtasks, notes and an estimated duration. A calendar (month, week and day) shows how each day
went, a morning review asks what to do with anything left unfinished, and timed tasks send
reminders. Habits are kept in "Don't break the chain", notes (for life and for Summit itself)
on the Notes tab, and course mode counts down to exams and suggests study blocks. Everything
is stored on the device. Phone and tablet are kept the same through a Firebase project of
your own (sign in with Google in Settings); only then does the app use the internet.

## Download
https://github.com/Alpcont/summit/releases/latest/download/Summit.apk

## Phone setup (once)
- Allow installing apps from your browser / file manager when Android asks.
- Allow notifications when Summit asks on first start (needed for reminders).
- If reminders arrive late: Settings → Apps → Summit → Battery → **Unrestricted**
  (there is a shortcut in Summit's own settings).

## How it works
- **Bottom bar**: Today, Calendar, Chains, Notes, Someday.
- **Today**: the list for today. Open tasks come first (by time, then priority), ticked ones
  sink to the bottom. Type in the bar at the bottom to add a task; the pencil opens the full form.
  **Swipe left or right anywhere on the page** to see the next or previous day (no limit); a
  "Back to today" chip brings you back. Exams coming up show as countdown chips under the date.
- **On a task**: tap the box to tick it, tap the text to edit. Hold for the menu (move, skip,
  delete); hold and drag to put it where you want. Tasks you placed by hand stay on top in your order.
- **Undo**: after deleting or moving a task, an Undo bar shows for five seconds.
- **Keyboard**: Esc always goes back to the Today page.
- **Calendar**: switch between Month and Week. The bar under each day shows how much of it
  was completed. Tap a day to see and add its tasks; swipe sideways to change month or week.
- **Day view** (Calendar → Day): the day by the hour. Tasks with a time are blocks as tall as
  their duration; phone calendar events appear as blocks too. Tap a block to edit, its box to
  tick. Hold a block and drag to move it, hold its lower edge and drag to change its length.
  Tasks without a time sit in a row on top: hold one and drag it into the day. Tap an empty
  slot to add a task there.
- **Start my day** (round button in the Day view, today only): starts a timer on the first
  open task. The card shows the percent of that task done and the percent of the day done
  (minutes worked out of minutes planned; a ticked task counts in full). "Finish" ticks the
  task and the timer moves to the next one. The button pauses in between. When a task has
  used its planned time, you get a notification and the numbers turn red.
- **Duration**: in the task form, pick a preset or "Custom" for any hours and minutes.
- **Someday**: tasks without a date.
- **Morning review**: opens once a day (from 08:00, changeable) when earlier tasks are still
  unticked. For each one choose Today, Another day or Drop.
- **Settings** (top right on Today): morning and evening times, categories and their colours.
- **Quick-add shortcuts**: type details in the same line and they are understood, with a
  preview above the bar. Dates: `today`, `tomorrow`, `mon`…`fri`, `on sat`, `17.11`, `17 nov`.
  Times: `18:00`, `6pm`. Duration: `45m`, `1.5h`. Repeats: `daily`, `weekdays`, `every mon wed`.
  Category: `#gym`, or a word that is a category name. Priority: `!high`, `!low`.
- **Widgets**: hold an empty spot on the home screen → Widgets → Summit. "Summit: today" lists
  all of today's tasks; "Summit: important" is smaller and lists only High and Medium tasks
  (scrolls if they do not fit), with today's habits as small flames below; tap a task to tick
  it, a flame to link that habit, + to add a task.
- **Theme**: Settings → Theme switches Dark (default) → Grey (a softer charcoal) → Light, per
  device. All screens and both widgets follow it.
- **Chains** (don't break the chain), flame cards: switch One / Marked / All at the top.
  One shows a big card per habit (swipe sideways, page dots): a giant streak number over a
  flame that burns warmer and brighter the longer the chain, the last 5 weeks (this week as
  interlocking links), badges and a big **Link today** button. Linking: a firm vibration, the
  flame flares with sparks and rings, the number rolls up with +1. Marked and All show mini
  cards and a month where a day with every planned habit linked glows. Only today and
  yesterday can be linked ("Forgot yesterday?" box). Badges at 7, 30 and 100 days with a
  full-screen moment ("Keep climbing" closes it). After a habit's evening reminder its flame
  flickers until linked. A broken chain shows the best streak and a fresh start.
  Each habit has a name, colour, days, Mark, an evening reminder time and an optional
  **streak freeze** (off by default: one missed planned day a month is forgiven; the card
  says when). Hold a card to edit, mark or delete it.
- **Task ↔ habit**: in the task form, pick a Habit. Ticking that task links the habit for the
  day, and linking the habit ticks the task.
- **Notes**: switch between Productivity and Developer at the top; the search field looks
  through all notes; + adds a note. Productivity notes can have a checklist and be pinned to a day (then they
  show on that day on the Today page, in Calendar → Day, and in the evening planning before it). Developer notes are about
  Summit itself, with a type (Bug, Idea, Change) and status (Open, Done), and filter chips
  Open / Bug / Idea / Change / Done. The **Copy for Claude** bar shows the open ones by type;
  turns all open developer notes into one prompt (grouped by type, with the app version) on the
  clipboard; mark notes Done once an update has them.
- **Courses** (cap icon on Today): Summit finds course codes like "ECON 101" in the phone
  calendar (Settings → Show calendar events must be on). Add each one with a colour, or mark it
  "Not a course". Add exam dates to a course; Today shows a countdown, and on each of the 5 days
  before an exam the Today page and Calendar → Day suggest a 1-hour study block (in a free gap
  between 09:00 and 21:00, or without a time if there is none). Accept or Skip each with one
  tap; accepted ones become normal tasks.
- **Assignments** (Courses → New assignment): name, course, deadline, total effort, number of
  sessions and the days to work on it. Summit spreads the sessions evenly over those days as
  normal tasks, which you can change afterwards like any task.
- **Progress** (bars icon on Today): streak, this week's chart, last 30 days, focus time.
  A streak day is a day on which every planned task was ticked; days without tasks are skipped.
- **Focus timer**: hold a task → Start focus timer. The minutes are logged on the task when
  the session ends, also if the app is closed in between.
- **Evening planning**: a nudge at 21:30 (changeable) opens a screen to set up tomorrow:
  add tasks, carry over what is still open, pull tasks from Someday, see notes pinned to
  tomorrow, and add a "Review … notes" task for each class you had today (or skip it).
- **Sunday weekly review**: on Sundays the evening planning starts with the week: what got
  done, what kept being moved, the summit streak and every habit chain; then you plan Monday.

## Sync and phone calendar
- **Phone calendar**: Settings → Show calendar events. Classes and appointments from the
  phone's calendar then appear above the tasks of each day. Summit only reads them.
- **Sync**: needs the file `app/google-services.json` from your Firebase project (Android app
  `com.alp.summit`, Google sign-in enabled, Firestore created). Once the file is in the repo,
  the next build has sync: Settings → Sign in with Google, on both devices.
- What syncs: tasks, categories, focus time, habits, notes, courses (with exams) and
  assignments. Theme and reminder times stay on each device.
- How sync decides: each task (habit, note, …) is compared separately; if both devices changed
  the same one, the later change wins. Deleting it on one device deletes it on the other.
- Firestore rules that keep the data private to your account are in the chat with Claude and
  in the Firebase console (Firestore → Rules).

## Plan
1. **Done**: tasks, calendar, morning review, reminders, categories.
2. **Done**: home-screen widget, streaks and stats, focus timer, smart quick-add, evening planning.
3. **Done**: phone calendar events next to tasks; sync between phone and tablet (switched on
   in 3.2 with the Firebase file in the repo).
5. **Done (4.2)**: Chains as flame cards, Notes redesign (Manrope font), task ↔ habit,
   streak freeze, habit flames on the small widget.
4. **Done (4.0)**: swipe between days, small widget, light theme, Notes, Chains, course mode
   with exam countdown and study blocks, assignment breakdown, class reviews, Sunday review.

## Ideas for later
- A "day starts at" hour, so tasks finished after midnight still count for the day before.
- Backup to a file and restore.
- Light theme.
- Turkish words in quick-add ("yarın", "bugün").
- A running focus timer shown in the notification bar.
- Not in 4.0 on purpose: auto-fill my day, energy hours, estimate check, routines, week
  templates, breaks, Do Not Disturb, share into Summit, score card.
- A "hand in" task on an assignment's deadline; exam times (now only the date).
- A "sort automatically again" button after tasks were dragged by hand.
- Rename a subtask in place.
