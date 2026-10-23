package com.pushbound.game.data

import android.content.Context
import android.content.SharedPreferences
import com.pushbound.game.progress.SavedSession
import com.pushbound.game.progress.SessionCodec
import com.pushbound.game.progress.SessionStore

/**
 * The shipped persistence for the resumable session: one small string in private
 * preferences, alongside but SEPARATE from progress.
 *
 * WHY A DIFFERENT FILE FROM [SharedPreferencesProgressStore] — progress is
 * permanent and shared by every mode; the session is transient, belongs to one
 * mode, and is replaced every time the player moves. Keeping them apart means a
 * future "reset progress" action cannot accidentally wipe a game in progress,
 * and clearing the session cannot touch an earned medal.
 *
 * WHY NOT MORE — the session is a handful of small integers per snapshot and the
 * player has at most one of them. A database, a serialisation library or a data
 * store would all be machinery in search of a problem, and each would add a
 * dependency the game does not otherwise need. One string reads instantly on
 * cold start, which matters because RESUME reads it before the menu draws.
 */
class SharedPreferencesSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): SavedSession? =
        prefs.getString(KEY_SESSION, null)?.let { SessionCodec.decode(it) }

    override fun save(session: SavedSession?) {
        val editor = prefs.edit()
        if (session == null) editor.remove(KEY_SESSION)
        else editor.putString(KEY_SESSION, SessionCodec.encode(session))
        // commit(), not apply(): a session is written at the moment the player
        // LEAVES, and an asynchronous write can be lost if the process is killed
        // right after. The payload is a few hundred bytes, so the cost is
        // invisible and the guarantee is real.
        editor.commit()
    }

    private companion object {
        const val FILE = "pushbound_session"

        /**
         * Versioned so a future format can migrate rather than silently
         * misinterpret an old payload.
         */
        const val KEY_SESSION = "session_v1"
    }
}
