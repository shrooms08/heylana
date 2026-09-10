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
demand and answers questions about it. Phase 2 makes it a guide: it points at the
one thing on screen the answer is about, speaks its answers, and listens when the
user holds it.

## Package layout

```
xyz.heylana.app
├── MainActivity.kt          setup checklist + start/stop buddy
├── overlay/                 everything that draws on top of other apps
│   ├── BuddyOverlayService  foreground service (specialUse), notification, ask flow
│   ├── BuddyOverlayView     window container: drag, snap-to-edge, panel placement
│   ├── BuddySpriteView      the 96x96dp sprite, drawn in code (no image assets)
│   ├── ChatPanelView        the chat card: question field, Send, answer, mute switch
│   └── HighlightOverlayView the pointer: pulsing box + arrow, in its own window
├── screen/                  reading the app the user is looking at
│   ├── HeylanaAccessibilityService  on-demand screen reads, no continuous work
│   └── ScreenSnapshot       the element list + its text rendering for the model
├── brain/                   talking to the model
│   ├── AnthropicClient      POST /v1/messages over OkHttp, JSON in, say/point_at out
│   └── HeylanaPrompt        the system prompt and user message, in one editable place
├── voice/                   Heylana's mouth and ears
│   ├── Speaker              text-to-speech; degrades to text only if it will not start
│   ├── Listener             SpeechRecognizer, held open only while the buddy is held
│   └── MicPermissionActivity  invisible one-shot prompt for the microphone
├── settings/                stored configuration
│   ├── HeylanaSettings      EncryptedSharedPreferences: API key + model name
│   └── SettingsActivity     the settings screen
└── ui/theme/                Compose theme (scaffolded)
```

## How the pieces talk to each other

**Response contract.** The model must reply with exactly
`{"say": "...", "point_at": <element id or null>}` and nothing else. `say` is 1 to
3 short sentences written to be read aloud — no markdown, no symbols. `point_at`
is the numeric id of the single element from the screen listing that the answer is
about, or null. Anything else — a missing field, a null, a non-number, or an id
that is not in the snapshot that was just taken — means "do not point at
anything". The snapshot is kept alive until the reply comes back so the id can be
turned into real screen bounds.

**The highlight window.** The pointer is drawn in its own full-screen window that
is not touchable and not focusable, so every touch falls straight through to the
app underneath. It converts accessibility bounds (which are display coordinates)
through its own `getLocationOnScreen`, so any status bar or cutout offset corrects
itself rather than being assumed away. The box clears after 8 seconds, or at once
when the next question is sent or the panel closes.

**Reading the screen.** The snapshot is taken after the keyboard is dismissed and
the app underneath has been given a moment to lay itself out again. With the
keyboard up, the bottom of the app is squeezed off screen — which is exactly where
the button the answer is about usually lives.

**Gestures on the sprite, kept strictly apart.** A tap toggles the chat panel. A
drag moves the buddy and snaps it to an edge. A press-and-hold opens the
microphone; if that hold turns into a drag, the listening is thrown away and it
becomes an ordinary drag. The overlay window only becomes focusable while the
panel was opened by a tap — never mid-gesture, because changing focusability tears
down the touch stream and would swallow the release that ends a hold.

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

5. **Heylana never acts for the user.** It reads the screen and points at things.
   It never taps, types, scrolls or gestures on the user's behalf, and it must
   never tell the user it has done something on their behalf.

6. **The API key never appears in code, logs, or git.** It is entered by the
   operator on the Settings screen and lives only in EncryptedSharedPreferences
   on the device. Never hardcode it, never write it to a log, a comment, a test,
   a commit, or a file in the repo. Never print a screen snapshot either: what
   Heylana reads off the screen is used for one request and then dropped, never
   logged and never persisted.
