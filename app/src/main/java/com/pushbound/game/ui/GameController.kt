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
import com.pushbound.game.ads.RewardedAvailability
import com.pushbound.game.ads.RewardedOutcome
import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.DailyPuzzle
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.Hints
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.progress.InMemorySessionStore
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressState
import com.pushbound.game.progress.ProgressStore
import com.pushbound.game.progress.SavedSession
import com.pushbound.game.progress.SessionSnapshot
import com.pushbound.game.progress.SessionStore
import com.pushbound.game.undo.InMemoryUndoCreditStore
import com.pushbound.game.undo.UndoAccess
import com.pushbound.game.undo.UndoCreditState
import com.pushbound.game.undo.UndoCreditStore
import com.pushbound.game.undo.UndoGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One animated push, expressed in grid coordinates. */
data class PushAnim(val from: Position, val to: Position, val id: Long)

/**
 * How a hint was paid for, so it lands in the right column of
 * [HintAllowance].
 */
private enum class HintCharge { FREE, REWARDED }

/**
 * The confirmation the player must answer before any rewarded ad is requested.
 *
 * Pure data so the whole decision — including what the player will be told —
 * is inspectable and testable without an SDK.
 *
 * [willFetch] changes the PROMISE, not the price. When it is true no ad is
 * loaded yet, so the confirmation says an ad will be attempted rather than
 * claiming one is ready; a fetch that then fails is reported as unavailable
 * and grants nothing. Rewarding a fetched ad works exactly as it does for a
 * preloaded one.
 */
data class RewardedHintOffer(
    /** The SDK's honest state, used to word the confirmation accurately. */
    val availability: RewardedAvailability,
    /** True when showing would have to fetch an ad first. */
    val willFetch: Boolean
)

/**
 * Marker for the "you have used all your Undo moves" restoration offer.
 *
 * Unlike [RewardedHintOffer] this carries NO payload, and the difference is
 * deliberate. A hint offer has two genuinely different promises to word — an
 * ad is already loaded versus one would have to be fetched first — so its
 * dialog reads the availability it was given. A restoration makes exactly one
 * promise whatever the SDK's state: five more moves in exchange for one short
 * ad, refused outright if the ad does not deliver. Nothing about the choice
 * varies, so a field would be decoration.
 *
 * Held as observable state rather than a callback for the two reasons
 * [RewardedHintOffer] is: it SURVIVES a recomposition, and a caller that wants
 * to forget the offer simply nulls it.
 *
 * WHAT MAKES IT AN OFFER RATHER THAN AN INTERRUPTION — it exists only after
 * the player has tapped Undo with a zero balance, it states the reward and the
 * ad before the choice, "Not now" is an equal exit that costs nothing, and
 * this object's ONLY caller into the SDK is [GameController.confirmUndoRestoration],
 * which the player can reach solely by choosing "Watch Ad". Opening this
 * dialog loads nothing and shows nothing.
 */
object UndoRestorationOffer

/**
 * The balance this controller starts with: whatever was saved, rolled forward
 * to the local calendar day, or a brand-new player's five credits.
 *
 * A FILE-LEVEL FUNCTION RATHER THAN A PRIVATE METHOD, because it runs DURING
 * construction — from a property initializer — so keeping it off `this` means
 * it cannot reach a field that has not been assigned yet. That is the classic
 * way a "just load the saved value" line becomes a cold-start crash.
 *
 * THE ROLL-FORWARD IS PERSISTED HERE as well, so the stored value stops
 * describing a day that has already ended instead of being reinterpreted on
 * every launch forever.
 */
private fun startingUndoCredits(store: UndoCreditStore): UndoCreditState {
    val today = DailyPuzzle.todayEpochDay()

    val saved = store.load()
    if (saved == null) {
        // A player who has never had a balance gets one written at once, so
        // every later read sees the same five rather than re-deriving them.
        return UndoGate.initial(today).also { store.save(it) }
    }

    val rolled = UndoGate.rollForward(saved, today)
    if (rolled != saved) store.save(rolled)
    return rolled
}

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
 *
 * [key] is the run's [ContentKey], not merely its level id, because WHICH
 * RECORD this result belongs to depends on the mode. Finishing `daily/2026-10-23`
 * and finishing `campaign/w3-2` can show identical numbers on an identical
 * grid and must still write two different records.
 */
data class CompletedRun(
    val key: ContentKey,
    val moves: Int,
    val pushes: Int,
    val medal: Medal
) {
    /** The content id inside the key — `w3-2` or `2026-10-23`. */
    val contentId: String get() = key.id

    /** True when this run belongs to the shared daily puzzle. */
    val isDaily: Boolean get() = key.isDaily
}

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
    /**
     * WHICH MODE this run belongs to, and which record it writes.
     *
     * Defaults to campaign so a caller that only ever plays campaign levels
     * needs no extra argument, but the default is a fact about that caller and
     * not a safe universal: a daily run MUST be constructed with
     * [ContentKey.daily], or its completion lands in the campaign's records —
     * which is exactly the defect this parameter exists to close.
     *
     * Declared AFTER the three long-standing parameters so existing positional
     * call sites keep meaning exactly what they always meant.
     */
    startKey: ContentKey = ContentKey.campaign(level.id),
    private val sessionStore: SessionStore = InMemorySessionStore(),
    private val ads: AdsGateway = NoOpAdsGateway,
    /**
     * The campaign used for STRUCTURE — ids, order, unlocking. The authored
     * form is used deliberately: building it costs no solver time, and
     * structural questions never need par. Levels are approved one at a time
     * through [Levels.approved] when they are actually played.
     */
    private val campaign: com.pushbound.game.levels.Campaign = Levels.campaign,
    /**
     * Where the Undo credit balance lives.
     *
     * LAST PARAMETER DELIBERATELY: every existing call site passes the earlier
     * ones positionally, and appending keeps each of them meaning exactly what
     * it always meant — including `GameController(level, scope, store)` in the
     * animation tests.
     *
     * PLAYER-SCOPED, not level-scoped and not mode-scoped. The balance belongs
     * to the player, so it survives a restart, a level change, a move between
     * modes and an app restart — which is precisely why NOTHING in [restart]
     * or [loadLevel] resets it, and why no test of level boundaries can find a
     * path that spends a credit during initialisation or restoration.
     */
    private val undoCreditStore: UndoCreditStore = InMemoryUndoCreditStore()
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

    /**
     * The mode and content id this controller is currently playing.
     *
     * Mutable because [loadLevel] can move the controller onto a different
     * piece of content — and when it does, the record being written has to move
     * with it. Keeping it in one observable place means the completion path has
     * exactly one source for "which key do I record under", instead of each
     * caller re-deriving it from the level id (which is how a daily run could
     * once end up recorded as a campaign level).
     */
    var contentKey by mutableStateOf(startKey)
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

    /**
     * The campaign level that follows this one, or null when this run is not
     * part of the campaign.
     *
     * GATED ON THE MODE, not on the board: a daily puzzle whose grid happens to
     * be `w3-2` must not offer "Continue" into `w3-3`, because finishing that
     * daily unlocks nothing in the campaign. In daily mode the completion
     * overlay offers "Play Again" instead.
     */
    val nextLevelId: String?
        get() = if (contentKey.isDaily) null else Levels.nextAfter(level.id)?.id

    /**
     * The best medal this player holds for THIS run's content.
     *
     * Read through [contentKey] so a daily puzzle shows its own history rather
     * than the medal earned on the campaign level it shares a grid with.
     */
    val bestMedalOnThisLevel: Medal? get() = progress.recordFor(contentKey)?.bestMedal

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

    /**
     * The rewarded-ad confirmation the player must answer, or null.
     *
     * THIS IS THE WHOLE "OPTIONAL VALUE EXCHANGE". Asking for a hint past the
     * free allowance NEVER opens an ad: it sets this state, the UI explains
     * what will be shown and what will be granted, and the ad is only requested
     * once the player explicitly confirms. Declining leaves the game exactly as
     * it was — no hint, no charge, nothing lost.
     *
     * Held as observable state rather than a callback so the confirmation
     * SURVIVES a recomposition, and so a move, restart or level change can
     * simply null it and forget the offer.
     */
    var rewardedHintOffer by mutableStateOf<RewardedHintOffer?>(null)
        private set

    /**
     * True while a confirmed ad is being fetched or shown. Guards against the
     * player tapping Confirm twice and opening two ads, and lets the UI show
     * that something is happening.
     */
    var rewardedHintPending by mutableStateOf(false)
        private set

    // --- undo credits -------------------------------------------------------

    /**
     * The player's persistent Undo balance: what is left, and how many
     * rewarded restorations have already been taken on this calendar day.
     *
     * Read from [undoCreditStore] and rolled forward to today during
     * construction, so a save written before midnight reads as the new day the
     * moment the controller exists rather than waiting for a tap to notice.
     *
     * Whole-state in one observable var rather than three separate ones: the
     * three fields are only ever written together, and one write means the
     * control bar cannot recompose half-updated.
     */
    private var creditState: UndoCreditState by mutableStateOf(startingUndoCredits(undoCreditStore))

    /**
     * Credits left, for the number drawn on the Undo control.
     *
     * Derived rather than copied, so the figure the player sees can never
     * drift from the balance the rules enforce.
     */
    val undoCreditsRemaining: Int get() = creditState.credits

    /** Successful rewarded restorations already taken on this local day. */
    val undoRestorationsToday: Int get() = creditState.restorationsToday

    /**
     * What tapping Undo would do right now.
     *
     * Derived from the balance alone — never stored — so the control cannot be
     * out of step with it, including in the frame straight after a restoration
     * lands or a credit is spent.
     */
    val undoAccess: UndoAccess get() = UndoGate.access(creditState)

    /**
     * True when the ONLY sensible thing a zero balance can do is offer the
     * rewarded top-up.
     *
     * Exists separately from [canUndo] so the control bar can stay TAPPABLE at
     * zero credits: a player who has just spent their fifth credit on the
     * fifth undo may have no history left, and a button gated on history alone
     * would strand them with no way to earn more.
     */
    val canRequestUndoRestoration: Boolean get() = undoAccess == UndoAccess.OFFER_REWARDED

    /**
     * The screen-reader announcement for the Undo control.
     *
     * The count is never the only signal: at zero the player is TOLD Undo is
     * unavailable rather than being left to infer it from a number.
     */
    val undoAccessibilityDescription: String get() = UndoGate.accessibilityLabel(creditState)

    /**
     * The restoration offer, or null. Raised only by [undo], answered only by
     * [confirmUndoRestoration] or [dismissUndoRestoration].
     */
    var undoRestorationOffer by mutableStateOf<UndoRestorationOffer?>(null)
        private set

    /**
     * True while a confirmed restoration ad is being fetched or shown. Stops
     * the player opening two ads on a double tap, and lets the dialog show
     * that something is happening.
     */
    var undoRestorationPending by mutableStateOf(false)
        private set

    /**
     * A short status line about Undo — why no moves were restored, or that
     * today's allowance is spent.
     *
     * NEVER A GATE. It is the same kind of line as [hintNotice], shown in the
     * same place with the same style, and it exists purely to answer "what
     * happened?" — the board underneath stays fully playable throughout.
     */
    var undoNotice by mutableStateOf<String?>(null)
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
        restoreSessionIfAny()
        refreshHintAccess()
    }

    /**
     * Puts the player back where they were, if the store still holds a session
     * for THIS content.
     *
     * Two conditions have to hold, and both are checked rather than assumed:
     *
     *  - the saved session must belong to this exact [contentKey]. A session for
     *    `daily/2026-10-23` is not a session for `campaign/w3-2` even when the
     *    two share a grid, so opening one never resurrects the other;
     *  - the save must still describe this level's board. Levels get redesigned,
     *    and a save that no longer matches is dropped instead of putting an
     *    impossible board on screen.
     *
     * When either fails the player simply starts the level fresh, which is the
     * only sane answer to a stale save.
     */
    private fun restoreSessionIfAny() {
        val saved = sessionStore.load() ?: return
        if (saved.key != contentKey) return
        if (!saved.isRestorableOn(level)) return

        if (saved.undo.isEmpty() && saved.redo.isEmpty()) {
            // No history survived the save: restore the position only, and make
            // the absence of an undo path visible rather than leaving a phantom
            // stack behind.
            session.restoreState(saved.current.toState())
        } else {
            session.restore(
                current = saved.current.toState(),
                undo = saved.undo.map { it.toState() },
                redo = saved.redo.map { it.toState() }
            )
        }
        syncFromSession()
    }

    /**
     * Writes the run's current position to the session store, or clears it once
     * the level is finished.
     *
     * Clearing on completion is what stops RESUME from reopening a solved board:
     * the moment the run is done, there is no longer anything to resume. The
     * same store is used for both modes, so leaving a daily mid-way and then
     * opening it again restores that daily — and only that daily.
     */
    private fun persistSession() {
        if (session.isComplete) {
            sessionStore.save(null)
            return
        }
        sessionStore.save(
            SavedSession(
                key = contentKey,
                current = SessionSnapshot.from(session.state),
                // Only the TAIL of each history is persisted. The stacks hold one
                // entry per move, so serialising all of them would make the
                // saved payload grow without bound and rewrite it after every
                // single move. Capping keeps the write a constant size however
                // long the player wanders; undoing further back than the cap
                // starts again from where the session was restored, which is a
                // far better trade than a janky frame.
                undo = session.undoSnapshots()
                    .takeLast(MAX_PERSISTED_HISTORY)
                    .map { SessionSnapshot.from(it) },
                redo = session.redoSnapshots()
                    .takeLast(MAX_PERSISTED_HISTORY)
                    .map { SessionSnapshot.from(it) },
                savedAtEpochMillis = System.currentTimeMillis()
            )
        )
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

    /**
     * Walks back one move — when the player still has an Undo credit to spend
     * on it.
     *
     * THE CREDIT IS CHARGED FOR THE ENGINE'S ANSWER, NOT FOR THE TAP. The
     * engine is asked first and only a successful undo commits the charge, so
     * a tap with no history to return to, an animation still running, or a
     * session just restored from a save never costs a credit — while a
     * genuinely accepted undo costs exactly one, however fast it was tapped.
     *
     * AT A ZERO BALANCE nothing is undone and no ad is opened by the act of
     * tapping: the restoration offer is raised instead, or — once today's
     * rewarded allowance is also spent — the reason is stated plainly. Either
     * way the control stays visible, and it stays tappable precisely so the
     * player's next move, earning more, remains reachable.
     */
    fun undo() {
        if (animationJob?.isActive == true) return
        queuedDirection = null

        // A player who left the app open across midnight is on the new day by
        // now, and the allowance — never the balance — belongs to that day.
        syncUndoDay()

        when (undoAccess) {
            UndoAccess.OFFER_REWARDED -> {
                // RAISED, NOT ACTED ON. Nothing is loaded here: the offer is a
                // question, and the ad is requested only by
                // [confirmUndoRestoration], which the player reaches solely by
                // choosing "Watch Ad".
                if (undoRestorationOffer == null && !undoRestorationPending) {
                    undoNotice = null // a stale "nothing happened" line would contradict it
                    undoRestorationOffer = UndoRestorationOffer
                }
                return
            }

            UndoAccess.EXHAUSTED -> {
                // Saying there is nothing left is more honest than offering an
                // ad that the daily limit would refuse a moment later.
                undoNotice =
                    "No rewarded Undo restorations left today — Undo returns tomorrow. " +
                    "You can keep playing either way."
                return
            }

            UndoAccess.AVAILABLE -> Unit
        }

        // Computed BEFORE the engine is asked, and committed only if it says
        // yes: `charged` is an immutable value, so a refused undo returns here
        // with the balance untouched and the board untouched.
        val charged = UndoGate.spend(creditState) ?: return
        if (!session.undo()) return // nothing to walk back: nothing to charge for

        creditState = charged
        undoCreditStore.save(charged)

        clearHint()
        moveId += 1
        currentMove = MoveAnim(MoveAnimKind.TRANSITION, moveId) // glide, stays in IDLE
        syncFromSession()
        lastPush = null // boxes snap back to their logical cells
        lastBlocked = null // cancel any pending effort pulse
    }

    // ------------------------------------------------- undo restoration --

    /**
     * The player confirmed they want to watch an ad to get their five Undo
     * moves back.
     *
     * NOTHING HERE GRANTS ANYTHING EXCEPT [RewardedOutcome.REWARDED]. A load
     * that failed, an ad closed early, a consent form declined, a timeout or a
     * lifecycle event all leave the balance exactly where it was, and every one
     * of them is reported as a short status line rather than an error.
     *
     * THE EXACTLY-ONCE GUARD IS CHECKED INSIDE THE CALLBACK rather than
     * trusted to the gateway: the first delivery clears the two flags below,
     * so a duplicate reward callback finds them already cleared and returns
     * with the balance still at five instead of topping it up to ten. The cost
     * of being wrong here is handing out a second set of moves for one watch.
     */
    fun confirmUndoRestoration() {
        if (undoRestorationOffer == null || undoRestorationPending) return
        undoRestorationPending = true

        // Re-checked now rather than at the tap: the offer could have stayed
        // open across midnight, and the count that gates it belongs to TODAY.
        syncUndoDay()

        ads.showRewarded { outcome ->
            if (!undoRestorationPending || undoRestorationOffer == null) return@showRewarded
            undoRestorationPending = false
            undoRestorationOffer = null

            when (outcome) {
                RewardedOutcome.REWARDED -> grantUndoRestoration()

                // Nothing was shown and nothing was lost. The distinction from
                // SKIPPED matters: the player made no decision, so the message
                // says the ad was unavailable rather than blaming them for
                // skipping something they never saw.
                RewardedOutcome.UNAVAILABLE ->
                    undoNotice = "No ad available right now — your Undo moves are unchanged."

                RewardedOutcome.DISMISSED ->
                    undoNotice = "No ad was shown, so your Undo moves are unchanged."

                RewardedOutcome.SKIPPED ->
                    undoNotice = "No Undo moves restored — the ad wasn't finished."
            }
        }
    }

    /**
     * The player dismissed the restoration offer.
     *
     * Declining costs NOTHING: no ad is requested, no credit moves, and the
     * game is left exactly as it was found. This is the "Not now" path and it
     * must be a genuine equal of "Watch Ad", not a grudging one.
     */
    fun dismissUndoRestoration() {
        if (undoRestorationPending) return
        undoRestorationOffer = null
        undoNotice = null
    }

    /**
     * Applies a CONFIRMED reward: the full balance, counted once against
     * today's allowance.
     *
     * ONLY REACHED FROM [RewardedOutcome.REWARDED], and only once per offer.
     * There is no partial restoration and no partial count — the reward is
     * five moves or it is nothing, which is what the offer promised.
     */
    private fun grantUndoRestoration() {
        syncUndoDay()

        val restored = UndoGate.restore(creditState, DailyPuzzle.todayEpochDay())
        if (restored == null) {
            // UNREACHABLE THROUGH THE UI — the offer is only ever raised while
            // the allowance has room, and this guard is checked after the day
            // has been rolled. It exists so that even a stale or duplicated
            // delivery cannot exceed the daily limit.
            undoNotice = "That's all the rewarded Undo restorations for today."
            return
        }

        creditState = restored
        undoCreditStore.save(restored)
        undoNotice = null

        // The same bookkeeping as a rewarded hint. A rewarded ad was shown,
        // and the quiet period that starts is a property of THAT fact rather
        // than of which feature asked for it — so the policy stays one policy.
        interstitialState = InterstitialPolicy.onRewardedShown(
            interstitialState, System.currentTimeMillis()
        )
    }

    /**
     * Applies the local-calendar-day rollover if midnight has passed, and
     * persists it when it has.
     *
     * CALLED FROM EVERY ENTRY POINT THAT CAN SPEND OR GRANT, never from a
     * timer or a lifecycle observer: a player who leaves the app open across
     * midnight is on the new day the next time they touch Undo, with no
     * background work and nothing to wake up for. It only ever rewrites
     * [UndoCreditState.restorationsToday] — the balance they are holding is
     * carried across untouched, which is the whole point of the distinction.
     */
    private fun syncUndoDay() {
        val rolled = UndoGate.rollForward(creditState, DailyPuzzle.todayEpochDay())
        if (rolled == creditState) return
        creditState = rolled
        undoCreditStore.save(rolled)
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
    fun loadLevel(
        newLevel: LevelDefinition,
        newKey: ContentKey = ContentKey.campaign(newLevel.id)
    ) {
        if (newLevel.id == level.id && newKey == contentKey && !isComplete) return

        animationJob?.cancel()
        animationJob = null
        queuedDirection = null

        // The key moves WITH the level. Leaving it behind is how a daily run
        // would get recorded as a campaign level, so the two are swapped
        // together and the session is re-anchored under the new identity.
        contentKey = newKey
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
     * CAMPAIGN ONLY. A daily puzzle has no successor: it is one board for one
     * date, and offering to continue out of it would drop the player into the
     * campaign while still holding the daily's session — the exact cross-mode
     * leak this architecture exists to prevent.
     *
     * @return true when a new level was opened.
     */
    fun continueToNextLevel(): Boolean {
        if (contentKey.isDaily) return false
        val next = Levels.nextAfter(level.id) ?: return false
        loadLevel(next, ContentKey.campaign(next.id))
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
     *
     * AN AD IS NEVER OPENED HERE. Past the free allowance this raises
     * [rewardedHintOffer] and returns; the ad is requested only by
     * [confirmRewardedHint], which the player can only reach by confirming.
     */
    fun requestHint() {
        if (isComplete) return
        if (rewardedHintOffer != null || rewardedHintPending) return

        val access = HintGate.access(hintAllowance, ads.rewardedAvailability())
        when (access) {
            HintAccess.GRANTED_FREE -> grantHintAsync(HintCharge.FREE)

            // The offer is RAISED, not acted on: the player still has to say yes.
            //
            // Note that no ad is fetched here. The rule is that an ad is loaded
            // only after the player has explicitly chosen to watch one, so the
            // request begins in confirmRewardedHint() — asking for a hint is not
            // yet choosing to watch anything.
            HintAccess.OFFER_REWARDED_AD, HintAccess.OFFER_REWARDED_AD_FETCH -> {
                rewardedHintOffer = RewardedHintOffer(
                    availability = ads.rewardedAvailability(),
                    willFetch = access == HintAccess.OFFER_REWARDED_AD_FETCH
                )
            }

            HintAccess.DENIED -> {
                // No free hints and no ad that can be offered. The notice is
                // about the allowance, which is the part the player can act on.
                //
                // Also nudges the SDK to retry in the background. If the ad was
                // unavailable because a load failed a moment ago, the next tap
                // can offer again — a brief dip in connectivity should not kill
                // advertising for the rest of the session. Where consent was
                // refused this is a no-op, so the player is never re-asked.
                ads.preloadRewarded()
                hintNotice = "No hints left for this attempt. Restart to get more."
                refreshHintAccess()
            }
        }
    }

    /**
     * The player confirmed they want to watch an ad for a hint.
     *
     * Nothing here touches progress, medals, counters or the board: the only
     * thing that can change is that a hint arrives, and only if the SDK reports
     * [RewardedOutcome.REWARDED]. Every other outcome is reported back to the
     * player and the game continues exactly as it was.
     */
    fun confirmRewardedHint() {
        val offer = rewardedHintOffer ?: return
        if (rewardedHintPending) return
        rewardedHintPending = true

        ads.showRewarded { outcome ->
            rewardedHintPending = false
            rewardedHintOffer = null
            when (outcome) {
                RewardedOutcome.REWARDED -> {
                    hintAllowance = HintGate.onRewardEarned(hintAllowance)
                    interstitialState = InterstitialPolicy.onRewardedShown(
                        interstitialState, System.currentTimeMillis()
                    )
                    grantHintAsync(HintCharge.REWARDED)
                }

                RewardedOutcome.UNAVAILABLE -> {
                    // Nothing was shown and nothing was lost. The distinction
                    // from SKIPPED matters: the player made no decision, so the
                    // message says the ad was unavailable rather than blaming
                    // them for skipping it.
                    hintNotice = if (offer.willFetch) {
                        "Couldn't load an ad just now. Your hint is untouched — try again, or keep playing."
                    } else {
                        "No ad available right now. Your hint is untouched — try again, or keep playing."
                    }
                    refreshHintAccess()
                }

                // The player was shown a consent form and did not grant
                // consent, so no ad was ever put on screen. Saying "the ad
                // wasn't finished" would blame them for not watching something
                // they never saw — and, more importantly, nothing was lost.
                RewardedOutcome.DISMISSED -> {
                    hintNotice =
                        "No ad was shown, so no hint this time. Nothing else changed."
                    refreshHintAccess()
                }

                RewardedOutcome.SKIPPED -> {
                    // Closed before the reward callback: no hint, by Google's own
                    // reward rules. Declining costs the player nothing else.
                    hintNotice = "No hint this time — the ad wasn't finished."
                    refreshHintAccess()
                }
            }
        }
    }

    /**
     * The player dismissed the confirmation. Nothing is granted, nothing is
     * charged, and no ad is ever requested — this is the "Not now" path and it
     * must leave the game exactly as it found it.
     */
    fun dismissRewardedHint() {
        if (rewardedHintPending) return
        rewardedHintOffer = null
        hintNotice = null
        refreshHintAccess()
    }

    private fun grantHintAsync(charge: HintCharge) {
        val tier = hintTier
        // `state` is an immutable data class, so this is a safe snapshot to
        // search from even if the player carries on moving meanwhile.
        val at = state
        hintPending = true
        hintCharge = charge

        scope.launch(Dispatchers.Default) {
            val hint = Hints.hintFor(level, at, tier)
            // Compose snapshot state may be written from any thread; no main
            // dispatcher hop is needed, and none would be available in tests.
            applyHint(hint, tier)
        }
    }

    /**
     * How the hint about to be derived was PAID for.
     *
     * The free allowance and the ad-backed allowance are counted separately in
     * [HintAllowance], so the two must not be conflated. Charging an ad hint as
     * a free one would silently shrink the free allowance a player who watched
     * nothing still gets; charging a free hint as an ad one would let the free
     * allowance be spent without limit.
     */
    private var hintCharge: HintCharge = HintCharge.FREE

    private fun applyHint(hint: Hints.Hint?, tier: Int) {
        // A move, an undo or a restart may have arrived while the search ran.
        // Dropping the stale result is correct: it was computed for a board
        // that no longer exists.
        if (!hintPending) return
        hintPending = false

        if (hint == null) {
            // The reward was earned but the search found nothing to say. The
            // allowance is NOT charged — the player paid attention and got
            // nothing, so the credit stays available for the next request.
            hintNotice = if (isComplete) null else "No useful move to suggest from here."
        } else {
            activeHint = hint
            hintNotice = null
            hintAllowance = when (hintCharge) {
                HintCharge.FREE -> HintGate.onFreeHintUsed(hintAllowance)
                // Counted in the AD column, so the free allowance of a player
                // who watched an ad is left exactly as it was.
                HintCharge.REWARDED -> HintGate.onRewardEarned(hintAllowance)
            }
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
        // Forgets any unanswered confirmation: the offer was for THIS board.
        rewardedHintOffer = null
        rewardedHintPending = false
        // THE STATUS LINE SHARES THIS ONE HOOK. `clearHint` is called from
        // every place the player acting ends a previous statement — a move, an
        // undo, a redo, a restart, a new level — so an Undo explanation like
        // "no ad available right now" disappears on the next genuine action
        // instead of lingering over unrelated play.
        //
        // It deliberately does NOT touch the restoration offer or its pending
        // flag. Clearing `undoRestorationPending` while an ad the player chose
        // to watch was still on screen would make the exactly-once guard reject
        // the real reward, so the player would have watched for nothing.
        undoNotice = null
    }

    private fun refreshHintAccess() {
        hintAccess = HintGate.access(hintAllowance, ads.rewardedAvailability())
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
        // Written after completion has been resolved, so a run that just
        // finished clears the session rather than immediately re-saving it.
        persistSession()
        refreshHintAccess()
    }

    /**
     * Records a finish exactly once per completion, then persists it.
     *
     * Leaving the solved state clears [lastResult] so re-finishing after an
     * undo re-derives the same medal from the same counters; progress itself is
     * never rolled back, because the achievement was genuinely earned.
     *
     * THE RECORD IS WRITTEN UNDER [contentKey], never under the level id. That
     * is the whole isolation: finishing `daily/2026-10-23` writes that key and
     * nothing else, so the campaign's completion counter does not move, no
     * campaign level is marked cleared, and no campaign level unlocks.
     */
    private fun resolveCompletion() {
        if (!session.isComplete) {
            lastResult = null
            return
        }
        if (lastResult != null) return

        val run = CompletedRun(
            key = contentKey,
            moves = state.moves,
            pushes = state.pushes,
            medal = Levels.medalFor(level, state.moves, state.pushes)
        )
        lastResult = run

        progress = Progress.recordCompletion(
            state = progress,
            key = run.key,
            moves = run.moves,
            pushes = run.pushes,
            medal = run.medal
        )
        progressStore.save(progress)
        interstitialState = InterstitialPolicy.onLevelCompleted(interstitialState)
    }

    companion object {
        const val MOVE_ANIMATION_MS = 110L

        /**
         * How many of the most recent undo and redo snapshots a session save
         * carries. Bounded so the persisted payload stays a constant size no
         * matter how long the player wanders — see [persistSession].
         */
        const val MAX_PERSISTED_HISTORY = 60
    }
}
