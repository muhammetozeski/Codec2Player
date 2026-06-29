# Codec2 Player 🎙️

**A pure-native Android app that *plays* and *creates* [Codec 2](http://www.rowetel.com/codec2.html) — the world's smallest open-source voice codec — in about ~300 KB installed.**

No third-party libraries (no AndroidX, no Kotlin, no Material) — just core `android.*` plus a single small JNI `.so`. Single ABI (`armeabi-v7a`). The whole app installs in roughly the size of a couple of screenshots.

> The app's UI is in Turkish, but it's trivial to follow. This README is in English.

<p align="center">
  <img src="screenshots/playing.png" width="30%" />
  <img src="screenshots/player.png" width="30%" />
  <img src="screenshots/settings.png" width="30%" />
</p>

---

## What is Codec 2?

[Codec 2](http://www.rowetel.com/codec2.html) (by David Rowe, LGPL) compresses human speech to **450 – 3200 bits per second**. How small is that? In this app, a **3-minute song shrinks to ~14 KB** in the 450 mode. It's built for radio/satellite/low-bandwidth voice and tiny voice archives.

## ✨ Features

**Playback**
- Multi-file playlist; tap to play, auto-advance
- One glowing play/pause button (pulse + circular progress ring)
- Touch-and-drag **waveform** scrubber, elapsed / total time
- ⏮ ⏭ prev/next; long-press to seek **±10 s**; "prev" restarts the track after 3 s
- Shuffle; **3-state repeat** (off / all / one)
- **Playback speed** 0.75×–2×
- **Audio gain** (dB) — unlimited, can go negative, free-form value
- **Sleep timer** (15/30/60 min)
- **Resume** where you left off (track + position persisted)
- Mode is **auto-detected** from the file header (3200…450); colour-coded badges

**Background & notification**
- Foreground **Service** + MediaSession (lock screen / headset buttons)
- Notification prev/play-pause/next; **notification hides when app is foregrounded**
- Battery: wakelock only while playing; auto-pause when headphones are unplugged

**Files & conversion**
- Add files / multiple files via SAF; **recursively** scan a folder (no permission needed)
- **Open `.c2` files from outside** (single & multiple intent-filters) → added to the list and played
- **Convert any audio to `.c2`** (mp3/aac/m4a/opus/ogg/wav/flac…) using the device's own
  codecs (MediaCodec) — **no external library**. Pick a size↔quality mode; the output is
  written next to the original (auto-numbered on collision)
- **Share as WAV**: decodes `.c2` back to a normal WAV and shares it (tiny built-in ContentProvider)
- Reorder the list + long-press menu (no delete); **Info** dialog shows file size
- Live **Log / Console** screen (prints conversion steps)

**Design** — animated gradient background, glowing/pulsing controls, smooth blue↔pink colour
shift, accent stripe on the playing row, marquee for long names, press-scale animation,
mode-coloured labels — all library-free, pure canvas/animator.

## 📦 Why is it so small?

| Part | Size |
|---|---|
| `libcodec2player.so` (armeabi-v7a, encode+decode) | ~263 KB |
| `classes.dex` (the whole app) | ~10 KB |
| res + manifest + signature | ~3 KB |
| **Total ≈ installed size** | **~300 KB** |

- **Only the Codec 2 voice core** is compiled; the FreeDV radio modems (OFDM/COHPSK/FSK/FDMDV)
  and the LDPC tables (`H_*.c`, megabytes) are **left out**. Encode is essentially free on top of
  decode (decode-only `.so` 268,544 B → encode+decode 268,800 B).
- **`extractNativeLibs=false`** → the `.so` is mmap'd from the APK, not copied on install.
- **`android:debuggable="true"`** → `run-from-apk`; the system skips `dex2oat`/`oat`, so installed
  size ≈ APK size. (Personal-use trick; not recommended for store distribution.)
- Single ABI, R8 + `shrinkResources`, v1-only EC signature.

## 🔨 Building

It's a standard Android project (AGP 8.0.2, compileSdk 33, minSdk 21):

```bash
./gradlew assembleRelease
```

It was developed with a portable, **offline** toolchain (bundled JDK + SDK + Gradle) driven by a
`Derle.ps1` script that also runs `zipalign -p` + `apksigner` (v1-only, EC) and applies the
minimal-installed-size settings above.

### How the native `.so` is built

`libcodec2player.so` is the **voice-only subset** of [Codec 2](https://github.com/drowe67/codec2)
1.0.x plus the JNI bridge in `src/main/cpp/Codec2JNI.c`, compiled with NDK clang (single ABI,
`-O2`, `--gc-sections`) and stripped. Codebook `.c` files are generated from `.txt`
(`native/gen_cb.py`). The steps live in `native/build_jni.ps1`. (The Codec 2 source itself comes
from upstream; this repo ships the prebuilt `.so`.)

## 🌍 Translations

The UI is fully localised. Strings live in `res/values/strings.xml` (English, default) and
`res/values-tr/strings.xml` (Turkish). To add a language, copy `strings.xml` into
`res/values-<lang>/` and translate the values — that's it. PRs welcome.

## 📜 License

- Application source (the original code in this repo): **MIT** — see `LICENSE`.
- The bundled **`libcodec2player.so`** is derived from Codec 2 and is licensed under
  **LGPL-2.1**, © David Rowe and contributors — <https://github.com/drowe67/codec2>.

## 🙏 Credits

- **David Rowe** and the Codec 2 team — the codec itself.
- The `.c2` file header (`C0 DE C2 …`) and the JNI approach were referenced from the
  [Codec2Recorder](https://github.com/scuttlebutt-tr/Codec2Recorder) project.
