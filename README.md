# Shortstop

**Small model first. Your secrets stay home.**

Shortstop checks texts, e-mails and links for scams with on-device AI first. When the small model is unsure, it hides names, phone numbers and account numbers on your device, then asks a cloud model with the masked text only. The same engine also guards the other way: before you paste a contract into an AI chat, it offers a masked copy.

Built with [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) hybrid inference for #BuildHybridAI 2026.

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

Kotlin + Jetpack Compose, `minSdk` 26 (needed by `firebase-ai-ondevice`), `targetSdk` 37. Needs JDK 17+ and the Android SDK (`sdk.dir` in `android/local.properties`).

```sh
cd android
# Firebase config is not in git. Fetch it once:
npx firebase-tools apps:sdkconfig ANDROID 1:973536798905:android:7bb757c6ce99deebb1d368 \
  --project shortstop-b108a --out app/google-services.json
./gradlew testDebugUnitTest assembleDebug lint
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
