# Asset Specifications — Sokoban (Native Android)

Authoritative numeric/technical spec for every asset. Pairs with `art-direction.md`
(visual intent) and `asset-pipeline.md` (workflow). Where a value is a target/range, it is
marked. Where it is exact, treat it as a hard constraint.

Grid reference unit: **TILE = 128** master pixels per logical cell. All proportions are
expressed relative to TILE.

---

## 1. Camera perspective
- **Orthographic** projection (no vanishing points, no perspective divergence between assets).
- Top-down, tilted **≈18°** from straight-down (**≈72°** pitch). **North-up, no yaw, no roll.**
- One global camera for the entire game. No per-asset relighting or re-angling.
- Ground plane authored **perfectly flat** (square tiles) → seamless tiling.
- Height expressed as **upward extrusion** from the ground cell (top face + short front/side face).

## 2. Tile / grid dimensions
- **1 logical cell = 128 × 128 (TILE).** Walls, floors, boxes, player, goals all snap to this.
- Sokoban boards are rectangles of whole cells. Objects occupy **exactly one cell**.
- Wall & floor sprites are built on the 128-wide cell so they tile edge-to-edge with **no seams**.
- Tall sprites may extrude **above** their cell (into negative Y); their ground band is still the
  128 cell. Anchor keeps them seated correctly (see §11).

## 3. Asset resolution
- **Master export = 128 px per TILE cell** (the "1×"/baseline density).
- **Authoring canvas = 256 × 256 px per cell** (2× supersample) → downsample for crisp, detailed
  128 output. Author at 2×, deliver at 1× (and optionally higher density buckets, §4).
- Effect atlases/UI panels may use larger canvases but keep the same DPI logic and proportions.

## 4. Pixel density / scale (Android)
- Baseline **1× = 128 px/cell** (mdpi, 160 dpi reference).
- Optional density buckets: **2× = 256** (xhdpi), **3× = 384** (xxhdpi), **4× = 512** (xxxhdpi).
- **Recommended for the prototype:** author high-res, place a single master PNG in
  `drawable-nodpi/`, and **scale in code to dp**. This avoids Android's automatic rescaling and
  filtering artifacts, and keeps one source of truth. (Alternative: ship density buckets — see
  `asset-pipeline.md` → Android import.)
- On-screen the board fits the device width; a cell renders ≈ **36–44 dp**. Assets are
  oversized and downsampled (linear filtering) → crisp HD result.

## 5. Lighting direction
- Single key light from **upper-left**, elevation **~40°**, warm-neutral daylight (~5600K).
- Highlights land on the **top and left** faces; shadow side is lower-right.
- Identical for every asset. A right-lit asset is wrong.

## 6. Shadow behavior
- Soft **contact shadow** under each object, offset toward **lower-right**.
- Multiply blend, low opacity (**~25–35%**), blurred edge — never a hard cast silhouette.
- **Ambient occlusion** in crevices (mortar, plank seams, wall base). Never crush to pure black.
- Floor tiles carry a faint ambient base shade but **no directional cast shadow** (shadows are
  cast by the objects onto the floor, layered above the floor, not baked into floor tiles).

## 7. Material rendering style
- Realistic, painterly/PBR-informed: **diffuse-dominant** with subtle broad specular on stone/wood.
- Real albedo texture (brick grain, wood planks, stone speckle), soft AO, gentle micro-variation.
- **No** cel-shading bands, **no** black outlines, **no** gloss/plastic sheen (except a tiny crisp
  highlight on metal corners).
- Clean and art-directed, not photo-scan noisy. Detail must survive downscale to ~36–44 dp.

## 8. Color palette
- Full palette + figure/ground rules in `art-direction.md` §6.
- Warm earthy environment; **player is the only cool hue** (muted teal `#3E6B78`).
- No two gameplay categories may share the same hue+value. Values are representative starting
  points; final hexes are frozen when the Style Reference is approved.

## 9. Background / floor treatment
- **Floor tiles are opaque, full-bleed, seamless** (no alpha, tile edge-to-edge). Base `#C9BBA6`
  with subtle variants (`_01`, `_02`) for non-repetitive ground. Grout lines align to the grid.
- Area **outside the board** = deep desaturated surround `#2B2622` with a soft vignette, so the
  puzzle board reads as the focal region. Decorative environment (vegetation, etc.) lives only
  here and must never overlap playable cells.

## 10. Transparency requirements
- **RGBA PNG**, **straight (non-premultiplied) alpha**. Transparent background for objects,
  character, goals, effects, UI. **No unintended background** anywhere.
- Floor tiles: opaque, no alpha. Walls: opaque across the full 128 footprint; alpha only outside
  the silhouette / above the top face.
- No semi-transparent "ghost" fringes; clean 1-px alpha edges. No alpha dithering artifacts.

## 11. Sprite anchoring
- **Anchor = BOTTOM-CENTER of the logical ground cell** for all gameplay sprites.
- In a 128-wide cell band, anchor = **(64, 128)** measured from the top-left of the cell band;
  extrusion extends upward (negative Y) from there.
- Guarantees feet/box-base/wall-base sit on the floor plane and tall elements extrude upward
  consistently. Every frame of an animation shares the **identical anchor**.

## 12. Character proportions (per TILE = 128)
- Occupies **exactly one cell** for gameplay.
- Ground footprint ≈ **72 × 72** (reads as a figure standing in the cell, not filling it).
- Total on-screen height ≈ **168** (≈1.3 × TILE), including head; silhouette clearly humanoid,
  simple and readable at small size.
- Stylized-real proportions (roughly 1:3.5 head-to-body) — believable, not chibi, not realistic-
  human-photograph. Muted teal outfit, neutral skin `#D9B79A`.
- See "Character pipeline" for how directional frames derive from one master.

## 13. Box proportions (per TILE = 128)
- Ground footprint ≈ **104 × 104**, centered in the cell (≈12 inset each side → reads as an
  object sitting in the cell, edges visible around it).
- Extrusion height ≈ **52** (top face + visible front and right faces). Warm oak, plank top,
  metal corner brackets.
- `object_box_on_goal` = same box composited over a lit goal (goal glow may spill slightly).

## 14. Wall proportions (per TILE = 128)
- Footprint = **full 128 width**, seamless side-to-side so walls join with no gaps.
- Extrusion height ≈ **64** (0.5 × TILE) — the tallest gameplay element → reads as solid barrier.
- Top face (cap) + front face of brick. Variants: straight, corner, end, junction — all sharing
  the same 128 cell, cap height, and brick scale so any combination tiles seamlessly.

## 15. Goal proportions (per TILE = 128)
- Flat floor marker, **no extrusion**. Emblem ≈ **96 × 96**, centered in the cell.
- Desaturated gold `#D9B24C` inlay, **low contrast** so a box on top stays the dominant read.
- Subtle recessed/embossed look (thin AO ring), not a bright glowing disc.

## 16. Naming conventions
- Pattern: `{category}_{element}[_{variant|state}][_NN]` — **lowercase, snake_case** only.
- Categories: `environment_`, `object_`, `character_`, `effect_`, `ui_`.
- Canonical names:
  `environment_wall_straight`, `environment_wall_corner`, `environment_wall_end`,
  `environment_wall_junction`, `environment_floor_base`, `environment_floor_variant_01`,
  `object_box`, `object_box_on_goal`, `object_goal`,
  `character_idle`, `character_walk_up`, `character_walk_down`, `character_walk_left`,
  `character_walk_right`,
  `effect_push_dust`, `effect_goal_activate`, `effect_level_complete`,
  `ui_restart`, `ui_undo`, `ui_back`, `ui_level_indicator`, `ui_complete`.
- **Forbidden:** `final_final.png`, `new_box.png`, `box2.png`, `wall_new.png`, capitals, spaces,
  version words (`v2`, `final`, `new`, `copy`), or ad-hoc numbers.

## 17. File formats
- **Delivery:** PNG-24/32, RGBA, **straight alpha**, no interlace, **sRGB**, no embedded ICC
  profile, no text/metadata chunks, no premultiplication.
- **Source/authoring:** layered lossless in `assets/source/` (e.g., `.kra`/`.psd`/`.clip`), one
  source per asset, named to match the delivery name.
- **Atlases (later):** TexturePacker-style `.png` + `.json` in `assets/processed/`, with padding
  and extrusion to prevent bleed; not needed for the 5-asset prototype.
- **Canva MCP export (generation backend):** when an asset is produced via Canva, use
  `export-design` with `format.type:"png"`. For sprites needing transparency, set
  `transparent_background:true` (or run `remove-background`), then `curl` the returned signed
  `export-download.canva.com` URL to `assets/generated/`. Treat Canva's output as a 2×-class
  draft to downsample/QC — its native canvas will NOT match TILE=128, so rescale + verify
  proportions/anchor against this spec before approval. (`media.canva.com` thumbnail URLs are
  signature-bound and usually 403 on `curl` — always use `export-design` URLs.)

## 18. Android import requirements
- Resource filenames: **`[a-z0-9_]` only, no capitals** (AAPT enforcement). Maps directly to the
  naming convention (§16).
- Place final PNGs in `app/src/main/res/drawable-nodpi/` and scale to dp in code (recommended), or
  ship density buckets in `drawable-{mdpi,xhdpi,xxhdpi,xxxhdpi}/`.
- Use **linear filtering** for downscale quality; keep **mipmaps off** for exact-grid sprites (and
  add atlas padding if atlasing) to avoid texel bleed at cell borders.
- Keep **sRGB**, no alpha premultiply, no nine-patch. Consider WebP for shipping size (lossless
  or high-quality lossy) but keep PNG as the pipeline interchange format.
- Disable/verify AAPT PNG-crunching does not alter alpha edges on these sprites; if it does,
  switch those files to WebP or `nodpi` raw assets.
- Sprite dimensions must be multiples of the cell logic where tiling matters; keep file names in
  sync with the code's sprite registry to avoid orphaned resources.

---

## Quick per-asset dimension table (master 1× = TILE 128)

| Asset | Canvas (w×h) | Footprint | Extrusion | Anchor (x,y from top-left of band) |
|---|---|---|---|---|
| `environment_floor_base` (+variants) | 128×128 | 128×128 | 0 | (64,128) |
| `environment_wall_straight` | 128×192 | 128 wide | ≈64 up | (64,128) |
| `environment_wall_corner` / `_end` / `_junction` | 128×192 | 128 wide | ≈64 up | (64,128) |
| `object_box` | 128×180 | ≈104×104 | ≈52 up | (64,128) |
| `object_box_on_goal` | 128×180 | ≈104×104 | ≈52 up | (64,128) |
| `object_goal` | 128×128 | ≈96×96 emblem | 0 | (64,128) |
| `character_idle` (+ walk frames) | 128×196 | ≈72×72 | to ≈168 tall | (64,128) — identical in all frames |

(Canvas heights include the upward extrusion above the 128 cell band; the ground band bottom is
the anchor line. Exact extrusion px may be tuned ±10% during Style Reference approval, then frozen.)
