# AIOStreams (ported code)

Source: https://github.com/Viren070/AIOStreams (`packages/android/exoplayer`)
License: GNU Affero General Public License v3.0 (see `LICENSE` in this folder)

Files ported from AIOStreams, used when "Use libmpv's libass for ASS/SSA subtitles" is enabled:

| Nuvio path | AIOStreams path |
| --- | --- |
| `androidApp/src/main/cpp/ass_surface.c` | `exoplayer/src/main/cpp/ass_surface.c` |
| `androidApp/src/main/cpp/ass/ass.h`, `ass_types.h` | `exoplayer/src/main/cpp/ass/` (libass public headers, ISC) |
| `androidApp/src/main/cpp/CMakeLists.txt` | `exoplayer/src/main/cpp/CMakeLists.txt` |
| `composeApp/.../player/aiostreams/AssSurface.kt` | `exoplayer/.../AssSurface.kt` |
| `composeApp/.../player/aiostreams/AttachedFonts.kt` | `exoplayer/.../AttachedFonts.kt` |
| `composeApp/.../player/aiostreams/FontNames.kt` | `exoplayer/.../FontNames.kt` |
| `composeApp/.../player/aiostreams/Matroska.kt` | `exoplayer/.../Matroska.kt` |
| `composeApp/.../player/aiostreams/SampleDependencies.kt` | `exoplayer/.../SampleDependencies.kt` |
| `composeApp/.../player/aiostreams/Subtitles.kt` | `exoplayer/.../Subtitles.kt` |

Modifications:

- Package changed to `com.nuvio.app.features.player.aiostreams`, and the JNI symbol prefix in `ass_surface.c` changed to match.
- `CMakeLists.txt` builds only the `aiostreams_ass` library.
- `Subtitles.kt`: `Silent` mutes only ASS/SSA; other subtitle formats are parsed by Media3's default parsers.
- `AiostreamsAssEngine.kt` is new Nuvio glue replacing the parts of AIOStreams' `ExoEngine.kt` and `Extractors.kt` that drive these files.
