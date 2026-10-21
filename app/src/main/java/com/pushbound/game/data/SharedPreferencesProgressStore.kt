package com.pushbound.game.data

import android.content.Context
import android.content.SharedPreferences
import com.pushbound.game.progress.ProgressState
import com.pushbound.game.progress.ProgressStore
import com.pushbound.game.progress.ProgressCodec

/**
 * The shipped persistence: a single small string in private preferences.
 *
 * WHY THIS LIVES IN `data` — every rule in [com.pushbound.game.progress] is
 * Android-free, and that is what lets unlocking, saving and hint derivation be
 * unit tested without a device. This class is the one place where storage
 * actually touches Android, so the boundary stays visible: rules in `progress`,
 * the platform adapter here. Nothing above this layer knows SharedPreferences
 * exists.
 *
 * WHY THIS AND NOT MORE — a player's entire progress is five integers per
 * finished level. A database, a serialisation library or a data store would all
 * be machinery in search of a problem, and each would add a dependency the game
 * does not otherwise need. One string reads instantly on cold start, costs no
 * I/O beyond a single tiny file, and cannot fail to deserialise because
 * [ProgressCodec] skips anything it does not recognise.
 *
 * OFFLINE-FIRST — local, no account, no service, no network permission.
 * Campaign play, saving and restoring all work on a device that has never been
 * online. Adding a cloud sync later means implementing [ProgressStore] again,
 * not changing a single rule.
 *
 * DURABILITY — [save] uses `apply()`, which updates the in-memory value
 * immediately and writes through shortly after, off the UI thread. For a payload
 * this size the write finishes well before the next frame, so recording a
 * completion never stalls gameplay.
 */
class SharedPreferencesProgressStore(context: Context) : ProgressStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): ProgressState {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return ProgressState()
        return ProgressCodec.decode(payload)
    }

    override fun save(state: ProgressState) {
        prefs.edit().putString(KEY_PAYLOAD, ProgressCodec.encode(state)).apply()
    }

    /** Forgets all stored progress. Used by tests and by a future reset action. */
    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private companion object {
        const val FILE = "pushbound_progress"

        /**
         * Versioned so a future format can migrate rather than silently
         * misinterpret an old payload.
         */
        const val KEY_PAYLOAD = "progress_v1"
    }
}
