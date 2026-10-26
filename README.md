<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio Enhanced" width="320" />

  <h1>Nuvio Enhanced 2</h1> 

  <p>
    An advanced, feature-packed fork of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a> that keeps pace with upstream while introducing a pure <b>CloudStream 3 (CS3)</b> & <b>Stremio</b> engine, out-of-the-box tracking integrations, hardware volume boost, and deep platform polish.
    <br /><br />
    Bring your own sources. Nuvio Enhanced transforms them into a unified streaming library with rich artwork, ratings, synchronized subtitles, and your progress saved across all devices.
  </p>

  <p>
    <a href="https://github.com/techforfriend/Nuvio-enhanced-2/releases/latest">
      <img src="https://img.shields.io/github/v/release/techforfriend/Nuvio-enhanced-2?color=blue&label=Latest%20Release&style=for-the-badge" alt="Latest Release" />
    </a>
    <img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green?style=for-the-badge" alt="Platform" />
    <img src="https://img.shields.io/badge/License-GPL--3.0-orange?style=for-the-badge" alt="License" />
  </p>

</div>

> [!IMPORTANT]
> **Unofficial Fork:** This build is independently maintained and is not officially affiliated with or endorsed by the upstream Nuvio team. Please report any issues or feature requests directly to **this** repository.

---

## 📥 Download & Install

### Android
Download the latest pre-compiled APK directly from the releases page:
- 📲 **[Download Latest Android APK](https://github.com/techforfriend/Nuvio-enhanced-2/releases/latest)**

### iOS (AltStore / SideStore / LiveContainer)
Grab the latest IPA build from the releases page and sideload via your preferred tool:
- 🍏 **[Download Latest iOS IPA](https://github.com/techforfriend/Nuvio-enhanced-2/releases/latest)**

---

## ✨ What Nuvio Enhanced Adds

Everything listed below has been built and refined on top of upstream Nuvio Mobile:

### 🔌 Next-Gen Extensions & Providers Engine

| Feature | Description | Where |
|---|---|---|
| **Pure CloudStream 3 (CS3) Architecture** | Full native integration with CloudStream 3 repositories and plugins. Clean, stable, and lightweight memory footprint. | Settings → Extensions → CloudStream |
| **Repository Master Toggles** | Turn entire extension repositories on or off with a single master toggle, or manage individual plugins granularly. | Settings → Extensions → Repositories |
| **Stremio Addon Ecosystem** | Native support for Stremio community and official addons for catalog discovery and multi-source streaming. | Settings → Extensions → Stremio Addons |
| **Home Screen Extension Selector** | Switch between all active extensions or choose a specific extension to instantly display dedicated catalog shelves. | Home Screen header dropdown |
| **Ordered Catalog Shelves** | Catalogs load concurrently with strict priority ordering: Stremio addons first, followed by CloudStream plugins. | Home Screen |

---

### 🎯 Out-of-the-Box Tracking Accounts

All major tracking platforms are pre-configured with verified credentials so you can link your account instantly without "Missing credentials" errors:

| Tracker | Authentication & Sync Capabilities | Verification Link |
|---|---|---|
| **Trakt.tv** | Full watch progress, continuous watching sync, scrobbling, and personal lists. Supports both Quick Device Code and Browser OAuth. | `https://auth.trakt.tv/activate` |
| **SIMKL** | Complete watch history and watchlist synchronization for Anime, TV Series, and Movies via fast PIN authorization. | `https://simkl.com/pin` |
| **MDBList** | Direct Device Code authorization to sync your custom MDBList collections, personal lists, and watch progress. | `https://mdblist.com/oauth/device/` |
| **AniList** | Native anime tracking, episode progress, list statuses (Watching, Planning, Completed, etc.), and score syncing. | Settings → Tracking → AniList |
| **WeTrakr** | Deep synchronization for continue watching, watch history, lists, and ratings. | Settings → Tracking → WeTrakr |
| **IntroDB Skipping** | Automated intro and outro skipping for supported anime and television series. | Automated during playback |

---

### 🎬 Ultimate Video Player & Audio

| Feature | Description | Where | Default |
|---|---|---|---|
| **Digital Volume Boost (Up to 200%)** | Hardware-accelerated Loudness Enhancer for ExoPlayer and digital boost for MPV with a distinct amber indicator. | Swipe up past 100% on right side | Always on |
| **Fast Seekbar Thumbnails** | Scrubbing preview frames decoded with high performance using a native Rust decoder. | Settings → Playback → **Seek Bar Thumbnails** | On |
| **Tap-to-Seek & Scrubbing** | Tap anywhere on the progress bar to jump directly, with live buffered range indication. | Player screen overlay | Always on |
| **Swipe to Seek Toggle** | Prevent accidental timeline scrubbing during touch gestures while preserving volume and brightness controls. | Settings → Playback → **Swipe to Seek** | On |
| **Stream Quality Selector** | Dynamic HLS stream quality picker with resolution badges on both legacy and modern layouts. | Player screen overlay | Best available |
| **Playback Info Inspector** | Real-time technical readout showing video resolution, audio codec, bitrate, and container info. | Player screen overlay → **Info** | — |
| **Subtitle Sync by Ear** | Tap "Heard" when spoken and "Saw" when shown to automatically calculate and apply the subtitle offset. | Subtitles panel → **Sync by ear** | — |
| **Picture-in-Picture & Mini Player** | Smooth PiP window switching or in-app floating mini player for multitasking. | Player header / Settings → Playback | On |
| **Private Viewing Mode** | Play any title without recording watch progress or scrobbling to connected trackers. | Settings → Playback → **Private Viewing** | Off |
| **Custom Playback Buffers** | Configure min, max, start, and stall buffer limits to prevent buffering on slower connections. | Settings → Playback → **Custom Playback Buffers** | Optimized |

---

### 📺 Live TV (IPTV)

Upstream Nuvio lacks Live TV support. Nuvio Enhanced integrates an entire IPTV suite:

| Feature | Where | Status |
|---|---|---|
| **M3U / M3U8 Playlists** | Settings → Integrations → Live TV → **Playlists** | Fully supported |
| **Xtream Codes API** | Settings → Integrations → Live TV → **Providers** → Xtream | Server, user & password |
| **Stalker Portal** | Settings → Integrations → Live TV → **Providers** → Stalker Portal | Portal URL & MAC |
| **TV Guide (EPG)** | Settings → Integrations → Live TV → **TV Guide (EPG)** | Auto-detected & custom XMLTV |
| **Navigation Tab** | Automatically appears in navigation when at least one playlist or provider is configured. | Dynamic |

---

### 🎨 Personalization & Visual Polish

- **Custom HSV Theme Picker:** Full custom color picker with Saturation/Value palette and Hue slider to style the entire app to your exact preference.
- **Hero Trailer Autoplay:** Stream high-definition trailers right in the hero carousel with customizable delay, sound defaults, and aspect-ratio letterbox zoom.
- **Dynamic Artwork Backgrounds:** Atmospheric gradient blending matching the active movie or show artwork.
- **Biometric Security:** Unlock PIN-locked profiles with fingerprint or Face ID for local privacy.
- **Episode Navigator:** Sticky series search bar to filter by episode title, plot, season, or rating.
- **In-App Ratings (1–10 Stars):** Rate media directly on detail pages and sync scores back to your connected trackers.

---

## 🛠️ Build from Source

### Android Build
Requires Android Studio, JDK 17, and Cargo (for native seekbar Rust components):
```bash
# Add Rust Android targets (one-time setup):
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
cargo install cargo-ndk --locked

# Assemble Release APK:
./gradlew :androidApp:assembleFullRelease
```

### iOS Build
Requires macOS, Xcode, and CocoaPods:
```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -sdk iphoneos \
  -derivedDataPath build/ios-derived-full \
  CODE_SIGNING_ALLOWED=NO \
  build
```

---

## Credits

Nuvio is built by [NuvioMedia](https://github.com/NuvioMedia) — all credit for the app itself belongs to them and its
contributors. This repository only adds to their work. If you enjoy Nuvio, [support the upstream project](https://nuvio.tv/support).

## License

[GNU General Public License v3.0](./LICENSE) — same as upstream.
