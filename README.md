# Nexus Shopping List

A headless [Rokid Nexus](https://github.com/Anezium/Rokid-Nexus) plugin
(id `shoplist`, package `com.volund.nexus.plugin.shoplist`) that shows a
shopping list on the glasses HUD and lets you check items off with the R08
smart-ring. Editing the list — add, remove, or paste a whole list from another
app — is done on the phone in the plugin's settings screen; headless plugins
never draw their own UI on the glasses.

## Features

- **HUD card surface** — one row per item with a `>` cursor and `[x]`/`[ ]`
  checked state, plus a footer with the remaining count.
- **R08 one-axis input** — NEXT/PREV move the cursor (wrap), SELECT checks the
  focused item off, BACK closes. Nothing needs a second axis, pointer or gesture.
- **Add by voice from the glasses** — the first card row is "Add item by voice";
  tap it to capture the glasses mic (Nexus `microphone` capability + `/audio`
  receive prefix) and dictate a new item. The buffered PCM is transcribed by
  OpenAI (`/v1/audio/transcriptions`) — set your API key in plugin settings.
  Tap to add, back to cancel. No Android mic permission (audio arrives over the hub).
- **Bulk paste import** — paste a multi-line list on the phone and "Add all"
  turns each non-blank line into an item.

## Capabilities

`surfaces` (HUD) and `microphone` (glasses mic for voice dictation). The
microphone is approved per-plugin in Rokid Nexus → Plugin access; the phone also
asks for the Android RECORD_AUDIO runtime permission (required by
SpeechRecognizer even though the audio arrives over the hub). Voice needs
Android 13+.

## R08 input mapping

| Verb | Keycodes | Action |
|------|----------|--------|
| NEXT | `DPAD_RIGHT` / `DPAD_DOWN` | move cursor down (wraps) |
| PREV | `DPAD_LEFT` / `DPAD_UP` | move cursor up (wraps) |
| SELECT | `DPAD_CENTER` / `ENTER` | voice dictate (row 0) / check the focused item |
| BACK | `KEYCODE_BACK` | cancel voice, else hide the surface (self-close) |

## Build

Standalone build against the published SDK on JitPack (JDK 17, Android SDK 36):

```bash
./gradlew :shoplist:testDebugUnitTest   # R08 state-machine + paste-parser proof
./gradlew :shoplist:assembleDebug       # debug APK
```

APK: `shoplist/build/outputs/apk/debug/shoplist-debug.apk`.

### Release (signed)

Set the signing env vars and assemble the release variant:

```bash
export NEXUS_RELEASE_KEYSTORE=/path/to/shoplist-release.p12
export NEXUS_RELEASE_KEYSTORE_PASSWORD=********
export NEXUS_RELEASE_KEY_ALIAS=shoplist
./gradlew :shoplist:assembleRelease
```

The same signing certificate must be reused for every release — the Nexus hub
and Android both pin `package + pluginId + signerSha256`; a new key forces every
user to reinstall and re-approve.

## Install

Published to the Nexus Store (RokidBrew registry). For manual sideload:

```bash
adb install -r shoplist-phone-release.apk
# then approve the "surfaces" capability in Rokid Nexus → Plugin access
```

## License

MIT — see [LICENSE](LICENSE).
