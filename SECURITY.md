# Security policy

This plugin runs inside Kiosk Satellite with the app's permissions. It listens on the local network for AirPlay, and it can run root commands through Shizuku. Please report security problems privately.

## Reporting a vulnerability

Use GitHub's private reporting: open the [Security tab](https://github.com/ian-white83/ks-airplay-receiver/security) and choose **Report a vulnerability**. Don't open a public issue for a vulnerability.

Include the plugin version, what an attacker could do, and how to reproduce it. You'll get a reply within 7 days. A fix is released as a new version, and the advisory is published once users have had time to update.

## Supported versions

Only the latest release gets security fixes. Kiosk Satellite shows when an update is available in **Settings > Plugin Manager**.

## What the plugin exposes

- **AirPlay control port** (TCP, 7100 by default) on the local network. Senders pair with AirPlay 2 transient pairing, after which the connection is encrypted with ChaCha20-Poly1305. Like most AirPlay speakers, there is no password: anyone on the network can play to it.
- **Audio and event ports** (TCP, random) for each stream. Audio packets are encrypted with the stream key the sender sets up.
- **PTP** (UDP 319 and 320, or 10319 and 10320 when redirected). The plugin only listens to timing messages; it never acts as a clock master.
- **Root commands through Shizuku.** Only when Shizuku runs as root, and only two fixed scripts: one adds `iptables` rules redirecting UDP 319 and 320 to 10319 and 10320, the other removes them. No other root command is run, and no user or network input goes into those scripts.

Things worth reporting include: a crafted network packet that crashes the plugin, hangs Kiosk Satellite or reads memory it shouldn't; a way to get past pairing or the channel encryption; and anything that makes the root scripts do more than described here.

AirPlay being open to everyone on the local network is how AirPlay works, not a vulnerability. Use network segmentation if you need to restrict it.
