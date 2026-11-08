# AirPlay Receiver for Kiosk Satellite

Turns a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) kiosk into an AirPlay speaker. Pick it in the AirPlay menu on an iPhone, iPad or Mac and the audio plays from the kiosk: music, podcasts, YouTube and anything else that plays sound.

- Plays audio from iOS, iPadOS and macOS, and from older AirPlay 1 senders.
- Follows the volume slider on the sender.
- Shows the track, artist and cover art on a Now Playing card over the dashboard.
- Publishes what is playing to Home Assistant, and gives you a switch to turn the receiver off.
- Turns AirPlay audio down while Voice Satellite listens and answers.
- Can wake the screen when playback starts, or run any gesture action through its triggers.
- Works on kiosks without a screen, such as an Echo Dot, as an audio-only speaker.

This plugin is not affiliated with, endorsed by or sponsored by Apple Inc. AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc.

## Installation

### Community Plugins (Recommended)

AirPlay Receiver is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=OWNER/ks-airplay-receiver)

Or search for `AirPlay Receiver` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/OWNER/ks-airplay-receiver` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Then turn the plugin on. The kiosk shows up in the AirPlay menu within a few seconds, under its Kiosk Satellite device name.

## What works and what does not

| Works | Does not work |
| --- | --- |
| Audio from Control Center, Music, Podcasts, Safari, YouTube and other apps | Screen mirroring and video to the screen |
| AirPlay 2 senders (iOS, iPadOS, macOS) and AirPlay 1 senders (older iTunes, many third-party apps) | AirPlay 2 multi-room: grouping this kiosk with HomePods or other AirPlay 2 speakers |
| ALAC and AAC audio, 44.1 kHz stereo | Adding the kiosk to the Apple Home app |
| Volume, track info, cover art | Password or PIN protection |
| One sender at a time. A new sender takes over, as on an Apple TV | |

AirPlay 2 multi-room needs PTP clock sync on ports 319 and 320, which an Android app cannot open without root. So the kiosk plays like a single AirPlay speaker: it shows up as its own device and plays whatever one sender sends it. Because audio is buffered, there is a short delay before playback starts. iPhones and Macs delay the picture to keep video in sync.

## Settings

### Receiver

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | empty | The name in the AirPlay menu. Empty uses this kiosk's device name from Kiosk Satellite |

### Playback

| Setting | Default | What it does |
| --- | --- | --- |
| Audio buffer | 500 ms | Audio held before playing. Raise it if playback stutters on weak Wi-Fi |
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
| Port | 7000 | TCP port for AirPlay connections. Change it only if another app already uses 7000 |
| AirPlay device ID | generated | Created on first start and saved. Clear it to make Apple devices see the kiosk as a new speaker |
| Legacy pairing | Off | Asks senders to pair before streaming. Off connects faster. Try it if a sender refuses to connect |
| Feature flags | empty | For troubleshooting only. Empty advertises the audio-only default `0x527FDE00` |

## Home Assistant

The plugin adds these entities to the kiosk's ESPHome device:

| Entity | Type | State |
| --- | --- | --- |
| AirPlay playing | Binary sensor | On while audio is playing |
| AirPlay track, AirPlay artist, AirPlay album | Text sensors | The current track's details, when the sender provides them |
| AirPlay source | Text sensor | The name of the connected iPhone, iPad or Mac |
| AirPlay volume | Sensor | The sender's volume slider, 0 to 100% |
| AirPlay receiver | Switch | Turns the receiver off and on. Off hides the kiosk from the AirPlay menu until it is turned on again or the plugin restarts |

The same readings show on the plugin's page in Plugin Manager, and the Remote Admin Overview gets an **AirPlay** status tile.

## Actions and triggers

- **Disconnect AirPlay sender** and **Restart AirPlay receiver** can go in the drawer, on a gesture or on an ESPHome button.
- **AirPlay playback started** and **AirPlay playback stopped** are gesture triggers. Map them under **Gestures > Plugin trigger** to run any action, such as opening a dashboard view when music starts.

## Privacy and network use

The plugin does not contact any server. It listens on the local network:

- Bonjour (mDNS) advertising of `_airplay._tcp` and `_raop._tcp`, through Android's network service discovery.
- The RTSP control port, 7000 by default, over TCP.
- Three UDP ports per stream, picked at random, for audio, retransmission and timing.

Audio, track details and cover art stay on the kiosk. Track details and playback state go to Home Assistant only through the kiosk's existing ESPHome connection.

## Troubleshooting

- **The kiosk is not in the AirPlay menu.** The phone and the kiosk must be on the same network and VLAN, with multicast allowed between them. Check the plugin's status line in Plugin Manager. "Bonjour is unavailable" means the device could not advertise.
- **"Port 7000 is in use".** Pick another port under Advanced.
- **The sender connects, then gives up.** Turn on **Legacy pairing**. If that does not help, set **Feature flags** to `0x5A7FFEE6`, the set UxPlay uses. It also advertises screen mirroring, which this plugin refuses.
- **Stutters or gaps.** Raise **Audio buffer** to 1000 ms or more.

## How it works

The receiver speaks the same AirPlay audio protocol as [UxPlay](https://github.com/FDH2/UxPlay) and [RPiPlay](https://github.com/FD-/RPiPlay), written in Java so it runs inside Kiosk Satellite with no native code:

- Ed25519 and X25519 for pairing, implemented in Java because Android only ships them from Android 13.
- FairPlay SAP v2.5 key exchange from [playfair](https://github.com/EstebanKubata/playfair), through the Java port in [java-airplay-lib](https://github.com/serezhka/java-airplay-lib). Its tables ship as `assets/fairplay.bin`.
- A Java ALAC decoder following Apple's open-source reference decoder. AAC and AAC-ELD use Android's MediaCodec.
- A jitter buffer that asks the sender to resend lost packets, and plays to Android's AudioTrack as media audio.

## Development

Requires Python 3, JDK 17 or newer and an Android SDK platform.

```sh
python3 tools/test.py   # unit tests, a simulated iPhone session and the plugin lifecycle
python3 tools/build.py  # builds dist/airplay-receiver-<version>.zip (needs Android build-tools for d8)
```

The tests check the crypto against RFC 8032 and RFC 7748 vectors and the JDK, the FairPlay port against UxPlay's C playfair, and the ALAC decoder bit for bit against ffmpeg. The session test plays a fake iPhone over localhost through pairing, FairPlay, encrypted audio with a lost packet, pause and resume, then an AirPlay 1 session.

Releases are built by the GitHub Actions workflow in `.github/workflows/build.yml` when you publish a release.

## License

GPL-3.0-or-later, because the FairPlay code derives from playfair (GPL-3.0). See [LICENSE](LICENSE). Kiosk Satellite's [plugin exception](https://github.com/jxlarrea/kiosk-satellite/blob/main/PLUGIN-EXCEPTION.md) allows independent plugins under their own license. The SDK interfaces in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) under Apache-2.0.
