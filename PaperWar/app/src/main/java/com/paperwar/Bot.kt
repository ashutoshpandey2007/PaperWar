package com.paperwar

import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val BOT_EASY = 0
const val BOT_MEDIUM = 1
const val BOT_HARD = 2

/**
 * Computer opponent (always plays Red, player 1).
 *
 * Fairness rules, so it never feels like "an exam with a calculator":
 *  1. It only reads the public game state a human player also sees on screen.
 *     It never sees your formation while you are still placing pieces.
 *  2. It plays through the normal Engine.move()/revive() calls, so every rule applies to it.
 *  3. It decides what it WANTS to do, then a human-error step spoils the flick (angle wobble, flicks
 *     that fall a little short, bad mood after a miss). The Engine then decides what really happens,
 *     and the bot does not know the result in advance.
 *  4. Difficulty only changes how big those errors are and how often it blunders.
 */
class Bot(level: Int, private val rnd: Random = Random()) {
    class Move(val id: Int, val ex: Float, val ey: Float, val dur: Long, val aimedKill: Boolean)

    private val lvl = level.coerceIn(0, 2)

    // index: 0 = easy, 1 = medium, 2 = hard
    private val angleSigma = floatArrayOf(0.15f, 0.075f, 0.035f)[lvl]   // radians of aim wobble
    private val lenSigma = floatArrayOf(0.09f, 0.05f, 0.025f)[lvl]      // flick strength wobble
    private val lenBias = floatArrayOf(0.95f, 0.97f, 0.985f)[lvl]        // humans tend to undershoot
    private val blunder = floatArrayOf(0.30f, 0.14f, 0.05f)[lvl]         // chance of a poor decision
    private val thinkMin = longArrayOf(1800L, 1200L, 900L)[lvl]
    private val thinkMax = longArrayOf(3500L, 2600L, 2000L)[lvl]

    /** Mood: goes up after a miss (sloppier), down after a hit (more confident). */
    private var tilt = 1f

    private fun gauss(): Float = rnd.nextGaussian().toFloat()

    fun thinkDelay(phase: Int): Long =
        if (phase == PHASE_DEPLOY) 1200L + rnd.nextInt(1800)
        else thinkMin + (rnd.nextFloat() * (thinkMax - thinkMin)).toLong()

    fun reviveDelay(): Long = 800L + rnd.nextInt(900)

    // ------------------------------------------------------------ deployment

    /** Random but sensible formation inside Red's zone (top half). Layout: [tx, ty, x0, y0, ... x9, y9]. */
    fun formation(): FloatArray {
        for (attempt in 0 until 30) {
            val d = tryFormation()
            if (d != null) return d
        }
        return gridFormation()
    }

    private fun tryFormation(): FloatArray? {
        val pts = ArrayList<FloatArray>()
        fun ok(x: Float, y: Float): Boolean {
            if (!Geo.inZone(1, x, y)) return false
            for (q in pts) if (Geo.dist(x, y, q[0], q[1]) < C.MIN_GAP + 4f) return false
            return true
        }
        val tx = 220f + rnd.nextFloat() * 560f
        val ty = 90f + rnd.nextFloat() * 200f
        pts.add(floatArrayOf(tx, ty))
        val style = rnd.nextInt(3)               // 0 = front wall, 1 = guard ring, 2 = scattered
        var tries = 0
        while (pts.size < 11 && tries < 600) {
            tries++
            val p = when (style) {
                0 -> floatArrayOf(80f + rnd.nextFloat() * 840f, 430f + rnd.nextFloat() * 220f)
                1 -> {
                    val a = rnd.nextFloat() * 6.2832f
                    val r = 100f + rnd.nextFloat() * 120f
                    floatArrayOf(tx + cos(a) * r, ty + sin(a) * r)
                }
                else -> floatArrayOf(80f + rnd.nextFloat() * 840f, 80f + rnd.nextFloat() * 640f)
            }
            if (ok(p[0], p[1])) pts.add(p)
        }
        if (pts.size < 11) return null
        val d = FloatArray(22)
        for (i in 0 until 11) {
            d[2 * i] = pts[i][0]
            d[2 * i + 1] = pts[i][1]
        }
        return d
    }

    /** Guaranteed-valid fallback: 11 random distinct cells of a 100-unit grid. */
    private fun gridFormation(): FloatArray {
        val cells = ArrayList<FloatArray>()
        var gx = 100f
        while (gx <= 900f) {
            var gy = 100f
            while (gy <= 700f) {
                cells.add(floatArrayOf(gx, gy))
                gy += 100f
            }
            gx += 100f
        }
        cells.shuffle(rnd)
        val d = FloatArray(22)
        for (i in 0 until 11) {
            d[2 * i] = cells[i][0]
            d[2 * i + 1] = cells[i][1]
        }
        return d
    }

    // ------------------------------------------------------------ battle

    fun pickRevive(s: GameState): Int {
        val dead = s.soldiers.filter { it.owner == 1 && !it.alive }
        if (dead.isEmpty()) return -1
        return dead[rnd.nextInt(dead.size)].id
    }

    private class Cand(val id: Int, val ex: Float, val ey: Float, val score: Float, val kills: Int)

    fun chooseMove(s: GameState): Move? {
        val me = 1
        val en = 0
        val mine = s.soldiers.filter { it.owner == me && it.alive }
        if (mine.isEmpty()) return null
        val foes = s.soldiers.filter { it.owner == en && it.alive }
        val reviveWanted = s.reviveState == RV_ACTIVE && s.dead(me) > 0
        val cands = ArrayList<Cand>()

        for (sol in mine) {
            val x0 = sol.x
            val y0 = sol.y
            // target = [x, y, overshoot]
            val targets = ArrayList<FloatArray>()
            for (f in foes) {
                targets.add(floatArrayOf(f.x, f.y, 0f))
                if (f.hasLine) {
                    for (t in floatArrayOf(0.15f, 0.5f, 0.85f)) {
                        val px = f.lx0 + (f.lx1 - f.lx0) * t
                        val py = f.ly0 + (f.ly1 - f.ly0) * t
                        // Crossing a line needs the flick to end PAST it: a small margin that humans often miss.
                        targets.add(floatArrayOf(px, py, 8f + rnd.nextFloat() * 30f))
                    }
                }
            }
            if (foes.isEmpty()) targets.add(floatArrayOf(s.tx[en], s.ty[en], 15f))
            if (reviveWanted) targets.add(floatArrayOf(s.rx, s.ry, 20f))
            // A few plain "reposition" moves, mostly toward the enemy side.
            for (k in 0 until 5) {
                val ang = 3.1416f * (0.15f + 0.7f * rnd.nextFloat())
                val len = 120f + rnd.nextFloat() * 180f
                targets.add(floatArrayOf(x0 + cos(ang) * len, y0 + sin(ang) * len, 0f))
            }

            for (t in targets) {
                var dx = t[0] - x0
                var dy = t[1] - y0
                val d = hypot(dx, dy)
                if (d < 1f) continue
                dx += dx / d * t[2]
                dy += dy / d * t[2]
                val len = hypot(dx, dy)
                if (len > C.MAX_FLICK) {
                    val k = C.MAX_FLICK / len
                    dx *= k
                    dy *= k
                }
                // Same end-point clamping as Engine.move()
                val x1 = (x0 + dx).coerceIn(C.SOLDIER_R, C.W - C.SOLDIER_R)
                val y1 = (y0 + dy).coerceIn(C.SOLDIER_R, C.H - C.SOLDIER_R)
                if (Geo.dist(x0, y0, x1, y1) < C.MIN_FLICK + 4f) continue

                // How this flick would turn out if it were executed perfectly (the bot's intention).
                var kills = 0
                val survivors = ArrayList<Soldier>()
                for (f in foes) {
                    val hit = (f.hasLine && Geo.segmentsIntersect(x0, y0, x1, y1, f.lx0, f.ly0, f.lx1, f.ly1)) ||
                        Geo.segPointDist(x0, y0, x1, y1, f.x, f.y) <= C.SOLDIER_R
                    if (hit) kills++ else survivors.add(f)
                }
                var sc = kills * 100f
                if (survivors.isEmpty() &&
                    Geo.segPointDist(x0, y0, x1, y1, s.tx[en], s.ty[en]) <= C.TREASURE_R
                ) sc += 10000f
                if (reviveWanted && Geo.segPointDist(x0, y0, x1, y1, s.rx, s.ry) <= C.COLLECT_R) sc += 70f

                // Be careful: don't end up where many enemy soldiers can reach me or my new line.
                var exposure = 0f
                for (f in survivors) {
                    val dEnd = Geo.dist(f.x, f.y, x1, y1)
                    if (dEnd <= C.MAX_FLICK) exposure += 10f * (1f - dEnd / (C.MAX_FLICK * 1.2f))
                    val dLine = Geo.segPointDist(x0, y0, x1, y1, f.x, f.y)
                    if (dLine <= C.MAX_FLICK) exposure += 6f * (1f - dLine / (C.MAX_FLICK * 1.2f))
                }
                sc -= exposure * (0.6f + 0.2f * lvl)

                // With nothing to hit, drift toward the enemy.
                if (kills == 0) {
                    var near = 2000f
                    for (f in foes) near = min(near, Geo.dist(x1, y1, f.x, f.y))
                    if (foes.isEmpty()) near = Geo.dist(x1, y1, s.tx[en], s.ty[en])
                    sc -= 0.03f * near
                }
                // Mood / randomness so it is not a deterministic machine.
                sc += (rnd.nextFloat() - 0.5f) * 14f
                cands.add(Cand(sol.id, x1, y1, sc, kills))
            }
        }
        if (cands.isEmpty()) return null
        cands.sortByDescending { it.score }
        val pick = if (rnd.nextFloat() < blunder) cands[rnd.nextInt(min(6, cands.size))] else cands[0]

        // ---- human error: this is what makes the bot miss "by a hair" sometimes ----
        val sol = s.soldiers[pick.id]
        val dx = pick.ex - sol.x
        val dy = pick.ey - sol.y
        var len = hypot(dx, dy)
        var ang = atan2(dy, dx)
        val farther = 0.6f + 0.8f * (len / C.MAX_FLICK)                  // long shots are harder
        ang += gauss() * angleSigma * tilt * farther
        len *= (lenBias + gauss() * lenSigma * tilt)
        len = len.coerceIn(C.MIN_FLICK + 6f, C.MAX_FLICK)
        val ex = sol.x + cos(ang) * len
        val ey = sol.y + sin(ang) * len
        val dur = 450L + rnd.nextInt(1100)                                // looks like a real flick
        return Move(pick.id, ex, ey, dur, pick.kills > 0)
    }

    /** Called after the move was executed, so the bot can get cocky or frustrated like a person. */
    fun afterMove(aimedKill: Boolean, killedSomething: Boolean) {
        tilt = when {
            aimedKill && !killedSomething -> min(1.5f, tilt * 1.18f)
            killedSomething -> max(0.85f, tilt * 0.93f)
            else -> tilt + (1f - tilt) * 0.1f
        }
    }
}
