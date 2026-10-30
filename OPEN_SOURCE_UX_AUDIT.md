# NotiSift: Independent UX and Open Source Launch Audit

Audit date: October 26, 2026

## Verdict

NotiSift has a compelling idea and useful foundations. Fix trust, first-use clarity, and visible layout issues before promoting it widely. An **experimental open source beta** is the appropriate next milestone; this is a product recommendation, not a certification of release readiness.

The strongest promise is **a calmer notification drawer with user control**. Lead with that outcome and present AI as optional.

## Scope and evidence

- Three separate review passes covered end-user/UX, Android reliability and privacy, and product/open source readiness.
- Reviews inspected the source, resources, README, and build/release configuration.
- The installed app was visually inspected on the connected Samsung. Its exact binary correspondence to the reviewed checkout was not established.
- No source changes were made during the audit. This document records findings and proposed changes.
- The Gradle unit-test and lint command succeeded, mostly using cached results. Existing XML reports contained **88 tests, zero failures, zero errors, and zero skipped tests**. Lint reported **28 warnings**.
- First-run installation, TalkBack, large fonts, Pixel behavior, reboot/update recovery, and notification-denial scenarios were not validated end to end.

## What already works

- Preview-first operation lets users observe decisions before notifications move.
- Decision explanations and user corrections provide a foundation for trust.
- Clearing and dismissing items have Undo.
- Material You, system dark mode, labeled navigation, and foldable layouts provide a native Android foundation.
- Log-before-cancel and copy-before-remove behavior help preserve access to notifications.
- Work-profile exclusion, encrypted API-key storage, and backup exclusion are valuable safeguards.

## Priority 1: Address before promoting public binaries

### 1. Fix the Home filter/action layout

**Observed:** On the connected Samsung, “Clear all” wrapped almost one letter per line beside the Important/Later/Junk filters, creating a large vertical gap.

**Recommendation:** Give tiers their own scrollable row and move Clear to a separate action or overflow menu. Validate compact widths, high counts, and large text.

Evidence: [HomeScreen.kt](app/src/main/java/io/github/notisift/ui/HomeScreen.kt#L65).

### 2. Align cloud privacy disclosures with app scanning

The app scan can send installed package names, app labels, notification channel identifiers, and historical notification titles to the configured model. The scan does not exclude protected notification sources. Stored OTP digits are masked; this finding is **not evidence of raw OTP leakage**, but titles and sender metadata can still be sensitive.

**Recommendation:** Use local setup by default, explicitly disclose cloud inventory analysis, minimize the transmitted data, and exclude protected sample titles. Explain this before requesting access or starting a cloud scan.

Evidence: [DeviceScanner.kt](app/src/main/java/io/github/notisift/scan/DeviceScanner.kt#L25), [PolicyAdvisor.kt](app/src/main/java/io/github/notisift/scan/PolicyAdvisor.kt#L70), [SettingsScreen.kt](app/src/main/java/io/github/notisift/ui/SettingsScreen.kt#L123).

### 3. Distinguish preview from stopping processing

“Pause” stops automatic movement of new notifications, while classification, logging, and cloud calls can continue. Users may reasonably expect a pause control to stop processing too.

**Recommendation:** Introduce clearly described **Preview sorting** and **Stop processing** states. Show whether processing is local or uses a cloud provider.

Evidence: [SiftListener.kt](app/src/main/java/io/github/notisift/service/SiftListener.kt#L152).

### 4. Separate moving an item from teaching a permanent rule

“Show in drawer” also pins a persistent template correction for similar notifications. “Junk” similarly teaches a lasting decision. These actions provide a message but do not offer the same Undo path as clearing.

**Recommendation:** Separate **Move this item** from **Always do this for similar alerts**, or explicitly explain the learning and offer Undo for both the move and the saved correction.

Evidence: [MainViewModel.kt](app/src/main/java/io/github/notisift/ui/MainViewModel.kt#L276).

### 5. Make promises precise and retention predictable

- The Junk empty state says notifications “never ping you,” although the original sound or vibration can occur before sorting.
- “Nothing is deleted” is too absolute for a system with clearing and retention.
- The README says moved notifications stay for a day, while configured history defaults to 14 days.
- Retention pruning runs at application startup rather than continuously enforcing the configured duration.

**Recommendation:** Explain that NotiSift organizes notifications after arrival. State the actual retention period consistently, prune after retention changes and periodically, and provide a separate **Delete notification history** action.

Evidence: [HomeScreen.kt](app/src/main/java/io/github/notisift/ui/HomeScreen.kt#L74), [Settings.kt](app/src/main/java/io/github/notisift/data/Settings.kt#L42), [SiftApp.kt](app/src/main/java/io/github/notisift/SiftApp.kt#L47), [README](README.md).

### 6. Show operating health accurately

“Your drawer is sorted” depends on listener connectivity and preview state, but does not verify notification-posting permission or blocked channels. Important mirroring has a permission safeguard; Later/Junk can still move into the app when summaries cannot be displayed.

**Recommendation:** Show **Active · On-device rules**, **Active · Provider name**, or **Needs attention**, with a direct recovery action. Explain degraded behavior instead of claiming full success.

Evidence: [StatusCard.kt](app/src/main/java/io/github/notisift/ui/StatusCard.kt#L56), [ImportantStack.kt](app/src/main/java/io/github/notisift/service/ImportantStack.kt#L114).

## Priority 2: Improve the first minute

Recommended journey:

1. **Try a sample inbox** without permissions, using synthetic messages, delivery updates, and promotions.
2. Explain on-device versus cloud processing and retention.
3. Request notification permissions at the relevant setup step.
4. Preview real decisions with an explicit “Nothing moved yet” message.
5. Review essential apps and consequential suggestions.
6. Offer **Start sorting**, with pause and recovery easy to find.

The current flow asks for notification-posting permission immediately, then notification access, app scanning, and a paused state. It labels steps 1 and 2 of 3 without presenting a clear third step.

Evidence: [MainActivity.kt](app/src/main/java/io/github/notisift/MainActivity.kt#L25), [StatusCard.kt](app/src/main/java/io/github/notisift/ui/StatusCard.kt#L57).

### Recommended Home experience

- Compact status showing processing mode and health.
- Clear Important/Later/Junk navigation that survives large counts and fonts.
- Search by app, sender, or notification text.
- Actual app icons beside entries.
- Short reasons such as “Promotion · local rule.”
- An obvious restore path and Recently cleared view.

Avoid claiming a count of interruptions prevented: original notifications can sound before sorting. Use accurately defined counts of notifications organized or moved instead.

## Icons and visual direction

### Highest-value addition: actual app icons

Add installed-app icons beside notification entries and app policy cards. Recognizing an app visually is faster than repeatedly reading its name. Current cards rely on text labels.

Evidence: [HomeScreen.kt](app/src/main/java/io/github/notisift/ui/HomeScreen.kt#L175), [AppsScreen.kt](app/src/main/java/io/github/notisift/ui/AppsScreen.kt#L167).

### Use one consistent icon family

| Meaning | Suggested vector icon |
|---|---|
| Important | Star outline |
| Later | Schedule |
| Junk | Block |
| Smart | Auto Awesome |
| Protected/local | Shield |
| Cloud processing | Cloud outline |
| Restore | Restore |
| Learned rule | Rule |
| Pause/active | Pause Circle / Check Circle |

Keep visible labels. Gradually replace emoji in interactive controls with consistent vectors. Preserve the existing labeled bottom navigation.

### Launcher identity

Retain the funnel/sieve idea, but explore a distinctive mark: **several notification cards entering, one clear signal emerging**. Add adaptive foreground/background assets and a monochrome variant, then test common launcher masks.

The current launcher is a simple funnel on an indigo square, with the same drawable used for regular and round icons.

Evidence: [ic_launcher.xml](app/src/main/res/drawable/ic_launcher.xml), [AndroidManifest.xml](app/src/main/AndroidManifest.xml#L14). Reference: [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Accessibility and compact-screen review

These are source-based risks requiring runtime validation:

- Four-choice segmented app controls and long action rows may become cramped on small screens or with large text.
- Consider a current-policy pill that opens a labeled bottom sheet instead of four narrow segments.
- Make switch rows expose a combined accessible label and state.
- Expose expanded/collapsed state for group headers.
- Provide accessible alternatives to swipe gestures.
- Use persistent text-field labels rather than relying on placeholders.

Validate at 320–360dp widths, with 200% font scaling, TalkBack, dark/light themes, and folded/unfolded layouts. Reference: [Android layout and sizing guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units).

## Product positioning and audience

Initial audience: Android enthusiasts overwhelmed by notifications who value customization, local rules, and optional bring-your-own AI.

Suggested headline:

> **Keep important notifications visible. Put the noise aside.**

Supporting line:

> An open-source Android notification organizer with local rules, preview mode, and optional AI.

The existing “replaces your notification drawer” and “any AI model” framing explains implementation and flexibility. The proposed wording emphasizes the user benefit first.

## Open source launch presentation

The README explains the engineering thoroughly. Its opening should also make the benefit and installation path immediately clear:

- A short before/after recording using synthetic content.
- Three screenshots: organized drawer, inbox with app icons, and preview/explanation.
- A prominent beta installation link and compatibility information.
- Clear privacy behavior and known limits.
- Contributor and architecture details below the user introduction.

### Release and contributor readiness

- Establish a persistent release signing identity before public APK distribution. Release currently uses debug signing.
- Publish versioned release artifacts, checksums, and clear update instructions.
- Add the missing `.env.example` referenced in build instructions, or correct those instructions.
- Add CONTRIBUTING, security reporting instructions, issue templates, a changelog, and a tested-device matrix.
- Request OEM/Android version and reproducible steps in bug reports; avoid requesting real sensitive notification content.

Evidence: [app/build.gradle.kts](app/build.gradle.kts#L58), [CI workflow](.github/workflows/ci.yml), [README](README.md). Reference: [GitHub community guidance](https://docs.github.com/en/communities/setting-up-your-project-for-healthy-contributions).

## Recommended roadmap

| Order | Work | Intended outcome |
|---|---|---|
| 1 | Privacy, pause semantics, learning Undo, accurate copy and health states | Users understand and control behavior |
| 2 | Home layout, app icons, compact controls, accessibility | A clear and recognizable everyday experience |
| 3 | Sample demo and guided preview | New users see value before committing |
| 4 | Search, Recently cleared, privacy/data controls | Users can find and recover important content |
| 5 | Release packaging, screenshots, demo, contributor docs | A credible public beta |
| Later | Scheduled digests, rule export/import, AI usage visibility | More value after the core journey is dependable |

## Validation required before broader launch

- New-user setup on Samsung and Pixel.
- Notification permission denial and channel blocking.
- Notification access grant, revoke, and regrant.
- Reboot, process death, and app update recovery.
- Exact-chat opening, replies, and restored-notification limitations.
- Offline operation, provider timeout, and provider errors.
- Bursts, repeated notifications, and preview-to-active transitions.
- Protected notifications on Android 15/16 and work-profile exclusion.
- Long-running retention enforcement.
- TalkBack, large text, compact widths, and foldable transitions.

Beta success means a new user can understand the benefit, preview decisions, recognize their apps, recover a mistake, and stop sorting without assistance. Source review and unit tests alone cannot establish that experience.

## Fix status (October 27, 2026)

| Audit item | Status |
|---|---|
| P1.1 Home filter/action layout | **Fixed.** Tiers scroll on their own row; "Clear all N" sits below. Verified on the Fold cover screen |
| P1.2 Scan privacy | **Fixed.** Scan samples exclude people, codes, money, calls and protected items; the Apps tab names the provider and what is sent |
| P1.3 Preview vs stop | **Fixed.** States are Preview (decides and logs, nothing moves), Sorting, and Off (reads nothing, no AI calls). The status card says where decisions are made |
| P1.4 Learning Undo | **Fixed.** "Show in drawer" and "Junk" now offer Undo that restores the item, the drawer row and the previous rule |
| P1.5 Precise promises and retention | **Fixed.** Copy no longer claims "never ping" or "nothing is deleted"; README states sorting happens after arrival and the real retention. Retention is enforced on change and every 6 hours; Settings has "Delete notification history" |
| P1.6 Operating health | **Fixed.** "Needs attention" when Android hides NotiSift's rows, with a direct link to fix it |
| P2 First minute | **Partly.** Steps are now 1–3 (access, apps, preview); the posting permission is requested at "Start sorting", not at launch. Sample inbox not done |
| App icons | **Done** on Home entries and Apps cards |
| `.env.example` | **Added** |
| Release signing, contributor docs | **Done** (v0.1.1, CONTRIBUTING.md, issue forms) |
| Recently cleared, search, sample inbox (Oct 29) | **Done.** Home has ↩️ Cleared with Restore (also for drawer clears), 🔍 search across tiers, and a made-up example until the first notification arrives. Home pages instead of stopping at 1,000; counts come from the database; Clear all above 50 asks first |
| Launcher icon, accessibility (Oct 29) | **Done in code, not yet checked with TalkBack.** Adaptive and themed icon; headers announce expanded/collapsed; cards offer Show in drawer / Done / Restore as TalkBack actions; the status card has one main button plus ⋯ |
| Still open | Translations (issue #1), vector icon family, TalkBack and 200% font check on a device |
