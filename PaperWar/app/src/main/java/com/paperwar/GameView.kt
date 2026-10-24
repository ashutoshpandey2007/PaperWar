package com.paperwar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the paper battlefield and handles deployment + flick touch input. */
class GameView(ctx: Context) : View(ctx) {
    var onChange: () -> Unit = {}

    private val blue = Color.parseColor("#1F5FBF")
    private val red = Color.parseColor("#C62828")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    private var selected = -1
    private var dragging = false
    private var dragStart = 0L
    private var curX = 0f
    private var curY = 0f
    private var dragIdx = -1
    private var pendId = -1
    private var pendX = 0f
    private var pendY = 0f
    private val timeout = Runnable { if (dragging) finishDrag(true) }

    private fun me() = Session.myIndex
    private fun colorOf(p: Int) = if (p == 0) blue else red
    private fun tint(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // Each player sees their own army at the bottom: player 1's view is rotated 180 degrees.
    private fun px(x: Float) = ox + (if (me() == 1) C.W - x else x) * sc
    private fun py(y: Float) = oy + (if (me() == 1) C.H - y else y) * sc
    private fun fromPx(v: Float): Float { val l = (v - ox) / sc; return if (me() == 1) C.W - l else l }
    private fun fromPy(v: Float): Float { val l = (v - oy) / sc; return if (me() == 1) C.H - l else l }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sc = min(w / C.W, h / C.H)
        ox = (w - C.W * sc) / 2f
        oy = (h - C.H * sc) / 2f
    }

    fun refresh() {
        val st = Session.state
        if (st.phase != PHASE_BATTLE || st.turn != me() || st.awaitingRevive != -1) {
            selected = -1
            dragging = false
            removeCallbacks(timeout)
        } else if (selected >= 0 && (selected >= st.soldiers.size || !st.soldiers[selected].alive)) {
            selected = -1
        }
        if (!Session.moveInFlight) pendId = -1
        if (Session.confirmed) dragIdx = -1
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val st = Session.state
        drawPaper(c)
        if (st.phase == PHASE_DEPLOY) drawDeploy(c) else drawBattle(c, st)
        if (st.reviveState == RV_ACTIVE || dragging) postInvalidateOnAnimation()
    }

    private fun lw() = max(3f, 6f * sc)

    private fun drawPaper(c: Canvas) {
        c.drawColor(Color.parseColor("#E9E4D4"))
        val l = ox
        val t = oy
        val r = ox + C.W * sc
        val b = oy + C.H * sc
        val midY = oy + C.MID * sc
        fill.color = Color.parseColor("#FBF8EF")
        c.drawRect(l, t, r, b, fill)
        fill.color = tint(colorOf(1 - me()), 22)
        c.drawRect(l, t, r, midY, fill)
        fill.color = tint(colorOf(me()), 22)
        c.drawRect(l, midY, r, b, fill)
        line.pathEffect = null
        line.strokeWidth = 1.5f
        line.color = Color.parseColor("#C9DDEC")
        var gy = 40f
        while (gy < C.H) {
            val yy = oy + gy * sc
            c.drawLine(l, yy, r, yy, line)
            gy += 40f
        }
        line.color = Color.parseColor("#F0B4B4")
        c.drawLine(l + 80f * sc, t, l + 80f * sc, b, line)
        line.color = Color.parseColor("#8D8870")
        line.strokeWidth = 3f
        c.drawRect(l, t, r, b, line)
        line.color = Color.parseColor("#444444")
        line.strokeWidth = 4f
        line.pathEffect = DashPathEffect(floatArrayOf(24f, 14f), 0f)
        c.drawLine(l, midY, r, midY, line)
        line.pathEffect = null
        label.textSize = 40f * sc
        label.color = tint(colorOf(1 - me()), 90)
        c.drawText("ENEMY ZONE", ox + C.W * sc / 2f, oy + 70f * sc, label)
        label.color = tint(colorOf(me()), 90)
        c.drawText("YOUR ZONE", ox + C.W * sc / 2f, oy + (C.H - 40f) * sc, label)
    }

    private fun drawSoldier(c: Canvas, x: Float, y: Float, owner: Int, alive: Boolean, sel: Boolean) {
        val cx = px(x)
        val cy = py(y)
        val r = C.SOLDIER_R * sc
        val col = colorOf(owner)
        if (!alive) {
            line.color = tint(col, 120)
            line.strokeWidth = lw()
            c.drawLine(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, line)
            c.drawLine(cx - r * 0.8f, cy + r * 0.8f, cx + r * 0.8f, cy - r * 0.8f, line)
            return
        }
        if (sel) {
            fill.color = Color.argb(120, 255, 214, 0)
            c.drawCircle(cx, cy, r * 1.9f, fill)
            line.color = Color.rgb(255, 160, 0)
            line.strokeWidth = 4f
            c.drawCircle(cx, cy, r * 1.9f, line)
        }
        fill.color = col
        c.drawCircle(cx, cy, r, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r * 0.45f, fill)
    }

    private fun drawTreasure(c: Canvas, x: Float, y: Float, owner: Int) {
        val cx = px(x)
        val cy = py(y)
        val w = C.TREASURE_R * sc
        val rect = RectF(cx - w, cy - w * 0.75f, cx + w, cy + w * 0.75f)
        fill.color = Color.parseColor("#F2B705")
        c.drawRoundRect(rect, 8f, 8f, fill)
        line.color = colorOf(owner)
        line.strokeWidth = lw()
        c.drawRoundRect(rect, 8f, 8f, line)
        fill.color = Color.parseColor("#6D4C00")
        c.drawCircle(cx, cy, w * 0.18f, fill)
    }

    private fun drawDeploy(c: Canvas) {
        val m = me()
        for (i in 0 until Session.placed) {
            if (i == 0) drawTreasure(c, Session.lx[0], Session.ly[0], m)
            else drawSoldier(c, Session.lx[i], Session.ly[i], m, true, false)
        }
        label.textSize = 34f * sc
        label.color = Color.parseColor("#555555")
        val cx = ox + C.W * sc / 2f
        val cy = oy + 1200f * sc
        if (Session.confirmed) {
            c.drawText("Formation locked", cx, cy, label)
        } else if (Session.placed == 0) {
            c.drawText("Tap here to place your treasure", cx, cy, label)
        } else if (Session.placed < 11) {
            c.drawText("Tap to place soldiers · drag to move", cx, cy + 90f * sc, label)
        }
    }

    private fun drawBattle(c: Canvas, st: GameState) {
        val m = me()
        for (s in st.soldiers) {
            if (s.alive && s.hasLine) {
                line.color = colorOf(s.owner)
                line.strokeWidth = lw()
                line.pathEffect = null
                c.drawLine(px(s.lx0), py(s.ly0), px(s.lx1), py(s.ly1), line)
                fill.color = colorOf(s.owner)
                c.drawCircle(px(s.lx0), py(s.ly0), lw() * 0.8f, fill)
            }
        }
        if (st.phase != PHASE_DEPLOY) {
            drawTreasure(c, st.tx[0], st.ty[0], 0)
            drawTreasure(c, st.tx[1], st.ty[1], 1)
        }
        // revive power-up
        if (st.reviveState == RV_ACTIVE) {
            val left = Session.reviveRemainingMs()
            val cx = px(st.rx)
            val cy = py(st.ry)
            val R = C.COLLECT_R * sc
            val pulse = 1f + 0.12f * sin(SystemClock.elapsedRealtime() / 180.0).toFloat()
            fill.color = Color.argb(70, 46, 160, 67)
            c.drawCircle(cx, cy, R * pulse, fill)
            fill.color = Color.parseColor("#2EA043")
            c.drawCircle(cx, cy, R * 0.5f, fill)
            line.color = Color.WHITE
            line.strokeWidth = lw()
            c.drawLine(cx - R * 0.26f, cy, cx + R * 0.26f, cy, line)
            c.drawLine(cx, cy - R * 0.26f, cx, cy + R * 0.26f, line)
            line.color = Color.parseColor("#1B7F34")
            line.strokeWidth = 7f
            c.drawArc(RectF(cx - R, cy - R, cx + R, cy + R), -90f, 360f * left / C.REVIVE_MS, false, line)
            label.textSize = 34f * sc
            label.color = Color.parseColor("#1B7F34")
            c.drawText("REVIVE %.1fs".format(left / 1000f), cx, cy - R * 1.25f, label)
        }
        // soldiers (fallen first)
        for (s in st.soldiers) if (!s.alive) drawSoldier(c, s.x, s.y, s.owner, false, false)
        for (s in st.soldiers) if (s.alive) drawSoldier(c, s.x, s.y, s.owner, true, s.id == selected)
        // pick a fallen soldier to revive
        if (st.awaitingRevive == m) {
            line.color = Color.parseColor("#2EA043")
            line.strokeWidth = 5f
            for (s in st.soldiers) if (s.owner == m && !s.alive) {
                c.drawCircle(px(s.x), py(s.y), C.SOLDIER_R * sc * 1.8f, line)
            }
        }
        // reach hint for selected soldier
        if (selected >= 0 && selected < st.soldiers.size && st.turn == m) {
            val so = st.soldiers[selected]
            line.color = tint(colorOf(m), 90)
            line.strokeWidth = 3f
            line.pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
            c.drawCircle(px(so.x), py(so.y), C.MAX_FLICK * sc, line)
            line.pathEffect = null
        }
        // live flick preview
        if (dragging && selected >= 0 && selected < st.soldiers.size) {
            val so = st.soldiers[selected]
            val e = clampedEnd(so)
            line.color = colorOf(m)
            line.strokeWidth = lw()
            line.pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
            c.drawLine(px(so.x), py(so.y), px(e[0]), py(e[1]), line)
            line.pathEffect = null
            val rem = max(0L, C.FLICK_MS - (SystemClock.elapsedRealtime() - dragStart))
            label.textSize = 40f * sc
            label.color = Color.parseColor("#222222")
            c.drawText("%.1f".format(rem / 1000f), px(e[0]), py(e[1]) - 40f * sc, label)
        }
        // move awaiting host confirmation
        if (Session.moveInFlight && pendId >= 0 && pendId < st.soldiers.size) {
            val so = st.soldiers[pendId]
            line.color = tint(colorOf(m), 150)
            line.strokeWidth = lw()
            line.pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
            c.drawLine(px(so.x), py(so.y), px(pendX), py(pendY), line)
            line.pathEffect = null
        }
    }

    private fun clampedEnd(so: Soldier): FloatArray {
        var dx = curX - so.x
        var dy = curY - so.y
        val len = hypot(dx, dy)
        if (len > C.MAX_FLICK) {
            val k = C.MAX_FLICK / len
            dx *= k
            dy *= k
        }
        return floatArrayOf(
            (so.x + dx).coerceIn(C.SOLDIER_R, C.W - C.SOLDIER_R),
            (so.y + dy).coerceIn(C.SOLDIER_R, C.H - C.SOLDIER_R)
        )
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val st = Session.state
        val x = fromPx(e.x)
        val y = fromPy(e.y)
        when (st.phase) {
            PHASE_DEPLOY -> deployTouch(e.actionMasked, x, y)
            PHASE_BATTLE -> battleTouch(e.actionMasked, x, y, st)
        }
        return true
    }

    private fun spotOk(x: Float, y: Float, except: Int): Boolean {
        for (i in 0 until Session.placed) {
            if (i == except) continue
            if (Geo.dist(x, y, Session.lx[i], Session.ly[i]) < C.MIN_GAP) return false
        }
        return true
    }

    private fun deployTouch(a: Int, x: Float, y: Float) {
        if (Session.confirmed) return
        val m = me()
        val cx = Geo.clampX(x)
        val cy = Geo.clampY(m, y)
        when (a) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bd = 48f
                for (i in 0 until Session.placed) {
                    val d = Geo.dist(x, y, Session.lx[i], Session.ly[i])
                    if (d < bd) { bd = d; best = i }
                }
                dragIdx = best
                if (best == -1 && Session.placed < 11) {
                    if (spotOk(cx, cy, -1)) {
                        Session.lx[Session.placed] = cx
                        Session.ly[Session.placed] = cy
                        Session.placed++
                        onChange()
                    } else {
                        Toast.makeText(context, "Too close to another piece", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragIdx >= 0 && spotOk(cx, cy, dragIdx)) {
                Session.lx[dragIdx] = cx
                Session.ly[dragIdx] = cy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragIdx = -1
        }
        invalidate()
    }

    private fun nearestOwn(st: GameState, x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bd = radius
        for (s in st.soldiers) {
            if (s.owner != me() || !s.alive) continue
            val d = Geo.dist(x, y, s.x, s.y)
            if (d < bd) { bd = d; best = s.id }
        }
        return best
    }

    private fun battleTouch(a: Int, x: Float, y: Float, st: GameState) {
        val m = me()
        when (a) {
            MotionEvent.ACTION_DOWN -> {
                if (st.turn != m || Session.moveInFlight) return
                if (st.awaitingRevive == m) {
                    for (s in st.soldiers) {
                        if (s.owner == m && !s.alive && Geo.dist(x, y, s.x, s.y) < 60f) {
                            Session.reviveSoldier(s.id)
                            return
                        }
                    }
                    return
                }
                if (st.awaitingRevive != -1) return
                val hit = nearestOwn(st, x, y, 55f)
                if (hit >= 0 && hit == selected) {
                    dragging = true
                    dragStart = SystemClock.elapsedRealtime()
                    curX = x
                    curY = y
                    postDelayed(timeout, C.FLICK_MS)   // 2-second flick limit
                } else if (hit >= 0) {
                    selected = hit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                curX = x
                curY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) { curX = x; curY = y; finishDrag(true) }
            MotionEvent.ACTION_CANCEL -> if (dragging) finishDrag(false)
        }
    }

    private fun finishDrag(commit: Boolean) {
        removeCallbacks(timeout)
        if (!dragging) return
        dragging = false
        val st = Session.state
        val id = selected
        if (!commit || id < 0 || id >= st.soldiers.size) { invalidate(); return }
        val so = st.soldiers[id]
        val dur = min(C.FLICK_MS, SystemClock.elapsedRealtime() - dragStart)
        if (Geo.dist(so.x, so.y, curX, curY) < C.MIN_FLICK) {
            Toast.makeText(context, "Flick a little farther", Toast.LENGTH_SHORT).show()
            invalidate()
            return
        }
        val e = clampedEnd(so)
        pendId = id
        pendX = e[0]
        pendY = e[1]
        selected = -1
        Session.sendMove(id, curX, curY, dur)
        invalidate()
    }
}
