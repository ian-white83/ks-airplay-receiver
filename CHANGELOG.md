# Changelog

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
