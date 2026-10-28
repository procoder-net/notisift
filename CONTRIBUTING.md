# Contributing to NotiSift

Thanks for helping make phones quieter. Bug reports, "this landed in the wrong place" reports, device reports,
docs, translations and code are all welcome.

## Ground rules

- **No real notification content anywhere public.** Not in issues, screenshots, test fixtures or logs. Rewrite
  messages with made-up names ("Alex: running late"), and take screenshots from the demo build
  (`./gradlew :app:assembleDemo`), which fills itself with invented data.
- **No secrets.** API keys live in your gitignored `.env` or in the app's Settings, never in code, tests or issues.
- **Your choices beat the model.** Changes must keep the order: protected notifications → the user's own rules →
  model or offline rules. Anything uncertain fails open, which means it stays in the drawer.
- Be kind. Assume good intent and keep feedback about the code.

## Set up

1. JDK 17+ (Android Studio's bundled JBR works) and Android SDK 36.
2. `git clone https://github.com/procoder-net/notisift.git && cd notisift`
3. Optional: `cp .env.example .env` and add a model key for your own **debug** builds. Release builds never
   include it.
4. Build and check:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

## Making a change

1. Fork the repo and create a branch: `fix/whatsapp-group-tap`, `feat/miui-drawer`, `rules/airline-delays`.
2. Keep pull requests small and focused on one thing.
3. **Engine and rule changes need tests** in `app/src/test/…`. A wrong-sort fix should add the notification (with
   made-up text) as a test case that failed before your change.
4. Match the surrounding code: Kotlin official style, short comments that say *why*, small functions.
5. Run the command from step 4 above. CI runs the same on your pull request.
6. Open the pull request and fill in the template. Use conventional commit titles: `feat:`, `fix:`, `docs:`,
   `test:`, `refactor:`, `chore:`.

## Who merges

Anyone can open a pull request from a fork. `main` is protected: changes land only through a pull request with
passing CI, reviewed and merged by a maintainer (see [CODEOWNERS](.github/CODEOWNERS)). Direct pushes and force
pushes to `main` are blocked.

## Where things live

| Area | Path |
|---|---|
| Decision pipeline, offline rules, groups | `app/src/main/java/io/github/notisift/engine/` |
| AI providers and the `DecisionModel` contract | `app/src/main/java/io/github/notisift/llm/` |
| Drawer (Important stack, waiting rows, day stacks) | `app/src/main/java/io/github/notisift/service/` |
| Screens | `app/src/main/java/io/github/notisift/ui/` |
| Demo data for screenshots | `app/src/main/java/io/github/notisift/DemoData.kt` |

## Reporting a security issue

Please don't open a public issue. Use GitHub's **Report a vulnerability** button on the Security tab instead.
