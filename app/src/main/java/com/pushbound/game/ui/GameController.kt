package com.pushbound.game.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pushbound.game.Levels
import com.pushbound.game.ads.AdsGateway
import com.pushbound.game.ads.AdPlacement
import com.pushbound.game.ads.HintAccess
import com.pushbound.game.ads.HintAllowance
import com.pushbound.game.ads.HintGate
import com.pushbound.game.ads.InterstitialPolicy
import com.pushbound.game.ads.InterstitialState
import com.pushbound.game.ads.NoOpAdsGateway
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.Hints
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressState
import com.pushbound.game.progress.ProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One animated push, expressed in grid coordinates. */
data class PushAnim(val from: Position, val to: Position, val id: Long)

/**
 * The logical move the renderer should animate right now: which visual kind
 * (walk timeline, push hold, or a plain transition glide) and a monotonic id
 * so each transition can restart its own timeline exactly once.
 */
data class MoveAnim(val kind: MoveAnimKind, val id: Long)

/**
 * One blocked-push effort reaction: the engine refused the move (no state
 * change, facing untouched), but the character visibly leans into the
 * ATTEMPTED direction and releases — purely visual, driven by a fresh id so
 * repeated presses replay the pulse.
 */
data class BlockedAnim(val direction: Direction, val id: Long)

/**
 * The immutable result of the run that finished. Captured at the moment of
 * completion and never recomputed, so the overlay always shows the numbers the
 * player actually achieved even after they undo out of the solved state.
 */
data class CompletedRun(
    val levelId: String,
    val moves: Int,
    val pushes: Int,
    val medal: Medal
)

/**
 * Thin Compose glue around the pure [GameSession]: exposes observable state for
 * the renderer, keeps a single queued input while a move animates, and never
 * contains puzzle rules.
 *
 * Everything added on top of movement — undo/redo history visibility, hints,
 * the medal awarded, progress persistence and the interstitial policy — is
 * DELEGATED to the pure modules that own those rules. This class only wires
 * them to observable state, which is what keeps the whole surface testable
 * without Android.
 */
class GameController(
    level: LevelDefinition,
    private val scope: CoroutineScope,
    private val progressStore: ProgressStore = InMemoryProgressStore(),
    private val ads: AdsGateway = NoOpAdsGateway,
    /**
     * The campaign used for STRUCTURE — ids, order, unlocking. The authored
     * form is used deliberately: building it costs no solver time, and
     * structural questions never need par. Levels are approved one at a time
     * through [Levels.approved] when they are actually played.
     */
    private val campaign: com.pushbound.game.levels.Campaign = Levels.campaign
) {
    /**
     * The level on the board right now. Mutable so [loadLevel] can advance the
     * campaign without rebuilding the controller — which is also what lets the
     * player's position, progress and history wiring survive a level change.
     *
     * Taken AS GIVEN on purpose: the controller is a thin glue class and must
     * also accept synthetic levels with no goals or boxes, which the animation
     * tests rely on. Campaign levels arrive already approved, because the only
     * two places that load one — GameScreen's resume and [continueToNextLevel]
     * — go through [Levels.byId] / [Levels.nextAfter], each of which approves
     * exactly that single level.
     */
    var level: LevelDefinition by mutableStateOf(level)
        private set

    private var session = GameSession(level)

    var state by mutableStateOf(session.state)
        private set
    var isComplete by mutableStateOf(session.isComplete)
        private set
    var canUndo by mutableStateOf(session.canUndo)
        private set
    var canRedo by mutableStateOf(session.canRedo)
        private set
    var lastPush by mutableStateOf<PushAnim?>(null)
        private set
    var currentMove by mutableStateOf<MoveAnim?>(null)
        private set
    var lastBlocked by mutableStateOf<BlockedAnim?>(null)
        private set
    var resetEpoch by mutableStateOf(0)
        private set

    // --- progression -------------------------------------------------------

    /** Everything this player has ever achieved, as loaded then updated. */
    var progress by mutableStateOf(progressStore.load())
        private set

    /** The run that finished, or null while the level is still open. */
    var lastResult by mutableStateOf<CompletedRun?>(null)
        private set

    /** True once the level after this one has been opened up by this run. */
    val nextLevelId: String? get() = Levels.nextAfter(level.id)?.id

    val bestMedalOnThisLevel: Medal? get() = progress.recordFor(level.id)?.bestMedal

    // --- hints -------------------------------------------------------------

    /** The most recent hint shown, cleared as soon as the player acts. */
    var activeHint by mutableStateOf<Hints.Hint?>(null)
        private set

    /** Which tier the NEXT request will draw from; escalates per grant. */
    private var hintTier = 0
    private var hintAllowance: HintAllowance = HintGate.reset()

    /** What asking for a hint would do right now — drives the button's label. */
    var hintAccess by mutableStateOf(HintAccess.GRANTED_FREE)
        private set

    /** A short status line when a hint cannot be granted (allowance + no ad). */
    var hintNotice by mutableStateOf<String?>(null)
        private set

    /**
     * True while a hint search is in flight. Lets the UI show that help is
     * coming, and lets a move or restart discard a result computed for a board
     * the player has already left.
     */
    var hintPending by mutableStateOf(false)
        private set

    // --- advertising policy ------------------------------------------------

    /** The interstitial throttle's memory. Pure data; the policy decides. */
    var interstitialState by mutableStateOf(InterstitialState())
        private set

    private var animationJob: Job? = null
    private var queuedDirection: Direction? = null
    private var pushId = 0L
    private var moveId = 0L
    private var blockedId = 0L

    init {
        refreshHintAccess()
    }

    /**
     * True while a move animation is running, i.e. the exact window in which
     * [onDirectionPressed] would QUEUE its input instead of performing it and
     * [undo] / [redo] would refuse to act.
     *
     * Read-only and pure — it changes no behaviour. It exists so callers can
     * WAIT for the input window to open rather than guessing at a sleep length;
     * on a loaded device the 110ms animation routinely outlasts a fixed 170ms
     * wait, which is how a queued press gets mistaken for a lost one.
     */
    val isAnimating: Boolean get() = animationJob?.isActive == true

    fun onDirectionPressed(direction: Direction) {
        if (isComplete) return
        if (animationJob?.isActive == true) {
            queuedDirection = direction // keep exactly one queued input
            return
        }
        performMove(direction)
    }

    private fun performMove(direction: Direction) {
        val result = session.move(direction)
        if (result is MoveResult.Blocked) {
            // Refused input: no state change at all, but the character puts
            // in a brief visual effort in the ATTEMPTED direction.
            blockedId += 1
            lastBlocked = BlockedAnim(direction, blockedId)
            return
        }
        if (result !is MoveResult.Moved) return

        clearHint()
        syncFromSession()
        lastBlocked = null // a real move supersedes any pending effort pulse
        val pushed = result.pushedBox
        moveId += 1
        currentMove = MoveAnim(
            kind = if (pushed != null) MoveAnimKind.PUSH else MoveAnimKind.STEP,
            id = moveId
        )
        if (pushed != null) {
            pushId += 1
            lastPush = PushAnim(pushed.first, pushed.second, pushId)
        }
        animationJob = scope.launch {
            delay(MOVE_ANIMATION_MS)
            val queued = queuedDirection
            queuedDirection = null
            if (queued != null) performMove(queued)
        }
    }

    fun undo() {
        if (animationJob?.isActive == true) return
        queuedDirection = null
        if (!session.undo()) return
        clearHint()
        moveId += 1
        currentMove = MoveAnim(MoveAnimKind.TRANSITION, moveId) // glide, stays in IDLE
        syncFromSession()
        lastPush = null // boxes snap back to their logical cells
        lastBlocked = null // cancel any pending effort pulse
    }

    /**
     * Steps forward into the state that was undone.
     *
     * Refuses silently when there is nothing to redo — a new action after an
     * undo has already cleared the forward branch, so the button is disabled in
     * exactly that case (see [canRedo]).
     */
    fun redo() {
        if (animationJob?.isActive == true) return
        queuedDirection = null
        if (!session.redo()) return
        clearHint()
        moveId += 1
        currentMove = MoveAnim(MoveAnimKind.TRANSITION, moveId)
        syncFromSession()
        lastPush = null
        lastBlocked = null
    }

    fun restart() {
        animationJob?.cancel()
        animationJob = null
        queuedDirection = null
        session.restart()
        clearHint()
        hintTier = 0
        hintAllowance = HintGate.reset()
        refreshHintAccess()
        syncFromSession()
        lastPush = null
        lastBlocked = null
        currentMove = null
        resetEpoch += 1 // renderer snaps instead of animating across the board
    }

    /**
     * Opens a different level in place, keeping the controller — and therefore
     * the loaded progress, the ad wiring and the composition — alive.
     *
     * Everything level-scoped resets: history, counters, the hint tier, the
     * hint allowance and any captured completion. Progress is NOT reset, since
     * it belongs to the player rather than the level.
     *
     * The player's own undo/redo stacks are level-scoped by definition, so
     * carrying them across would silently apply one puzzle's history to
     * another — hence a full reset here.
     */
    fun loadLevel(newLevel: LevelDefinition) {
        if (newLevel.id == level.id && !isComplete) return

        animationJob?.cancel()
        animationJob = null
        queuedDirection = null

        level = newLevel
        session = GameSession(newLevel)

        hintTier = 0
        hintAllowance = HintGate.reset()
        clearHint()

        lastPush = null
        lastBlocked = null
        currentMove = null
        resetEpoch += 1

        syncFromSession()
    }

    /**
     * Advances to the next level in the campaign, if this one has a successor.
     *
     * @return true when a new level was opened.
     */
    fun continueToNextLevel(): Boolean {
        val next = Levels.nextAfter(level.id) ?: return false
        loadLevel(next)
        return true
    }

    // ----------------------------------------------------------------- hints --

    /**
     * Requests the next hint, following the free-then-optional-ad rule.
     *
     * The hint itself is DERIVED from where the player actually stands, so it
     * stays honest after any amount of wandering. Nothing here can block play:
     * when the allowance is spent and no rewarded ad exists, a notice is shown
     * and the game continues untouched.
     *
     * RUNS OFF THE MAIN THREAD. Deriving a hint is a breadth-first search, and
     * a search on the largest shipped board measured **2.4 seconds** on a
     * mid-range ARM device — doing that synchronously would freeze the frame
     * the tap belongs to. The request returns immediately; the result lands on
     * [activeHint] when it is ready.
     */
    fun requestHint() {
        if (isComplete) return

        when (HintGate.access(hintAllowance, ads.isRewardedAvailable())) {
            HintAccess.GRANTED_FREE -> grantHintAsync()

            HintAccess.OFFER_REWARDED_AD -> ads.showRewarded(
                onReward = {
                    hintAllowance = HintGate.onRewardEarned(hintAllowance)
                    interstitialState = InterstitialPolicy.onRewardedShown(
                        interstitialState, System.currentTimeMillis()
                    )
                    grantHintAsync()
                },
                onSkipped = {
                    hintNotice = "No hint this time — the ad was skipped."
                    refreshHintAccess()
                }
            )

            HintAccess.DENIED -> {
                hintNotice = "No hints left for this attempt. Restart to get more."
                refreshHintAccess()
            }
        }
    }

    private fun grantHintAsync() {
        val tier = hintTier
        // `state` is an immutable data class, so this is a safe snapshot to
        // search from even if the player carries on moving meanwhile.
        val at = state
        hintPending = true

        scope.launch(Dispatchers.Default) {
            val hint = Hints.hintFor(level, at, tier)
            // Compose snapshot state may be written from any thread; no main
            // dispatcher hop is needed, and none would be available in tests.
            applyHint(hint, tier)
        }
    }

    private fun applyHint(hint: Hints.Hint?, tier: Int) {
        // A move, an undo or a restart may have arrived while the search ran.
        // Dropping the stale result is correct: it was computed for a board
        // that no longer exists.
        if (!hintPending) return
        hintPending = false

        if (hint == null) {
            hintNotice = if (isComplete) null else "No useful move to suggest from here."
        } else {
            activeHint = hint
            hintNotice = null
            hintAllowance = HintGate.onFreeHintUsed(hintAllowance)
            // Escalate: direction, then the block, then the exact next move.
            hintTier = (tier + 1).coerceAtMost(Hints.TIERS - 1)
        }
        refreshHintAccess()
    }

    private fun clearHint() {
        activeHint = null
        hintNotice = null
        // Cancels any search still running: its board no longer exists.
        hintPending = false
    }

    private fun refreshHintAccess() {
        hintAccess = HintGate.access(hintAllowance, ads.isRewardedAvailable())
    }

    // ------------------------------------------------------- completion flow --

    /**
     * Asks the interstitial policy whether an ad may be shown for [placement].
     *
     * Call sites only ever propose a moment; the policy — not the caller —
     * decides, and every disruptive moment is refused by rule. With no ad SDK
     * configured this is inert, but the wiring is real, so adding an SDK later
     * cannot bypass the throttle by accident.
     */
    fun maybeShowInterstitial(placement: AdPlacement, nowMs: Long = System.currentTimeMillis()) {
        if (!InterstitialPolicy.shouldShow(placement, interstitialState, nowMs)) return
        ads.showInterstitial {
            interstitialState = InterstitialPolicy.onInterstitialShown(interstitialState, nowMs)
        }
    }

    private fun syncFromSession() {
        state = session.state
        isComplete = session.isComplete
        canUndo = session.canUndo
        canRedo = session.canRedo
        resolveCompletion()
        refreshHintAccess()
    }

    /**
     * Records a finish exactly once per completion, then persists it.
     *
     * Leaving the solved state clears [lastResult] so re-finishing after an
     * undo re-derives the same medal from the same counters; progress itself is
     * never rolled back, because the achievement was genuinely earned.
     */
    private fun resolveCompletion() {
        if (!session.isComplete) {
            lastResult = null
            return
        }
        if (lastResult != null) return

        val run = CompletedRun(
            levelId = level.id,
            moves = state.moves,
            pushes = state.pushes,
            medal = Levels.medalFor(level, state.moves, state.pushes)
        )
        lastResult = run

        progress = Progress.recordCompletion(
            state = progress,
            levelId = run.levelId,
            moves = run.moves,
            pushes = run.pushes,
            medal = run.medal
        )
        progressStore.save(progress)
        interstitialState = InterstitialPolicy.onLevelCompleted(interstitialState)
    }

    companion object {
        const val MOVE_ANIMATION_MS = 110L
    }
}
