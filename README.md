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
| Android app | `android/` (coming) |

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

## Privacy

- Message text never goes to the cloud unmasked when an on-device model is available.
- Screenshots never leave the device. Without an on-device model, Shortstop asks you to paste the text instead.
- Without an on-device model, the first look runs in the cloud on rule-masked text, and the receipt says so.
- Strict mode (Remote Config `strict_on_device`) never calls the cloud.
- Firestore stores only hashes of reported site names.

## License

Apache-2.0
