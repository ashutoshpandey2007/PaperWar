package com.paperwar

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object Geo {
    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(ax - bx, ay - by)

    fun inZone(p: Int, x: Float, y: Float): Boolean {
        if (x < C.MARGIN || x > C.W - C.MARGIN) return false
        return if (p == 0) y >= C.MID + C.MARGIN && y <= C.H - C.MARGIN
        else y >= C.MARGIN && y <= C.MID - C.MARGIN
    }

    fun clampX(x: Float) = x.coerceIn(C.MARGIN, C.W - C.MARGIN)

    fun clampY(p: Int, y: Float) =
        if (p == 0) y.coerceIn(C.MID + C.MARGIN, C.H - C.MARGIN) else y.coerceIn(C.MARGIN, C.MID - C.MARGIN)

    /** Shortest distance from point (px,py) to segment (x0,y0)-(x1,y1). */
    fun segPointDist(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return dist(x0, y0, px, py)
        val t = (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0f, 1f)
        return dist(x0 + t * dx, y0 + t * dy, px, py)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSeg(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)

    /** Do segments A-B and C-D intersect (including touching)? */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        ) return true
        if (d1 == 0f && onSeg(cx, cy, dx, dy, ax, ay)) return true
        if (d2 == 0f && onSeg(cx, cy, dx, dy, bx, by)) return true
        if (d3 == 0f && onSeg(ax, ay, bx, by, cx, cy)) return true
        if (d4 == 0f && onSeg(ax, ay, bx, by, dx, dy)) return true
        return false
    }
}
