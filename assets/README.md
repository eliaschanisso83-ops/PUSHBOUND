# Assets — folder guide

Lifecycle staging (`generated/` → `processed/`) plus categorized homes for approved assets.
Full workflow: `../docs/asset-pipeline.md`.

| Folder | Purpose |
|---|---|
| `source/` | Layered authoring files (`.kra`/`.psd`/`.clip`), one per asset, name-matched to delivery. |
| `references/` | Real-world material/inspiration refs used as input. Never ships. |
| `approved/` | **The Style Reference** + authority card + frozen approved masters. The visual authority. |
| `generated/` | Raw generator output, pre-QC. May be dirty. Correct canonical names from the start. |
| `processed/` | QC-passing delivery files: 1× master, clean alpha, sRGB, straight alpha, no metadata. |
| `sprites/` | Generic sprite drop (effects/misc) as needed. |
| `tiles/` | Grid tiles (walls, floors) organized for the tileset. Must tile seamlessly. |
| `characters/` | ONE approved master character + derived directional frames. |
| `environment/` | Environment sets: walls, floors, later vegetation/decoration. |
| `objects/` | Gameplay objects: `object_box`, `object_goal`, `object_box_on_goal`. |
| `ui/` | UI icons/panels. |

**Lifecycle:** `source/` → `generated/` → (QC) → `processed/` → categorized folder → `approved/`
(if it helps define the look).

**Naming:** `{category}_{element}[_{variant|state}][_NN]`, lowercase snake_case only.
Canonical names live in `../docs/asset-specifications.md` §16.

> **Status:** this is the pipeline scaffold. No assets have been generated yet, and the Style
> Reference does not exist yet. Milestone 1 will produce five prototype assets (brick wall,
> floor, wooden box, goal, player) and compose the first Style Reference from them.
