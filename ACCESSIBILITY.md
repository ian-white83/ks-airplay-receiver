# Accessibility

The plugin should be usable by everyone who uses the kiosk it runs on. Most of its interface is Kiosk Satellite's own: its settings render in Kiosk Satellite's settings screens and inherit their accessibility. This page covers the parts the plugin draws itself: the floating player and the full-screen Now Playing view.

## What the plugin does

- **Kiosk Satellite's styling.** Text uses the kiosk's font, theme colours and UI scale, so it grows with **UI scale** in Kiosk Satellite's display settings.
- **Readable contrast.** The full-screen view draws white text on a dark background derived from the cover art and darkened, so text stays readable on light artwork.
- **Not colour alone.** Playing and paused states are written out ("Playing on AirPlay", "Paused"), and the full-screen view shows elapsed and remaining time as text beside the progress bar.
- **Screen readers.** The cover art and the floating player carry descriptions for TalkBack. The floating player says it opens the full-screen view.
- **No sound or touch required.** Everything the views show is also published to Home Assistant (track, artist, album, source, volume, playing), so it can be shown or announced elsewhere.
- **Can be turned off.** The floating player and the full-screen view each have an **Off** option, and the floating player can sit at the top or bottom of the screen.

## Known limitations

- **No on-screen playback controls.** Play, pause and skip happen on the sender (iPhone, iPad or Mac).
- **Brief text in the floating player.** Long titles are cut off with an ellipsis. The full-screen view shows up to two lines.
- **Cover art only as an image.** There's no text description of the artwork beyond the album name.
- **Untested with TalkBack on Kiosk Satellite's kiosk mode.** Kiosk Satellite may limit system accessibility services while it holds the screen.

## Reporting a barrier

If something in the plugin is hard or impossible for you to use, [open an issue](https://github.com/ian-white83/ks-airplay-receiver/issues/new?template=bug_report.yml) and say what you were trying to do and what got in the way. Accessibility bugs are treated as bugs, not feature requests.
