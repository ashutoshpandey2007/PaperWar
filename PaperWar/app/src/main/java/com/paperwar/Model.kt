package com.paperwar

/** All tunable game constants. Field coordinates are logical: 1000 x 1600, boundary at y = 800. */
object C {
    const val W = 1000f
    const val H = 1600f
    const val MID = 800f
    const val MARGIN = 60f
    const val SOLDIERS = 10
    const val SOLDIER_R = 28f
    const val TREASURE_R = 40f
    const val MIN_GAP = 70f
    const val MAX_FLICK = 380f
    const val MIN_FLICK = 30f
    const val FLICK_MS = 2000L
    const val FLICK_TOL_MS = 400L
    const val REVIVE_MS = 10000L
    const val REVIVE_GRACE_MS = FLICK_MS + FLICK_TOL_MS
    const val REVIVE_PICK_MS = 15000L
    const val COLLECT_R = 60f
    const val PORT = 45454
    const val DISCOVERY_PORT = 45455
}

const val PHASE_DEPLOY = 0
const val PHASE_BATTLE = 1
const val PHASE_ENDED = 2

const val RV_NONE = 0
const val RV_ACTIVE = 1
const val RV_USED = 2
const val RV_EXPIRED = 3

fun playerName(p: Int) = if (p == 0) "Blue" else "Red"

class Soldier(val id: Int) {
    var x = 0f
    var y = 0f
    var alive = true
    var hasLine = false
    var lx0 = 0f
    var ly0 = 0f
    var lx1 = 0f
    var ly1 = 0f
    val owner: Int get() = id / C.SOLDIERS
}

class GameState {
    var phase = PHASE_DEPLOY
    var turn = 0
    val soldiers = ArrayList<Soldier>()      // index == soldier id (0-9 Blue, 10-19 Red)
    val tx = FloatArray(2)
    val ty = FloatArray(2)
    val deployed = BooleanArray(2)
    var reviveState = RV_NONE
    var rx = 0f
    var ry = 0f
    var reviveLeftMs = 0L                    // snapshot value, see Session.reviveRemainingMs()
    var awaitingRevive = -1                  // player who must pick a soldier to revive
    var winner = -1
    var moveCount = 0
    var eventSeq = 0
    var event = ""

    fun alive(p: Int) = soldiers.count { it.owner == p && it.alive }
    fun dead(p: Int) = soldiers.count { it.owner == p && !it.alive }
}
