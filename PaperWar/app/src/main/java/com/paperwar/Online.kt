package com.paperwar

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * Online play over Firebase Realtime Database.
 *
 * Layout:  rooms/{CODE}/hostUid, guestUid, presence/{host|guest}, to_host/{pushId}, to_guest/{pushId}
 * Each side pushes JSON strings into the OTHER side's inbox and deletes messages from its own inbox once read.
 * Push ids are time-ordered, so message order is preserved. The game logic is untouched: Session just sees a [Link].
 */
object Online {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I

    /** False when google-services.json was not part of the build. */
    fun available(ctx: Context): Boolean =
        try { FirebaseApp.getApps(ctx).isNotEmpty() } catch (e: Exception) { false }

    // Explicit URL so it works even if google-services.json was downloaded before the database existed.
    private const val DB_URL = "https://paperwar-85db9-default-rtdb.firebaseio.com"

    fun db(): FirebaseDatabase = FirebaseDatabase.getInstance(DB_URL)

    fun rooms(): DatabaseReference = db().getReference("rooms")

    fun randomCode(): String {
        val r = java.security.SecureRandom()
        return String(CharArray(5) { ALPHABET[r.nextInt(ALPHABET.length)] })
    }

    fun withAuth(onReady: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val auth = FirebaseAuth.getInstance()
            val u = auth.currentUser
            if (u != null) { onReady(u.uid); return }
            auth.signInAnonymously().addOnCompleteListener { t ->
                val uid = if (t.isSuccessful) t.result?.user?.uid else null
                if (uid != null) onReady(uid)
                else onError("Online sign-in failed: ${t.exception?.message ?: "unknown error"}")
            }
        } catch (e: Exception) {
            onError("Online mode is not set up: ${e.message}")
        }
    }
}

class FirebaseLink(
    private val room: DatabaseReference,
    private val me: String,    // "host" or "guest"
    private val them: String
) : Link {
    override var onMessage: (JSONObject) -> Unit = {}
    override var onClosed: (String) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val inbox = room.child("to_$me")
    private val outbox = room.child("to_$them")
    private val myPresence = room.child("presence").child(me)
    private val theirPresence = room.child("presence").child(them)
    private val connectedRef = Online.db().getReference(".info/connected")

    private var closed = false
    private var sawThem = false
    private var inboxL: ChildEventListener? = null
    private var theirL: ValueEventListener? = null
    private var connL: ValueEventListener? = null

    private val offlineTimeout = Runnable { fail("Connection lost") }
    private val neverSeen = Runnable { if (!sawThem) fail("Your opponent could not connect") }

    override fun start() {
        // Our own connection state: re-announce presence on every (re)connect, give up after 12 s offline.
        val cl = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (closed) return
                if (s.getValue(Boolean::class.java) == true) {
                    main.removeCallbacks(offlineTimeout)
                    myPresence.onDisconnect().removeValue()
                    myPresence.setValue(true)
                } else {
                    main.removeCallbacks(offlineTimeout)
                    main.postDelayed(offlineTimeout, 12000)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        connL = cl
        connectedRef.addValueEventListener(cl)

        // Messages for us
        val il = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                val txt = s.getValue(String::class.java)
                s.ref.removeValue()
                if (closed || txt == null) return
                val o = try { JSONObject(txt) } catch (e: Exception) { return }
                onMessage(o)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        inboxL = il
        inbox.addChildEventListener(il)

        // Opponent presence
        val tl = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (closed) return
                if (s.exists()) {
                    sawThem = true
                    main.removeCallbacks(neverSeen)
                } else if (sawThem) {
                    fail("Your opponent left the game")
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        theirL = tl
        theirPresence.addValueEventListener(tl)
        main.postDelayed(neverSeen, 20000)
    }

    override fun send(o: JSONObject) {
        if (closed) return
        outbox.push().setValue(o.toString())
    }

    private fun fail(reason: String) {
        if (closed) return
        cleanup()
        onClosed(reason)
    }

    override fun close() {
        if (closed) return
        cleanup()
    }

    private fun cleanup() {
        closed = true
        main.removeCallbacks(offlineTimeout)
        main.removeCallbacks(neverSeen)
        try { connL?.let { connectedRef.removeEventListener(it) } } catch (e: Exception) {}
        try { inboxL?.let { inbox.removeEventListener(it) } } catch (e: Exception) {}
        try { theirL?.let { theirPresence.removeEventListener(it) } } catch (e: Exception) {}
        try {
            myPresence.onDisconnect().cancel()
            myPresence.removeValue()
            if (me == "host") {
                room.onDisconnect().cancel()
                room.removeValue()          // host owns the room: delete it
            } else {
                room.child("guestUid").removeValue()
            }
        } catch (e: Exception) {}
    }
}

/** Creates a room, shows its code, and hands a [Link] over once a friend joins. */
class OnlineHost(
    private val onCode: (String) -> Unit,
    private val onConnected: (Link) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    private var handedOff = false
    private var room: DatabaseReference? = null
    private var guestL: ValueEventListener? = null

    fun start() {
        Online.withAuth({ uid -> claim(uid, 0) }, { e -> onError(e) })
    }

    private fun claim(uid: String, attempt: Int) {
        if (stopped) return
        if (attempt >= 8) { onError("Could not create a room, please try again"); return }
        val code = Online.randomCode()
        val r = Online.rooms().child(code)
        r.child("hostUid").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                if (d.value != null) return Transaction.abort()
                d.value = uid
                return Transaction.success(d)
            }

            override fun onComplete(err: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                if (stopped) {
                    if (committed) r.removeValue()
                    return
                }
                when {
                    err != null -> onError("Could not create room: ${err.message}")
                    committed -> {
                        room = r
                        r.onDisconnect().removeValue()   // crash/offline cleanup
                        onCode(code)
                        watch(r)
                    }
                    else -> claim(uid, attempt + 1)      // code already taken
                }
            }
        })
    }

    private fun watch(r: DatabaseReference) {
        val ref = r.child("guestUid")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (stopped || handedOff || !s.exists()) return
                handedOff = true
                ref.removeEventListener(this)
                onConnected(FirebaseLink(r, "host", "guest"))
            }
            override fun onCancelled(e: DatabaseError) {
                if (!stopped && !handedOff) onError("Room error: ${e.message}")
            }
        }
        guestL = l
        ref.addValueEventListener(l)
    }

    fun stop() {
        stopped = true
        val r = room
        guestL?.let { r?.child("guestUid")?.removeEventListener(it) }
        guestL = null
        if (!handedOff && r != null) {
            try { r.onDisconnect().cancel(); r.removeValue() } catch (e: Exception) {}
        }
    }
}

object OnlineGuest {
    fun join(rawCode: String, onConnected: (Link) -> Unit, onError: (String) -> Unit) {
        val code = rawCode.trim().uppercase()
        if (code.length != 5) { onError("Room codes have 5 characters"); return }
        Online.withAuth({ uid ->
            val r = Online.rooms().child(code)
            r.child("hostUid").get().addOnCompleteListener { t ->
                if (!t.isSuccessful) { onError("Could not reach the server: ${t.exception?.message}"); return@addOnCompleteListener }
                if (!t.result.exists()) { onError("No game with code $code"); return@addOnCompleteListener }
                r.child("guestUid").runTransaction(object : Transaction.Handler {
                    override fun doTransaction(d: MutableData): Transaction.Result {
                        if (d.value != null) return Transaction.abort()
                        d.value = uid
                        return Transaction.success(d)
                    }

                    override fun onComplete(err: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                        when {
                            err != null -> onError("Could not join: ${err.message}")
                            committed -> onConnected(FirebaseLink(r, "guest", "host"))
                            else -> onError("That game is already full")
                        }
                    }
                })
            }
        }, { e -> onError(e) })
    }
}
