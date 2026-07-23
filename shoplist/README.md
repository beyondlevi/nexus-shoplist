# Shopping List — Rokid Nexus plugin

A headless Nexus plugin (id `shoplist`) that shows a shopping list on the
glasses HUD as a single card and lets you check items off with the R08 ring.
Editing the list (add / remove / clear checked) is done on the phone in the
plugin's settings screen — headless plugins never draw on the glasses.

## Surface

- **Card** surface (`surfaces` capability only). One row per item, a `>` cursor
  on the focused row and `[x]`/`[ ]` for checked state, a footer with the
  remaining count.

## R08 one-axis input

Fully operable by the R08 Access Bridge through `onNexusInput`:

| Verb | Keycodes | Action |
|------|----------|--------|
| NEXT | `DPAD_RIGHT` / `DPAD_DOWN` | move cursor down (wraps) |
| PREV | `DPAD_LEFT` / `DPAD_UP` | move cursor up (wraps) |
| SELECT | `DPAD_CENTER` / `ENTER` | check / uncheck the focused item |
| BACK | `KEYCODE_BACK` | hide the surface (self-close) |

The navigability contract is proven off-device by `ShopListStateTest`.

## Limits honoured

- Card rows windowed to 60 (< the 64-row ceiling); labels capped at 120 chars
  (< the 240-char line limit).
- `contentKey` is a short hash of the visible content (`shop-<hex>`, well under
  128 chars) — never concatenated content.

## Build

From the Rokid-Nexus checkout:

```bash
./gradlew :plugin-shoplist:testDebugUnitTest   # R08 state-machine proof
./gradlew :plugin-shoplist:assembleDebug       # debug APK
```

APK: `plugins/shoplist/build/outputs/apk/debug/*.apk`.

## Layout

- `ShopItem` — immutable list entry.
- `ShopListState` — pure, Android-free one-axis state machine (the testable core).
- `ShopListStore` — SharedPreferences + JSON persistence with the card limits enforced.
- `ShopListPluginService` — bus adapter; card surface + ring input.
- `ShopListActivity` — phone settings screen (NexusUi kit) + uninstall card.
