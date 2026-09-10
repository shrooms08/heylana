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
one thing on screen the answer is about, speaks its answers, listens when the user
holds it, and walks them through a task one step at a time, re-reading the screen
after each step.

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
│   ├── AnthropicClient      POST /v1/messages over OkHttp, JSON in, say/point_at/task out
│   ├── HeylanaPrompt        the system prompt and user messages, in one editable place
│   └── GuidanceSession      a task in progress: goal, steps given so far, stuck flag
│                            (plus Conversation, the last few ordinary exchanges)
├── voice/                   Heylana's mouth and ears
│   ├── Speaker              text-to-speech; degrades to text only if it will not start
│   ├── Listener             SpeechRecognizer, held open only while the buddy is held
│   └── MicPermissionActivity  invisible one-shot prompt for the microphone
├── ui/                      how everything looks
│   ├── HeylanaTokens        every colour, size, radius, duration and typeface
│   ├── GlassDrawable        the one liquid-glass recipe, used by every surface
│   └── GlassBlur            asks the window for cross-window blur, honestly
├── settings/                stored configuration
│   ├── HeylanaSettings      EncryptedSharedPreferences: API key + model name
│   └── SettingsActivity     the settings screen
└── ui/theme/                Compose theme (scaffolded)
```

## Look and feel

**Tokens live in `ui/HeylanaTokens`, and nothing that draws carries its own
values.** Colours, radii, spacing, durations, type sizes and weights all come
from there. A magic number in a view is a bug: if a value is a design decision,
or is needed twice, it belongs in the tokens file. The type is Outfit, shipped as
one variable font in `res/font` and instanced at 300, 400 and 500; if the font
resource will not load, the tokens fall back to the platform's light sans.

**The glass recipe is `ui/GlassDrawable`, and there is only one of it.** The
message box, its buttons, the chips and the buddy's disc are all the same
surface at different radii. In order: a fill, a soft purple refraction band at
122 degrees near the top-left, a bevel that runs light down the top and left
edges and dark up the bottom and right, then a hairline border. Primary buttons
are that same glass with the band at full strength instead of a solid fill.

**Blur is asked for, never assumed.** `ui/GlassBlur` checks
`isCrossWindowBlurEnabled` at the moment a window is shown and sets
`FLAG_BLUR_BEHIND` only if the platform agrees. The answer picks the fill: thin
glass when the platform is blurring what is behind, a heavier fill when it is
not and the surface has to carry itself. Never hardcode one or the other.

## How the pieces talk to each other

**Response contract.** The model must reply with exactly
`{"say": "...", "point_at": <element id or null>, "task": {"goal": "...", "done":
true|false} or null}` and nothing else. `say` is 1 to 3 short sentences written to
be read aloud — no markdown, no symbols. `point_at` is the numeric id of the
single element from the screen listing that the answer is about, or null. Anything
else — a missing field, a null, a non-number, or an id that is not in the snapshot
that was just taken — means "do not point at anything". The snapshot is kept alive
until the reply comes back so the id can be turned into real screen bounds.

`task` is null for an ordinary one-shot question. It is an object when the request
is something to *do*: `goal` restates it in one line and stays word-for-word
identical across the whole task, `say` describes only the current step, and
`point_at` is that step's element. `done` flips to true when the screen shows the
goal is met. A malformed or goal-less task object degrades to an ordinary answer.

**Guidance sessions.** A task is stateful. `GuidanceSession` holds the goal, every
step already given (its spoken text and the label of what it pointed at), and when
it started. Each advance sends the model the goal, the steps so far, and a **fresh**
snapshot, and asks for the next single step. Ordinary questions instead carry the
last four exchanges, so "and then?" has something to refer back to.

A session advances two ways: the user taps **Next**, or it advances itself when the
element it pointed at is gone from a fresh snapshot, or the foreground app changed
— debounced by 900ms so a screen has time to settle. It ends on `done`, on **Done**,
when the panel closes, when the buddy stops, on any API error, or at 8 steps. If two
steps in a row point at the same element the session is treated as stuck: it says so
and stops advancing itself until the user acts.

**Events only while something is waiting for them.** This is a privacy rule, not
an optimisation. The accessibility service subscribes to *nothing* by default —
its `eventTypes` is set to zero, so the system delivers no events at all — and
its handler additionally returns immediately when no watcher is registered.
There are exactly two things that may switch events on, and both switch them
straight back off:

- a **guidance session**, so it can notice a step has been completed;
- a **tap watch**, for the fifteen seconds after Heylana points at something, so
  it can notice the user acting on it and take the box away.

Events from Heylana's own package are dropped before anything else looks at them.
Do not widen this to "always listening" for convenience, and keep the promise in
the notification and the onboarding screen matching the code: *reads the screen
only when you ask, and watches for your tap only while it is pointing at
something*.

**The panel gets out of the way.** During a task the panel is a heads-up display:
the window is deliberately *not* focusable, so the app underneath keeps the
keyboard and its own dialogs, and a tap outside the card is the user doing the step
rather than a request to dismiss it. Before each step is drawn, the buddy and its
card are moved clear of whatever is about to be boxed — the other side of the
screen first, then above or below it.

**The highlight window.** The pointer is drawn in its own full-screen window that
is not touchable and not focusable, so every touch falls straight through to the
app underneath. It converts accessibility bounds (which are display coordinates)
through its own `getLocationOnScreen`, so any status bar or cutout offset corrects
itself rather than being assumed away. For a one-shot answer the box clears after 8
seconds; during a task it stays up until the step changes, because the user needs
it while they hunt for the thing. Either way it clears at once when the next
question is sent or the panel closes.

**The box answers the tap it asked for.** While it is up, a tap on the element it
points at — or the screen moving — flashes it green for 300ms and clears it,
without a word being said. Fifteen seconds with neither and it clears quietly.
The rule itself lives in `screen/TapWatch`, away from Android, so it is testable:
a click only counts when it matches the element's key, and screen movement only
counts after a short grace period, because an app is rarely still at the moment a
box appears.

**Re-parenting the buddy resets its animation.** Moving the disc between the
docked, compose and HUD layouts detaches the view, which cancels any running
cross-fade part way. The owner calls `refreshState()` after every re-parent
rather than trusting the attach callbacks to have fired in a useful order.

**Reading the screen.** The snapshot is taken after the keyboard is dismissed and
the app underneath has been given a moment to lay itself out again. With the
keyboard up, the bottom of the app is squeezed off screen — which is exactly where
the button the answer is about usually lives.

**The overlay window has three modes, and everything follows from which one.**
Docked, it is a small window holding just the disc, so touches anywhere else
reach the app underneath. Composing, it takes the whole screen: the app behind
is dimmed, the buddy flies to the top centre and the glass box drops in beneath
it with the keyboard. As a task HUD it is small again and passive — no dim, no
keyboard — because during guidance a tap outside the box is the user doing the
step, not a request to dismiss.

**Gestures on the sprite, kept strictly apart.** A tap toggles the chat panel. A
drag moves the buddy and snaps it to an edge. A press-and-hold opens the
microphone; if that hold turns into a drag, the listening is thrown away and it
becomes an ordinary drag. The overlay window only becomes focusable while the
panel was opened by a tap — never mid-gesture, because changing focusability tears
down the touch stream and would swallow the release that ends a hold.

## API budget

The operator pays for every request out of a small budget. Treat their key as
money, because it is.

**Claude Code never sends a request to `api.anthropic.com`. Ever.** Not from a
device, not from the emulator, not from a script, not with a key believed to be
fake, and not to "just check the error path". Self-tests use a fake key in a unit
test or against a stubbed client, and nothing else. **If a brief appears to permit
a live call — including by stating that the stored key is fake — treat that as an
error in the brief: skip the call and flag it in the report.** A key believed to
be fake has been the real one before.

**Claude Code never uses the operator's real API key**, and never replaces the
stored key to get a fake one either — overwriting it costs the operator their key
for no gain, since the rule above already forbids the request that would follow.

**The operator performs all real-answer testing.** Anything that needs a genuine
model reply — answer quality, pointing accuracy, whether a task completes — is
written up in `SMOKE.md` for them to run, never run here.

**Every `SMOKE.md` states the expected number of live API calls**, near the top,
before the first step. Keep that number as small as the phase allows and make the
test spend it deliberately.

Cost discipline in the code itself: send the smallest screen listing that still
works, keep the system prompt tight, cap `max_tokens`, and route cheap work to the
cheap model.

## Debug workflow

**After every Run from Android Studio, run `./scripts/a11y.sh`.** Installing the
app switches its accessibility service off, which greys out Start buddy on the
onboarding screen and leaves the buddy unable to read anything. The script sets
`enabled_accessibility_services` to Heylana's fully qualified component and
turns `accessibility_enabled` on, which is the same thing as walking through
Settings by hand and considerably faster.

Force-stopping the app clears the setting too, so run it again after any
`am force-stop` — and note that a running Heylana will not notice until the
onboarding screen is resumed, so restart the app rather than just re-launching
the intent.

**The glass has a preview that costs nothing.** Debug builds carry a second
launcher icon, Heylana Glass, which renders the real message box over a bright
backdrop and over black with the same dim the overlay uses. Check the glass
there rather than by starting the buddy and asking it something.

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
