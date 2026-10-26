# Paper War (Android, Kotlin, no dependencies)

Two-player paper-and-pencil army game over local Wi-Fi or Bluetooth. No internet, no accounts.

## Get the APK
**Option A – GitHub (no install needed):** create a GitHub repo, upload this whole folder (keep `.github/`),
open the **Actions** tab -> *Build APK* -> run -> download the **PaperWar-apk** artifact -> unzip -> `app-debug.apk`.

**Option B – Android Studio:** open this folder, let it sync, **Build > Build APK(s)**.
(or `gradle assembleDebug` with Gradle 8.9 + JDK 17; APK in `app/build/outputs/apk/debug/`).

Install on both phones (allow "install unknown apps").

## Play
- Wi-Fi: one phone taps *Create game – Wi-Fi*, the other *Join game – Wi-Fi* (auto-discovers, or type the IP).
  Same Wi-Fi network or one phone's hotspot.
- Bluetooth: pair the phones once in system settings, host taps *Create game – Bluetooth*, joiner picks the host from the paired list.

## Decisions for the 9 open rules (edit constants in `Model.kt` / logic in `Engine.kt`)
1. Treasure: each player places their own during deployment.
2. Combat: a new line kills an enemy soldier if it crosses that soldier's latest line OR passes through its body
   (so soldiers that never moved are not invulnerable).
3. Every soldier hit by one flick dies.
4. Movement: touch trajectory, max length 380 units (field is 1000x1600).
5. REVIVE: spawns once, after 2-5 moves and once someone has died; 10 s countdown runs continuously from spawn.
6. A flick that STARTED before the countdown ended still counts (host checks start = commit time - duration).
7. Needs a fallen soldier of your own to collect it.
8. Both players deploy privately and simultaneously; formations are hidden until both lock in.
9. The boundary only restricts deployment.
Also: random first player; revived soldier appears on the power-up spot; a 15 s limit to pick the revived soldier.

## Architecture
Host-authoritative. `Engine.kt` (host only) validates every action (turn, soldier ownership, 2 s flick limit,
duplicate sequence numbers) and the host broadcasts the full state as JSON lines (`Net.kt`, `Codec.kt`).
Heartbeat detects disconnects. Wi-Fi = TCP + UDP broadcast discovery; Bluetooth = RFCOMM.

## Status
Engine/geometry compiled and sanity-tested off-device. The Android UI/network code was written without an
Android SDK available, so first build may need small fixes - send me any compiler errors.

## Online play (Firebase)
Adds "Create/Join game – Online" with a 5-character room code. Game logic is unchanged: `Online.kt` provides a
`FirebaseLink` that implements the same `Link` interface as the Wi-Fi/Bluetooth `Connection`, so the host-authoritative
`Engine`/`Session` work exactly as before.

Setup (one time):
1. Firebase console -> create project -> add Android app with package `com.paperwar`.
2. Download `google-services.json` into `PaperWar/app/`.
3. Build -> Authentication -> Sign-in method -> enable **Anonymous**.
4. Build -> Realtime Database -> Create database -> Rules -> paste `database.rules.json` -> Publish.
Without `google-services.json` the app still builds; the Online buttons just show a "not set up" message.

## Play vs Bot (single player)
`Bot.kt` plays Red on the host's own `Engine`, through the same `deploy/move/revive` calls a human uses, and only reads
the public game state. It chooses a good flick, then a human-error step (aim wobble, flicks that fall slightly short,
mood after a miss) spoils it a little. Easy/Medium/Hard only change the size of those errors.
