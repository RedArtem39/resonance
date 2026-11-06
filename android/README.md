# Resonance for Android

Native Android port of Resonance (Kotlin, Jetpack Compose, Media3). Same look — Material 3 Expressive, a tonal palette generated from the
current cover (OKLCH), shape-morphing buttons with spring motion, the wavy progress ring and bar, the cookie-shaped cover, the glass-tube
equalizer with its lightning — and the same feature set.

## What is ported

| Desktop (Electron) | Android |
| --- | --- |
| Local library, albums / artists / favourites views, search | Same. Folders are picked with the system folder picker (SAF); a download folder is always scanned. Tags and cover art come from `MediaMetadataRetriever`; metadata is cached. |
| Queue, shuffle, repeat, speed, sleep timer with fade-out | Media3 `ExoPlayer` in a foreground `MediaSessionService`: lock-screen / notification / headset controls, keeps playing with the screen off. |
| Web Audio graph: 10-band EQ, preamp, balance, mono, swap, dynamics + limiter, channel test | Own DSP in `audio/Fx.kt` (RBJ biquads with the Web Audio shelf/peaking formulas, equal-power panner, soft-knee compressor, brick-wall limiter) plugged into ExoPlayer as an `AudioProcessor`. The channel test runs through its own copy of the same chain. |
| Analyser → visualizer (bars / solid) and the EQ glow | `audio/Spectrum.kt` (2048-point FFT, Blackman window, same dB range and smoothing as `AnalyserNode`) + a port of `viz.js`. |
| Synced lyrics (LRCLIB), word-by-word fill, manual picker with timing scale | Same, cached on disk. |
| YouTube search + download (yt-search + yt-dlp) | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0). Optional cookies import works as on desktop. |
| Rename / move to album / delete (context menu) | Long-press menus. Edits are kept in the app profile, audio files are not modified. |
| Updates and rollback from GitHub Releases | Looks for `Resonance-*.apk` release assets, checks the SHA-256 digest, hands the APK to the system installer. |
| Tray, window header, keyboard shortcuts | Replaced by the media notification, edge-to-edge layout and system gestures. |

On phones the layout is a bottom navigation bar + mini player that opens the full-screen player. At ≥ 840 dp width (tablets, landscape
foldables) it switches to the three-column desktop layout.

## Differences worth knowing

- **Delete** removes the file for good — Android has no Recycle Bin for app-managed files (the dialog says so).
- **"Show in folder"** is gone (no file manager to reveal in).
- **WMA** is not listed: ExoPlayer cannot decode it.
- **Downloads** live in the app's own `Music/Resonance` folder (`Android/data/io.github.redartem39.resonance/files/Music/Resonance`), so no storage permission is needed. They are removed when the app is uninstalled.
- **Rollback**: Android refuses to install an older `versionCode` over a newer one. Rolling back needs the current version uninstalled first.
- **YouTube** extraction breaks from time to time when YouTube changes; the fix is to bump `NewPipeExtractor` in `app/build.gradle.kts`.

## Build

Needs a JDK 17+ and the Android SDK (platform 35, build-tools 34+).

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed with keystore.properties if present, otherwise with the debug key
```

Release signing: create a keystore once and keep it — updates only install over a build signed with the same key.

```bash
keytool -genkeypair -keystore resonance-release.keystore -alias resonance -keyalg RSA -keysize 2048 -validity 36500
cat > keystore.properties <<PROPS
storeFile=resonance-release.keystore
storePassword=...
keyAlias=resonance
keyPassword=...
PROPS
```

`*.keystore` and `keystore.properties` are git-ignored. Publishing a release for the in-app updater:

```bash
# bump appVersion / versionCode in app/build.gradle.kts, then
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk Resonance-<version>.apk
gh release upload v<version> Resonance-<version>.apk     # or create the release with it
```

## Layout

```
app/src/main/java/io/github/redartem39/resonance/
  MainActivity.kt, AppVm.kt        entry point; all app state and actions (library, player, lyrics, YouTube, updates)
  audio/   Fx.kt                    EQ / balance / mono / swap / dynamics / limiter DSP
           FxProcessor.kt           ExoPlayer AudioProcessor around it
           Spectrum.kt              FFT analyser for the visualizer and EQ glow
           ChannelTest.kt           left / right / both test tones
           PlaybackService.kt       ExoPlayer + MediaSession
  data/    Library.kt, Covers.kt, CoverProvider.kt   scanning, tags, cover art
           Lyrics.kt, Yt.kt, Cookies.kt, Updater.kt  LRCLIB, YouTube, cookies, GitHub releases
  ui/      Theme.kt                 OKLCH → tonal palette
           Components.kt, Common.kt shape-morphing surfaces, sliders, cookie shape
           NowPlaying.kt, Viz.kt    cover ring, lyrics, queue, dock, visualizer
           EqScreen.kt              glass-tube equalizer and the lightning
           Library.kt, SettingsScreen.kt, Overlays.kt, App.kt
```
