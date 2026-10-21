package com.pushbound.game.levels

import com.pushbound.game.engine.LevelDefinition

/**
 * A chapter of the campaign: a theme plus the levels that teach it.
 *
 * Worlds are DATA. Nothing in the engine or the UI assumes how many exist or
 * how many levels each contains, so a future pack adds content by appending
 * here and nowhere else.
 */
data class World(
    /** 1-based world index. Matches the `world` field of every level it holds. */
    val index: Int,
    /** Display name, e.g. "World 1". */
    val name: String,
    /** The concept this chapter teaches, e.g. "PUSH". */
    val theme: String,
    /** One sentence shown on the level select screen. */
    val blurb: String,
    val levels: List<LevelDefinition>
) {
    init {
        require(levels.isNotEmpty()) { "World $index has no levels" }
        require(levels.all { it.world == index }) {
            "Every level in world $index must declare world $index"
        }
    }
}

/**
 * The whole playable campaign: ordered worlds, and a flat, STABLE ordering of
 * levels that progression and the daily puzzle both rely on.
 *
 * [allLevels] is world-major and then level-number order, which is the order a
 * player advances through. Because the list is derived once at construction and
 * never rebuilt from mutable state, a level's position is a constant of the
 * content — safe to use as a progression index.
 */
data class Campaign(val worlds: List<World>) {

    init {
        require(worlds.isNotEmpty()) { "Campaign must contain at least one world" }
        require(worlds.map { it.index }.distinct().size == worlds.size) {
            "World indices must be unique"
        }
    }

    /** Every level, in play order. */
    val allLevels: List<LevelDefinition> = worlds.flatMap { it.levels }

    /** Stable identifier -> level, built once. */
    private val byId: Map<String, LevelDefinition> = allLevels.associateBy { it.id }

    val levelCount: Int get() = allLevels.size

    fun level(id: String): LevelDefinition? = byId[id]

    /** 0-based position in play order, or -1 when the id is unknown. */
    fun positionOf(id: String): Int = allLevels.indexOfFirst { it.id == id }

    /** The level after [id] in play order, or null when it was the last. */
    fun nextAfter(id: String): LevelDefinition? {
        val position = positionOf(id)
        return if (position in 0 until allLevels.lastIndex) allLevels[position + 1] else null
    }

    fun world(index: Int): World? = worlds.firstOrNull { it.index == index }

    fun levelsInWorld(index: Int): List<LevelDefinition> = world(index)?.levels.orEmpty()

    init {
        val duplicates = allLevels.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate level ids: $duplicates" }
        require(byId.size == allLevels.size) { "Level ids must be unique" }
    }
}
