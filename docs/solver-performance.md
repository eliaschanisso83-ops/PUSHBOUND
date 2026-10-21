# Solver performance and difficulty model

Measured on 21 Oct 2026 on the shipped 15-level campaign plus synthetic boards.
Every number here was produced by running the code, not estimated.

## Method

Two searches were compared under **identical conditions**: the same
`GameEngine`, the same move semantics, and the same budget of 400 000 nodes.

- **step-BFS** — the approach this project used until now, reimplemented in the
  benchmark harness from the same engine primitives so it could be measured
  alongside its replacement. A node is one dequeued player state, so every step
  of every walk is its own state.
- **push search** — `LevelSolver` as rewritten. A node is one
  (boxes, player) pair, and a whole walk becomes a single edge weighted by that
  walk's length. Its state count is found by binary-searching `maxNodes` down
  to the smallest budget that still solves the board, which is the same method
  used to measure the step search.

The step search runs in a child JVM with a capped heap, because on the larger
boards it does not merely exhaust its node budget — it exhausts the heap, and
the failure surfaces as an unrecoverable allocation error rather than a
throwable.

Both searches must agree on the optimum wherever both finish; the harness
fails loudly if they ever disagree on `moveCount`.

## Shipped content

| Level | Boxes | Floor | step-BFS states | push states | Reduction | step-BFS time | push time |
|---|---|---|---|---|---|---|---|
| w1-1 | 1 | 39 | 425 | **26** | 16.3x | 4 ms | 2 ms |
| w1-3 | 2 | 30 | 759 | **44** | 17.3x | 3 ms | 2 ms |
| w3-3 | 2 | 36 | 392 | **38** | 10.3x | 1 ms | 2 ms |
| w5-2 | 2 | 42 | 851 | **76** | 11.2x | 3 ms | 2 ms |
| w5-3 | 3 | 56 | 292 094 | **10 956** | **26.7x** | 4 045 ms | **175 ms** |

Par values are unchanged on every level (13/3, 10/3, 12/2, 12/2, 25/12): the
new search returns the same optimal solutions, not merely comparable ones.

The full unit-test suite dropped from 13.4 s to 3.6 s as a side effect, because
level validation runs the solver on every level.

## Scaling

Same shape of board, growing the box count:

| Board | Floor | Boxes | step-BFS | push | Ratio |
|---|---|---|---|---|---|
| shelf-3 | 72 | 3 | 697 | 102 | 6.8x |
| shelf-4 | 80 | 4 | 5 783 | 578 | 10.0x |
| shelf-5 | 88 | 5 | 86 930 | 6 014 | 14.5x |
| shelf-6 | 96 | 6 | **> budget** (unsolved) | 37 701, 3 017 ms | solved |
| shelf-7 | 104 | 7 | **> budget** (unsolved) | 394 136, 27 543 ms | solved |
| shelf-8 | 112 | 8 | **> budget** (unsolved) | **out of memory** | neither solves |

The gap widens as boxes are added, which is the whole point: the step search's
cost is driven by the player's position multiplied against every box
arrangement, so the walk enumeration is what exploded, not the boxes
themselves.

## What was applied, and what was deliberately not

Applied, because each was justified by a measurement or is sound:

1. **Push-based search with walk-distance costs** (Dijkstra over non-negative
   weights). This is the largest single win. It still returns the fewest
   MOVES, because every walk segment can be shortened to the shortest path
   within the reachable region that exists at that moment — walking neither
   moves a box nor changes which cells are reachable afterwards.
2. **Dead-square detection** (`DeadlockDetector.deadSquares`). A reverse push
   sweep from the goals. This subsumes the corner test and removed most of the
   remaining exploration: on w5-3 it is the difference between 49 030 and
   10 956 states.
3. **Frozen 2x2 block detection**. A configuration deadlock the per-cell sweep
   cannot see. Sound because a player can never stand inside a full block, and
   a box leaving the block would need the player one step deeper inside it.
4. **Exact bitset state keys**. Boxes as a bitset over board cells with the
   player cell alongside, so no per-state `Position` allocation or hashing of
   box lists in the inner loop.

Deliberately **not** applied:

- **Translation canonicalisation of the box cluster.** A classic speed-up, and
  unsound on a bounded board: the walls do not translate with the boxes, so
  two identical relative arrangements can have entirely different futures.
  Merging them prunes real solutions — which is exactly how a solvable level
  gets reported as unsolvable. The solver keys on the real board instead.
- **Bidirectional search.** It halves depth in the best case but roughly
  doubles memory and complicates move reconstruction, and the measurements
  above show the state count, not the depth, was the bottleneck.
- **Restricting the game to two boxes.** Not needed, and not considered. The
  3-box shipped level solves in 175 ms and the 7-box synthetic board still
  solves within the budget.

## Remaining measured limitation

**An 8-box board of 112 floor cells exhausts the heap** in the push search, not
just the node budget. At 7 boxes on 104 cells the search completes, but at
27.5 s it is at the very edge of the 400 000-node budget (394 136 states).

This is a real ceiling and it is not hidden. It does not affect the shipped
campaign — the largest shipped level has 3 boxes on 56 cells and solves in
175 ms — but a future pack must not exceed roughly 7 boxes on a board of that
size without further work, and validation for such a level would report
`BUDGET_EXHAUSTED` rather than a solution.

The solver never runs on the main thread and is used only for validation,
tooling, hints and background warm-up, so the ceiling bounds background work,
never responsiveness.

## Difficulty model

Measured across the 15 shipped levels, the old model's box term alone carried
**46–70%** of every score, and box count was the thing that separated the
bands: 11 of 15 levels landed on EASY purely by having one box. A two-box level
with nine moves and two pushes outranked a one-box level with thirteen moves
and three pushes, which is backwards about the puzzle in front of the player.

The cause was that boxes entered linearly with the largest weight, so a third
box added a flat 0.40 while every other factor had to earn its points against
that.

Changes:

- Box count now scales by **square root**, which keeps boxes the strongest
  single factor without letting them decide alone. The box term now carries
  **23–42%** of a score.
- **Repositioning** added: pushes per box, i.e. how much shuffling each box
  needed beyond going straight home.
- **Dead-cell density** added: the share of the floor from which a box can
  never reach a goal. The earlier candidate, the fatal-corner count, was
  measured to be **exactly 4 for every shipped level** — they are all closed
  rectangular rooms — so it carried no information and was dropped.
- Band cuts moved into the measured valleys between levels rather than fitted
  to taste.

Result: **EASY 11 → 5**, NORMAL 3 → 8, HARD 1 → 1, EXPERT 0 → 1.

The cuts are provisional by design. The shipped content genuinely contains ten
levels that are near-identical in shape, and they are allowed to clump in one
band rather than being artificially spread across five. This model should be
revisited once real levels exist; it is not worth tuning further against
fifteen data points.
