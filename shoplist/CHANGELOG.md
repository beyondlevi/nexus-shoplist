# Changelog — Shopping List

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
