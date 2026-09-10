# Heylana

Heylana is an on-screen AI buddy for the Solana Seeker phone. It lives as a small
draggable sprite floating on top of every other app, always within reach, so the
user can talk to it without leaving whatever they are doing. This repo is a
hackathon build: an Android app (Kotlin, Jetpack Compose, Gradle Kotlin DSL,
package `xyz.heylana.app`, minSdk 31) built in numbered phases. Phase 0 is the
floating overlay buddy — the sprite, the drag-and-snap behaviour and the
foreground service that keeps it alive. AI, network, wallet and accessibility
features come in later phases and must not leak into earlier ones. Phase 1 adds
screen reading and text chat: the buddy reads the app in front of the user on
demand and answers questions about it.

## Package layout

```
xyz.heylana.app
├── MainActivity.kt          setup checklist + start/stop buddy
├── overlay/                 everything that draws on top of other apps
│   ├── BuddyOverlayService  foreground service (specialUse), notification, ask flow
│   ├── BuddyOverlayView     window container: drag, snap-to-edge, panel placement
│   ├── BuddySpriteView      the 96x96dp sprite, drawn in code (no image assets)
│   └── ChatPanelView        the chat card: question field, Send, answer area
├── screen/                  reading the app the user is looking at
│   ├── HeylanaAccessibilityService  on-demand screen reads, no continuous work
│   └── ScreenSnapshot       the element list + its text rendering for the model
├── brain/                   talking to the model
│   ├── AnthropicClient      POST /v1/messages over OkHttp, JSON in, {"say"} out
│   └── HeylanaPrompt        the system prompt and user message, in one editable place
├── settings/                stored configuration
│   ├── HeylanaSettings      EncryptedSharedPreferences: API key + model name
│   └── SettingsActivity     the settings screen
└── ui/theme/                Compose theme (scaffolded)
```

## Working rules

1. **Every phase is built on its own branch.** Phase 0 lives on `phase0-overlay`,
   phase 1 on `phase1-...`, and so on. Never build a phase directly on `master`
   or on another phase's branch.

2. **Every build is verified before reporting done.** Run
   `./gradlew :app:assembleDebug` and fix every error until it compiles cleanly.
   "It should work" is not a report. Install with
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (if `adb` is not on PATH, use `$HOME/Library/Android/sdk/platform-tools/adb`).

3. **The operator cannot read Kotlin.** Every report must be written for someone
   who will only ever touch the phone. State exactly which button to tap, in what
   order, and exactly what the screen should show after each tap. Never explain a
   change by pointing at code, a class name, or a diff. If a phase needs manual
   verification, write it up as a numbered tap-by-tap test in `SMOKE.md`.

4. **No scope creep between phases.** Do not add dependencies, permissions or
   subsystems a phase does not need.

5. **The API key never appears in code, logs, or git.** It is entered by the
   operator on the Settings screen and lives only in EncryptedSharedPreferences
   on the device. Never hardcode it, never write it to a log, a comment, a test,
   a commit, or a file in the repo. Never print a screen snapshot either: what
   Heylana reads off the screen is used for one request and then dropped, never
   logged and never persisted.
