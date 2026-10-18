package com.pushbound.game.ui

/**
 * Development-only grid overlay (milestone 4, section 17). Shows cell
 * boundaries, board outline and semantic cell tints (wall / goal / box /
 * player) so alignment can be verified at a glance. Disabled by default and
 * never part of the normal production presentation; flip [ENABLED] to true
 * temporarily when validating placement, then flip it back.
 */
object DebugGridConfig {
    const val ENABLED = false
}
