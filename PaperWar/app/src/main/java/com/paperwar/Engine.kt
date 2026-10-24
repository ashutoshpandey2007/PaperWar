package com.paperwar

import java.util.Random
import kotlin.math.hypot

/**
 * Authoritative game rules. Only the HOST owns an Engine; the joining player just
 * sends requests (deploy / move / revive) and renders the state the host sends back.
 */
class Engine(private val rnd: Random = Random()) {
    val s = GameState()
    private val lastSeq = intArrayOf(-1, -1)
    private val formation = arrayOfNulls<FloatArray>(2)
    private var reviveSpawnMove = 0
    private var reviveExpiresAt = 0L
    private var awaitDeadline = 0L

    private fun say(msg: String) {
        s.eventSeq++
        s.event = msg
    }

    // ---------------------------------------------------------------- deployment

    /** d = [treasureX, treasureY, x0, y0, ... x9, y9]. Returns an error message or null. */
    fun deploy(p: Int, d: FloatArray): String? {
        if (s.phase != PHASE_DEPLOY) return "The battle already started"
        if (s.deployed[p]) return "Formation already locked"
        if (d.size != 2 + 2 * C.SOLDIERS) return "Invalid formation"
        val n = d.size / 2
        for (i in 0 until n) {
            val x = d[2 * i]
            val y = d[2 * i + 1]
            if (!x.isFinite() || !y.isFinite()) return "Invalid formation"
            if (!Geo.inZone(p, x, y)) return "Everything must stay inside your own zone"
            for (j in 0 until i) {
                if (Geo.dist(x, y, d[2 * j], d[2 * j + 1]) < C.MIN_GAP - 0.5f) return "Pieces overlap"
            }
        }
        formation[p] = d.copyOf()
        s.deployed[p] = true
        if (s.deployed[0] && s.deployed[1]) startBattle()
        return null
    }

    private fun startBattle() {
        for (p in 0..1) {
            val f = formation[p]!!
            s.tx[p] = f[0]
            s.ty[p] = f[1]
            for (k in 0 until C.SOLDIERS) {
                val so = Soldier(p * C.SOLDIERS + k)
                so.x = f[2 + 2 * k]
                so.y = f[3 + 2 * k]
                s.soldiers.add(so)
            }
        }
        s.phase = PHASE_BATTLE
        s.turn = rnd.nextInt(2)
        reviveSpawnMove = 2 + rnd.nextInt(4)
        say("Battle begins! ${playerName(s.turn)} moves first.")
    }

    // ---------------------------------------------------------------- moves

    /** Returns null on success, "dup" for a duplicated message, otherwise an error text. */
    fun move(p: Int, seq: Int, id: Int, ex: Float, ey: Float, durMs: Long, now: Long): String? {
        if (seq <= lastSeq[p]) return "dup"
        lastSeq[p] = seq
        if (s.phase != PHASE_BATTLE) return "The battle is not running"
        if (p != s.turn) return "Not your turn"
        if (s.awaitingRevive != -1) return "A revive choice is pending"
        if (!ex.isFinite() || !ey.isFinite()) return "Invalid move"
        if (id < 0 || id >= s.soldiers.size) return "Invalid soldier"
        val me = s.soldiers[id]
        if (me.owner != p) return "That is not your soldier"
        if (!me.alive) return "That soldier is eliminated"
        if (durMs < 0 || durMs > C.FLICK_MS + C.FLICK_TOL_MS) return "Flick took too long"

        var dx = ex - me.x
        var dy = ey - me.y
        val len = hypot(dx, dy)
        if (len < 1f) return "Flick too short"
        if (len > C.MAX_FLICK) {
            val k = C.MAX_FLICK / len
            dx *= k
            dy *= k
        }
        val x0 = me.x
        val y0 = me.y
        val x1 = (x0 + dx).coerceIn(C.SOLDIER_R, C.W - C.SOLDIER_R)
        val y1 = (y0 + dy).coerceIn(C.SOLDIER_R, C.H - C.SOLDIER_R)
        if (Geo.dist(x0, y0, x1, y1) < C.MIN_FLICK) return "Flick too short"

        val msgs = ArrayList<String>()

        // Commit the line.
        me.x = x1
        me.y = y1
        me.hasLine = true
        me.lx0 = x0
        me.ly0 = y0
        me.lx1 = x1
        me.ly1 = y1

        // Combat: the new line kills an enemy soldier if it crosses that soldier's latest line
        // or passes through its body (so soldiers that never moved are not invulnerable).
        var kills = 0
        for (e in s.soldiers) {
            if (e.owner == p || !e.alive) continue
            val hit = (e.hasLine && Geo.segmentsIntersect(x0, y0, x1, y1, e.lx0, e.ly0, e.lx1, e.ly1)) ||
                Geo.segPointDist(x0, y0, x1, y1, e.x, e.y) <= C.SOLDIER_R
            if (hit) {
                e.alive = false
                e.hasLine = false
                kills++
            }
        }
        if (kills > 0) msgs.add("${playerName(p)} eliminated $kills soldier${if (kills > 1) "s" else ""}!")

        // Treasure.
        val en = 1 - p
        if (Geo.segPointDist(x0, y0, x1, y1, s.tx[en], s.ty[en]) <= C.TREASURE_R) {
            if (s.alive(en) == 0) {
                s.winner = p
                s.phase = PHASE_ENDED
                s.moveCount++
                say("${playerName(p)} captured the treasure and wins!")
                return null
            } else {
                msgs.add("${playerName(en)}'s treasure is protected while any soldier lives!")
            }
        }

        // Revive power-up: the flick must have STARTED before the countdown ended,
        // the flick must pass through the power-up, and the player needs a fallen soldier.
        if (s.reviveState == RV_ACTIVE) {
            val startedAt = now - durMs
            if (startedAt <= reviveExpiresAt &&
                Geo.segPointDist(x0, y0, x1, y1, s.rx, s.ry) <= C.COLLECT_R &&
                s.dead(p) > 0
            ) {
                s.reviveState = RV_USED
                s.awaitingRevive = p
                awaitDeadline = now + C.REVIVE_PICK_MS
                s.moveCount++
                msgs.add("${playerName(p)} grabbed REVIVE!")
                say(msgs.joinToString("  "))
                return null
            }
        }

        s.moveCount++
        finishTurn(now)
        if (msgs.isNotEmpty()) say(msgs.joinToString("  "))
        return null
    }

    fun revive(p: Int, id: Int, now: Long): String? {
        if (s.phase != PHASE_BATTLE || s.awaitingRevive != p) return "No revive is pending"
        if (id < 0 || id >= s.soldiers.size) return "Invalid soldier"
        val d = s.soldiers[id]
        if (d.owner != p || d.alive) return "Pick one of your fallen soldiers"
        d.alive = true
        d.hasLine = false
        d.x = s.rx
        d.y = s.ry
        s.awaitingRevive = -1
        say("${playerName(p)} revived a soldier!")
        finishTurn(now)
        return null
    }

    private fun finishTurn(now: Long) {
        val other = 1 - s.turn
        // If the opponent has nobody left to move, the current player keeps the turn.
        if (s.alive(other) > 0) s.turn = other
        maybeSpawnRevive(now)
    }

    private fun maybeSpawnRevive(now: Long) {
        if (s.reviveState != RV_NONE || s.moveCount < reviveSpawnMove) return
        if (s.soldiers.none { !it.alive }) return          // nobody to revive yet
        var px = 500f
        var py = 800f
        var found = false
        // Pass 1: free spot that the player to move (if he has a fallen soldier) can actually reach.
        // Pass 2: any free spot.
        for (pass in 0..1) {
            for (i in 0 until 120) {
                val x = 150f + rnd.nextFloat() * 700f
                val y = 560f + rnd.nextFloat() * 480f
                if (!spotFree(x, y)) continue
                if (pass == 0 && !reachableBy(s.turn, x, y) && !reachableBy(1 - s.turn, x, y)) continue
                if (pass == 0 && s.dead(s.turn) > 0 && !reachableBy(s.turn, x, y)) continue
                px = x
                py = y
                found = true
                break
            }
            if (found) break
        }
        s.rx = px
        s.ry = py
        s.reviveState = RV_ACTIVE
        reviveExpiresAt = now + C.REVIVE_MS
        say("REVIVE appeared! Reach it within 10 seconds.")
    }

    /** True if some living soldier of player p can reach (x,y) with one maximum-length flick. */
    private fun reachableBy(p: Int, x: Float, y: Float): Boolean {
        for (so in s.soldiers) {
            if (so.owner == p && so.alive && Geo.dist(x, y, so.x, so.y) <= C.MAX_FLICK * 0.85f) return true
        }
        return false
    }

    private fun spotFree(x: Float, y: Float): Boolean {
        for (so in s.soldiers) if (so.alive && Geo.dist(x, y, so.x, so.y) < 110f) return false
        for (p in 0..1) if (Geo.dist(x, y, s.tx[p], s.ty[p]) < 110f) return false
        return true
    }

    /** Call regularly on the host. Returns true if the state changed and must be broadcast. */
    fun tick(now: Long): Boolean {
        if (s.phase != PHASE_BATTLE) return false
        if (s.reviveState == RV_ACTIVE && now > reviveExpiresAt + C.REVIVE_GRACE_MS) {
            s.reviveState = RV_EXPIRED
            say("The REVIVE power-up vanished.")
            return true
        }
        if (s.awaitingRevive != -1 && now > awaitDeadline) {
            val p = s.awaitingRevive
            val d = s.soldiers.firstOrNull { it.owner == p && !it.alive }
            if (d != null) revive(p, d.id, now) else {
                s.awaitingRevive = -1
                finishTurn(now)
            }
            return true
        }
        return false
    }

    /** Fill the time-dependent fields before sending the state. */
    fun snapshot(now: Long) {
        s.reviveLeftMs = if (s.reviveState == RV_ACTIVE) maxOf(0L, reviveExpiresAt - now) else 0L
    }
}
