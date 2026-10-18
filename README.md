# PUSHBOUND

Native Android Sokoban puzzle built with Kotlin and Jetpack Compose.

## Status

Milestone 3 - first playable prototype: one fully playable level with the
complete core loop (move, push the box, box on goal, level complete), plus
undo, restart and a subtle move counter.

## Structure

- `app/` - Android application module (Kotlin, Jetpack Compose)
  - `app/src/main/java/com/pushbound/game/engine/` - pure Kotlin game logic, no Android or UI dependencies
  - `app/src/main/java/com/pushbound/game/ui/` - Compose rendering, controls and screen composition
  - `app/src/main/res/drawable-nodpi/` - approved raster game assets
  - `app/src/test/` - JUnit tests for the game logic
- `assets/` - Pushbound asset pipeline (approved artwork and generated candidates)
- `docs/` - art direction, asset specifications and pipeline documentation
- `tools/` - asset QA scripts used during approval
- `.github/workflows/` - GitHub Actions build

## Building

Requires JDK 17.

    ./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
    ./gradlew testDebugUnitTest    # game logic tests

## Playtest

The prototype level (9x8) exercises the whole loop: walk to the box, push it
right, reposition below it, push it up, reposition to its right, push it left
onto the goal. The wall stub below the box blocks the cheap straight push.
