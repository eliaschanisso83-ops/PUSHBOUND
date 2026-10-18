# Art Direction — Sokoban (Native Android)

This document is the **visual authority** for every asset in the project. No asset may be
generated, edited, or shipped without conforming to what is written here. If an asset and
this document disagree, the document wins — the asset is regenerated.

---

## 1. Creative intent

A modern, high-definition reinterpretation of the Sokoban games that shipped on early
keypad/feature phones. The nostalgia lives in the **top-down board feeling, the strict grid,
and the push-a-box-around-a-warehouse simplicity** — not in retro pixel aesthetics.

The presentation is **HD and physically believable**: real brick, real wood, real stone,
soft natural light. Every asset should look like it was rendered from a real miniature set
photographed from directly above with a slight downward tilt.

### What this style is
- 2D assets with a **realistic rendered** appearance (painterly/PBR-informed, not flat)
- Physically believable materials (brick, wood, stone, vegetation)
- Slightly-angled top-down camera, orthographic
- Soft natural lighting + subtle ambient shadows
- Clear silhouettes and strong figure/ground separation

### What this style is NOT (hard bans)
- Flat vector art · generic mobile-game UI · cartoonish · pixel art
- Overly colorful / neon / saturated rainbows
- Futuristic / sci-fi / holographic
- Dark "corporate SaaS" or moody horror palettes
- Obvious AI-generated artifacts (extra limbs, warped geometry, gibberish text, muddy mush)

---

## 2. STYLE REFERENCE (the visual authority)

The **Style Reference** is a single approved composite image that becomes the law for all
subsequent assets. It fixes, in one picture: camera angle, lighting direction, shadow softness,
material realism, color language, and level of detail. Every future asset is diffed against it.

> **STATUS: NOT YET CREATED.**
> The Style Reference is produced in the first generation milestone (see
> `asset-pipeline.md` → "Milestone 1"). It is composed from the first five approved
> prototype assets rendered together on one board. Until it is approved and stored in
> `assets/approved/style_reference_v1.png`, **no decorative, effect, UI, or additional
> environment asset may be generated.**

The Style Reference must visibly demonstrate:
1. The exact camera tilt and orthographic grid.
2. The key-light direction (upper-left) and the resulting soft shadows (lower-right).
3. The brick wall, floor, wooden box, goal, and player together, at gameplay scale.
4. The restrained earthy palette with the player as the only cool-hued element.
5. Seamless floor and wall tiling with no visible seams.

A short written "authority card" accompanies it:
`assets/approved/style_reference_v1.md` (see template in `asset-pipeline.md`).

---

## 3. Camera & perspective (summary — full spec in `asset-specifications.md`)

- **Orthographic**, top-down, tilted approximately **18°** from straight-down (≈72° pitch).
- **North-up**, no roll, no yaw. One single global camera for the whole game.
- The **ground plane is authored perfectly flat** (square floor tiles, no perspective
  distortion) so tiles align and tile seamlessly.
- **Height is implied by extrusion**: walls, boxes, and the character show a top face plus a
  short vertical front/side face, drawn upward from the ground cell. This yields the
  "slightly angled" read while keeping the grid mathematically perfect.

Because the camera is shared, **two assets must never disagree about the angle**. An asset
rendered with a different tilt is rejected even if it looks good in isolation.

---

## 4. Lighting (single global light)

| Property | Value |
|---|---|
| Key light azimuth | **Upper-left** (from top-left of screen, pointing down-right) |
| Key light elevation | **~40°** above the ground plane |
| Light color | Warm-neutral daylight (~5600K, very slightly warm) |
| Shadow direction | Cast toward **lower-right**, opposite the light |
| Shadow softness | **Soft** — contact shadow with blurred edge, never a hard silhouette |
| Shadow opacity | ~25–35%, multiply blend, low contrast |
| Ambient fill | Neutral, moderate — crevices darken (AO), never crush to pure black |
| Specular | Broad and subtle on stone/wood; tiny crisp highlight only on metal corners |

**Every asset uses this identical light.** There is no per-asset artistic relighting. If a
proposed asset's highlight sits on the right side, it is lit wrong → reject and regenerate.

---

## 5. Materials

Physically believable, art-directed realism — clean, not noisy. Think "digitally sculpted and
painted miniature," not "photo-scan with dirt everywhere."

- **Brick** — matte fired clay, subtle tonal variation brick-to-brick, recessed mortar with AO,
  slightly chipped/soft edges. No glossy sheen.
- **Wood** — warm oak planks, visible straight grain, soft satin sheen, darker seams between
  planks, faint wear on edges. Metal corner brackets with a small crisp highlight.
- **Stone / floor** — light neutral warm stone, fine speckle, matte, faint large-scale mottling.
  Grout lines subtle. Low texture contrast so gameplay objects pop.
- **Vegetation (later)** — muted sage/olive, soft clumps, matte, understated. Never competes
  with the board.
- **Character** — cloth/matte surfaces, simple readable shapes, no busy patterns.

**Level of detail discipline:** enough detail to read as "real material" at gameplay size, but
**never** so much that surface noise distracts from the puzzle. Detail must survive downscaling
to a ~36–44 dp tile without turning to mush.

---

## 6. Color language & palette

Restrained, earthy, natural, nostalgic. Warm environment, with the **player as the only cool
hue** so the eye finds the controllable character instantly.

### Palette (roles + representative values)

| Role | Use | Hex (representative) |
|---|---|---|
| Brick base | walls | `#A65A44` |
| Brick shadow / mortar | wall crevices | `#6E3A2C` / `#CFC3B4` |
| Brick highlight | lit wall edges | `#C67C5F` |
| Wood base | box | `#B07A42` |
| Wood shadow | box seams | `#6E4A28` |
| Wood highlight | lit box top | `#D2A05E` |
| Metal corner | box brackets | `#4A4A4E` |
| Floor base | floor tiles | `#C9BBA6` |
| Floor grout | tile seams | `#A79884` |
| Goal inlay | goal marker | `#D9B24C` (desaturated gold, low-contrast) |
| Player outfit | character | `#3E6B78` (muted teal — the only cool hue) |
| Player accent | character | `#2A4A54` |
| Player skin/face | character | `#D9B79A` |
| Vegetation (later) | trees/bushes | `#5F6E4A` / `#7C8B5B` |
| Board surround | outside-the-board vignette | `#2B2622` (deep desaturated brown-grey) |

### Figure/ground rules (readability is non-negotiable)
- **Wall** = warm, heavily textured, tallest → reads as solid obstacle.
- **Floor** = lightest, lowest texture → reads as walkable ground.
- **Box** = warm saturated wood, mid height, strong silhouette → reads as pushable.
- **Goal** = subtle, low-contrast emblem → reads as destination but never fights a box on top.
- **Player** = cool hue + distinct silhouette → the single most findable element.

No two gameplay categories may share the same hue+value combination. If a change makes the box
and the wall the same value, the change is wrong.

---

## 7. Consistency rules (the gate)

Every asset obeys the **same** camera, perspective, lighting, shadow direction, scale, material
quality, color language, level of detail, and realism. Assets are never generated "off in their
own world." The workflow is:

1. Read this document + `asset-specifications.md`.
2. Generate/derive the asset to spec.
3. **Review it against the approved Style Reference.**
4. Flag any mismatch (see the checklist below). Do **not** silently approve.

### Mandatory consistency checklist (run for every asset)

| # | Check | Fails if |
|---|---|---|
| 1 | Perspective | tilt/angle differs from the Style Reference |
| 2 | Scale | object is bigger/smaller than its spec'd footprint |
| 3 | Lighting | highlight/shadow sides don't match the global light |
| 4 | Shadow | missing, wrong direction, too hard, or too dark |
| 5 | Material | wrong realism level or wrong substance (e.g., plasticky brick) |
| 6 | Color | hue/value outside the palette or breaks figure/ground |
| 7 | Excessive detail | surface noise that distracts at gameplay size |
| 8 | Insufficient detail | reads as flat/toy/placeholder |
| 9 | Proportions | doesn't match the character/box/wall/goal proportions |

An asset that fails any row is **rejected** and returned to generation with the failing row(s)
called out. There is no "ship it and fix later."

> **Generation backends (incl. Canva MCP) never override this document.** Whether an asset comes
> from Canva's image model, a Blender render, or hand-painting, it is measured against the
> camera/light/palette/proportion rules defined here. Canva is an approved *generation backend*
> (see `asset-pipeline.md` §3.1) — a source of drafts — not an authority on the look.

---

## 8. Composition of the board (context rule)

Gameplay assets are judged **in context, on a board**, not in isolation. A wall that looks fine
alone may be wrong next to the floor. Final approval always happens with the asset placed on a
representative grid beside the Style Reference, at gameplay scale, on the target device width.
