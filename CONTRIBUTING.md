# Contributing

Thanks for helping. Bug reports from real devices matter most here: the protocol code is tested against simulated senders, and only real iPhones, Macs and Echo Shows show what still needs fixing.

## Reporting a bug

Open a [bug report](https://github.com/ian-white83/ks-airplay-receiver/issues/new?template=bug_report.yml). The form asks for:

- **Versions**: the plugin, Kiosk Satellite, the device and its Android or LineageOS version, and the sender (for example iPhone 15, iOS 26.1).
- **The AirPlay multi-room reading** from the plugin's page in Plugin Manager.
- **The plugin log**, from the plugin's page in Plugin Manager. It records what the sender asked for, PTP clock samples and playout corrections. Copy the part around the problem.
- **What you did, what you expected and what happened**, for example "grouped the kitchen Show with a HomePod mini, audio is about half a second behind".

## Suggesting a feature

Open a [feature request](https://github.com/ian-white83/ks-airplay-receiver/issues/new?template=feature_request.yml). Say what you want to do, not only how. That leaves room for a simpler way to get there.

Some things depend on Kiosk Satellite or Android rather than this plugin. Kiosk Satellite's own floating player and Now Playing view are one example: they need a plugin SDK addition. Those belong in [Kiosk Satellite's issues](https://github.com/jxlarrea/kiosk-satellite/issues).

## Changing the code

You need Python 3, JDK 17 or newer and an Android SDK platform (for `android.jar`).

```sh
python3 tools/test.py    # unit tests, simulated AirPlay 2 sessions and the plugin lifecycle
```

- Plugin code under `src/` must compile with `--release 8` and run on Android 7 (API 24), the oldest Echo devices. Don't use Java or Android APIs newer than that without a fallback.
- Keep protocol code independent of Android. Code under `ks.airplay` (except `ks.airplay.android`) runs on a desktop JVM in the tests. Android-only code belongs in `ks.airplay.android` behind the `Platform` interface.
- Add or update a test with every change. Protocol changes should come with a check in `FlowTest` or `CoreTest`.
- Don't add libraries. The plugin package is limited to 4 MiB, and every dependency becomes code that runs inside Kiosk Satellite with its permissions.
- Update `README.md` for anything users see, and add a line to `CHANGELOG.md`.

Open a pull request against `main`. The template has a short checklist. Releases are built only by the GitHub Actions workflow when a release is published. Don't attach build artifacts by hand: Kiosk Satellite rejects them.

## Licence

By contributing, you agree that your contribution is licensed under the project's licence, GPL-3.0-or-later.
