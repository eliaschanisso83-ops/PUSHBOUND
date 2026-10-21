#!/data/data/com.termux/files/usr/bin/bash
#
# Compiles and runs the Pushbound JVM unit tests WITHOUT the Android SDK.
#
# Why this exists: the build device has no Android SDK, so Gradle cannot
# assemble or run tests locally. tooling/ carries a vendored Kotlin compiler
# and the JUnit 4 console, which is enough for everything in the pure layers
# (engine/ plus the arithmetic ui/ files).
#
# TWO CONSTRAINTS THAT ARE NOT OBVIOUS — do not "simplify" them away:
#
#  1. Main and test sources MUST be compiled as ONE module. BoardLayoutTest
#     destructures `result.pushedBox`, a `val` on a data class that lives in
#     the main sources. Kotlin only smart-casts properties declared in the
#     same module, so compiling main and test into separate outputs fails to
#     compile with "non-nullable value required to call 'component1()'".
#
#  2. The runtime classpath MUST include tooling/kotlinc/lib/kotlin-stdlib.jar,
#     or every single test fails with
#     ClassNotFoundException: kotlin.NoWhenBranchMatchedException.
#
# Only files with no Android/Compose imports may be listed below. Adding a file
# that imports androidx.* will fail; see docs/AUDIT-PHASE1.md.
#
# Usage: tools/run-unit-tests.sh [--details tree|summary|none]

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

KC=tooling/kotlinc/bin/kotlinc
STDLIB=tooling/kotlinc/lib/kotlin-stdlib.jar
JUNIT=tooling/junit4.jar
HAMCREST=tooling/hamcrest-core.jar
COMPOSE=tooling/compose-runtime.jar
COROUTINES=tooling/coroutines-core.jar
CONSOLE=tooling/junit-platform-console-standalone.jar

OUT="${TMPDIR:-/data/data/com.termux/files/usr/tmp}/pushbound-tests"
CLASSES="$OUT/classes"

DETAILS=summary
if [ "${1:-}" = "--details" ] && [ -n "${2:-}" ]; then
    DETAILS="$2"
fi

# --- sources -----------------------------------------------------------------
# engine/ is pure Kotlin. ui/ contributes only its Android-free arithmetic
# files; GameController is included because it compiles against the Compose
# runtime and coroutines jars vendored in tooling/.
MAIN_SOURCES=(
    app/src/main/java/com/pushbound/game/Levels.kt
    app/src/main/java/com/pushbound/game/engine/*.kt
    app/src/main/java/com/pushbound/game/engine/solver/*.kt
    app/src/main/java/com/pushbound/game/levels/*.kt
    app/src/main/java/com/pushbound/game/progress/Progress.kt
    app/src/main/java/com/pushbound/game/progress/ProgressStore.kt
    app/src/main/java/com/pushbound/game/ads/*.kt
    app/src/main/java/com/pushbound/game/ui/BoardLayout.kt
    app/src/main/java/com/pushbound/game/ui/ControlLayout.kt
    app/src/main/java/com/pushbound/game/ui/GameLayout.kt
    app/src/main/java/com/pushbound/game/ui/CharacterAnimation.kt
    app/src/main/java/com/pushbound/game/ui/GameController.kt
)
TEST_SOURCES=(app/src/test/java/com/pushbound/game/*.kt)

# --- compile -----------------------------------------------------------------
rm -rf "$CLASSES"
mkdir -p "$CLASSES"

COMPILE_CP="$JUNIT:$HAMCREST:$COMPOSE:$COROUTINES"

# kotlinc prints an unrelated jansi native-loader warning on this device; it is
# cosmetic and must not be mistaken for a compile failure.
"$KC" -nowarn -cp "$COMPILE_CP" -d "$CLASSES" \
    "${MAIN_SOURCES[@]}" "${TEST_SOURCES[@]}" \
    2>&1 | grep -v '^warning:' \
         | grep -v 'jansi\|UnsatisfiedLinkError\|osinfo\|Failed to load native' || true

if ! [ -d "$CLASSES/com" ]; then
    echo "COMPILE FAILED — no classes produced" >&2
    exit 1
fi

# --- run ---------------------------------------------------------------------
RUNTIME_CP="$CLASSES:$STDLIB:$JUNIT:$HAMCREST:$COMPOSE:$COROUTINES"

LOG="$OUT/last-run.log"

java -jar "$CONSOLE" execute \
    -cp "$RUNTIME_CP" \
    --scan-classpath="$CLASSES" \
    --details="$DETAILS" \
    > "$LOG" 2>&1 || true

grep -v 'jansi\|UnsatisfiedLinkError\|osinfo\|Failed to load native' "$LOG"

# The console launcher exits 0 even when tests fail, so the counts are the
# authoritative signal. Anything that is not a clean sweep is a failure.
FAILED="$(grep -oE '[[:space:]]+[0-9]+ tests failed' "$LOG" | grep -oE '[0-9]+' | head -1)"
TOTAL="$(grep -oE '[[:space:]]+[0-9]+ tests (found|successful)' "$LOG" | grep -oE '[0-9]+' | sort -rn | head -1)"
FOUND="$(grep -oE '[[:space:]]+[0-9]+ tests found' "$LOG" | grep -oE '[0-9]+' | head -1)"
ERRORS="$(grep -cE '^[[:space:]]*(java|org)\..*(Exception|Error):' "$LOG" || true)"

if [ "${ERRORS:-0}" -gt 0 ]; then
    echo "" >&2
    echo "RUNTIME ERRORS DETECTED ($ERRORS) — see $LOG" >&2
    exit 1
fi

if [ -z "${FAILED:-}" ] || [ -z "${FOUND:-}" ]; then
    echo "" >&2
    echo "COULD NOT PARSE RESULT — see $LOG" >&2
    exit 1
fi

if [ "$FAILED" != "0" ]; then
    echo "" >&2
    echo "FAILED: $FAILED of $FOUND tests — see $LOG" >&2
    exit 1
fi

echo ""
echo "OK: $TOTAL/$FOUND tests passing"
