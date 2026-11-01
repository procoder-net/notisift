# Agent changelog

One bold UX change per run. Each entry records what changed, why, how it is measured and how to undo it, so later
runs build on what worked instead of repeating or undoing it. Update **Result** once there is real data.

NotiSift sends no analytics. Measures are kept on the phone (Settings → Advanced) and read from your own device or
from testers who share theirs.

## 2026-10-30 · Content-first Home (compact Home)

- **Problem:** Home opened on chrome, not notifications.
  - At 1.0× text, the first notification started about 80% down the first screen. Above it sat the status card,
    the AI offer, two view chips, group chips and a hint.
  - At 200% text, the first notification was more than two screens down.
  - Home is where people catch up on Later, and Later is the biggest pile: on the maintainer's phone, Telegram alone
    sent 260 notifications in a day.
- **Change:**
  - **Status:** a one-line status while sorting works normally. Every state that needs the user keeps the full card.
  - **Tiers:** pinned while scrolling.
  - **AI offer:** a one-line banner instead of a card.
  - **View switch:** one "By day" chip instead of two.
  - **Swipe hint:** shown until the first visit with an action.
  - **Also fixed:** Settings switch rows now flip from the whole row, not only the small switch. This was a 48dp and
    TalkBack gap found while testing the rollback.
- **Hypothesis:** if notifications start on the first screen, more Home visits end with an action (open, clear,
  move), because there is something to act on without scrolling.
- **Metric:** share of Home visits with at least one action, compact vs classic. Counted once per visit, on the
  phone only.
  - Shown in Settings → Advanced under **Compact Home**.
  - Stored in DataStore `home_stats`.
- **Flag:** `compactHome` (AppSettings, default on). Roll back per phone with the **Compact Home** switch in
  Settings → Advanced, or for everyone by flipping the default.
- **Files:**
  - `ui/HomeHeader.kt` (new)
  - `data/HomeStats.kt` (new)
  - `ui/HomeScreen.kt`, `ui/StatusCard.kt`, `ui/SettingsScreen.kt`, `ui/MainViewModel.kt`
  - `data/Settings.kt`, `SiftApp.kt`
  - `res/values/strings.xml`
  - Tests: `data/HomeStatsTest.kt`, `androidTest/.../HomeHeaderTest.kt`
  - `app/build.gradle.kts`, `gradle/libs.versions.toml`: Compose UI test dependencies; UI tests run on the demo
    build so they never replace your own install.
  - `README.md`
- **Evidence from this run (emulator, API 34, same build and conditions for both):**
  - First notification on screen: before at about 80% of the first screen (1.0× text), and more than two screens down
    (200% text). After at about 48% (1.0×) and on the first screen (200%).
  - Cold start median over 5 launches: main 2.64 s, compact 2.70 s. The difference is within noise.
  - Slow frames over the same navigation pass: main 84%, compact 35%. The emulator was overloaded, so treat both
    numbers as rough.
- **Review fixes before shipping:**
  - A visit is credited to the layout it started with, and only after settings have loaded.
  - A visit ends when Home goes away; rotating the screen doesn't count a new one.
  - The hint is decided when Home opens, so it doesn't vanish mid-swipe.
  - TalkBack reads the status line without "star".
- **Known limit:** a visit to an empty Home still counts as a visit without an action, for both layouts alike.
- **Result:** pending.

## 2026-10-29 · Guided first-run setup (PR #6)

- **Problem:** The first launch opened on a red "Step 1 of 3" card behind a 7-step tour.
  - Android's notification-access warning came with no explanation.
  - The user was asked to approve a preview before there was anything to preview.
- **Change:** a full-screen 4-step setup:
  1. Welcome, with an animated before/after.
  2. An access primer, then a return to the app automatically.
  3. Automatic app setup.
  4. A live preview of the user's own drawer, sorted, with **Start sorting**.
- **Hypothesis:** showing people their own drawer sorted before asking them to turn sorting on raises the share of
  installs that start sorting.
- **Metric:** not measured. There were no on-phone counters yet.
- **Flag:** none. The old tour is still in Help.
- **Result:** pending.
