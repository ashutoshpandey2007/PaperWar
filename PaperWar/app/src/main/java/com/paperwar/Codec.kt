package com.paperwar

import org.json.JSONArray
import org.json.JSONObject

object Codec {
    fun toJson(s: GameState): JSONObject {
        val o = JSONObject()
        o.put("t", "state")
        o.put("ph", s.phase)
        o.put("tn", s.turn)
        o.put("dp", JSONArray().put(s.deployed[0]).put(s.deployed[1]))
        o.put(
            "tr", JSONArray().put(s.tx[0].toDouble()).put(s.ty[0].toDouble())
                .put(s.tx[1].toDouble()).put(s.ty[1].toDouble())
        )
        o.put("rv", JSONArray().put(s.reviveState).put(s.rx.toDouble()).put(s.ry.toDouble()).put(s.reviveLeftMs))
        o.put("aw", s.awaitingRevive)
        o.put("wn", s.winner)
        o.put("mc", s.moveCount)
        o.put("es", s.eventSeq)
        o.put("ev", s.event)
        val a = JSONArray()
        for (so in s.soldiers) {
            a.put(
                JSONArray().put(so.x.toDouble()).put(so.y.toDouble())
                    .put(if (so.alive) 1 else 0).put(if (so.hasLine) 1 else 0)
                    .put(so.lx0.toDouble()).put(so.ly0.toDouble())
                    .put(so.lx1.toDouble()).put(so.ly1.toDouble())
            )
        }
        o.put("so", a)
        return o
    }

    fun fromJson(o: JSONObject, s: GameState) {
        s.phase = o.getInt("ph")
        s.turn = o.getInt("tn")
        val dp = o.getJSONArray("dp")
        s.deployed[0] = dp.getBoolean(0)
        s.deployed[1] = dp.getBoolean(1)
        val tr = o.getJSONArray("tr")
        s.tx[0] = tr.getDouble(0).toFloat()
        s.ty[0] = tr.getDouble(1).toFloat()
        s.tx[1] = tr.getDouble(2).toFloat()
        s.ty[1] = tr.getDouble(3).toFloat()
        val rv = o.getJSONArray("rv")
        s.reviveState = rv.getInt(0)
        s.rx = rv.getDouble(1).toFloat()
        s.ry = rv.getDouble(2).toFloat()
        s.reviveLeftMs = rv.getLong(3)
        s.awaitingRevive = o.getInt("aw")
        s.winner = o.getInt("wn")
        s.moveCount = o.getInt("mc")
        s.eventSeq = o.getInt("es")
        s.event = o.optString("ev", "")
        val a = o.getJSONArray("so")
        s.soldiers.clear()
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            val so = Soldier(i)
            so.x = r.getDouble(0).toFloat()
            so.y = r.getDouble(1).toFloat()
            so.alive = r.getInt(2) == 1
            so.hasLine = r.getInt(3) == 1
            so.lx0 = r.getDouble(4).toFloat()
            so.ly0 = r.getDouble(5).toFloat()
            so.lx1 = r.getDouble(6).toFloat()
            so.ly1 = r.getDouble(7).toFloat()
            s.soldiers.add(so)
        }
    }
}
