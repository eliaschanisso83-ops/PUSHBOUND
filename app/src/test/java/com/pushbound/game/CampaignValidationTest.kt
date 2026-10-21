package com.pushbound.game

import com.pushbound.game.engine.Difficulty
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.Medals
import com.pushbound.game.engine.MedalThresholds
import com.pushbound.game.engine.solver.LevelSolver
import com.pushbound.game.engine.solver.LevelValidator
import com.pushbound.game.levels.Campaign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on shipped content: every level in the campaign must be
 * structurally sound, proven solvable, and stamped with measured par values.
 *
 * This is the "APPROVE" step of the validation pipeline. A level that cannot
 * be solved fails here, before it can ever reach a player.
 */
class CampaignValidationTest {

    private val campaign: Campaign get() = Levels.validatedCampaign

    @Test
    fun everyShippedLevelIsApproved() {
        val reports = LevelValidator.reportAll(Levels.campaign.allLevels)
        val failures = reports.filter { !it.approved }

        assertTrue(
            "Levels failing validation:\n" +
                failures.joinToString("\n") { "${it.levelId}: ${it.problems.joinToString("; ")}" },
            failures.isEmpty()
        )
    }

    @Test
    fun everyLevelHasAMeasuredPar() {
        campaign.allLevels.forEach { level ->
            assertTrue("${level.id} has no par moves", level.parMoves > 0)
            assertTrue("${level.id} has no par pushes", level.parPushes > 0)
            assertTrue(
                "${level.id}: par pushes cannot exceed par moves",
                level.parPushes <= level.parMoves
            )
        }
    }

    @Test
    fun everyLevelWasSolvedByThePipeline() {
        campaign.allLevels.forEach { level ->
            val report = LevelValidator.validate(level)
            assertEquals("${level.id} must be solved", LevelSolver.Outcome.SOLVED, report.outcome)
            assertNotNull("${level.id} must have a solution", report.solution)
        }
    }

    @Test
    fun everySolutionReplaysToCompletionInTheRealEngine() {
        campaign.allLevels.forEach { level ->
            val report = LevelValidator.validate(level)
            val solution = report.solution
            assertNotNull("${level.id} is unsolvable", solution)
            assertTrue(
                "${level.id}: the solver's moves must replay through the game engine",
                LevelSolver.replaysToCompletion(level, solution!!)
            )
        }
    }

    @Test
    fun campaignStructureIsConsistent() {
        val worlds = campaign.worlds
        assertTrue("the campaign must ship worlds", worlds.size >= 1)

        // World indices must be the contiguous 1-based sequence they claim to be.
        assertEquals(
            (1..worlds.size).toList(),
            worlds.map { it.index }
        )

        // Level numbering within a world starts at 1 and has no gaps.
        worlds.forEach { world ->
            assertEquals(
                "${world.name} level numbers must be 1..n",
                (1..world.levels.size).toList(),
                world.levels.map { it.number }
            )
        }

        // The flat ordering the player advances through is world-major.
        val flat = Levels.campaign.allLevels
        assertEquals(Levels.campaign.levelCount, flat.size)
        assertEquals(
            "play order must be world-major",
            flat.map { it.world to it.number },
            flat.map { it.world to it.number }.sortedWith(compareBy({ it.first }, { it.second }))
        )
    }

    @Test
    fun levelIdsAreUniqueAndResolve() {
        val ids = Levels.campaign.allLevels.map { it.id }
        assertEquals("level ids must be unique", ids.size, ids.toSet().size)

        ids.forEach { id ->
            assertNotNull("id $id must resolve", Levels.byId(id))
            assertTrue("id $id must have a position", Levels.campaign.positionOf(id) >= 0)
        }
    }

    @Test
    fun theLastLevelHasNoSuccessor() {
        val last = campaign.allLevels.last()
        assertNull("the final level must not have a next level", Levels.nextAfter(last.id))
    }

    @Test
    fun progressionWalksEveryLevelInOrder() {
        val seen = mutableListOf<String>()
        var cursor = campaign.allLevels.first()

        while (true) {
            seen += cursor.id
            val next = Levels.nextAfter(cursor.id) ?: break
            cursor = next
        }

        assertEquals(
            "progression must reach every level exactly once",
            campaign.allLevels.map { it.id },
            seen
        )
    }

    @Test
    fun difficultyIsDerivedNotFromLevelNumber() {
        val levels = campaign.allLevels

        // Every level's stamped band must equal what the scoring model says
        // about that level's own measures — not something implied by position.
        levels.forEach { level ->
            val scored = com.pushbound.game.engine.DifficultyModel.evaluate(level)
            val inputs = com.pushbound.game.engine.DifficultyModel.Inputs.of(level)
            assertEquals(
                "${level.id}: stamped difficulty must match the model " +
                    "(score=${com.pushbound.game.engine.DifficultyModel.score(inputs)}, " +
                    "inputs=$inputs)",
                scored,
                level.difficulty
            )
        }

        // The scores must be real numbers driven by par and board shape, and
        // the model must be monotonic in the factors it claims to use.
        val easy = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 1, floorCells = 20, parMoves = 6, parPushes = 2
        )
        val hard = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 4, floorCells = 90, parMoves = 60, parPushes = 25
        )
        assertTrue(
            "a bigger, longer puzzle must score higher than a trivial one",
            com.pushbound.game.engine.DifficultyModel.score(hard) >
                com.pushbound.game.engine.DifficultyModel.score(easy)
        )
        assertEquals(
            Difficulty.EASY,
            com.pushbound.game.engine.DifficultyModel.bandFor(
                com.pushbound.game.engine.DifficultyModel.score(easy)
            )
        )
        assertTrue(
            com.pushbound.game.engine.DifficultyModel.bandFor(
                com.pushbound.game.engine.DifficultyModel.score(hard)
            ).atLeastAsSevereAs(Difficulty.NORMAL)
        )
    }

    @Test
    fun boxCountNoLongerOutvotesEveryOtherFactor() {
        // The first model let a third box add a flat 0.40 on its own, which made
        // box count the thing that separated the bands: 11 of the 15 shipped
        // levels landed on EASY purely by having one box. Squaring the box term
        // keeps boxes the strongest single factor without letting them decide
        // alone — measured across the campaign, the box term now carries
        // 23-42% of a score instead of 46-70%.
        val one = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 1, floorCells = 40, parMoves = 15, parPushes = 5
        )
        val three = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 3, floorCells = 40, parMoves = 15, parPushes = 15
        )

        // Same board, same solution length, same shuffling per box — only the
        // box count differs, so the entire difference IS the box term.
        val addedByTwoExtraBoxes =
            com.pushbound.game.engine.DifficultyModel.score(three) -
                com.pushbound.game.engine.DifficultyModel.score(one)
        assertTrue(
            "a puzzle must not triple in score by gaining boxes alone",
            addedByTwoExtraBoxes < 0.5
        )

        // And boxes must not outweigh every other factor combined.
        val boxTerm =
            com.pushbound.game.engine.DifficultyModel.W_BOXES *
                kotlin.math.sqrt(3.0 / com.pushbound.game.engine.DifficultyModel.REF_BOXES)
        val total = com.pushbound.game.engine.DifficultyModel.score(three)
        assertTrue(
            "boxes must not outweigh every other factor combined",
            boxTerm < total - boxTerm
        )
    }

    @Test
    fun moreTrapsOnTheFloorRaiseTheScore() {
        // A board where a third of the cells strand a box punishes one careless
        // push; an open board forgives it. That is a real difference the model
        // has to see — the fatal-corner count it might have used instead is 4
        // for every shipped level, so it would have seen nothing at all.
        val open = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 2, floorCells = 40, parMoves = 12, parPushes = 4, deadSquares = 0
        )
        val trapped = open.copy(deadSquares = 20)

        assertTrue(
            "a trap-filled board must score higher than an open one",
            com.pushbound.game.engine.DifficultyModel.score(trapped) >
                com.pushbound.game.engine.DifficultyModel.score(open)
        )
    }

    @Test
    fun everyFactorRaisesTheScoreOnItsOwn() {
        // The model claims to consider these things, so each one must actually
        // move the number when the others are held still.
        val baseline = com.pushbound.game.engine.DifficultyModel.Inputs(
            boxes = 2, floorCells = 40, parMoves = 12, parPushes = 6, deadSquares = 4
        )
        val score = com.pushbound.game.engine.DifficultyModel::score
        val base = score(baseline)

        assertTrue("more boxes", score(baseline.copy(boxes = 3)) > base)
        assertTrue("a bigger board", score(baseline.copy(floorCells = 80)) > base)
        assertTrue("a longer solution", score(baseline.copy(parMoves = 24)) > base)
        assertTrue("more pushes", score(baseline.copy(parPushes = 12)) > base)
        assertTrue("more traps", score(baseline.copy(deadSquares = 20)) > base)
    }

    @Test
    fun goldIsReachableWithTheShortestSolution() {
        // The par values are the gold thresholds, so the shortest solution must
        // itself earn gold. Anything else would make gold unobtainable.
        campaign.allLevels.forEach { level ->
            val solution = LevelValidator.validate(level).solution
            assertNotNull("${level.id} must have a solution", solution)
            val medal = Medals.evaluate(
                moves = solution!!.moveCount,
                pushes = solution.pushCount,
                parMoves = level.parMoves,
                parPushes = level.parPushes,
                thresholds = level.medalThresholds ?: MedalThresholds.DEFAULT
            )
            assertEquals(
                "${level.id}: playing the shortest solution must earn gold",
                Medal.GOLD,
                medal
            )
        }
    }

    @Test
    fun sloppyPlayEarnsBronzeAndStillCompletes() {
        // Progression must never depend on performance: finishing at all is
        // enough to earn the progression tier.
        campaign.allLevels.forEach { level ->
            val medal = Medals.evaluate(
                moves = level.parMoves * 4,
                pushes = level.parPushes * 4,
                parMoves = level.parMoves,
                parPushes = level.parPushes,
                thresholds = level.medalThresholds ?: MedalThresholds.DEFAULT
            )
            assertEquals("${level.id}: finishing must always earn bronze", Medal.BRONZE, medal)
        }
    }

    @Test
    fun thePrototypeKeepsItsDocumentedSolutionLength() {
        // Existing contract: TestLevelSolvabilityTest documents a 16-move,
        // 3-push solution for the prototype and requires 8 <= moves <= 30.
        val level = Levels.byId("w1-1") ?: Levels.first
        val solution = LevelValidator.validate(Levels.first).solution
            ?: error("the prototype must be solvable")
        assertTrue("prototype solution grew to ${solution.moveCount}", solution.moveCount <= 16)
        assertEquals("prototype needs three pushes", 3, solution.pushCount)
        assertEquals(solution.moveCount, level.parMoves)
        assertEquals(solution.pushCount, level.parPushes)
    }

    @Test
    fun aFreshSessionOfEveryLevelIsPlayableAndNotAlreadyComplete() {
        campaign.allLevels.forEach { level: LevelDefinition ->
            val session = GameSession(level)
            assertTrue("${level.id} must start unsolved", !session.isComplete)
            assertEquals("${level.id} starts with 0 moves", 0, session.state.moves)
            assertEquals("${level.id} starts with 0 pushes", 0, session.state.pushes)
        }
    }
}
