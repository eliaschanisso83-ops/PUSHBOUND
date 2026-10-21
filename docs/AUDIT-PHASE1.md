# Pushbound — Phase 1 Audit

Baseline established before any change: **94 / 94 unit tests passing** (verified locally, see
"How this was verified"). No Android SDK is available on the build device, so `assembleDebug` /
`lint` are executed by CI (`.github/workflows/android-build.yml`, last run on `7af6f42`: success).

---

## 0. What the repository actually contains

| Area | State |
| --- | --- |
| Source files | 20 Kotlin files, 2256 lines (`engine/` 7 files + `ui/` 10 + `Levels.kt` + `MainActivity.kt`) |
| Test files | 9 Kotlin files, 1768 lines, **94 tests, all passing** |
| Activities | 1 (`MainActivity`, 21 lines, `setContent { PushboundTheme { GameScreen() } }`) |
| Screens | 1 (gameplay only — no menu, no level select) |
| Levels | **1** (`Levels.first`, 9×8 ASCII) |
| Assets | 24 PNGs in `res/drawable-nodpi` (character ×16, environment ×2, object ×2, UI ×4) |
| Network | **None.** No `INTERNET` permission, no HTTP client, no ads, no analytics |
| Persistence | **None.** No SharedPreferences, DataStore, or database |
| Dependencies | Compose BOM 2024.06.00, core-ktx, activity-compose, lifecycle-runtime-ktx, ui, ui-graphics, foundation, material3, JUnit 4.13.2. **No third-party runtime deps.** |
| minSdk / targetSdk | 24 / 35, AGP 8.7.0, Kotlin 2.0.21, Java 17 |

### Architecture as built

```
MainActivity
  └─ PushboundTheme            (Material3, warm-neutral palette)
       └─ GameScreen           (orientation-aware composition)
            ├─ GameController  (Compose glue around GameSession — no rules)
            ├─ LevelHeader     (level name + MOVES)
            ├─ BoardView       (BoardRenderer, sprite-based)
            ├─ ControlsBar     (DPad + Undo + Restart)
            └─ CompletionOverlay
```

`engine/` is **pure Kotlin with zero Android/Compose imports** — that separation is deliberate,
well executed, and is the single most valuable property of this codebase. `ui/` contains a second
pure layer (`BoardLayout`, `ControlLayout`, `GameLayout`, `CharacterAnimation`, `DebugGridConfig`)
that is pure arithmetic and unit-testable on the JVM. Only 5 files touch Android
(`MainActivity`, `GameScreen`, `BoardRenderer`, `Controls`, `Assets`) plus `GameController` and
`PushboundTheme` which touch Compose.

---

## A. What already works correctly

These are correct, tested, and must not be rewritten.

1. **Deterministic movement rules** (`GameEngine.move`). Floor step, push-when-free, refusal on
   wall / board edge / second box / completed level. Invalid moves are refused without being
   counted — `MoveResult.Blocked` leaves the state (and the counter) untouched. Already satisfies
   "do not count an invalid movement as a movement".
2. **Immutable state snapshots.** `GameState` is a data class; every move produces a new snapshot.
   This makes undo trivially correct and makes the whole engine side-effect free.
3. **Unlimited undo within a level.** `GameSession.history` is an `ArrayDeque<GameState>` with no
   cap. Restores player, boxes, move count, and facing. Tested to exhaustion back to level start.
4. **Immediate, exact restart.** `GameSession.restart()` clears history and rebuilds the initial
   state; tested to restore the *exact* initial state and to make the level playable again.
5. **Immediate completion detection.** `GameEngine.isComplete` requires ≥1 goal and every box on a
   goal. A completed level ignores further movement (tested).
6. **Level authoring format.** `LevelDefinition.fromAscii` parses a textual grid (`#`, ` `, `G`,
   `P`, `B`, `+`) with validation (rectangular, exactly one player, known symbols only). Adding
   levels requires no engine or UI change.
7. **Rotation.** `android:configChanges="keyboardHidden|orientation|screenSize|screenLayout|uiMode"`
   plus `GameController` living above the orientation branch means rotation is a pure UI change:
   level, player, boxes, move count and undo history all survive. Tested at the geometry level.
8. **Responsive layout without hardcoded pixels.** `GameLayout`, `ControlsLayout`, `BoardLayout`
   and `CharacterAnimation` are all pure functions of measured window size, each with its own test
   file. Portrait and landscape both keep the board square and the controls at their portrait size.
9. **Animation/input model.** A single queued input while a move animates (`MOVE_ANIMATION_MS` =
   110 ms), blocked moves give visual feedback with no state change, and `resetEpoch` makes
   restart/undo snap rather than glide across the board.
10. **Offline by construction.** There is no network code at all. Campaign play is offline-first
    today and needs no work to stay that way.
11. **Test discipline.** 94 tests including a BFS solvability test and a documented-solution test
    that replays an exact move list against the real level.

---

## B. What partially works

1. **Undo exists; Redo does not.** `GameSession` has a single history deque. Redo requires a second
   deque and the "new action clears redo" rule.
2. **Moves are counted; pushes are not.** `GameState` has `moves` but no `pushes`. The engine already
   knows a push happened (`MoveResult.Moved.pushedBox`), so the counter is cheap to add — but it
   changes `GameState` equality, which undo/redo snapshots and many existing tests depend on.
3. **Level model is data-driven but under-specified.** `LevelDefinition` has the grid essentials
   (width, height, walls, goals, playerStart, boxStarts) but no id, world, number, mechanics,
   difficulty, par moves, or par pushes.
4. **Level collection is flat and single-entry.** `Levels.all` is `listOf(first)`. There is no
   world/chapter notion and no level selection.
5. **Completion detects but does not resolve.** The overlay shows "Level Complete" and "Continue"
   simply *restarts the same level*. Nothing is preserved, calculated, unlocked, or persisted.
6. **A solver exists, but only inside a test.** `TestLevelSolvabilityTest.shortestSolution` is a
   correct BFS over immutable states returning the shortest move count. It is production-quality
   logic trapped in `src/test`, with no path reconstruction, no push count, no deadlock detection,
   and no reuse.

---

## C. What is missing

Mapped against the required target state. **Nothing below exists even partially.**

| # | Requirement | Status |
| --- | --- | --- |
| 1 | Push counter | missing |
| 2 | Redo | missing |
| 3 | Campaign → Worlds → Levels structure | missing |
| 4 | Level ids, world, number, mechanics, difficulty, par, metadata | missing |
| 5 | Three-tier medal system (Bronze/Silver/Gold) | missing |
| 6 | Difficulty model (board size, blocks, solution length, branching, mechanics) | missing |
| 7 | Production solver / validation pipeline | missing (test-only BFS) |
| 8 | Deadlock detection | missing |
| 9 | Par-move / par-push calculation | missing |
| 10 | Hint system (3 levels) | missing |
| 11 | Game mode abstraction (Campaign, Daily Puzzle) | missing |
| 12 | Daily puzzle (deterministic per date, offline) | missing |
| 13 | Save / progress (unlocked, completed, best medal, best moves, best pushes) | missing |
| 14 | Level progression / unlocking | missing |
| 15 | Level-select navigation | missing |
| 16 | Rewarded-ad architecture | missing |
| 17 | Interstitial-ad architecture | missing (and none to audit — see H) |
| 18 | HUD showing pushes, redo, hint | missing |
| 19 | Tests for all of the above | missing |

---

## D. What should be refactored

1. **Promote the solver out of the test sources.** `shortestSolution` should become
   `engine/solver/LevelSolver` in main, returning a full solution (direction list, moves, pushes)
   so that par calculation, deadlock detection, validation *and* hints all share one implementation.
   This is the highest-leverage refactor available.
2. **`Levels.kt` is both data and catalogue.** Split into `levels/` package: raw level definitions
   (data) and a `Campaign` catalogue that owns world/level ordering. Keeps the engine ignorant of
   content, as required.
3. **`GameController` constructs its own `GameSession`.** It should receive its inputs so progress
   persistence and mode selection can be injected without touching gameplay code.
4. **No state restoration.** `remember { GameController(...) }` means process death loses the run.
   Rotation is safe (configChanges), but a `rememberSaveable`-style path is worth adding for the
   current level state once progress persistence exists.

---

## E. What must NOT be changed

Explicitly protected — already correct, and changing them risks regression:

- The **movement/push/collision rules** in `GameEngine.move`.
- **`GameState` immutability** and the snapshot-per-move undo model.
- **Restart semantics** (clear history, exact initial state).
- **The ASCII level format** and `LevelDefinition.fromAscii` validation.
- **The rotation handling** (`configChanges` + controller above the branch) and the
  portrait/landscape `GameLayout` contract.
- **All pure-math testability** (`BoardLayout`, `ControlLayout`, `GameLayout`,
  `CharacterAnimation` stay free of Android imports).
- **The `engine/` purity boundary** — no Android or Compose import may enter `engine/`.
- **The existing palette and visual identity** (`PushboundTheme` warm neutrals) and the approved
  sprite constants in `ApprovedSprites`.
- **The 94 existing tests** — they encode the contract; they may be *extended* to cover new fields,
  never weakened or deleted.
- **The logo / brand kit** — out of scope entirely.

---

## F. Architectural risks

1. **Adding `pushes` to `GameState` perturbs equality.** `GameState` is a data class used as a map
   key in the BFS solver and asserted with `assertEquals` across several tests. Adding a field is
   correct but every existing construction site and equality assertion must be audited.
2. **Unlimited undo memory.** Each snapshot allocates a fresh `Set<Position>` for `boxes`. On a
   10×10 board with 300 moves this is still only kilobytes, so the current model is acceptable and
   should be kept — but it must not be silently copied into a second structure per move when redo
   is added (redo should store the *same* snapshot objects, not clones).
3. **Solver cost in the gameplay loop.** BFS over `(player, boxes)` states is exponential in box
   count. It must run only at level load / validation / explicit hint request, never per move.
4. **No dependency injection.** Introducing persistence and modes without a container means wiring
   by hand through `MainActivity` → `GameScreen` → controller. Acceptable at this size, but it must
   be threaded deliberately rather than via globals.
5. **Release build has `isMinifyEnabled = false`.** Not a gameplay risk, but R8 is untested; adding
   reflection-based persistence would break it. Persistence must use no reflection.
6. **No instrumentation test source set exists at all.** UI behaviour can only be verified by unit
   tests on the pure layers plus manual/CI build.

---

## G. Gameplay risks

1. **Only one level ships.** Any unlocking or medal system is untestable against real data until
   the catalogue has enough levels to exercise progression.
2. **Medal thresholds need real par values.** Without a solver-derived par, medals would be
   arbitrary — which the requirements explicitly forbid.
3. **Hint quality depends on solver completeness.** If the solver times out on a larger level,
   hints must degrade gracefully rather than hang.
4. **Redo + completion interaction.** Undoing out of a completed state must clear the completed
   flag and the medal award; redoing into it must re-derive them. This is an easy place to desync.
5. **Daily puzzle determinism across devices.** Selection must depend only on the date, never on
   locale, time zone internals, or iteration order of a hash map.

---

## H. Monetization risks

**Current state: there is no monetization of any kind.** No AdMob dependency, no ad code, no
`INTERNET` permission, no analytics. Therefore:

- There is **no interstitial frequency to audit or reduce** — the requirement "if the current
  implementation has an aggressive frequency, reduce it" is satisfied vacuously.
- There is **no banner to remove** — the gameplay screen is already clean, as required.
- Enabling ads later **requires adding the `INTERNET` permission**, which is the only thing that
  would touch the current offline guarantee. That permission must be added *only* when ads are
  actually configured, and ad loading must stay out of the gameplay path.

Risk to avoid: shipping the Google Mobile Ads SDK without the publisher's real ad unit IDs produces
a build that cannot earn and can crash on load. The correct move is to build the **gateway
architecture** now (hint → optional rewarded ad → hint) with a no-op default, and wire the real SDK
only once ad unit IDs exist. See the implementation report for what was built and what still needs
the publisher's credentials.

---

## I. Testing gaps

Existing 94 tests cover: movement, invalid movement, pushing, block collision, goal detection,
level completion, completion-ignores-input, undo (single, repeated, empty), restart (exact restore,
replayability), board layout, control layout, game layout, character animation, facing, and level
solvability (BFS + documented solution).

**Not covered at all:** push counter, redo, redo-clearing-after-new-action, medal calculation, level
unlocking, save/load round-trip, level validation, deadlock detection, hint generation, daily
puzzle determinism, offline campaign behaviour, campaign/world ordering.

---

## J. Recommended implementation order

Ordered so each step is independently testable and nothing depends on something below it.

1. **Engine: `pushes` counter** on `GameState`, counted only on successful pushes. Extend the
   existing tests that construct `GameState`.
2. **Engine: redo** in `GameSession` (second deque, new action clears redo, undo into completion
   clears it). Tests: redo, redo-after-new-action, redo/undo/redo round trip, completion desync.
3. **Level model v2** — add id / world / number / mechanics / difficulty / parMoves / parPushes,
   keeping `fromAscii` as the grid parser. Introduce `levels/` package and a `Campaign` catalogue.
4. **Production solver** — move BFS into `engine/solver`, return the full direction list and both
   counts; add corner-deadlock detection and a per-level time/visit budget.
5. **Par + validation pipeline** — `LevelValidator` producing solvable / parMoves / parPushes /
   difficulty, run over the whole catalogue in a test that asserts every shipped level is approved.
6. **Medals** — `Medals.evaluate(moves, pushes, parMoves, parPushes)` with per-level thresholds.
7. **Difficulty model** — documented, tunable scoring over board size, blocks, par, mechanics.
8. **Progress persistence** — `ProgressStore` interface, SharedPreferences-backed implementation,
   no reflection, storing unlocked / completed / best medal / best moves / best pushes.
9. **Hints** — three levels derived from solver output, degrading gracefully, never auto-solving.
10. **Modes + daily puzzle** — `GameMode` abstraction, deterministic date → level selection.
11. **UI integration** — level select, HUD (level / moves / pushes), redo + hint controls,
    completion overlay with medal and stats, progress write on completion.
12. **Monetization architecture** — `AdsGateway` with a no-op default wired into the hint flow.
13. **Test suite for everything above**, then CI build + lint.

---

## How the baseline was verified

The build device has no Android SDK, so Gradle cannot assemble locally. Tests are compiled and run
with the vendored Kotlin compiler and JUnit console in `tooling/`:

- **Main and test sources must be compiled as ONE module.** `BoardLayoutTest` destructures
  `result.pushedBox`, a `val` on a data class from the main sources; Kotlin only smart-casts
  properties declared in the same module, so compiling main and test separately fails to compile.
- Runtime classpath needs `kotlin-stdlib.jar` from `tooling/kotlinc/lib` or every test fails with
  `ClassNotFoundException: kotlin.NoWhenBranchMatchedException`.

Result: **94 tests, 94 passing, 0 failing.**

## Existing code that can be reused instead of rewritten

- `LevelDefinition.fromAscii` — the level authoring format for the whole catalogue.
- `GameEngine` — unchanged rules; only gains a push counter.
- `TestLevelSolvabilityTest.shortestSolution` — becomes the production solver, the source of par
  values, the deadlock validator, and the basis of the hint system.
- `GameSession` — gains redo alongside its existing undo; the snapshot model is kept as-is.
- The pure-math pattern of `BoardLayout` / `ControlLayout` / `GameLayout` — the template for the new
  medal, difficulty and daily-puzzle logic, keeping all of it unit-testable without Android.
