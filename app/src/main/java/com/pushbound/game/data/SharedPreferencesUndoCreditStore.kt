package com.pushbound.game.data

import android.content.Context
import android.content.SharedPreferences
import com.pushbound.game.undo.UndoCreditCodec
import com.pushbound.game.undo.UndoCreditState
import com.pushbound.game.undo.UndoCreditStore

/**
 * The shipped persistence for Undo credits: one small string in private
 * preferences, in its own file alongside — but separate from — progress and
 * the session.
 *
 * WHY A THIRD FILE. Progress is permanent and shared by every mode; the
 * session is transient and belongs to one run; the Undo balance is a PLAYER
 * resource that is neither. Keeping the three apart means a future "reset
 * progress" cannot silently hand back Undo credits, clearing a run cannot
 * touch them, and restoring a session cannot charge for one.
 *
 * WHY NOT MORE — this is three integers. A database, a serialisation library
 * or a data store would all be machinery in search of a problem, and each
 * would add a dependency the game does not otherwise need. One string reads
 * instantly on cold start, which matters because the balance is read before
 * the control bar draws.
 *
 * OFFLINE-FIRST — local, no account, no service, no network of its own. Undo
 * credits, the daily allowance and the midnight rollover all work on a device
 * that has never been online; only the optional rewarded top-up ever needs
 * connectivity, and its absence is already handled as a refusal rather than a
 * failure.
 */
class SharedPreferencesUndoCreditStore(context: Context) : UndoCreditStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): UndoCreditState? =
        UndoCreditCodec.decode(payload)

    override fun save(state: UndoCreditState) {
        // apply(), not commit(), for the reason the other two stores give:
        // the balance is written after every single undo, and commit() would
        // perform that disk write on the UI thread — janking the very frame
        // the player is watching. apply() updates the in-memory value at once
        // and flushes off-thread, and the framework waits for pending writes
        // when the activity pauses, which is exactly when this most needs to
        // be on disk.
        prefs.edit().putString(KEY_CREDITS, UndoCreditCodec.encode(state)).apply()
    }

    /**
     * Forgets the balance, so the next load is a fresh player.
     *
     * Kept for symmetry with [SharedPreferencesProgressStore.clear] and for
     * tests; nothing in the shipped game calls it — there is no "reset
     * resources" action, and a player's credits are not user-facing settings.
     */
    fun clear() {
        prefs.edit().remove(KEY_CREDITS).apply()
    }

    private val payload: String?
        get() = prefs.getString(KEY_CREDITS, null)

    private companion object {
        const val FILE = "pushbound_undo"

        /**
         * Versioned so a future format can migrate rather than silently
         * misinterpret an old payload — the same convention progress and the
         * session already use.
         */
        const val KEY_CREDITS = "undo_credits_v1"
    }
}
