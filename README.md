# AirPlay 2 Multi-Room for Kiosk Satellite

Turns a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) kiosk into an AirPlay 2 speaker that you can group with HomePods, Apple TVs and other AirPlay 2 speakers. Pick several speakers in the AirPlay menu on an iPhone, iPad or Mac and they play in sync.

Kiosk Satellite already has its own AirPlay receiver for single-speaker playback. This plugin adds what that receiver doesn't: AirPlay 2 buffered audio locked to the sender's clock, so the kiosk can be part of a group.

- Groups with other AirPlay 2 speakers for multi-room audio.
- Follows the sender's volume slider, and shows the track, artist and cover art on a Now Playing card.
- Publishes what is playing to Home Assistant, with a switch to turn the receiver off.
- Turns AirPlay audio down while Voice Satellite listens and answers.
- Can wake the screen when playback starts, or run any gesture action through its triggers.
- Works on kiosks without a screen, such as an Echo Dot.

This plugin is not affiliated with, endorsed by or sponsored by Apple Inc. AirPlay, HomePod, iPhone, iPad and Mac are trademarks of Apple Inc.

## Installation

### Community Plugins (Recommended)

AirPlay 2 Multi-Room is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=ian-white83/ks-airplay-receiver)

Or search for `AirPlay 2 Multi-Room` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/OWNER/ks-airplay-receiver` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

### Give it root through Shizuku

Multi-room needs root, granted through Shizuku:

1. Start [Shizuku](https://shizuku.rikka.app/guide/setup/) **as root** on the rooted device. Shizuku started over ADB only gives shell access, which is not enough.
2. In Kiosk Satellite, open **Settings > Device > Shizuku**, or the plugin's page in Plugin Manager, and tap **Shizuku access**. Approve the prompt on the kiosk.
3. Turn the plugin on. Its **AirPlay multi-room** reading should say "Multi-room ready".

Without root the plugin still plays AirPlay 2 audio, but it isn't synced to other speakers.

## Why it needs root

An AirPlay 2 group shares one clock. The sender runs a PTP clock (IEEE 1588) and sends its timing on UDP ports 319 and 320. Every speaker plays each moment of audio when that clock says to.

Android apps can't open ports below 1024. So at start, the plugin uses Shizuku as root once to add `iptables` rules. The rules redirect incoming UDP 319 and 320 to 10319 and 10320, which the plugin can open. The rules match only those two ports, and the plugin removes them when it stops.

Nothing else runs as root. Kiosk Satellite allows plugins only short root commands, not root services. If a device already lets apps bind ports 319 and 320, the plugin uses them directly and skips iptables.

## What works and what does not

| Works | Does not work |
| --- | --- |
| Grouping with HomePods, Apple TVs and AirPlay 2 speakers from Control Center or the Music app | Screen mirroring and video |
| iOS, iPadOS and macOS senders | AirPlay 1 senders: Kiosk Satellite's own receiver plays those |
| AAC and ALAC buffered audio, 44.1 kHz stereo | 48 kHz and lossless 24-bit formats (the plugin doesn't offer them, so senders use 44.1 kHz) |
| Volume, track info, cover art | Password protection |
| | Adding the kiosk to the Apple Home app has not been tested and may not work |

Sync accuracy is a few milliseconds. PTP packets get their timestamps in Java rather than in the kernel. That's fine between rooms; in the same room you may hear a slight difference against a HomePod.

## Settings

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
| Now Playing card | On track change | **Off**, **On track change** (shows for 8 seconds when a track starts) or **While playing** |
| Card position | Bottom | Top or bottom of the screen |

### Advanced

| Setting | Default | What it does |
| --- | --- | --- |
| Port | 7100 | TCP port for AirPlay connections. The default keeps clear of Kiosk Satellite's own receiver |
| AirPlay device ID | generated | Created on first start and saved. Clear it to make Apple devices see the kiosk as a new speaker |
| Feature flags | empty | For troubleshooting only. Empty advertises `0x401FCA00,0x1C340`, the set shairport-sync uses |

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

The Remote Admin Overview also gets an **AirPlay** status tile.

## Actions and triggers

- **Disconnect AirPlay sender** and **Restart AirPlay receiver** can go in the drawer, on a gesture or on an ESPHome button.
- **AirPlay playback started** and **AirPlay playback stopped** are gesture triggers under **Gestures > Plugin trigger**.

## Privacy and network use

The plugin does not contact any server. On the local network it uses:

- Bonjour (mDNS) advertising of `_airplay._tcp` and `_raop._tcp`, through Android's network service discovery.
- The control port, 7100 by default, over TCP. Senders pair with the standard AirPlay 2 transient pairing, and the connection is then encrypted.
- One TCP port per stream for audio and one for events, picked at random.
- UDP 319 and 320 (redirected to 10319 and 10320) to receive PTP timing from senders.

Track details and playback state reach Home Assistant only through the kiosk's existing ESPHome connection.

## Troubleshooting

Check the **AirPlay multi-room** reading on the plugin's page first.

- **"start Shizuku as root…"** Shizuku isn't running, KS has no access, or Shizuku is running as shell (uid 2000) rather than root (uid 0).
- **"the iptables redirect failed"** The reading includes the error. IPv6 failures alone are only logged. Most senders time over IPv4.
- **"Multi-room ready, no clock yet"** The redirect is in place but no PTP traffic has arrived. Clocks appear once a sender starts playing to a group that includes the kiosk. Check that multicast and UDP between the phone and the kiosk aren't blocked, for example by Wi-Fi client isolation or a separate VLAN.
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
