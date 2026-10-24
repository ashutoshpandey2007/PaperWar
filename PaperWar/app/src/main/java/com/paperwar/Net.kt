package com.paperwar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** Line-delimited JSON over any stream (TCP or Bluetooth RFCOMM), with heartbeat. */
class Connection(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) {
    @Volatile private var closed = false
    @Volatile private var lastRx = System.currentTimeMillis()
    var onMessage: (JSONObject) -> Unit = {}
    var onClosed: (String) -> Unit = {}
    private val writer = Executors.newSingleThreadExecutor()

    fun start() {
        Thread({ readLoop() }, "pw-read").apply { isDaemon = true }.start()
        Thread({ beat() }, "pw-beat").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        try {
            val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            while (!closed) {
                val line = r.readLine() ?: break
                lastRx = System.currentTimeMillis()
                val o = try { JSONObject(line) } catch (e: Exception) { continue }
                if (o.optString("t") == "ping") continue
                onMessage(o)
            }
            fail("Your opponent left the game")
        } catch (e: Exception) {
            fail("Connection lost")
        }
    }

    private fun beat() {
        try {
            while (!closed) {
                Thread.sleep(2000)
                send(JSONObject().put("t", "ping"))
                if (System.currentTimeMillis() - lastRx > 10000) fail("Connection timed out")
            }
        } catch (e: InterruptedException) {
        }
    }

    fun send(o: JSONObject) {
        if (closed) return
        val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        try {
            writer.execute {
                try {
                    synchronized(output) {
                        output.write(bytes)
                        output.flush()
                    }
                } catch (e: Exception) {
                    fail("Connection lost")
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun fail(reason: String) {
        val first: Boolean
        synchronized(this) {
            first = !closed
            closed = true
        }
        if (first) {
            try { closer() } catch (e: Exception) {}
            writer.shutdown()
            onClosed(reason)
        }
    }

    fun close() {
        val first: Boolean
        synchronized(this) {
            first = !closed
            closed = true
        }
        if (first) {
            try { closer() } catch (e: Exception) {}
            writer.shutdown()
        }
    }
}

object Net {
    fun localIp(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun connect(ip: String, onConnected: (Connection) -> Unit, onError: (String) -> Unit) {
        Thread({
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(ip, C.PORT), 6000)
                onConnected(Connection(sock.getInputStream(), sock.getOutputStream()) {
                    try { sock.close() } catch (e: Exception) {}
                })
            } catch (e: Exception) {
                onError("Could not connect to $ip: ${e.message}")
            }
        }, "pw-connect").apply { isDaemon = true }.start()
    }
}

class WifiHost(
    private val onConnected: (Connection) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    private var server: ServerSocket? = null

    fun start() {
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(C.PORT))
                server = ss
                beacon()
                val sock = ss.accept()
                sock.tcpNoDelay = true
                stopped = true
                try { ss.close() } catch (e: Exception) {}
                onConnected(Connection(sock.getInputStream(), sock.getOutputStream()) {
                    try { sock.close() } catch (e: Exception) {}
                })
            } catch (e: Exception) {
                if (!stopped) onError("Could not host: ${e.message}")
            }
        }, "pw-host").apply { isDaemon = true }.start()
    }

    private fun beacon() {
        Thread({
            try {
                val ds = DatagramSocket()
                ds.broadcast = true
                while (!stopped) {
                    val ip = Net.localIp()
                    if (ip != null) {
                        val b = "PAPERWAR|$ip".toByteArray()
                        ds.send(DatagramPacket(b, b.size, InetAddress.getByName("255.255.255.255"), C.DISCOVERY_PORT))
                    }
                    Thread.sleep(1000)
                }
                ds.close()
            } catch (e: Exception) {
            }
        }, "pw-beacon").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (e: Exception) {}
    }
}

class WifiDiscovery(private val onFound: (String) -> Unit) {
    @Volatile private var stopped = false
    private var sock: DatagramSocket? = null

    fun start() {
        Thread({
            try {
                val s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(C.DISCOVERY_PORT))
                sock = s
                val buf = ByteArray(128)
                while (!stopped) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length)
                    if (txt.startsWith("PAPERWAR|")) onFound(txt.substring(9))
                }
            } catch (e: Exception) {
            }
        }, "pw-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { sock?.close() } catch (e: Exception) {}
    }
}

val BT_UUID: UUID = UUID.fromString("7b0b0f3e-5d57-4b3e-9a43-0c9a7a7f9a11")

@SuppressLint("MissingPermission")
class BtHost(
    private val adapter: BluetoothAdapter,
    private val onConnected: (Connection) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    private var server: BluetoothServerSocket? = null

    fun start() {
        Thread({
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord("PaperWar", BT_UUID)
                server = ss
                val sock = ss.accept()
                stopped = true
                try { ss.close() } catch (e: Exception) {}
                onConnected(Connection(sock.inputStream, sock.outputStream) {
                    try { sock.close() } catch (e: Exception) {}
                })
            } catch (e: Exception) {
                if (!stopped) onError("Bluetooth hosting failed: ${e.message}")
            }
        }, "pw-bt-host").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (e: Exception) {}
    }
}

@SuppressLint("MissingPermission")
object BtClient {
    fun connect(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        onConnected: (Connection) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread({
            var sock: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(BT_UUID)
                sock = s
                s.connect()
                onConnected(Connection(s.inputStream, s.outputStream) {
                    try { s.close() } catch (e: Exception) {}
                })
            } catch (e: Exception) {
                try { sock?.close() } catch (x: Exception) {}
                onError("Bluetooth connection failed: ${e.message}")
            }
        }, "pw-bt-connect").apply { isDaemon = true }.start()
    }
}
