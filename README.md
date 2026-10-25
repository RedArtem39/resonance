<p align="center">
  <img src="docs/icon.png" width="128" alt="Resonance icon">
</p>

<h1 align="center">Resonance</h1>

<p align="center">
  An offline music player with a <b>Material 3 Expressive</b> look, a real 10-band equalizer,
  word-by-word synced lyrics and built-in YouTube search &amp; download.
</p>

> **Honest note: this project is vibe-coded.** I (the author) did not write the code by hand — it was produced in conversation with an AI
> (Claude, by Anthropic) while I steered the product, art-directed the design and tested every iteration. See [How this was made](#how-this-was-made).
> Expect rough edges, and read the code before you trust it with anything important.

---

## Features

**Player**
- Plays local `mp3`, `flac`, `ogg`, `opus`, `m4a`, `aac`, `wav`, `webm`. Add folders from the sidebar or drop them onto the window.
- Library views: tracks, albums, artists, favourites. Fast search across title, artist and album.
- Queue, shuffle, repeat (off / all / one), playback speed (0.75–1.5×) and a sleep timer with a fade-out.
- Media keys and the OS media overlay are supported.
- System tray: play/pause, next/previous and show/hide from the tray menu; the tooltip shows the current track. Closing the window hides it to the tray and the music keeps playing (can be turned off in *Settings*); *Quit* in the tray menu exits. Launching Resonance again just brings the existing window back.

**Design**
- Material 3 Expressive: tonal surfaces, large shapes, shape-morphing buttons and spring motion.
- The whole palette is generated from the cover art of the current track (OKLCH tonal palette) and changes smoothly between tracks. Dark and light themes.
- A wavy progress ring around the cover that fills as the track plays (drag it to seek), plus a wavy progress bar.
- An optional visualizer behind the transport controls (toggle button next to the sleep timer).
- The window header is hidden until you move the pointer to the top edge, then slides in over the UI without moving anything.

**Sound**
- 10-band equalizer (31 Hz – 16 kHz, ±12 dB) with a live frequency-response curve and 8 presets.
- Preamp, stereo balance (with L/R readouts), mono, left/right swap, dynamic range (off / balanced / night: compressor plus a brick-wall limiter) and a channel test (left, right, both — through the whole chain, so it also shows what Balance/Swap/Mono do).
- The equalizer view has a lightning effect: when you apply a preset or release a slider, a crackling bolt runs through the handles and every band it touches pulses. It can be turned off in *Settings*.
- UI sound effects (clicks, switches, slider ticks, panel swooshes, a chime when a download finishes). They are synthesized live with Web Audio — no audio files — and can be turned off or turned down in *Settings*.
- Built on the Web Audio API, so it works on every track, local or downloaded.

**Lyrics**
- Synced lyrics from [LRCLIB](https://lrclib.net), cached on disk. The active line is in focus, the rest fades with distance, and words inside the line fill progressively. Click a line to seek.
- LRCLIB provides timestamps per *line*, not per word, so word timing inside a line is estimated from word length. Plain (unsynced) lyrics are shown when no synced version exists.

**YouTube (optional)**
- Search YouTube from the same search box; click a result to download its audio and play it immediately.
- Can be switched off in *Settings*. When off, search is local only; when on, results from YouTube and the local library are shown together.
- Downloads go to `Music\Resonance` and become part of your library (with cover art and title/artist taken from the video).
- Uses [yt-dlp](https://github.com/yt-dlp/yt-dlp), which Resonance downloads on first use (it is not bundled). *Settings → yt-dlp → Update* refreshes it when YouTube changes something.

**Updates and rollback**
- *Settings → Updates* lists the releases published on GitHub. Pick a newer one to update, or an older one to **roll back**.
- Nothing is installed silently: the installer of the chosen version is downloaded, its SHA-256 is checked against the digest GitHub reports, and then it starts and asks you. Your library and settings are kept.
- A quiet check on startup can be turned off in the same place.

## Install

Download `Resonance-Setup-<version>.exe` from the [Releases](../../releases) page and run it. It is a small custom installer (also built with Electron, see [`installer/`](installer)): per-user, no administrator rights, it creates shortcuts and registers an uninstaller in *Apps & features*. Run it again later to update or repair.

The installer is **not code-signed**, so Windows SmartScreen may show an "unknown publisher" warning. Choose *More info → Run anyway*.

The installer is a self-extracting Electron app, so its window can take several seconds to appear after you start it (it has to unpack the ~320 MB Electron runtime first; Electron itself starts in about a second).

Silent install for scripts: `Resonance-Setup-<version>.exe --silent [--dir=<path>] [--desktop] [--startmenu] [--launch]`.
Silent uninstall: `"<install dir>\Resonance.exe" --uninstall --silent [--purge]` (`--purge` also deletes settings and cache; downloaded music is never touched).

Resonance currently targets **Windows 10/11 (x64)**. The yt-dlp integration downloads the Windows executable.

## Keyboard

| Key | Action |
| --- | --- |
| `Space` | Play / pause |
| `Ctrl` + `→` / `←` | Next / previous track |
| `/` | Focus search |

## Build from source

Requires [Node.js](https://nodejs.org) 20 or newer.

```bash
git clone https://github.com/RedArtem39/resonance
cd resonance
npm install

npm start            # run in development
npm run icons        # regenerate build/icon.svg, icon.png, icon.ico
npm run installer    # build the custom installer -> dist-installer/Resonance-Setup-<version>.exe
npm run dist:nsis    # (alternative) classic NSIS installer -> dist/
```

Publishing a release (the in-app updater looks for `Resonance-Setup-*.exe` assets):

```bash
# bump "version" in package.json, then
npm run installer
gh release create v<version> dist-installer/Resonance-Setup-<version>.exe --title "Resonance <version>" --notes "What changed"
```

Project layout:

```
src/boot.js        Entry point: player, or the uninstaller when started with --uninstall
src/main.js        Electron main process: library scan, rmedia:// protocol (HTTP Range support), YouTube, lyrics, window
src/update.js      Update check / rollback via GitHub Releases
src/uninstall.*    Uninstaller (window + logic)
src/preload.js     Safe bridge between main and renderer
src/util.js        Helpers and icon set
src/eq.js          Web Audio graph (equalizer, balance, dynamics) and the "Sound" view
src/viz.js         Visualizer
src/lyrics.js      LRC parsing and the word-by-word lyrics view
src/app.js         Library, search, playback, settings, UI wiring
src/styles.css     Material 3 Expressive styling
installer/         The installer app (Electron) and its build script
tools/             Icon generator
```

## Data and privacy

- Settings, favourites and the equalizer state are stored in the app profile (`%APPDATA%\Resonance`).
- Network access: lyrics requests go to `lrclib.net`; YouTube search and downloads go to YouTube; the update check talks to `api.github.com`. Nothing else leaves your machine, and there is no telemetry.

## Legal note

Downloading content from YouTube may violate YouTube's Terms of Service and the copyright of the content owners, depending on what you download and where you live. Resonance only provides the tooling; **use it for content you have the right to download** (your own uploads, Creative Commons or public-domain music, and so on). The YouTube features can be turned off in *Settings*.

## How this was made

Resonance is **vibe-coded**: essentially all of the code was written by an AI (Claude, by Anthropic) through a long back-and-forth, not typed by hand. My part was the product and the taste — what it should do, what it should feel like, and an unforgiving eye on every screenshot ("too chunky", "this header is annoying", "that looks like AI", and so on). Many pieces went through several rounds of that: the equalizer view, the window header, the icon, even the installer.

What that means for you:
- The result is deliberately art-directed rather than default-looking — but it has had **no security audit and no formal testing**. The features were exercised end to end during development, not covered by an automated test suite.
- Bugs are likely. Issues and pull requests are welcome.
- I would rather say so plainly than let anyone assume otherwise.

## Acknowledgements

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [yt-search](https://github.com/talmobi/yt-search) (MIT), [music-metadata](https://github.com/Borewit/music-metadata) (MIT), [Electron](https://www.electronjs.org) (MIT), [electron-builder](https://www.electron.build) (MIT; used only as a packager).
- Lyrics: [LRCLIB](https://lrclib.net).
- The equalizer's band layout and presets, the audio panel (balance / mono / swap / dynamic range / channel test), and the ideas of the lightning effect and UI sounds were inspired by the media widget of [ilyamiro/serpantinum](https://github.com/ilyamiro/serpantinum) (AGPL-3.0). No code or audio assets were copied: everything here is implemented from scratch.
- Design language: [Material 3 Expressive](https://m3.material.io).

## License

Copyright (C) 2026 RedArtem39

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either **version 3** of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.
