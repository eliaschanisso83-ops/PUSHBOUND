# Pushbound — Brand Identity

Original logo system for **Pushbound** (PUSH + BOUND), a native Android Sokoban puzzle game.
Every asset here was generated from a single geometry model in `branding/LogoKit.java`,
rendered to raster (Java2D) and vector (hand-authored SVG) from the *same* primitives —
no system fonts are used anywhere, and the wordmark is drawn from geometric primitives
rather than typed, which is what makes it proprietary.

---

## 1. The symbol

A closed square **bound** with a recess bitten out of the *inner* face of its right wall,
and the **block** seated inside that recess behind an even keyline.

| Signal it carries | How |
|---|---|
| Boundary / wall | The unbroken outer square silhouette |
| Pushing | The wall has *yielded* exactly where the block presses |
| Direction | The block sits 105 units right of centre — the empty left is distance already travelled |
| Puzzle solving | A block inside a confined frame |
| Progression | The frame opens toward the right |

Two constraints drove the construction and must be preserved if it is ever re-drawn:

1. **The outer silhouette is never broken.** 60 units of wall are left standing at
   `x790..850` through the whole recess, so the mark degrades to a plain frame at tiny
   sizes instead of collapsing into a "C".
2. **Every keyline is uniform.** The block clears the recess on three sides by exactly
   45 units, so the mono version still separates.

### Geometry (1000 × 1000 canvas)

| Element | Value |
|---|---|
| Mark box | `150 … 850` (700 units, 70 % of canvas → inside the Android adaptive-icon safe zone) |
| Wall thickness | `130` |
| Recess | `y315 … y685`, depth `70` (inner face `720` → back `790`) |
| Remaining wall sliver | `60` (`x790 … x850`) |
| Block | `280 × 280` at `(465, 360)` |
| Keyline (recessed sides) | `45` |
| Keyline (top / bottom, inside the bound) | `80` |
| Gap between recess and top/bottom wall | `35` |

---

## 2. The wordmark

Geometric sans drawn entirely from primitives — stems, half-annuli and one stroked
Bézier for the `s`. Consistent `16`-unit stem, `76` x-height, `105` cap/ascender,
circular bowls (perfect circles for `o b d`, half-annuli for `P u n h`).

Round geometric counters are the proprietary tell: the `o b d u` counters are exact
circles of radius `22`, and the `P` bowl is a concentric half-annulus.

---

## 3. Palette

| Name | Hex | Role |
|---|---|---|
| **Deep Teal** | `#0F5A63` | Primary — the bound / wall |
| **Kiln Red** | `#D6462B` | Accent — the block / the player / all energy |
| **Ink** | `#191817` | Wordmark, monochrome artwork, dark grounds |
| **Paper** | `#F4EFE6` | Primary light ground, reversed artwork |
| **Bone** | `#EFE8DA` | Secondary light ground |

Near-complementary pairing: warm fired-clay red against cool teal, echoing the game's
own palette (earth-toned walls, the single cool-hued player). Flat fills only — no
gradients.

---

## 4. Compositions

| Variant | Files |
|---|---|
| Horizontal | `lockup-horizontal-{color,mono,reversed}` |
| Stacked | `lockup-stacked-{color,mono,reversed}` |
| Symbol only | `mark-{color,mono,reversed}` |
| Android icon | `app-icon-*`, `app-icon-dark-*`, `app-icon-mono-*` (512/192/144/96/48) |
| Splash | `splash-1080x1920` |

`color` and `mono` ship on **transparent** grounds; `reversed` and every icon ship on a
**solid** ground (see `qc.png` for the audit).

---

## 5. Usage

- **Clear space:** 25 % of the symbol's height on all sides (already baked into the
  lockups as `0.5 × cap`).
- **Minimum sizes:** symbol `16 px` (survives — but the recess only reads from ~`32 px`,
  so use a plain-frame treatment below that); horizontal lockup ≈ `320 px` wide.
- **Stacked vs horizontal:** horizontal for headers, landing pages and in-game chrome;
  stacked for splash and centred placements; symbol alone for the app icon, favicons
  and social avatars.

**Do not:** recolour outside the palette, stretch or condense, add gradients, bevels,
shadows or outlines, rotate the mark, reposition the block away from the recess, or set
the logo on a busy image without a solid ground behind it.

---

## 6. Provenance

- Generator: `branding/LogoKit.java` (modes: `concepts sheet round2 … round6 wordmark
  glyphs solo lockups symtest proof kit qc`)
- The PNG and SVG are produced from one model. Arc angles were verified at pixel level
  because Java2D measures arc angles **counter-clockwise** (0 = east, +90 = north) while
  SVG's `sweep-flag` runs clockwise — the vector backend inverts the flag and negates the
  endpoints so both formats agree.
- Nothing in `../` was modified; this folder is generated output only.
