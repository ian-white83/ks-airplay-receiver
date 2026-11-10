# Changelog

## 1.0.0

Everything in 0.7.0, plus fixes from the Community Plugins security review. Needs Kiosk Satellite 2026.11.14 or later.

**If you're updating from 0.6 or earlier:** pick the plugin under **Settings > Media Player > Player > AirPlay 2**, and set up Now Playing, lyrics and ducking in Kiosk Satellite's Media Player settings. See 0.7.0 below.

Security and stability:

- Nothing on the network can crash Kiosk Satellite through the plugin any more. Every plugin thread catches whatever it throws and logs it.
- Property lists from senders are parsed with limits, so a tiny crafted one can't expand into billions of objects. `/info` ignores large bodies.
- A buffered stream asking for more than 8192 samples per frame is refused.
- At most four event connections per session, and at most 32 PTP clocks: spoofed packets can't make either grow without end.
- A bad answer from a sender's remote control is an error, not a crash.
- Group peer addresses are parsed, never looked up by name, and the hellos to them share one thread.
- Stopping the plugin returns within Kiosk Satellite's three-second limit, removes the PTP redirect first and no longer leaks a thread.

Boot start (optional, off by default):

- The plugin no longer changes system settings. It doesn't add its key to Android's trusted ADB keys (you allow it once on the screen) or set ADB's port (a one-time command in the setup guide). Existing setups keep working.
- The ADB key moves out of settings into private storage on each kiosk, so configuration exports and Fleet Management never copy it. Existing keys move over with no new prompt.
- Turning the boot start off removes the plugin's key from Android's trusted ADB keys and deletes it. The README explains how to undo it by hand.
- Removed **Shizuku start command**: the plugin only runs `libshizuku.so` from Shizuku's installed package, never a command from settings or `start.sh` from shared storage.

Other:

- Added NOTICE: the ALAC decoder is ported from Apple's Apache-2.0 reference decoder.

## 0.7.0

The plugin is now a Kiosk Satellite player. Needs Kiosk Satellite 2026.11.14 or later.

- **Pick it under Settings > Media Player > Player** as **AirPlay 2**. It advertises and plays only while picked, so the kiosk no longer shows up twice in the AirPlay menu and there's nothing to turn off.
- Audio goes through Kiosk Satellite's own output, so Kiosk Satellite's Now Playing view, floating player, lyrics, media volume, assistant ducking and echo cancellation all work with AirPlay. Sync uses that output's playback timestamps.
- Track details, cover art, position and state reach Home Assistant through Kiosk Satellite's media player entity.
- Removed, because Kiosk Satellite now does them: the plugin's own floating and full-screen players, the screensaver view, lyrics, **Lower AirPlay while the assistant talks**, **Wake the screen when playback starts**, the playback text sensors, the **AirPlay receiver** switch and the play/pause and skip actions. Set these up in Kiosk Satellite's Media Player settings instead.
- The plugin no longer contacts any internet server.

## 0.6.6

- Fix: playing to kiosks first and then adding a first-generation HomePod still put them out of sync. The HomePod sends timing under the iPhone's clock ID but with its own time, days away, and the plugin mixed those readings with the iPhone's. Clocks are now kept apart per sender, and when two senders use one ID, the one whose time fits the audio's anchor is followed. The log names the sender.

## 0.6.5

- Fix: kiosks still played out of sync, or dropped out, with a first-generation HomePod in the group. When the HomePod joins, the iPhone steps its clock by hours to follow it, and the plugin's smoothing let the clock move back only a fraction of a millisecond at a time. A jump of more than a second that two readings agree on is now taken at once, in either direction, and logged.

## 0.6.4

- Fix: adding a first-generation HomePod to a group made the kiosks drop out or play out of sync. The HomePod follows the iPhone's clock but sends its own time, hours away, and the plugin sometimes followed the HomePod instead of the iPhone. The clock the sender names now always comes first.
- The log says which PTP clock each timeline follows.

## 0.6.3

- Fix: with Fleet Management, the iPhone listed only one of the kiosks and grouped audio could go to the wrong one. The plugin saved a random AirPlay device ID as a setting, and the fleet copied the leader's to every kiosk. The ID is now made from each device, so every kiosk has its own. Apple devices see each kiosk as a new speaker once.
- **AirPlay device ID** under Advanced is now an optional override, empty by default.
- The boot start's ADB key setup retries when another root command was still running, instead of waiting for the next boot.

## 0.6.2

- Fix: Android asked "Allow USB debugging?" at every restart, with a new fingerprint each time, and multi-room stayed off until someone answered. The plugin lost its ADB key at each restart. It now keeps it in a secret **ADB key** setting under Advanced, so Android asks once. Clear the setting to make a new key.
- At boot, if nobody answers the prompt, the plugin asks again every 30 seconds for up to ten minutes instead of giving up.
- The keys earlier versions left in Android's trusted ADB keys are removed.

## 0.6.1

- Fix: play/pause and skip buttons didn't show. The plugin only took the sender's remote-control details from the audio connection; iPhones may send them on a separate remote-control connection. It now takes them from either, and keeps them between sessions.
- The log shows which headers each sender's SETUP carries, to diagnose remote control.
- Play, pause and skip from the kiosk aren't possible with iPhones and iPads: they don't offer DACP remote control to AirPlay 2 speakers. The buttons only show for a sender that offers it. See Playback controls in the README.

## 0.6.0

- **Lyrics** in the full-screen player, in time with the music, from LRCLIB like Kiosk Satellite's own Now Playing. A lyrics button opens them in place of the title; the current line lights up and stays in view. New **Lyrics** and **Lyrics timing** settings.
- **Play/pause and skip** on the players, when the sender offers remote control (DACP). Play/pause on the floating card; previous, play/pause and next on the full-screen player.
- New actions: **Play or pause AirPlay**, **Next AirPlay track** and **Previous AirPlay track**.

## 0.5.1

- Fix: the floating and full-screen players never appeared. Their overlay names had hyphens, which Kiosk Satellite doesn't accept, so it refused to show them. The screensaver view wasn't affected.

## 0.5.0

- New **Player style** setting: **Floating** or **Full screen**. The full-screen player now opens over the dashboard, so it works with the screensaver turned off.
- The floating player has a full-screen button, and the full-screen player has a button that shrinks it back to the floating card. Back does the same.
- Fix: the floating player no longer stays hidden after the screensaver has shown the full-screen view, or after back closed it.
- **Floating player** is now **Show the player**, and **Full-screen view** is now **In the screensaver**. Saved choices carry over.

## 0.4.4

- Fix: the kiosk went silent in groups that include a HomePod. When a HomePod leads the group's clock, the plugin now says hello to every group member the sender lists (SETPEERS), as nqptp does, so the HomePod starts sending it timing.
- Fix: when the only clock heard isn't the group's, the plugin no longer uses its time, which made every frame look late and dropped it. It waits for the right clock, then plays unsynced instead of staying silent, and says so in the log.
- The log now lists the group members each time the group changes.

## 0.4.3

- Fix: multi-room could lose its clock ("Multi-room ready, no clock yet") after a plugin update, because the outgoing version removed the PTP port redirect just after the new one added it. The plugin now checks the redirect every 30 seconds and puts it back if it's missing, and logs when it does.

## 0.4.2

- The Shizuku button now says **Started** while Shizuku runs as root, and **Start** when it doesn't. Tapping **Started** checks that the boot start can still reach ADB. The status reading stays below it.
- Turn off Kiosk Satellite's own AirPlay receiver (**Settings > Media Player > AirPlay > Enable AirPlay receiver**), otherwise the kiosk shows up twice in the AirPlay menu.

## 0.4.1

- The Shizuku section now comes first on the plugin's page, right under Shizuku access.
- New **Shizuku** status reading under the Start button: Running as root, Running as shell, Not running, no access for Kiosk Satellite, or Starting. It also shows why a start failed, and is published to Home Assistant.

## 0.4.0

- New **Start Shizuku as root at boot** setting. Shizuku stops when the kiosk restarts; with this on, the plugin starts it again through the device's own ADB (Rooted debugging, port 5555), with no computer or extra apps.
- New **Start Shizuku now** button to run the same start by hand and approve the plugin's ADB key the first time.
- New **Shizuku start command** setting under Advanced, for Shizuku versions that keep their starter somewhere unusual.
- Screen readers describe the cover art and the floating player.

## 0.3.0

- Full-screen Now Playing view: large cover art, track details and live progress on a background tinted from the artwork. It shows in place of the screensaver while AirPlay plays, or right away if you choose.
- The floating player stays up while AirPlay plays (the new default), shows progress and opens the full-screen view when tapped.
- Track progress from the sender now drives both views.

## 0.2.1

- New **Audio offset** setting under Playback: shift AirPlay audio from 3 seconds earlier to 3 seconds later. It applies to the playing stream straight away.

## 0.2.0

- AirPlay 2 multi-room: the kiosk groups with HomePods and other AirPlay 2 speakers and plays in sync.
- HomeKit transient pairing and the encrypted AirPlay 2 control channel.
- PTP clock following, with ports 319 and 320 redirected through Shizuku running as root.
- Buffered audio (AAC and ALAC) played at the moment the sender's anchor sets, corrected against AudioTrack timestamps.
- New **AirPlay multi-room** reading shows sync status.
- Removed the AirPlay 1 and realtime audio path, which Kiosk Satellite's own receiver covers, and the FairPlay key tables it needed.
- Default port is now 7100, clear of Kiosk Satellite's receiver. The plugin ID is now `airplay2-multiroom`.

## 0.1.0

- First release: AirPlay 2 and AirPlay 1 audio receiver with ALAC and AAC, sender volume, track details, cover art and a Now Playing card.
- Home Assistant entities for playback state, track, source and volume, and a switch for the receiver.
- Lowers AirPlay audio while Voice Satellite talks.
- Gesture triggers for playback start and stop, and commands to disconnect the sender or restart the receiver.
