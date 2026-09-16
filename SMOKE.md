# Smoke test — quick actions, release build, crash reports

For Minos, on the Seeker, **Judge plan**, wallet connected.

**Live calls this test spends: 5 questions (`/chat`) and 5 spoken answers (`/tts`).**
Steps 1 to 4 are one question and one spoken answer each; step 5 asks one typed
question on the release build, and its answer is spoken too. (The brief said 4 and 4;
the release question is the fifth.) No Sentry step talks to the model.

## 0. Set up

1. In `worker`: `npx wrangler deploy`, then `npx wrangler tail` in a second terminal.
2. Install this debug build, run `./scripts/a11y.sh`, start the buddy.
3. On the computer: `adb logcat -s HeylanaState | grep -E "brain:|action:"`

## 1. An alarm

1. Go to the home screen. Tap the buddy, type `set an alarm for 7 tomorrow`, send.

Expected: the Clock app comes to the front with a **7:00 AM** alarm added and switched
on, and Heylana says **"Alarm set for 7 AM tomorrow."** Logcat: `quick-actions=loaded`,
`action: guard verdict=allowed intent=alarm hour=7 minutes=0`, `action: fired
intent=alarm`. **1 chat, 1 tts.** Delete the test alarm afterwards.

## 2. A timer

1. Home screen. Tap the buddy, type `set a timer for 5 minutes`, send.

Expected: the Clock app opens on a **5:00 timer, running**, and Heylana says "Timer set
for 5 minutes." Logcat: `intent=timer seconds=300`. **1 chat, 1 tts.** Stop the timer.

## 3. Opening an app

1. Home screen. Tap the buddy, type `open the wallet`, send.

Expected: the **Wallet** app comes to the front and Heylana says "Opening Wallet."
Logcat: `action: open_app match="Wallet" package=com.solanamobile.wallet score=100`.
**1 chat, 1 tts.**

## 4. The dialer, never a call

1. Home screen. Tap the buddy, type `call 0800 123 4567`, send.

Expected: the Phone app opens with **08001234567** typed in and **no call placed** — the
green call button is waiting for you. Heylana says "Opening the dialer with
08001234567." Logcat: `intent=dial digits=11 name=false`. **1 chat, 1 tts.** Back out
without calling.

## 5. The release build

The release build is signed with your own key, so it cannot install over the debug
build: the debug build has to come off first, which signs the wallet out and clears
Heylana's settings on the phone.

1. **Once only, make the key** (it asks for a password; use the same one twice when
   asked for the key password):

   ```
   mkdir -p ~/.heylana
   keytool -genkeypair -v -keystore ~/.heylana/release.keystore -alias heylana \
     -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
   ```

   Back the file and its password up somewhere safe: an update signed by any other key
   will not install over this one.

2. Add to `local.properties`:

   ```
   heylana.keystore=~/.heylana/release.keystore
   heylana.keystorePass=<the password>
   heylana.keyAlias=heylana
   heylana.keyPass=<the same password>
   ```

3. Its SHA-256 for Seed Vault: `keytool -list -v -keystore ~/.heylana/release.keystore
   -alias heylana | grep SHA256`. Add it to `ASSETLINKS_SHA256` in `worker/wrangler.toml`
   after the debug one, comma-separated, and `npx wrangler deploy`.
4. `./scripts/release.sh`

Expected: it ends with the APK path `…/dist/heylana-0.9.0.apk`, its SHA-256, and the
signing certificate's SHA-256 (the same as step 3).

5. `adb uninstall xyz.heylana.app`, then `adb install -r dist/heylana-0.9.0.apk`, then
   `./scripts/a11y.sh`.
6. Open Heylana.

Expected: onboarding shows the setup checklist and **Start buddy** works once the
switches are on. Settings: scroll to the bottom — **no Debug section** (no Debug
states, no Simulate Free plan), and the last line reads **"Heylana 0.9.0 (1)"**.

7. Connect the wallet again. Start buddy, tap it, type `what does this screen show`, send.

Expected: an answer about the screen, spoken. **1 chat, 1 tts** (Logcat is silent in a release build;
check `wrangler tail` for the `/chat` line). Then reinstall the debug build the same way
(uninstall first) for step 6.

## 6. Crash reports

1. In Sentry (sentry.io): **Projects → Create Project**, platform **Android**, name it
   `heylana-app`. Then **Settings → Projects → heylana-app → Client Keys (DSN)**, and copy
   the DSN.
2. Add to `local.properties`:

   ```
   heylana.sentryDsn=<that DSN>
   heylana.sentryDebug=true
   ```

3. Rebuild and install the debug build, run `./scripts/a11y.sh`.
4. Settings → scroll to the bottom → **long-press "Heylana 0.9.0 (1)"**.

Expected: Heylana closes (a crash, on purpose). Open Heylana again — the report goes on
this second start.

5. In Sentry: **Issues**, project heylana-app.

Expected: a `TestCrash` issue whose message reads "Heylana test crash. This address must
arrive as [address]: **[address]**" — no 44-character address anywhere in the event, no
user, no screenshot, no view hierarchy. **No live calls.**

6. Take `heylana.sentryDebug=true` out of `local.properties` (release builds report without
   it).

**The worker:** create a second project, platform **Cloudflare Workers**, named
`heylana-worker`; copy its DSN from **Settings → Projects → heylana-worker → Client Keys
(DSN)**, then in `worker/`: `npx wrangler secret put SENTRY_DSN` and paste it. It reports
only errors the worker did not handle, so nothing appears unless one happens.
