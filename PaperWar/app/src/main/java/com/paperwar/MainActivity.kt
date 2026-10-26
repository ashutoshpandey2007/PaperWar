package com.paperwar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Session.Ui {
    private val paper = Color.parseColor("#FBF8EF")
    private val ink = Color.parseColor("#2B2B2B")
    private lateinit var root: FrameLayout
    private var inGame = false
    private var onMenu = true
    private var gameView: GameView? = null
    private var topText: TextView? = null
    private var primary: Button? = null
    private var statusView: TextView? = null
    private var btList: LinearLayout? = null
    private var afterPerm: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        setContentView(root)
        Session.ui = this
        if (Session.connected) showGame() else showMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Session.ui === this) Session.ui = null
        if (isFinishing) Session.leave()
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(paper)
        setPadding(dp(24), dp(40), dp(24), dp(24))
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun credit(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        setTextColor(Color.parseColor("#C62828")); letterSpacing = 0.08f
        gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun setScreen(v: View, game: Boolean = false, menu: Boolean = false) {
        Session.stopDiscovery()
        inGame = game
        onMenu = menu
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ screens

    private fun showMenu() {
        Session.leave()
        val p = page()
        p.addView(title("PAPER WAR"))
        p.addView(credit("— By Ashutosh Pandey —"))
        p.addView(body("Play the computer, or two players online, Wi-Fi or Bluetooth"))
        p.addView(button("Play vs Bot") { showBotMenu() })
        p.addView(button("Create game – Online") { showHostOnline() })
        p.addView(button("Join game – Online") { showJoinOnline() })
        p.addView(button("Create game – Wi-Fi") { showHostWifi() })
        p.addView(button("Join game – Wi-Fi") { showJoinWifi() })
        p.addView(button("Create game – Bluetooth") { hostBt() })
        p.addView(button("Join game – Bluetooth") { showJoinBt() })
        p.addView(button("Game rules") { showRules() })
        p.addView(button("Exit") { finishAffinity() })
        setScreen(p, menu = true)
    }

    private fun showBotMenu() {
        val p = page()
        p.addView(title("Play vs Bot"))
        p.addView(body("The bot sees only what you see and follows the same rules. Harder bots simply miss less."))
        p.addView(button("Easy") { Session.hostBot(BOT_EASY) })
        p.addView(button("Medium") { Session.hostBot(BOT_MEDIUM) })
        p.addView(button("Hard") { Session.hostBot(BOT_HARD) })
        p.addView(button("Back") { showMenu() })
        setScreen(p)
    }

    private fun showHostOnline() {
        if (!Online.available(this)) {
            toast("Online play isn't set up in this build (google-services.json missing)")
            return
        }
        val p = page()
        p.addView(title("Create game"))
        p.addView(body("Send this room code to your friend. They choose “Join game – Online” and type it in."))
        val codeView = TextView(this).apply {
            text = "· · · · ·"; textSize = 44f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink); gravity = Gravity.CENTER; letterSpacing = 0.15f
            setPadding(0, dp(12), 0, dp(12))
        }
        p.addView(codeView)
        statusView = body("Creating room…")
        p.addView(statusView)
        p.addView(button("Cancel") { showMenu() })
        setScreen(p)
        Session.hostOnline { code ->
            codeView.text = code
            statusView?.text = "Waiting for opponent…"
        }
    }

    private fun showJoinOnline() {
        if (!Online.available(this)) {
            toast("Online play isn't set up in this build (google-services.json missing)")
            return
        }
        Session.leave()
        val p = page()
        p.addView(title("Join game"))
        p.addView(body("Type the 5-character room code your friend sees on their screen."))
        val input = EditText(this).apply {
            hint = "ROOM CODE"; setSingleLine(); textSize = 28f; gravity = Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(5))
        }
        p.addView(input)
        p.addView(button("Join") { Session.joinOnline(input.text.toString().trim()) })
        statusView = body("")
        p.addView(statusView)
        p.addView(button("Back") { showMenu() })
        setScreen(p)
    }

    private fun showHostWifi() {
        val p = page()
        p.addView(title("Create game"))
        val ip = Net.localIp()
        p.addView(
            body(
                if (ip != null)
                    "Your address:\n$ip\n\nOn the other phone choose “Join game – Wi-Fi”. Both phones must be on the same Wi-Fi network (or one phone’s hotspot)."
                else
                    "No Wi-Fi network found. Connect to Wi-Fi or turn on a hotspot, then try again."
            )
        )
        statusView = body("Waiting for opponent…")
        p.addView(statusView)
        p.addView(button("Cancel") { showMenu() })
        setScreen(p)
        Session.hostWifi()
    }

    private fun showJoinWifi() {
        Session.leave()
        val p = page()
        p.addView(title("Join game"))
        p.addView(body("Games found on your network appear below. You can also type the host’s address."))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(list)
        val input = EditText(this).apply { hint = "Host address, e.g. 192.168.1.20"; setSingleLine() }
        p.addView(input)
        p.addView(button("Connect") { Session.joinWifi(input.text.toString().trim()) })
        statusView = body("Searching…")
        p.addView(statusView)
        p.addView(button("Back") { showMenu() })
        setScreen(p)
        val seen = HashSet<String>()
        Session.startDiscovery { ip ->
            if (seen.add(ip)) {
                statusView?.text = "Found a game!"
                list.addView(button("Join game at $ip") { Session.joinWifi(ip) })
            }
        }
    }

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun withBtPermission(then: () -> Unit) {
        if (btAdapter() == null) { toast("Bluetooth is not available on this device"); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPerm = then
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 7)
        } else then()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 7) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) afterPerm?.invoke()
            else toast("Bluetooth permission is required")
            afterPerm = null
        }
    }

    private fun hostBt() = withBtPermission {
        val p = page()
        p.addView(title("Create game"))
        p.addView(body("Make this phone visible, then on the other phone choose “Join game – Bluetooth”.\n\nThe two phones must be paired once (Bluetooth settings)."))
        statusView = body("Waiting for opponent…")
        p.addView(statusView)
        p.addView(button("Cancel") { showMenu() })
        setScreen(p)
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        @Suppress("DEPRECATION")
        startActivityForResult(i, 8)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8) {
            val ad = btAdapter()
            if (resultCode == RESULT_CANCELED || ad == null) { toast("Bluetooth visibility was declined"); showMenu() }
            else Session.hostBluetooth(ad)
        }
    }

    private fun showJoinBt() = withBtPermission {
        val p = page()
        p.addView(title("Join game"))
        p.addView(body("Pick the host’s phone from your paired devices."))
        btList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(btList)
        statusView = body("")
        p.addView(statusView)
        p.addView(button("Refresh list") { fillBtList() })
        p.addView(button("Pair a new device (Bluetooth settings)") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        p.addView(button("Back") { showMenu() })
        setScreen(p)
        fillBtList()
    }

    @SuppressLint("MissingPermission")
    private fun fillBtList() {
        val ad = btAdapter() ?: return
        val list = btList ?: return
        list.removeAllViews()
        val devs = ad.bondedDevices ?: emptySet()
        if (devs.isEmpty()) list.addView(body("No paired devices yet. Pair with the other phone first."))
        for (d in devs) {
            list.addView(button("${d.name ?: "Unknown"}  (${d.address})") {
                statusView?.text = "Connecting…"
                Session.joinBluetooth(ad, d)
            })
        }
    }

    private fun showRules() {
        val p = page()
        p.addView(title("Rules"))
        val t = TextView(this).apply {
            textSize = 15f; setTextColor(ink)
            text = """
GOAL
Eliminate the whole enemy army, then capture the enemy treasure.

SETUP
Each player secretly places their treasure and 10 soldiers anywhere in their own half. Tap to place, drag to adjust, then confirm. Nothing can be placed across the dashed front line. The battle starts when both players are locked in.

YOUR TURN
1. Tap one of your soldiers to select it.
2. Press on it again and flick/drag. You have 2 seconds; when time runs out the line is committed where your finger is.
3. The soldier ends at the end of the line (max length is limited).

COMBAT
Your new line eliminates an enemy soldier if it crosses that soldier’s latest line, or passes through its body. Every soldier your line hits is eliminated.

TREASURE
Touching the enemy treasure only wins once the enemy has no living soldiers.

REVIVE (once per match)
After a few moves, once someone has fallen, a green REVIVE token appears for 10 seconds. Flick through it to collect it (you need a fallen soldier), then tap one of your fallen soldiers to bring it back. A flick that started before the timer ended still counts.
            """.trimIndent()
        }
        val sv = ScrollView(this)
        sv.addView(t)
        p.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        p.addView(button("Back") { showMenu() })
        setScreen(p)
    }

    // ------------------------------------------------------------ game screen

    private fun showGame() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        topText = TextView(this).apply {
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val gv = GameView(this).apply { onChange = { updateBars() } }
        gameView = gv
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        primary = Button(this).apply {
            isAllCaps = false; visibility = View.GONE
            setOnClickListener {
                when (Session.state.phase) {
                    PHASE_DEPLOY -> Session.confirmFormation()
                    PHASE_ENDED -> Session.rematch()
                }
            }
        }
        bar.addView(primary, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Quit"; isAllCaps = false
            setOnClickListener { confirmQuit() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(topText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setScreen(col, game = true)
        updateBars()
    }

    private fun updateBars() {
        if (!inGame) return
        val st = Session.state
        val me = Session.myIndex
        val other = 1 - me
        val t: String
        var color = Color.parseColor("#555555")
        when (st.phase) {
            PHASE_DEPLOY -> {
                color = if (me == 0) Color.parseColor("#1F5FBF") else Color.parseColor("#C62828")
                t = when {
                    Session.confirmed -> if (st.deployed[other]) "Starting…" else "Formation locked – waiting for opponent…"
                    Session.placed == 0 -> "You are ${playerName(me)} · place your treasure"
                    Session.placed < 11 -> "Soldiers left to place: ${11 - Session.placed}"
                    else -> "Ready – confirm your formation"
                }
            }
            PHASE_BATTLE -> {
                val turnColor = if (st.turn == 0) "#1F5FBF" else "#C62828"
                color = Color.parseColor(turnColor)
                val score = "   (you ${st.alive(me)} – ${st.alive(other)} enemy)"
                t = when {
                    st.awaitingRevive == me -> "REVIVE! Tap one of your fallen soldiers"
                    st.awaitingRevive == other -> "Opponent is choosing a soldier to revive…"
                    st.turn == me -> "YOUR TURN – tap a soldier, then flick$score"
                    Session.vsBot -> "Bot is thinking…$score"
                    else -> "${playerName(other)}’s turn…$score"
                }
            }
            else -> {
                color = if (st.winner == me) Color.parseColor("#2E7D32") else Color.parseColor("#6D1B1B")
                t = if (st.winner == me) "VICTORY! You captured the treasure" else "DEFEAT – your treasure was captured"
            }
        }
        topText?.text = t
        topText?.setBackgroundColor(color)
        val b = primary ?: return
        when (st.phase) {
            PHASE_DEPLOY -> { b.visibility = View.VISIBLE; b.text = "Confirm formation"; b.isEnabled = Session.placed == 11 && !Session.confirmed }
            PHASE_ENDED -> { b.visibility = View.VISIBLE; b.text = "Rematch"; b.isEnabled = true }
            else -> b.visibility = View.GONE
        }
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this).setMessage("Leave this match?")
            .setPositiveButton("Leave") { _, _ -> showMenu() }
            .setNegativeButton("Stay", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inGame -> confirmQuit()
            !onMenu -> showMenu()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ Session.Ui

    override fun onState() {
        if (inGame) {
            gameView?.refresh()
            updateBars()
        }
    }

    override fun onToast(msg: String) = toast(msg)

    override fun onConnected() = showGame()

    override fun onDisconnected(reason: String) {
        AlertDialog.Builder(this).setTitle("Disconnected").setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("Main menu") { _, _ -> showMenu() }.show()
    }

    override fun onStatus(msg: String) {
        statusView?.text = msg
    }
}
