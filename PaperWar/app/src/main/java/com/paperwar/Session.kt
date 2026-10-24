package com.paperwar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glue between UI, network and rules. Everything here runs on the main thread.
 * HOST: owns the Engine, applies both players' actions, broadcasts the full state.
 * CLIENT: sends requests, renders whatever state the host sends.
 */
object Session {
    interface Ui {
        fun onState()
        fun onToast(msg: String)
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onStatus(msg: String)
    }

    var ui: Ui? = null
    private val main = Handler(Looper.getMainLooper())

    var isHost = false
    var myIndex = 0
    var connected = false
    private var conn: Link? = null
    private var engine: Engine? = null
    private var clientState = GameState()
    val state: GameState get() = engine?.s ?: clientState
    var snapshotAt = 0L
    private var seq = 0
    private var lastEvent = 0

    // Local formation: item 0 = treasure, items 1..10 = soldiers (absolute field coordinates)
    val lx = FloatArray(11)
    val ly = FloatArray(11)
    var placed = 0
    var confirmed = false
    var moveInFlight = false

    private var wifiHost: WifiHost? = null
    private var btHost: BtHost? = null
    private var discovery: WifiDiscovery? = null
    private var onlineHost: OnlineHost? = null

    fun now(): Long = SystemClock.elapsedRealtime()

    private fun resetLayout() {
        placed = 0
        confirmed = false
        moveInFlight = false
        lastEvent = 0
    }

    fun leave() {
        main.removeCallbacks(tick)
        wifiHost?.stop(); wifiHost = null
        btHost?.stop(); btHost = null
        discovery?.stop(); discovery = null
        onlineHost?.stop(); onlineHost = null
        conn?.let { it.onClosed = {}; it.close() }
        conn = null
        connected = false
        engine = null
        clientState = GameState()
        isHost = false
        myIndex = 0
        resetLayout()
    }

    // ------------------------------------------------------------ connecting

    fun hostWifi() {
        leave()
        isHost = true; myIndex = 0; engine = Engine()
        val h = WifiHost({ c -> main.post { attach(c) } }, { e -> main.post { ui?.onStatus(e) } })
        wifiHost = h
        h.start()
    }

    fun hostBluetooth(adapter: BluetoothAdapter) {
        leave()
        isHost = true; myIndex = 0; engine = Engine()
        val h = BtHost(adapter, { c -> main.post { attach(c) } }, { e -> main.post { ui?.onStatus(e) } })
        btHost = h
        h.start()
    }

    fun joinWifi(ip: String) {
        if (ip.isBlank()) { ui?.onStatus("Enter the host address first"); return }
        leave()
        isHost = false; myIndex = 1
        ui?.onStatus("Connecting to $ip …")
        Net.connect(ip, { c -> main.post { attach(c) } }, { e -> main.post { ui?.onStatus(e) } })
    }

    fun joinBluetooth(adapter: BluetoothAdapter, device: BluetoothDevice) {
        leave()
        isHost = false; myIndex = 1
        BtClient.connect(adapter, device, { c -> main.post { attach(c) } }, { e -> main.post { ui?.onStatus(e) } })
    }

    /** Online (Firebase) host: [onCode] is called with the room code to show to the friend. */
    fun hostOnline(onCode: (String) -> Unit) {
        leave()
        isHost = true; myIndex = 0; engine = Engine()
        val h = OnlineHost(
            { code -> main.post { onCode(code) } },
            { l -> main.post { attach(l) } },
            { e -> main.post { ui?.onStatus(e) } }
        )
        onlineHost = h
        h.start()
    }

    fun joinOnline(code: String) {
        if (code.isBlank()) { ui?.onStatus("Enter the room code first"); return }
        leave()
        isHost = false; myIndex = 1
        ui?.onStatus("Joining room ${code.uppercase()} …")
        OnlineGuest.join(code, { l -> main.post { attach(l) } }, { e -> main.post { ui?.onStatus(e) } })
    }

    fun startDiscovery(onFound: (String) -> Unit) {
        discovery?.stop()
        val d = WifiDiscovery { ip -> main.post { onFound(ip) } }
        discovery = d
        d.start()
    }

    fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    private fun attach(c: Link) {
        wifiHost?.stop(); wifiHost = null
        btHost?.stop(); btHost = null
        onlineHost?.stop(); onlineHost = null
        discovery?.stop(); discovery = null
        conn = c
        connected = true
        resetLayout()
        c.onMessage = { m -> main.post { handle(m) } }
        c.onClosed = { r ->
            main.post {
                if (conn === c) {
                    connected = false
                    ui?.onDisconnected(r)
                }
            }
        }
        c.start()
        if (isHost) {
            c.send(JSONObject().put("t", "hello").put("you", 1))
            broadcast()
            main.postDelayed(tick, 250)
        }
        ui?.onConnected()
    }

    private val tick = object : Runnable {
        override fun run() {
            val e = engine
            if (e != null && connected) {
                if (e.tick(now())) broadcast()
                main.postDelayed(this, 250)
            }
        }
    }

    // ------------------------------------------------------------ messages

    private fun handle(m: JSONObject) {
        try {
            when (m.optString("t")) {
                "hello" -> myIndex = m.optInt("you", 1)
                "state" -> if (!isHost) {
                    Codec.fromJson(m, clientState)
                    snapshotAt = now()
                    moveInFlight = false
                    notifyState()
                }
                "err" -> {
                    moveInFlight = false
                    ui?.onToast(m.optString("m"))
                    ui?.onState()
                }
                "reset" -> {
                    resetLayout()
                    ui?.onState()
                }
                "deploy" -> if (isHost) {
                    val a = m.getJSONArray("d")
                    val d = FloatArray(a.length()) { a.getDouble(it).toFloat() }
                    reply(engine!!.deploy(1, d))
                }
                "move" -> if (isHost) {
                    reply(
                        engine!!.move(
                            1, m.getInt("seq"), m.getInt("id"),
                            m.getDouble("ex").toFloat(), m.getDouble("ey").toFloat(),
                            m.getLong("dur"), now()
                        )
                    )
                }
                "revive" -> if (isHost) reply(engine!!.revive(1, m.getInt("id"), now()))
                "rematch" -> if (isHost) hostRematch()
            }
        } catch (e: Exception) {
            // ignore malformed messages
        }
    }

    private fun reply(err: String?) {
        if (err != null && err != "dup") conn?.send(JSONObject().put("t", "err").put("m", err))
        broadcast()
    }

    private fun broadcast() {
        val e = engine ?: return
        e.snapshot(now())
        snapshotAt = now()
        moveInFlight = false
        conn?.send(Codec.toJson(e.s))
        notifyState()
    }

    private fun notifyState() {
        val st = state
        if (st.eventSeq != lastEvent) {
            lastEvent = st.eventSeq
            if (st.event.isNotEmpty()) ui?.onToast(st.event)
        }
        ui?.onState()
    }

    // ------------------------------------------------------------ player actions

    fun confirmFormation() {
        if (placed < 11 || confirmed) return
        confirmed = true
        val d = FloatArray(22)
        for (i in 0 until 11) {
            d[2 * i] = lx[i]
            d[2 * i + 1] = ly[i]
        }
        if (isHost) {
            val err = engine!!.deploy(0, d)
            if (err != null) {
                confirmed = false
                ui?.onToast(err)
            }
            broadcast()
        } else {
            conn?.send(JSONObject().put("t", "deploy").put("d", JSONArray(d.map { it.toDouble() })))
            ui?.onState()
        }
    }

    fun sendMove(id: Int, ex: Float, ey: Float, durMs: Long) {
        if (moveInFlight) return
        moveInFlight = true
        val sq = seq++
        if (isHost) {
            val err = engine!!.move(0, sq, id, ex, ey, durMs, now())
            if (err != null) ui?.onToast(err)
            broadcast()
        } else {
            conn?.send(
                JSONObject().put("t", "move").put("seq", sq).put("id", id)
                    .put("ex", ex.toDouble()).put("ey", ey.toDouble()).put("dur", durMs)
            )
            ui?.onState()
        }
    }

    fun reviveSoldier(id: Int) {
        if (isHost) {
            val err = engine!!.revive(0, id, now())
            if (err != null) ui?.onToast(err)
            broadcast()
        } else {
            conn?.send(JSONObject().put("t", "revive").put("id", id))
        }
    }

    fun rematch() {
        if (isHost) hostRematch() else conn?.send(JSONObject().put("t", "rematch"))
    }

    private fun hostRematch() {
        engine = Engine()
        resetLayout()
        conn?.send(JSONObject().put("t", "reset"))
        broadcast()
    }

    /** Milliseconds left on the REVIVE countdown (0 when not active). */
    fun reviveRemainingMs(): Long {
        val st = state
        if (st.reviveState != RV_ACTIVE) return 0L
        return maxOf(0L, st.reviveLeftMs - (now() - snapshotAt))
    }
}
