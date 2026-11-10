# Accessibility

The plugin should be usable by everyone who uses the kiosk it runs on. It draws no screens of its own. Its settings render in Kiosk Satellite's settings screens, and what's playing shows in Kiosk Satellite's own Now Playing view and floating player, so both inherit Kiosk Satellite's accessibility: its font, theme colours, UI scale and screen-reader support.

## What the plugin does

- **Full track details.** It hands Kiosk Satellite the title, artist, album, cover art, length and position the sender provides, so Now Playing can show and announce them.
- **No sound or touch required.** Playback state, track details and volume reach Home Assistant through Kiosk Satellite's media player entity, so they can be shown or announced elsewhere.
- **Status in words.** The **AirPlay multi-room** and **Shizuku** readings say what is happening and why, not only a colour.

## Known limitations

- **No playback controls from the kiosk with iPhones and iPads.** Play, pause and skip happen on the sender. See Playback controls in the README.
- **Setup needs a computer once**, with a terminal, to install and start Shizuku.

## Reporting a barrier

If something in the plugin is hard or impossible for you to use, [open an issue](https://github.com/ian-white83/ks-airplay-receiver/issues/new?template=bug_report.yml) and say what you were trying to do and what got in the way. Accessibility bugs are treated as bugs, not feature requests.
