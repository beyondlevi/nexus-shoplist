# Changelog — Shopping List

## 1.2.3

- Lower `minSdk` from 31 to 30 (Android 11) to match the Nexus platform's
  supported floor — a minSdk-31 APK can't be parsed/installed on Android 11
  phones, which surfaced as a misleading "downloaded APK does not match the
  registry" error in the Store. No functional change.

## 1.2.2

- Voice dictation now shows a confirm step: after transcription the recognized
  item is shown as "Add this item?" — tap to add, back to discard — instead of
  adding it straight to the list.

## 1.2.1

- Fix voice input that never received audio on device: the manifest now includes
  the `/audio` receive prefix so the hub actually delivers the glasses mic frames.
- Rework speech-to-text to the buffered OpenAI path (proven in rokid-inbox-nexus):
  buffer the 16 kHz PCM from `nexusAudioSession`, encode WAV, POST to OpenAI
  `/v1/audio/transcriptions`. Removes the unreliable injected-audio
  `SpeechRecognizer` approach and the `RECORD_AUDIO` permission (adds `INTERNET`).
- Plugin settings now hold the OpenAI API key (stored encrypted), a voice
  on/off toggle, language, and model. Builds against `bus-client:sdk-v0.2.1`.

## 1.2.0

- Add items by voice from the glasses: the card now has an "Add item by voice"
  row; tapping it captures the glasses microphone (Nexus `microphone`
  capability) and pipes the audio into on-device speech-to-text, appending the
  spoken item. Tap to stop, back to cancel.
- STT is Android's on-device `SpeechRecognizer` fed via an injected-audio pipe
  (no cloud, no bundled model) — transposed from Rokid-Relay's Android engine.
- New plugin settings: allow the microphone (RECORD_AUDIO) and set an optional
  recognition language (BCP-47, blank = device default).
- Adds the `microphone` capability, so the plugin returns to Pending until you
  re-approve it in Rokid Nexus → Plugin access.

## 1.1.2

- Declare the built-in `ICON` key `cart` so the cart glyph also renders on the
  glasses HUD, keeping `ICON_DRAWABLE` as a fallback for hubs that don't
  recognize the built-in yet.

## 1.1.1

- Settings header now reads the real `versionName` from the package manager
  instead of a hardcoded string, so it can no longer drift from the manifest.
- Launcher/monochrome icon now uses the plugin's own cart glyph: the adaptive
  foreground insets `@drawable/nexus_glyph_cart` instead of the sample glyph, so
  the cart shows up everywhere the app icon appears.

## 1.1.0

- Bulk import: paste a multi-line list on the phone settings screen and "Add
  all" turns each non-blank line into an item (split on newlines, trimmed,
  capped to the list/label limits). Parsing is pure and unit-tested.

## 1.0.0

- First release: headless `shoplist` plugin.
- HUD card surface listing items with a one-axis R08 cursor; SELECT checks an
  item off, BACK closes.
- Phone settings screen (NexusUi kit) to add, remove and clear-checked items,
  plus the mandatory uninstall card.
- SharedPreferences/JSON persistence with card-contract limits enforced
  (<= 60 items, 120-char labels, hashed contentKey).
