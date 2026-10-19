package com.pushbound.game.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.R
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Milestone 4 grid foundation. ONE GRID CELL IS THE SINGLE SPATIAL UNIT:
 * [BoardGeometry] derives every placement from one authoritative cell size,
 * and each approved sprite's measured SUBJECT (not its full 1024² frame) is
 * mapped into its cell via [BoardSprites] footprints. Walls fill one cell of
 * footprint with the brick face tiled at the art's natural course pitch
 * (global phase, so vertical runs are continuous) and a lit cap course at
 * exposed run ends. See BoardLayout.kt for the measured source values.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BoardView(controller: GameController, modifier: Modifier = Modifier) {
    val level = controller.level
    val floor = rememberGameBitmap(R.drawable.environment_floor_base)
    val wall = rememberGameBitmap(R.drawable.environment_wall_straight)
    val goal = rememberGameBitmap(R.drawable.object_goal)
    val box = rememberGameBitmap(R.drawable.object_box)
    val player = rememberGameBitmap(R.drawable.character_idle) // DOWN frame (approved master)
    val playerUp = rememberGameBitmap(R.drawable.character_idle_up)
    val playerLeft = rememberGameBitmap(R.drawable.character_idle_left)
    val playerRight = rememberGameBitmap(R.drawable.character_idle_right)
    // Approved 12-frame animation set (md5-verified deliveries in
    // res/drawable-nodpi): walk A/B strides and the four push frames.
    val walkDownA = rememberGameBitmap(R.drawable.character_walk_down_a)
    val walkDownB = rememberGameBitmap(R.drawable.character_walk_down_b)
    val walkUpA = rememberGameBitmap(R.drawable.character_walk_up_a)
    val walkUpB = rememberGameBitmap(R.drawable.character_walk_up_b)
    val walkLeftA = rememberGameBitmap(R.drawable.character_walk_left_a)
    val walkLeftB = rememberGameBitmap(R.drawable.character_walk_left_b)
    val walkRightA = rememberGameBitmap(R.drawable.character_walk_right_a)
    val walkRightB = rememberGameBitmap(R.drawable.character_walk_right_b)
    val pushDown = rememberGameBitmap(R.drawable.character_push_down)
    val pushUp = rememberGameBitmap(R.drawable.character_push_up)
    val pushLeft = rememberGameBitmap(R.drawable.character_push_left)
    val pushRight = rememberGameBitmap(R.drawable.character_push_right)
    val textMeasurer = rememberTextMeasurer()

    val state = controller.state

    val playerTarget = Offset(state.player.col.toFloat(), state.player.row.toFloat())
    val playerAnim = remember { Animatable(playerTarget, Offset.VectorConverter) }
    // Linear 0..1 timeline of the current cell transition: the walk phase
    // math in CharacterAnimation is defined over linear move progress, so
    // this runs on LinearEasing while the position glide keeps its easing.
    // Starts settled (1f): first composition must show a plain IDLE frame,
    // never an in-place step cycle.
    val moveProgress = remember { Animatable(1f) }
    var lastEpoch by remember { mutableStateOf(controller.resetEpoch) }
    var lastTarget by remember { mutableStateOf(playerTarget) }
    LaunchedEffect(playerTarget, controller.resetEpoch) {
        if (lastEpoch != controller.resetEpoch) {
            lastEpoch = controller.resetEpoch
            playerAnim.snapTo(playerTarget)
            moveProgress.snapTo(1f) // restart: everything settles at once
        } else if (lastTarget != playerTarget) {
            moveProgress.snapTo(0f)
            launch {
                moveProgress.animateTo(
                    1f,
                    tween(GameController.MOVE_ANIMATION_MS.toInt(), easing = LinearEasing)
                )
            }
            playerAnim.animateTo(
                playerTarget,
                tween(GameController.MOVE_ANIMATION_MS.toInt(), easing = FastOutSlowInEasing)
            )
        }
        lastTarget = playerTarget
    }

    val push = controller.lastPush
    var activePush by remember { mutableStateOf<PushAnim?>(null) }
    val boxAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(push?.id, controller.resetEpoch) {
        val current = push
        if (current == null) {
            activePush = null
        } else {
            activePush = current
            boxAnim.snapTo(Offset(current.from.col.toFloat(), current.from.row.toFloat()))
            // The box stays put while the character closes the gap, then
            // travels and arrives together with the character.
            delay(CharacterAnimation.boxStartDelayMs(GameController.MOVE_ANIMATION_MS))
            boxAnim.animateTo(
                Offset(current.to.col.toFloat(), current.to.row.toFloat()),
                tween(
                    CharacterAnimation.boxDurationMs(GameController.MOVE_ANIMATION_MS).toInt(),
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    // --- blocked-push effort pulse: one 140ms frame-clock run per id ---
    val blocked = controller.lastBlocked
    var blockedElapsedMs by remember { mutableStateOf(-1L) }
    LaunchedEffect(blocked?.id, controller.resetEpoch) {
        if (blocked == null) {
            blockedElapsedMs = -1L
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            val elapsed = (withFrameNanos { it } - start) / 1_000_000L
            blockedElapsedMs = elapsed
            if (elapsed >= CharacterAnimation.BLOCKED_PULSE_MS) break
        }
    }

    val progress = moveProgress.value
    val kind = controller.currentMove?.kind ?: MoveAnimKind.TRANSITION
    val blockedPulse = if (blocked != null) CharacterAnimation.blockedPulse(blockedElapsedMs) else 0f
    val pulseActive = blockedPulse > 0f
    // A running move always wins over a pulse (a fresh press can start a
    // step while the previous effort envelope is still releasing).
    val pose = if (pulseActive && progress >= 1f) {
        CharacterPose.PUSH
    } else {
        CharacterAnimation.movePose(progress, kind)
    }
    val settled = progress >= 1f && !pulseActive

    // --- idle breathing clock: runs only while the character is settled -
    var breathNanos by remember { mutableStateOf(0L) }
    LaunchedEffect(settled, controller.resetEpoch) {
        if (!settled) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            breathNanos = withFrameNanos { it } - start
        }
    }

    // All render offsets in cell fractions — the ONE spatial unit — so the
    // renderer never introduces a second coordinate system.
    val bobFraction =
        if (kind == MoveAnimKind.STEP) CharacterAnimation.walkBob(progress) else 0f
    val breathFraction =
        if (settled) CharacterAnimation.idleBreath(breathNanos / 1_000_000L) else 0f
    val lean = if (pulseActive && progress >= 1f && blocked != null) {
        CharacterAnimation.forwardCells(
            blocked.direction,
            CharacterAnimation.blockedOffset(blockedElapsedMs)
        )
    } else {
        CellOffset(0f, 0f)
    }

    // Pose -> the four directional bitmaps of that pose (stable after first
    // composition; identity and style are identical across the whole set).
    val poseFrameSets = remember(
        player, playerUp, playerLeft, playerRight,
        walkDownA, walkDownB, walkUpA, walkUpB,
        walkLeftA, walkLeftB, walkRightA, walkRightB,
        pushDown, pushUp, pushLeft, pushRight
    ) {
        mapOf(
            CharacterPose.IDLE to mapOf(
                Direction.DOWN to player, Direction.UP to playerUp,
                Direction.LEFT to playerLeft, Direction.RIGHT to playerRight
            ),
            CharacterPose.STRIDE_A to mapOf(
                Direction.DOWN to walkDownA, Direction.UP to walkUpA,
                Direction.LEFT to walkLeftA, Direction.RIGHT to walkRightA
            ),
            CharacterPose.STRIDE_B to mapOf(
                Direction.DOWN to walkDownB, Direction.UP to walkUpB,
                Direction.LEFT to walkLeftB, Direction.RIGHT to walkRightB
            ),
            CharacterPose.PUSH to mapOf(
                Direction.DOWN to pushDown, Direction.UP to pushUp,
                Direction.LEFT to pushLeft, Direction.RIGHT to pushRight
            )
        )
    }

    BoxWithConstraints(modifier) {
        // Fit in dp space just to size the board canvas; the authoritative
        // pixel geometry is rebuilt from the canvas' own size below, so no
        // explicit dp<->px conversion is needed anywhere.
        val board = BoardGeometry.fit(maxWidth.value, maxHeight.value, level.width, level.height)
        if (board.cellSize > 0f) {
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(board.boardWidth.dp, board.boardHeight.dp)
            ) {
                val geometry = BoardGeometry.fit(size.width, size.height, level.width, level.height)
                val wallSubset = level.walls

                // --- floor: exact cell fill, tiles edge to edge -------------
                for (row in 0 until level.height) {
                    for (col in 0 until level.width) {
                        val l = geometry.cellLeft(col.toFloat()).roundToInt()
                        val t = geometry.cellTop(row.toFloat()).roundToInt()
                        val r = geometry.cellLeft((col + 1).toFloat()).roundToInt()
                        val b = geometry.cellTop((row + 1).toFloat()).roundToInt()
                        floor.drawSubject(this, l, t, r - l, b - t)
                    }
                }

                // --- goals: floor-plane, centered on the cell center ---------
                for (goalPosition in level.goals) {
                    BoardSprites.goal.draw(this, goal, geometry, goalPosition.col.toFloat(), goalPosition.row.toFloat())
                }

                // --- walls: one cell footprint, tiled face + cap at run ends -
                val pitch = WallTiling.facePitch(geometry.cellSize)
                    .let { if (it > 0f) it else 1f }
                for (wallPosition in wallSubset) {
                    val l = geometry.cellLeft(wallPosition.col.toFloat()).roundToInt()
                    val t = geometry.cellTop(wallPosition.row.toFloat()).roundToInt()
                    val r = geometry.cellLeft((wallPosition.col + 1).toFloat()).roundToInt()
                    val b = geometry.cellTop((wallPosition.row + 1).toFloat()).roundToInt()
                    clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()) {
                        // Face = one full masonry course per tile (mortar +
                        // brick face + AO under-shadow), tiled on the global
                        // phase anchor. Alternate courses shift half a brick
                        // (running bond), wrapping at the tile seam.
                        val faceH = ApprovedSprites.WALL_FACE_BOTTOM - ApprovedSprites.WALL_FACE_TOP
                        val bondPx = WallTiling.bondOffset(geometry.cellSize).roundToInt()
                        for (m in WallTiling.faceTileIndices(t.toFloat(), b.toFloat(), pitch)) {
                            val top = (m * pitch).roundToInt()
                            val bottom = ((m + 1) * pitch).roundToInt()
                            val dstH = bottom - top
                            val dx = if (WallTiling.isBondOffsetCourse(m)) bondPx else 0
                            if (dx in 1 until (r - l)) {
                                wall.drawSubject(
                                    this, l + dx, top, r - l - dx, dstH,
                                    srcX = ApprovedSprites.WALL_BRICK_BOND,
                                    srcY = ApprovedSprites.WALL_FACE_TOP,
                                    srcW = ApprovedSprites.FRAME - ApprovedSprites.WALL_BRICK_BOND,
                                    srcH = faceH
                                )
                                wall.drawSubject(
                                    this, l, top, dx, dstH,
                                    srcX = 0,
                                    srcY = ApprovedSprites.WALL_FACE_TOP,
                                    srcW = ApprovedSprites.WALL_BRICK_BOND,
                                    srcH = faceH
                                )
                            } else {
                                wall.drawSubject(
                                    this, l, top, r - l, dstH,
                                    srcX = 0,
                                    srcY = ApprovedSprites.WALL_FACE_TOP,
                                    srcW = ApprovedSprites.FRAME,
                                    srcH = faceH
                                )
                            }
                        }
                        val above = Position(wallPosition.row - 1, wallPosition.col) in wallSubset
                        val below = Position(wallPosition.row + 1, wallPosition.col) in wallSubset
                        if (!above || !below) {
                            wall.drawSubject(
                                this, l, t, r - l, WallTiling.capHeight(geometry.cellSize).roundToInt(),
                                srcX = 0, srcY = ApprovedSprites.WALL_CAP_TOP,
                                srcW = ApprovedSprites.FRAME,
                                srcH = ApprovedSprites.WALL_CAP_BOTTOM - ApprovedSprites.WALL_CAP_TOP
                            )
                        }
                    }
                }

                // --- boxes: ground-line anchored, animated in grid coords ---
                for (boxPosition in state.boxes) {
                    val animated = activePush?.to == boxPosition
                    val colF: Float
                    val rowF: Float
                    if (activePush != null && animated) {
                        colF = boxAnim.value.x
                        rowF = boxAnim.value.y
                    } else {
                        colF = boxPosition.col.toFloat()
                        rowF = boxPosition.row.toFloat()
                    }
                    BoardSprites.box.draw(this, box, geometry, colF, rowF)
                }

                // --- player: ground-line anchored, animated in grid coords --
                // Direction change = sprite change AND pose change = frame
                // change, both on the SAME common scale/anchor: the frame
                // follows the engine's facing and the current [pose] without
                // jumping, resizing or shifting (every pose frame shares one
                // height, ground line and center — see CharacterFrames).
                // Bob, breathing and the blocked-effort lean are cell
                // fractions added straight into the grid position.
                val facing = state.facing
                val frameImage = poseFrameSets.getValue(pose).getValue(facing)
                BoardSprites.playerFrames.drawFrame(
                    this, frameImage, geometry,
                    playerAnim.value.x + lean.col,
                    playerAnim.value.y + lean.row - bobFraction - breathFraction,
                    facing, pose
                )

                // --- development-only grid overlay (off by default) ---------
                if (DebugGridConfig.ENABLED) {
                    drawDebugGrid(geometry, level.width, level.height, state.player, state.boxes, wallSubset, level.goals, textMeasurer)
                }
            }
        }
    }
}

/** Draws an ImageBitmap region into an integer rect. */
private fun ImageBitmap.drawSubject(
    scope: DrawScope, left: Int, top: Int, width: Int, height: Int,
    srcX: Int = ApprovedSprites.floor.x, srcY: Int = ApprovedSprites.floor.y,
    srcW: Int = ApprovedSprites.floor.width, srcH: Int = ApprovedSprites.floor.height
) {
    scope.drawImage(
        image = this,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(width, height)
    )
}

/** Places one sprite's subject into the logical cell via its footprint. */
private fun SpritePlacement.draw(scope: DrawScope, image: ImageBitmap, geometry: BoardGeometry, col: Float, row: Float) {
    val rect = rectIn(geometry, col, row)
    scope.drawImage(
        image = image,
        srcOffset = IntOffset(subject.x, subject.y),
        srcSize = IntSize(subject.width, subject.height),
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
    )
}

/** Places one character pose frame on the shared scale/anchor. */
private fun CharacterFrames.drawFrame(
    scope: DrawScope, image: ImageBitmap, geometry: BoardGeometry,
    col: Float, row: Float, facing: Direction, pose: CharacterPose
) {
    val rect = rectIn(geometry, col, row, facing, pose)
    val subject = subjectFor(facing, pose)
    scope.drawImage(
        image = image,
        srcOffset = IntOffset(subject.x, subject.y),
        srcSize = IntSize(subject.width, subject.height),
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
    )
}

/** Debug overlay: cell boundaries, board outline, semantic tints, coordinates. */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawDebugGrid(
    geometry: BoardGeometry,
    columns: Int,
    rows: Int,
    player: Position,
    boxes: Set<Position>,
    walls: Set<Position>,
    goals: Set<Position>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    fun cellOffset(c: Int, r: Int) = Offset(geometry.cellLeft(c.toFloat()), geometry.cellTop(r.toFloat()))
    fun cellSize() = Size(geometry.cellSize, geometry.cellSize)

    for (w in walls) drawRect(Color(0x33FF9500), cellOffset(w.col, w.row), cellSize())
    for (g in goals) drawRect(Color(0x33FFD60A), cellOffset(g.col, g.row), cellSize())
    for (b in boxes) drawRect(Color(0x3330D158), cellOffset(b.col, b.row), cellSize())
    drawRect(Color(0x330A84FF), cellOffset(player.col, player.row), cellSize())

    val line = Color(0xAAFF3B30)
    for (r in 0..rows) {
        val y = geometry.cellTop(r.toFloat())
        drawLine(line, Offset(0f, y), Offset(geometry.boardWidth, y), strokeWidth = 1f)
    }
    for (c in 0..columns) {
        val x = geometry.cellLeft(c.toFloat())
        drawLine(line, Offset(x, 0f), Offset(x, geometry.boardHeight), strokeWidth = 1f)
    }

    for (r in 0 until rows) {
        for (c in 0 until columns) {
            drawText(
                textMeasurer = textMeasurer,
                text = "$c,$r",
                topLeft = cellOffset(c, r) + Offset(3f, 2f),
                style = TextStyle(color = Color(0xCCFF3B30), fontSize = 9.sp)
            )
        }
    }
}
