# Asset Pipeline — Sokoban (Native Android)

The end-to-end workflow for producing **consistent, high-quality** game assets. This pipeline
exists so that every future asset — now or later — matches the same camera, lighting, scale,
and material language. Follow it in order. Do not skip gates.

Companion docs: `art-direction.md` (visual authority) · `asset-specifications.md` (numbers).

---

## 1. Repository layout

```
assets/
  source/        # layered authoring files (.kra/.psd/.clip), one per asset, name-matched
  references/    # photo/material/inspiration refs used as input (not shipped)
  approved/      # the STYLE REFERENCE + authority card + any frozen approved masters
  generated/     # raw output straight from the generator, pre-QC (may be dirty)
  processed/     # post-processed, QC-passing, density/atlas-prepped delivery files
  sprites/       # generic sprite drop (effects/misc) [organized as needed]
  tiles/         # grid tiles (walls, floors) organized for the tileset
  characters/    # master character + derived directional frames
  environment/   # environment sets (walls, floors, vegetation)
  objects/       # gameplay objects (box, goal, box_on_goal)
  ui/            # UI icons/panels
docs/
  art-direction.md
  asset-pipeline.md
  asset-specifications.md
.opencode/skills/game-asset-pipeline/SKILL.md   # reusable agent skill encoding this workflow
```

> The `environment/`, `objects/`, `characters/`, `ui/`, `tiles/`, `sprites/` folders are the
> **organized home** for approved, categorized assets. The lifecycle folders
> (`generated/` → `processed/`) are the **staging area**. An asset moves:
> `source/` → `generated/` → (QC) → `processed/` → (categorized folder) → `approved/` (if it
> becomes part of the Style Reference set).

---

## 2. Pipeline stages

1. **Specify** — read `art-direction.md` + `asset-specifications.md`. Note the exact cell,
   footprint, extrusion, anchor, palette role, and lighting for the target asset.
2. **Reference** — gather/confirm real-world material reference in `assets/references/`
   (e.g., real brick, real oak crate). Reference is input only; it never ships.
3. **Generate** — produce the asset to spec at 2× authoring res. Output goes to
   `assets/generated/` with the **correct canonical name** from the start.
4. **Process** — downsample to 1× master, clean alpha edges, strip metadata, enforce sRGB,
   straight alpha, correct canvas/anchor. Output → `assets/processed/`.
5. **QC gate** — run the full checklist (§5). Any failure → back to step 3.
6. **Consistency review** — diff against the approved **Style Reference** using the
   checklist in `art-direction.md` §7. Flag every mismatch; do **not** silently approve.
7. **Place** — move the approved file into its categorized folder (`environment/`, `objects/`,
   …) and, for the prototype set, into the game's `res/drawable-nodpi/`.
8. **Freeze** — if the asset helps define the look, add it to `assets/approved/` and update the
   authority card.

---

## 3. Generation methods (allowed)

Pick the method per asset, but the **output must obey the same spec** regardless of method:

- **Vector + raster material overlay** — block shapes in a vector tool, then apply real texture
  and lighting in a raster editor. Good for tiles/objects with clean silhouettes.
- **2.5D render (Blender / procedural)** — model simple primitives (cube wall, crate, capsule
  character), set the **one global orthographic camera + upper-left light**, render with real
  materials, then paint-over for the art-directed finish. **Best for guaranteed lighting/perspective
  consistency** — recommended for walls, boxes, and the character master.
- **Hand-painted** in a raster editor (Krita/Clip Studio/Procreate) over a light/grid guide.
- **Image-generation assist** — permitted ONLY as a first pass that is then **cleaned, re-lit to
  the global light, proportion-corrected, and de-artifacted** before QC. Raw model output is
  never approved directly (it will fail lighting/perspective/proportion checks).
- **Canva MCP (official generation backend)** — see §3.1. Connected via OpenCode's native remote
  MCP + OAuth. Used for text-to-image generation, reference-guided image editing/restyle,
  background removal, and PNG export (incl. transparent background). **A backend only — it is
  never the visual authority.** Canva output is a first-class draft that MUST pass the same QC +
  Style Reference review as any other method.

Whichever method is used, the **Style Reference is the tiebreaker**. If in doubt, render/match
the Style Reference.

### 3.1 Canva MCP generation backend

**Connection:** OpenCode → remote MCP `https://mcp.canva.com/mcp` (the general/waitlist endpoint
— this one supports Dynamic Client Registration, which the Developer Portal endpoint
`api.canva.com/connect/v1/mcp` does NOT). Auth is OpenCode-native OAuth: `opencode mcp auth canva`
→ browser → authorize → tokens stored + auto-refreshed. No manual client_id/secret.

**Target tool flow (verified available):**
```
visual spec (docs/art-direction.md + asset-specifications.md)
  → craft a precise prompt (camera/light/palette/scale baked into the prompt text)
  → canva.generate-image            (text-to-image, or reference-guided edit via imageReferences)
  → canva.get-generate-image-job    (poll → returns MEDIA content ref)
  → place into a design / export
  → canva.export-design             (format.type "png"; supports transparent_background, width/height)
  → signed export-download.canva.com URL → curl → local PNG (2× master)
  → downsample to 1× → QC → Style Reference comparison → approved asset
```

**Key capabilities relevant to this pipeline:**
- `generate-image` — text-to-image; `imageReferences` for reference-guided consistency (feed a
  previously approved asset to steer style).
- `export-design` — PNG export with optional `transparent_background:true` (needed for sprites),
  and `width`/`height` control.
- `remove-background` — strip a background to transparent (useful for object/character sprites).
- `separate-image-layers` — split a flat image into text/objects/background layers.
- `get-design-thumbnails`, `export-design` — return a **signed URL**; `curl` it to a local file,
  then `Read` it → **genuine visual inspection is AVAILABLE** (verified).

**Visual inspection:** the model CAN see Canva output. `export-design` returns a signed
`export-download.canva.com` PNG URL → `curl` → valid PNG → `Read` renders it. This enables real
visual QC, not just metadata checks.

**Honest limits (why Canva is a backend, not the authority):**
- Canva's image model will not automatically obey the strict orthographic 18° camera, TILE=128
  grid, or the exact upper-left light across assets. Every prompt must restate camera/light/
  palette/scale, and output still requires QC + Style Reference correction.
- Canva leans design/vector; it will not by itself hit the "realistic brick/wood/stone PBR with
  AO + soft contact shadow" bar. Treat Canva output as a strong draft/base to refine, not a
  finished master.
- **`docs/art-direction.md` remains the single source of truth** for camera, perspective,
  lighting, materials, colors, proportions, grid, scale, shadows, and consistency. Canva never
  overrides it.

---

## 4. The STYLE REFERENCE workflow (visual authority)

The Style Reference is created **once**, early, and then governs everything.

**Creation (Milestone 1):**
1. Generate the five prototype assets to spec: brick wall, floor, wooden box, goal, player.
2. Assemble them on a small representative board (e.g., a 5×4 fragment) at gameplay scale,
   correct lighting, correct layering (floor → goal → box → wall → player → shadows).
3. Export as `assets/approved/style_reference_v1.png`.
4. Write the authority card `assets/approved/style_reference_v1.md` (template below).

**Authority card template** (`assets/approved/style_reference_v1.md`):
```markdown
# Style Reference v1 — Authority Card
- Image: style_reference_v1.png
- Approved date: YYYY-MM-DD
- Camera: orthographic, ~18deg tilt, north-up
- Light: upper-left, ~40deg elevation, warm-neutral
- Shadow: soft, lower-right, ~30% multiply
- Palette anchors: floor #____, brick #____, wood #____, goal #____, player #____
- Scale: TILE=128; wall extrude ___, box footprint ___ , character height ___
- Frozen decisions: (anything tuned during approval)
- Notes / known trade-offs:
```

**Usage:** every new asset is placed beside / compared against this image at gameplay scale.
Run the 9-point consistency checklist (`art-direction.md` §7). Log pass/fail. **No silent
approvals.** To change the established look, you must deliberately issue a NEW Style Reference
version — you do not drift asset-by-asset.

---

## 5. Quality-control checklist (every asset, before `processed/`)

Verify **all**; a single failure blocks the asset:

- [ ] Correct dimensions (matches spec table; correct canvas + cell band)
- [ ] Correct transparency (straight-alpha RGBA; no unintended background; clean edges)
- [ ] Correct file format (PNG, sRGB, no metadata/ICC, not premultiplied)
- [ ] Correct naming (canonical snake_case name; no forbidden names)
- [ ] Correct scale (footprint/extrusion per spec; box/char occupy exactly one cell)
- [ ] Correct anchor (bottom-center = (64,128) of the cell band)
- [ ] No text, no watermark, no signature, no UI chrome baked in
- [ ] No duplicated/mirrored objects, no malformed geometry
- [ ] No AI artifacts (extra/again limbs, warped edges, gibberish, mush, tiling seams)
- [ ] Visual consistency with the Style Reference (all 9 checklist rows)
- [ ] Correct grid alignment (tiles tile seamlessly; corners connect; no seams)

Automatable checks (recommended small script later): dimension assert, alpha-presence assert,
filename lint (regex), metadata strip, and a "corner pixels / background" heuristic. Manual
checks: everything visual.

---

## 6. Naming enforcement
- Canonical pattern `{category}_{element}[_{variant|state}][_NN]`, lowercase snake_case.
- Maintain a single **sprite registry** (a plain list, later a code enum) so filenames used in
  code always match files on disk. No orphaned resources, no case mismatches.

---

## 7. Milestones (do NOT front-load quantity)

**Milestone 1 — Visual prototype (current goal).** ONE playable level, FIVE assets only:
1. `environment_wall_straight` (brick)
2. `environment_floor_base`
3. `object_box`
4. `object_goal`
5. `character_idle`

Compose the Style Reference from these five, approve it, and validate them **together in one
playable prototype** on a real device width. This proves the look and the pipeline before any
decoration exists.

**Do NOT** create the 15 final levels, decorative assets, effects, vegetation, or UI yet.

**Milestone 2 — After the prototype reads well:** remaining tileset (wall corner/end/junction,
floor variants), directional character frames (derived from the approved master), `object_box_on_goal`.

**Milestone 3 —** effects (push dust, goal activation, level complete), UI set, environmental
decoration/vegetation, and the full level content.

**Rule:** optimize for **consistency and quality, never quantity.** Never batch-generate a large
set that hasn't been proven against the Style Reference first.

---

## 8. Character pipeline (one master, derived frames)

1. Create **ONE** approved master character (proportions, palette, silhouette, lighting) →
   `assets/characters/character_master` (source + processed).
2. Approve it against the Style Reference. Freeze proportions + anchor.
3. **Derive** all directional frames from the master — do NOT generate them independently:
   `character_idle`, `character_walk_up`, `character_walk_down`, `character_walk_left`,
   `character_walk_right`.
4. Normalize **every** frame to the **same canvas size** and the **same anchor (64,128)**.
5. Keep identical scale and lighting across frames. Only pose changes; camera/light/scale never do.

Independent generation of "walk left" vs "walk right" from scratch is forbidden — it guarantees
scale/lighting drift.

---

## 9. Tileset / grid rules

- Single logical tile = **TILE (128)**. Everything is built on it.
- Walls and floors tile **seamlessly**; corners and ends must connect correctly in any
  combination. **No standalone wall images that can't tile.**
- Boxes, player, and goals occupy **exactly one cell**.
- Validate tiles by assembling a real multi-tile run and checking for seams at gameplay scale.

---

## 10. Handoff to the game (later — not now)

When gameplay implementation begins, assets land in
`app/src/main/res/drawable-nodpi/` (or density buckets) and are referenced through the sprite
registry. **This pipeline step is intentionally deferred** — no gameplay code, no Gradle project,
and no asset generation happens in this milestone.
