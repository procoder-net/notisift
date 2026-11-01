# NotiSift marketing kit

Ready-to-use copy for the store listing, social posts and launch sites. Everything here matches what the app
actually does (0.1.3). Media lives in `docs/`: `demo.gif` (drawer before and after), `talk-to-notisift.gif`
(rules and questions), and `screenshots/` (demo data only, every name is made up).

## Positioning

**One line:** Your phone's AI sorts your notifications. Free, private, open source.

**Short pitch (25 words):** NotiSift decides per notification what deserves your attention. It runs on your phone's
own AI, costs nothing, and lets you set rules just by saying them.

**Who it's for:** people with 200+ notifications a day who mute whole apps and then miss the one delivery, code or
message that mattered.

**The problem in their words:** "I muted Amazon and missed my delivery." "Group chats bury my mom's messages." "I
turned off everything and now I check my phone every five minutes anyway."

## Key messages

1. **Per notification, not per app.** Android makes you choose per app or channel. NotiSift reads each notification,
   so Amazon's "arriving today" stays on top while Amazon's deals wait quietly.
2. **Your phone's own AI, free and private.** On phones with Gemini Nano it uses the AI built into the phone. No key,
   no account, no server, no cost, and nothing leaves the phone.
3. **Talk to it.** "Mute Instagram." "Family group only when someone tags me." "Did my package ship?" Rules in plain
   words, confirmed with one tap, undone with one tap.
4. **A notification scheduler with judgment.** Everything that can wait arrives at the times you choose (9 am, 6 pm).
   What needs you now still gets through, and the AI moves up anything that turns urgent.
5. **Any decision model, almost no cost.** Prefer a cloud model? Bring any key (Perplexity, OpenAI, Anthropic,
   Gemini, OpenRouter, Groq) or run one on your own computer. Codes and chats never leave the phone, repeats are
   remembered instead of re-asked, so a cloud model typically costs well under $1 a month (estimate in the README).
6. **Safe by default.** Codes, people's messages, calls and alarms are never hidden. Nothing is deleted: Later and
   Junk stay in the app, and anything cleared can be restored.
7. **Open source.** MIT licensed, no ads, no tracking, no account.

## Proof points (use these numbers, they are real)

- On a Galaxy Z Fold8, the on-phone AI answered in about 0.6–0.8 seconds per notification, at 11% battery, offline.
- Typical day in the demo data: 15 on top, 18 moved out of the way, 7 junk.
- 175 automated tests; every release is signed with the same key and published with its SHA-256.

## Taglines (pick one per channel)

- Your notifications, sorted by your phone's own AI.
- Show Amazon deliveries. Hide Amazon deals.
- Mute the noise, not the app.
- Say it once: "Mute Instagram." Done.
- Free AI for your notification drawer. It never leaves your phone.
- The drawer that knows what can wait.

## Store listing

**Title (30 chars):** NotiSift: AI Notification Sorter

**Short description (80 chars):** Your phone's own AI sorts notifications. Free, private, no account. Just say it.

**Full description:**

> Too many notifications? NotiSift decides **per notification** what deserves your attention, so you can stop muting
> whole apps and missing the one thing that mattered.
>
> ⭐ **Important** stays on top: messages from people, codes, deliveries arriving now, reminders.
> 🕓 **Later** waits quietly in one tidy row per group, or arrives at the times you choose.
> 🗑️ **Junk** disappears: promos, review requests, update nags. Still in the app if you need it.
>
> **Your phone's own AI, free.** On phones with Gemini Nano, NotiSift uses the AI built into your phone. No key, no
> account, no cost, and nothing leaves the phone. It writes a one-line summary of each waiting group and learns what
> matters to you.
>
> **Just say it.** Type "Mute Instagram" or "Family group only when someone tags me" and confirm the rule. Ask "Did
> my package ship?" and get the answer.
>
> **Important on top.** When something in Later turns urgent ("gate changed", "arriving today"), NotiSift moves it up.
>
> **Works on every phone.** No on-phone AI? Built-in rules sort offline, or plug in any AI model you like: Perplexity,
> OpenAI, Anthropic, Gemini, OpenRouter, Groq, or one on your own computer.
>
> **Safe by default.** Codes, chats, calls and alarms are never hidden. Nothing is deleted, and every change can be
> undone. New installs start in Preview, where nothing moves until you say so.
>
> Open source (MIT). No ads. No tracking. No account.

## Social posts

### X / Bluesky / Threads (thread)

1. I built an Android app that lets your phone's own AI sort your notifications. Free, private, open source. 🧵
   [attach talk-to-notisift.gif]
2. Android lets you mute per app. But Amazon sends both "arriving today" and "50% off". NotiSift decides per
   notification: deliveries on top, deals out of the way.
3. On phones with Gemini Nano it runs on the phone's built-in AI. No key, no account, no cost. Your notifications
   never leave the phone.
4. You set rules by saying them: "Mute Instagram." "Family group only when someone tags me." Or ask: "Did my package
   ship?"
5. No on-phone AI? Built-in rules work offline, or bring any model (Perplexity, OpenAI, Claude, Gemini, Groq, local
   Ollama). Typically well under $1/month, because repeats are remembered, not re-asked.
6. MIT licensed. APK on GitHub: https://github.com/procoder-net/notisift

### LinkedIn

> I got tired of choosing between muting an app and drowning in it, so I built **NotiSift**, an open-source Android
> app that decides **per notification** what deserves attention.
>
> What I'm most excited about: it runs on the **AI built into the phone** (Gemini Nano). No API key, no server, no
> cost, and notifications never leave the device. On my Galaxy Z Fold8 it decides in under a second, offline.
>
> You manage it in plain language. "Mute Instagram." "Only show the family group when someone tags me." "Did my
> package ship?" Every rule is confirmed with one tap and undone with one.
>
> If you prefer a cloud model, any decision model plugs in (Perplexity, OpenAI, Anthropic, Gemini, OpenRouter, Groq,
> or a local Ollama). Because codes and chats stay on the phone and repeat notifications are remembered, the cloud
> cost is typically well under a dollar a month.
>
> MIT licensed, contributors welcome: https://github.com/procoder-net/notisift

### Reddit (r/androidapps, r/fossdroid, r/LocalLLaMA)

**Title:** I made a free, open-source notification sorter that runs on your phone's own AI (Gemini Nano), with no
key and no cloud

> Android's notification controls are per app or channel. NotiSift decides per notification: Important on top,
> Later in one quiet row per group (or at set times), Junk out of sight but restorable.
>
> - On phones with Gemini Nano it uses the on-phone AI: free, offline, nothing leaves the phone.
> - Set rules in plain words ("mute Instagram", "family group only when tagged") and ask questions ("did my package
>   ship?").
> - No Nano? Built-in rules work offline, or bring any model, including Ollama/LM Studio on your own computer.
> - Codes, chats, calls and alarms are never hidden; everything can be undone.
>
> MIT, no ads, no tracking, no account. APK and source: https://github.com/procoder-net/notisift
> Feedback very welcome, especially from people with 300+ notifications a day.

*For r/LocalLLaMA, lead with: "Point it at Ollama or LM Studio on your wifi and every decision stays in your house."*

### Hacker News

**Title:** Show HN: NotiSift – Android notification sorting with on-device Gemini Nano

> NotiSift decides per notification (Important / Later / Junk plus a group) instead of per app. Pipeline: protected
> categories (codes, chats, calls) are decided by local rules and never sent anywhere; user rules come next; then a
> decision model or offline heuristics. Confident model answers are cached per notification template, so repeats
> cost nothing.
>
> New in 0.1.3: on phones with AICore it uses Gemini Nano via the ML Kit Prompt API. Android only allows that while
> the app is in the foreground, so Nano teaches the background rules when you open the app, writes group summaries,
> and powers a plain-language box for rules and questions. Any OpenAI-compatible endpoint, Perplexity's decision
> API, or a local model also works.
>
> Kotlin, Compose, Room, MIT. Feedback on the foreground-only design is especially welcome.
> https://github.com/procoder-net/notisift

### Product Hunt

**Tagline:** Your phone's own AI sorts your notifications, for free

**Description:** NotiSift keeps what matters on top and quietly sets the rest aside, deciding per notification.
It runs on the AI built into your phone (no key, no cost, nothing leaves the device), lets you set rules just by
saying them, and works on any phone with built-in rules or the AI model of your choice. Open source.

**First comment:** Hi! I built NotiSift because muting apps kept making me miss the one notification that mattered.
The part I'm proudest of: on phones with Gemini Nano, the whole thing runs on-device for free. Try typing "mute
Instagram" or "did my package ship?" and tell me what you'd want it to understand next.

## Answers to common objections

- **"Another app reading my notifications?"** It has to, to sort them. With the on-phone AI or built-in rules nothing
  leaves the phone. It has no account and no server, and it's open source, so you can check.
- **"AI costs money."** On-phone AI and built-in rules are free. A cloud model is optional and typically costs well
  under $1 a month.
- **"What if it hides something important?"** Codes, people's messages, calls and alarms are never hidden. Nothing is
  deleted. New installs start in Preview, where nothing moves until you say so.
- **"Does it work on my phone?"** Android 8.0 or newer. The on-phone AI needs a phone with Gemini Nano; everything
  else works everywhere.

## Don't claim

- Don't name specific phone models beyond "Galaxy Z Fold8" (tested) or "phones with Gemini Nano".
- Don't quote exact cloud prices; say "typically well under $1 a month" and link the README estimate.
- Don't say it works while the app is closed for on-phone AI tasks; Android allows Gemini Nano only while NotiSift is
  open (background sorting uses the rules it learned, or your AI key).
