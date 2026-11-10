# AirPlay 2 Multi-Room for Kiosk Satellite

Turns a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) kiosk into an AirPlay 2 speaker that you can group with HomePods, Apple TVs and other AirPlay 2 speakers. Pick several speakers in the AirPlay menu on an iPhone, iPad or Mac and they play in sync.

Kiosk Satellite already has its own AirPlay receiver for single-speaker playback. This plugin adds what that receiver doesn't: AirPlay 2 buffered audio locked to the sender's clock, so the kiosk can be part of a group.

> **Turn off Kiosk Satellite's own AirPlay receiver.** If both run, the kiosk shows up twice in the AirPlay menu, and only this plugin's entry can join a group. In Kiosk Satellite, open **Settings > Media Player > AirPlay** and turn off **Enable AirPlay receiver**. That section only shows while **AirPlay** is the picked **Player**; if it isn't, the built-in receiver is already off.

- Groups with other AirPlay 2 speakers for multi-room audio.
- Follows the sender's volume slider.
- Lyrics in time with the music in the full-screen player.
- Shows what's playing: a floating player or a full-screen player with large cover art over the dashboard, and the same full-screen view in place of the screensaver while AirPlay plays.
- Publishes what is playing to Home Assistant, with a switch to turn the receiver off.
- Turns AirPlay audio down while Voice Satellite listens and answers.
- Can wake the screen when playback starts, or run any gesture action through its triggers.
- Works on kiosks without a screen, such as an Echo Dot.

This plugin is not affiliated with, endorsed by or sponsored by Apple Inc. AirPlay, HomePod, iPhone, iPad and Mac are trademarks of Apple Inc.

## Before you start

For multi-room you need:

- **A rooted kiosk whose Android has Rooted debugging.** LineageOS builds have it, including Kiosk Satellite's Echo builds. Without root the plugin still plays AirPlay 2 audio, but not in sync with other speakers.
- **Kiosk Satellite 2026.10.45 or later** with Plugin Manager turned on.
- **[Shizuku](https://shizuku.rikka.app/) 13 or later** on the kiosk. Setup below shows how to install it without an app store.
- **A computer with ADB, once**, to install Shizuku and start it the first time. Get [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) for Windows, macOS or Linux. After setup the plugin starts Shizuku by itself, so you don't need the computer again.
- **The kiosk and your Apple devices on the same network**, with mDNS and multicast allowed between them (no Wi-Fi client isolation or separate VLAN).

## Installation

### Community Plugins (Recommended)

AirPlay 2 Multi-Room is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=ian-white83/ks-airplay-receiver)

Or search for `AirPlay 2 Multi-Room` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/ian-white83/ks-airplay-receiver` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Set up multi-room

These steps take about 15 minutes, once per kiosk. Steps 1 to 5 use Android's Settings app and your computer; the rest happen in Kiosk Satellite.

### 1. Turn on Developer options

1. Open Android's **Settings** app on the kiosk. If Kiosk Satellite keeps you in kiosk mode, exit it first.
2. Go to **About tablet** (or **About phone**) and tap **Build number** seven times, until it says you are a developer.
3. Go back and open **System > Developer options**.

### 2. Turn on debugging

In **Developer options**, turn on:

- **Android debugging** (called **USB debugging** on some builds).
- **Rooted debugging.** This lets ADB run as root. It is what makes multi-room possible, and the plugin needs it at every boot.
- **ADB over network**, if your build has it. It makes ADB listen on port 5555.

Note the IP address shown under **ADB over network**, or under **About tablet > IP address**.

If your build has no **ADB over network** switch, connect the kiosk to the computer by USB once and run `adb tcpip 5555`.

### 3. Connect from the computer

On the computer, in a terminal in the Platform-Tools folder:

```sh
adb connect 192.168.1.50:5555
```

Use the kiosk's IP address. The kiosk asks **Allow USB debugging?** Tick **Always allow from this computer** and tap **Allow**, then run the command again. `adb devices` should list the kiosk as `device`.

Don't use **Wireless debugging** and its pairing codes. That is a different feature, it can't run as root, and on a kiosk its pairing notification often never appears.

### 4. Install Shizuku

Kiosk Satellite's builds have no app store, so install Shizuku from its GitHub releases:

1. On the computer, download the latest `shizuku-v….apk` from [Shizuku's releases](https://github.com/RikkaApps/Shizuku/releases).
2. Install it:

   ```sh
   adb install path/to/shizuku.apk
   ```

   Use the path and name of the file you downloaded.

### 5. Start Shizuku as root, once

```sh
adb root
adb connect 192.168.1.50:5555
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

`adb root` restarts ADB on the kiosk and drops the connection, which is why you connect again. If it says **adbd cannot run as root**, Rooted debugging is off or the build doesn't support it.

Newer Shizuku versions start differently. If the last command says the file isn't found, open Shizuku on the kiosk, tap **Start via connected computer > View command** and run the command it shows.

Open Shizuku: it should say **Shizuku is running** with **root** as the user. Rooted devices have no `su` here, and `adb root` takes its place, so ignore Shizuku's "Start (for rooted devices)" button.

### 6. Install the plugin

Install it from Community Plugins or by URL, as in [Installation](#installation).

Then turn off Kiosk Satellite's own AirPlay receiver, so the kiosk doesn't show up twice in the AirPlay menu: **Settings > Media Player > AirPlay > Enable AirPlay receiver** off. If **AirPlay** isn't the picked **Player** under **Settings > Media Player**, it's already off.

### 7. Give Kiosk Satellite access to Shizuku

On the plugin's page in **Plugin Manager** (or in **Settings > Device > Shizuku**), tap **Shizuku access** and approve Shizuku's prompt on the kiosk. The plugin's **Shizuku** status should say **Running as root**, and its **AirPlay multi-room** reading **Multi-room ready** within a few seconds.

### 8. Make it survive restarts

Shizuku stops whenever the kiosk restarts. The plugin can start it again by itself, with no computer and no extra apps:

1. In the plugin's **Shizuku** section, turn on **Start Shizuku as root at boot**. Do this while Shizuku still runs as root from step 5: the plugin then adds its own key to Android's trusted ADB keys and keeps ADB on port 5555 after restarts, so there is no prompt.
2. The button in the **Shizuku** section should say **Started**, because Shizuku already runs as root. Tap it: it checks the path and should report that the boot start can reach ADB as root. The **Shizuku** status under it shows the current state.
3. Restart the kiosk. About a minute after Kiosk Satellite comes up, **Shizuku** should say **Running as root** again and **AirPlay multi-room** **Multi-room ready**.

If Android shows **Allow USB debugging?** for the plugin's key (`ks-airplay@kiosk`), tick **Always allow** and tap **Allow**. It should not ask again after a restart; if it does, see Troubleshooting. At boot the plugin keeps asking for up to ten minutes, so there's time to get to the screen.

You can now unplug or close the computer. Every time the kiosk boots, the plugin connects to its own ADB on 127.0.0.1:5555, switches it to root and runs Shizuku's starter, the same as the commands in step 5.

The plugin finds Shizuku's starter by itself. If a Shizuku version puts it somewhere else, paste the command from **Shizuku > Start via connected computer > View command** into **Shizuku start command** under Advanced.

The plugin's ADB key gives root through ADB, like the key of a computer you allowed. It lives in the plugin's **ADB key** setting under Advanced, a secret setting that Kiosk Satellite masks everywhere, so it stays the same from boot to boot and Android only asks once. Kiosk Satellite's configuration exports and Fleet Management's plugin secrets include it, like any other secret setting, so keep those private. To make a new key, clear **ADB key**. To revoke the key, use **Revoke USB debugging authorizations** in Developer options. That also removes your computer's key.

### 9. Play to a group

On an iPhone, open Control Center, long-press the audio card, tap the AirPlay icon and tick the kiosk together with your other speakers.

## Why it needs root

An AirPlay 2 group shares one clock. The sender runs a PTP clock (IEEE 1588) and sends its timing on UDP ports 319 and 320. Every speaker plays each moment of audio when that clock says to.

Android apps can't open ports below 1024. So at start, the plugin uses Shizuku as root once to add `iptables` rules. The rules redirect incoming UDP 319 and 320 to 10319 and 10320, which the plugin can open. The rules match only those two ports, and the plugin removes them when it stops.

Apart from the optional boot start above, nothing else runs as root. Kiosk Satellite allows plugins only short root commands, not root services. If a device already lets apps bind ports 319 and 320, the plugin uses them directly and skips iptables.

## What works and what does not

| Works | Does not work |
| --- | --- |
| Grouping with HomePods, Apple TVs and AirPlay 2 speakers from Control Center or the Music app | Screen mirroring and video |
| iOS, iPadOS and macOS senders | AirPlay 1 senders: Kiosk Satellite's own receiver plays those |
| AAC and ALAC buffered audio, 44.1 kHz stereo | 48 kHz and lossless 24-bit formats (the plugin doesn't offer them, so senders use 44.1 kHz) |
| Volume, track info, cover art, synced lyrics | Password protection |
| | Play, pause and skip from the kiosk: see [Playback controls](#playback-controls) |
| | Choosing which speakers play from the kiosk: the iPhone or Mac picks the group |
| | Adding the kiosk to the Apple Home app has not been tested and may not work |

Sync accuracy is a few milliseconds. PTP packets get their timestamps in Java rather than in the kernel. That's fine between rooms; in the same room you may hear a slight difference against a HomePod.

## Settings

### Shizuku

| Setting | Default | What it does |
| --- | --- | --- |
| Start Shizuku as root at boot | Off | Starts Shizuku as root through the device's own ADB whenever the kiosk boots. See [step 8](#8-make-it-survive-restarts) |
| Start Shizuku now | button | **Start** while Shizuku isn't running as root: runs the same start by hand. It turns into **Started** once Shizuku runs as root; tapping that checks that the boot start can still reach ADB |

The **Shizuku** status under the button shows whether Shizuku is running as root, as shell or not at all, and why a start failed.

### Receiver

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | empty | The name in the AirPlay menu. Empty uses this kiosk's device name from Kiosk Satellite |

### Playback

| Setting | Default | What it does |
| --- | --- | --- |
| Audio offset | 0 s | Shifts AirPlay audio later (positive) or earlier (negative), from −3 to +3 seconds, to line it up with video or other speakers. Changes apply straight away |
| Lower AirPlay while the assistant talks | On | Turns AirPlay down while Voice Satellite is listening, thinking or answering |
| Lowered volume | 20% | How loud AirPlay stays while the assistant talks |
| Wake the screen when playback starts | Off | Dismisses the screensaver and wakes the display when audio starts |

### Now Playing

| Setting | Default | What it does |
| --- | --- | --- |
| Show the player | While playing | When the player shows over the dashboard: **Off**, **On track change** (8 seconds when a track starts) or **While playing** |
| Player style | Floating | **Floating**: a card with the cover art, track, artist and progress, and a button that opens the full-screen player. **Full screen**: large cover art, the track, artist, album and progress over the whole dashboard, on a background tinted from the artwork, and a button that shrinks it to the floating card |
| Floating player position | Bottom | Top or bottom of the screen |
| Lyrics | On | A lyrics button in the full-screen player, with lyrics in time with the music from [LRCLIB](https://lrclib.net), the free database Kiosk Satellite's own Now Playing uses. The current line lights up and stays in view. Tracks with only plain lyrics show them without timing |
| Lyrics timing | 0 s | Moves the lyrics against the music, from -3 to +3 seconds. Positive values show each line earlier |
| In the screensaver | When the screensaver starts | **When the screensaver starts** shows the full-screen view instead of your screensaver while AirPlay plays, like Kiosk Satellite's own Now Playing. **Right away** also starts the screensaver as soon as playback starts. **Off** leaves the screensaver alone. Needs the screensaver turned on in Kiosk Satellite |

The floating and full-screen players work whether or not the screensaver is on. Back closes either one: the full-screen player shrinks to the floating card, and the floating card stays away until the next track.

The screensaver view is a plugin screensaver, listed as **AirPlay Now Playing (AirPlay 2 Multi-Room)**. While AirPlay plays, the plugin makes it the screensaver, and puts your own screensaver back 15 seconds after playback stops. Your saved screensaver setting is never changed. Close the view the way you close any screensaver.

Kiosk Satellite's built-in floating player and Now Playing view can't show this plugin's audio yet: plugins have no way to feed them. These views stand in until they can.

### Advanced

| Setting | Default | What it does |
| --- | --- | --- |
| Port | 7100 | TCP port for AirPlay connections. The default keeps clear of Kiosk Satellite's own receiver |
| AirPlay device ID | generated | Created on first start and saved. Clear it to make Apple devices see the kiosk as a new speaker |
| Feature flags | empty | For troubleshooting only. Empty advertises `0x401FCA00,0x1C340`, the set shairport-sync uses |
| Shizuku is running as root | set by the plugin | Switches the button between **Start** and **Started**. The plugin keeps it in step with Shizuku, so changing it has no effect |
| Shizuku start command | empty | Empty finds Shizuku's starter automatically. Otherwise the command from **Shizuku > Start via connected computer > View command** |

## Home Assistant

The plugin adds these entities to the kiosk's ESPHome device:

| Entity | Type | State |
| --- | --- | --- |
| AirPlay playing | Binary sensor | On while audio is playing |
| AirPlay track, AirPlay artist, AirPlay album | Text sensors | The current track's details, when the sender provides them |
| AirPlay source | Text sensor | The name of the connected iPhone, iPad or Mac |
| AirPlay volume | Sensor | The sender's volume slider, 0 to 100% |
| AirPlay multi-room | Text sensor | Whether PTP sync is running and which sender clock it follows, or why it is off |
| AirPlay receiver | Switch | Turns the receiver off and on |
| Shizuku | Text sensor | Whether Shizuku runs as root, as shell or not at all, or is starting |

The Remote Admin Overview also gets an **AirPlay** status tile.

## Playback controls

**Play, pause and skip from the kiosk aren't possible with iPhones and iPads.** Control playback on the iPhone, from Control Center or the lock screen, as with any AirPlay 2 speaker group.

The only remote control an unofficial receiver can use is DACP, the older AirPlay remote. iPhones and iPads don't offer it when they play to AirPlay 2 speakers: the plugin checks what each sender offers, and they send no remote. AirPlay 2's own remote control isn't publicly documented.

The plugin keeps DACP support for any sender that does offer it. Then the players show play/pause and skip buttons; otherwise they show none.

## Actions and triggers

- **Disconnect AirPlay sender** and **Restart AirPlay receiver** can go in the drawer, on a gesture or on an ESPHome button. **Play or pause AirPlay**, **Next AirPlay track** and **Previous AirPlay track** are listed too, but do nothing with iPhones and iPads: see [Playback controls](#playback-controls).
- **AirPlay playback started** and **AirPlay playback stopped** are gesture triggers under **Gestures > Plugin trigger**.

## Privacy and network use

The plugin contacts one server, and only with **Lyrics** on: [LRCLIB](https://lrclib.net), to look up lyrics. It sends the track's title, artist, album and length, nothing else. Turn **Lyrics** off and the plugin contacts no server at all.

On the local network it uses:

- Bonjour (mDNS) advertising of `_airplay._tcp` and `_raop._tcp`, through Android's network service discovery.
- The control port, 7100 by default, over TCP. Senders pair with the standard AirPlay 2 transient pairing, and the connection is then encrypted.
- One TCP port per stream for audio and one for events, picked at random.
- UDP 319 and 320 (redirected to 10319 and 10320) to receive PTP timing from senders. It also sends a short PTP Announce to each member of the group the sender lists, so the device leading the group's clock starts sending it timing, as shairport-sync's nqptp does.
- Remote control (DACP), only for a sender that offers it: when you tap play/pause or skip, it finds the sender's `_dacp._tcp` service over Bonjour and sends it the command over HTTP, with the remote ID the sender gave it.
- Only with **Start Shizuku as root at boot** on: ADB on the device itself, 127.0.0.1:5555, signed in with the plugin's own key, to start Shizuku. While Shizuku runs as root it also adds that key to Android's trusted ADB keys and sets ADB to listen on port 5555 after restarts, which other devices on the network can reach but only with a key you allowed.

Track details and playback state reach Home Assistant only through the kiosk's existing ESPHome connection.

## Troubleshooting

Check the **Shizuku** status and the **AirPlay multi-room** reading on the plugin's page first.

- **`su: inaccessible or not found`** Expected. These builds have no `su`. Use `adb root` from a computer, as in [step 5](#5-start-shizuku-as-root-once).
- **No pairing code notification in Shizuku.** That is Wireless debugging, which you don't need. Use ADB over network on port 5555 instead, as in [step 3](#3-connect-from-the-computer).
- **"adbd cannot run as root" or "ADB cannot switch to root"** Turn on **Rooted debugging** in Developer options.
- **"Nothing answers on 127.0.0.1:5555"** ADB isn't listening on port 5555. Turn on **ADB over network**, or run `adb tcpip 5555` from a computer, then tap **Start** again.
- **"Nobody allowed USB debugging"** The approval prompt timed out. Tap **Start** and approve **Allow USB debugging?** on the kiosk within a minute.
- **"Allow USB debugging?" at every restart, with a different fingerprint each time.** Versions 0.4.0 to 0.6.1 lost their ADB key at each restart. Update, tick **Always allow** once more, and it stops.
- **Shizuku says "Running as shell, not root".** It was started without `adb root`. Tap **Start**: the plugin restarts it as root.

- **"start Shizuku as root…"** Shizuku isn't running, KS has no access, or Shizuku is running as shell (uid 2000) rather than root (uid 0).
- **"the iptables redirect failed"** The reading includes the error. IPv6 failures alone are only logged. Most senders time over IPv4.
- **"Multi-room ready, no clock yet"** The redirect is in place but no PTP traffic has arrived. Clocks appear once a sender starts playing to a group that includes the kiosk. Check that multicast and UDP between the phone and the kiosk aren't blocked, for example by Wi-Fi client isolation or a separate VLAN.
- **The kiosk shows up twice in the AirPlay menu.** Kiosk Satellite's own AirPlay 1 receiver is still on. Turn off **Settings > Media Player > AirPlay > Enable AirPlay receiver**. Only this plugin's entry can join a group.
- **The kiosk is not in the AirPlay menu.** The phone and the kiosk must be on the same network with mDNS allowed. "Bonjour is unavailable" in the status line means the device could not advertise.
- **Audio plays but drifts from the other speakers.** Send me the plugin log from Plugin Manager. It shows the clock offset and the playout corrections.

## How it works

The receiver follows [shairport-sync](https://github.com/mikebrady/shairport-sync), the reference open-source AirPlay 2 receiver, and its clock companion [nqptp](https://github.com/mikebrady/nqptp). It's rewritten in Java so it fits in a Kiosk Satellite plugin with no native code:

- **Pairing:** HomeKit transient pair-setup (SRP-6a, 3072-bit, SHA-512, PIN 3939). After that, the control connection is encrypted with ChaCha20-Poly1305 using HKDF-derived keys.
- **Timing:** a PTP follower in the style of nqptp. It reads Sync and Follow_Up messages, estimates each sender clock's offset from Android's monotonic clock, and favours the fastest packets.
- **Audio:** the sender pushes encrypted packets over TCP seconds ahead. The plugin decrypts each with its session key and decodes ALAC in Java or AAC through Android's MediaCodec.
- **Playout:** SETRATEANCHORTIME pins one RTP frame to one moment on the sender's clock. The plugin works out when every frame is due and compares that with AudioTrack's playback timestamps. It pads or trims at the start, then drops or repeats single frames to stay within a millisecond.
- **Crypto:** ChaCha20-Poly1305, Ed25519 and X25519 are implemented in Java, because Android 7 (the Echo Dot) ships none of them.

## Development

Requires Python 3, JDK 17 or newer and an Android SDK platform.

```sh
python3 tools/test.py   # unit tests, simulated AirPlay 2 sessions and the plugin lifecycle
python3 tools/build.py  # builds dist/airplay2-multiroom-<version>.zip (needs Android build-tools for d8)
```

The tests check:

- The crypto against RFC 8032, 7748 and 8439 vectors, the JDK and Python's `cryptography`.
- The ALAC decoder bit for bit against ffmpeg.
- The PTP filter against synthetic network delay.
- Two full sessions with a simulated iPhone that is also the PTP master. The test covers pairing, the encrypted channel, a buffered stream, anchoring, pause, flush and resume. It checks each frame reaches the fake speaker within 10 ms of its anchored time (a margin for slow CI machines) and that the audio is intact; they typically land within 0.2 ms.

`tools/interop_pyatv.py` is an optional extra check. It pairs with a running receiver using [pyatv](https://github.com/postlund/pyatv)'s HomeKit client, the one pyatv uses with real Apple TVs, then exercises the encrypted channel.

Releases are built by `.github/workflows/build.yml` when you publish a GitHub release.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Kiosk Satellite's [plugin exception](https://github.com/jxlarrea/kiosk-satellite/blob/main/PLUGIN-EXCEPTION.md) allows independent plugins under their own license. The SDK interfaces in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) under Apache-2.0.
