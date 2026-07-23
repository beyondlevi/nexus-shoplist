# Changelog — Shopping List

## 1.1.0 — unreleased

- Bulk import: paste a multi-line list on the phone settings screen and "Add
  all" turns each non-blank line into an item (split on newlines, trimmed,
  capped to the list/label limits). Parsing is pure and unit-tested.

## 1.0.0 — unreleased

- First release: headless `shoplist` plugin.
- HUD card surface listing items with a one-axis R08 cursor; SELECT checks an
  item off, BACK closes.
- Phone settings screen (NexusUi kit) to add, remove and clear-checked items,
  plus the mandatory uninstall card.
- SharedPreferences/JSON persistence with card-contract limits enforced
  (<= 60 items, 120-char labels, hashed contentKey).
