# Shortstop

**Small model first. Your secrets stay home.**

Shortstop checks texts, e-mails and links for scams with on-device AI first. When the small model is unsure, it hides names, phone numbers and account numbers on your device, then asks a cloud model with the masked text only. The same engine also guards the other way: before you paste a contract into an AI chat, it offers a masked copy.

Built with [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) hybrid inference for #BuildHybridAI 2026.

| | |
|---|---|
| Try it | https://shortstop-b108a.web.app (press any example) |
| Video | https://youtu.be/x3d33KkGsHY |
| Chrome extension and Android APK | [Releases](https://github.com/its-janghoon/shortstop/releases/latest) |

## Results

150 synthetic test messages written by someone who never saw the engine or the earlier test sets (50 scams, 50 tricky legitimate messages, 50 documents full of private data), measured once, in Chrome on a laptop with Gemini Nano (RTX 4060). Raw rows: `bench-results/bench-final2-nano.jsonl`; scripts: `scripts/bench.mjs`, `scripts/bench-report.mjs`.

| | Correct | Scams caught | Finished on device | Private values sent to the cloud |
|---|---|---|---|---|
| Gemini Nano alone | 81.2% | 78% | 100% | 0 of 346 |
| Plain cloud checker (original text) | 98.0% | 100% | 0% | 348 of 348 |
| **Shortstop** | **92.6%** | **98%** | **82%** | **1 of 348** |

Without an on-device model (every first look goes to the cloud with rule-masked text) Shortstop scored 96.7% with 33 of 348 values sent: the rules miss lower-case first names that Nano catches (`bench-results/bench-final2.jsonl`).

## Test it as a judge

1. **Web:** open https://shortstop-b108a.web.app in Chrome and press an example. Without Gemini Nano the pill says "No on-device model" and the first look runs in the cloud on masked text. To see the on-device path, Chrome 138+ on a supported desktop: turn on Settings → AI innovations → On-device AI, then click the yellow pill "Download on-device model". Turn Wi-Fi off and press "Parcel fee text": the verdict still appears.
2. **Chrome extension:** download `shortstop-extension.zip` from Releases, unzip, `chrome://extensions` → Developer mode → Load unpacked. Right-click a link or selected text → "Check with Shortstop". The side panel opens. For the paste guard, open a site and press "Turn on for this site" in the side panel.
3. **Android:** install `shortstop.apk` from Releases. Share any text to "Check with Shortstop". On [phones that support the ML Kit Prompt API](https://developers.google.com/ml-kit/genai#prompt-device) the first look runs on the phone. Cloud calls from a sideloaded APK need an App Check debug token (shown in logcat as `DebugAppCheckProvider`); without it the app falls back to on-device or rules and says so.

## How it decides

```
text or screenshot
  -> rules on device: hide e-mail, phone, card, account, ID numbers; check links for look-alikes
  -> on-device model (Gemini Nano): verdict, confidence, names to hide
  -> sure?  yes -> answer (nothing left the device)
            no  -> masked text only -> cloud Gemini -> placeholders put back on device -> answer
```

Every answer comes with a **receipt**: where each step ran, how long it took, and the exact text that left the device.

| Part | Where |
|---|---|
| Engine (masking, link rules, scam word rules, routing) | `engine/` |
| Web app (hosted demo) | `web/` |
| Chrome extension (right-click check, side panel, paste guard) | `extension/` |
| Prompts, answer schema, config defaults, demo examples | `shared/` (read by every platform) |
| Android app (share target, Gemini Nano via ML Kit Prompt API) | `android/` |

## Firebase

| Product | Use |
|---|---|
| AI Logic | Triage with `PREFER_ON_DEVICE`, deep check with `ONLY_IN_CLOUD`, screenshots with `ONLY_ON_DEVICE` |
| App Check | Required by AI Logic. reCAPTCHA Enterprise on web, debug token for local runs |
| Remote Config | Confidence threshold, on-device length limit, cloud model, strict mode |
| Authentication | Anonymous sign-in, for per-user limits and report rules |
| Firestore | Community reports: only the SHA-256 of a site name and a count |
| Hosting | Web app |

## Run

```sh
yarn install
cp .env.example .env   # fill in your Firebase web config
yarn test
yarn dev               # web app
yarn build             # dist/web and dist/extension
```

Without a Firebase config the app runs in rules-only mode.

Load the extension: `chrome://extensions` → Developer mode → Load unpacked → `dist/extension`.

## Android

Kotlin + Jetpack Compose, `minSdk` 26 (needed by `firebase-ai-ondevice`), `targetSdk` 37.

The build needs two things from your machine; nothing in git points at them:

| What | How |
|---|---|
| JDK 21 (AGP 9.5 needs 17+; built and tested with Temurin 21) | `export JAVA_HOME=/path/to/jdk-21` |
| Android SDK with `platforms;android-37.0` and `build-tools;37.0.0` | `android/local.properties` with `sdk.dir=/path/to/android-sdk` (git-ignored), or `export ANDROID_HOME=/path/to/android-sdk` |

```sh
cd android
# Firebase config is not in git. Fetch it once:
npx firebase-tools apps:sdkconfig ANDROID 1:973536798905:android:7bb757c6ce99deebb1d368 \
  --project shortstop-b108a --out app/google-services.json
./gradlew testDebugUnitTest assembleDebug lint
./gradlew testDebugUnitTest --tests '*ShotsTest*' -Pshots=/tmp/shots   # render every screen to PNG
adb install app/build/outputs/apk/debug/app-debug.apk
```

- Without `google-services.json` the app builds and runs in rules-only mode.
- `shared/*.json` is copied into the app assets at build time; the unit tests read the same files.
- Debug builds use the App Check debug provider: copy the token from logcat (`DebugAppCheckProvider`) into Firebase console → App Check → Apps → Manage debug tokens. Release builds use Play Integrity. App Check failures never block the app.
- On-device AI runs only on [phones that support the ML Kit Prompt API](https://developers.google.com/ml-kit/genai#prompt-device). Elsewhere the status chip says the cloud sees masked text only, and screenshots are refused.
- Share a text or a screenshot to "Check with Shortstop" from any app. History is kept in a local Room database (masked text only, excluded from backup) and can be cleared.

## Privacy

- Message text never goes to the cloud unmasked when an on-device model is available.
- Screenshots never leave the device. Without an on-device model, Shortstop asks you to paste the text instead.
- Without an on-device model, the first look runs in the cloud on rule-masked text, and the receipt says so.
- Strict mode (Remote Config `strict_on_device`) never calls the cloud.
- Firestore stores only hashes of reported site names.

## License

Apache-2.0
