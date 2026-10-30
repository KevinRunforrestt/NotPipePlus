Coding agent instructions for the **notPipe+** project — a YouTube client for legacy Android (1.5+).

## Project Overview

notPipe+ (`io.github.gohoski.notpipeplus` applicationId, app label "notPipe+") is a fork of
[notPipe](https://github.com/gohoski/notPipe) by gohoski, with extra features (universal
subscriptions import, self-healing avatars, subscriptions feed, local playlists, WebVTT
subtitles) added by Kevin_Runforrestt. The fork installs **alongside** the original app
(different applicationId, same code package `io.github.gohoski.notpipe`).

notPipe is a YouTube client targeting **Android 1.5 (API 3) and above**. It uses multiple
APIs (Invidious, yt2009, Piped, YtAPILegacy) with random instance selection plus
fallback chains to combat YouTube blocks. The code is written against old Dalvik
runtimes — respect the constraints below or the app will crash on real devices.

**Primary test device**: first-generation Kindle Fire (Android 2.3 Gingerbread, 512 MB RAM,
ARMv7, no SNI in TLS, TLS 1.0 only). If it works there, it works almost everywhere.

## Build & Sign (read this BEFORE running anything)

### Commands that actually work
```bash
export JAVA_HOME=<path-to-jdk8>            # JDK 8: newer javac breaks old AGP/gradle
export ANDROID_HOME=<path-to-android-sdk>  # build-tools 25.0.0
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=<writable-dir>    # avoid downloading gradle again
./gradlew assembleRelease --no-daemon
```

**IMPORTANT gotchas:**
- Judge success by the **`BUILD SUCCESSFUL` line in the log**, never by shell exit codes:
  leftover gradle/JVM processes frequently hang the calling shell minutes after the build
  already finished (or failed). Redirect output to a log file and grep it.
- Release builds run ProGuard (`minifyEnabled`, `shrinkResources`) — compile errors from
  obfuscation/reflection show up here, not at authoring time.
- The APK appears at `app/build/outputs/apk/notPipePlus-<version>-release-unsigned.apk`.

### Signing for Android <4.0 (mandatory, or the device refuses to install)
Old Dalvik requires a **v1 (JAR) signature with SHA-1**; SHA-256 v1 signatures need API 18+,
and v2-only signatures need API 24+. Recipe:
1. `zipalign -f 4 unsigned.apk aligned.apk` **first** (v1 signing invalidates alignment).
2. Sign with `apksigner` (from build-tools 25.0.0) run with the **JDK 8 `java` binary** —
   running it with a modern JDK throws `sun.security.x509` errors:
   ```bash
   $JDK8/bin/java -jar $SDK/build-tools/25.0.0/lib/apksigner.jar sign \
     --ks notpipeplus.keystore --ks-pass pass:notpipeplus --key-pass pass:notpipeplus \
     --min-sdk-version 3 --v1-signing-enabled true --v2-signing-enabled false aligned.apk
   ```
3. Verify: `apksigner verify --print-certs` and check that `META-INF/*.SF` says
   `SHA1-Digest` (not `SHA-256-Digest`).

### Keystore
The signing key lives in the repo root: `notpipeplus.keystore`, alias `notpipeplus`,
store AND key password `notpipeplus` (JKS format, RSA 2048, generated 2026-10-29).
It is NOT secret (public app, self-signed); commit/keep it with the source so update
builds keep installing over each other. NOTE: this key was regenerated after a
workspace loss — builds signed with the older lost key need an uninstall first.

### Android Studio (humans)
Android Studio 2.3.2 is the historical recommendation (simultaneously old and supported);
newer versions still build this project. compileSdk 25 / buildTools 25.0.0 work fine for a
minSdk 3 app — an old SDK is NOT required for development.

## Hard platform constraints (Android 1.5–2.3 target)

- **No modern Java APIs at runtime**: `String.isEmpty()` is API 9+, `getExternalFilesDir`
  is API 8+, `ActionBar`/`Fragment` are API 11+, `Surface.isValid` API 9+. Anything newer
  than API 3 must be reached through reflection guarded by `NotPipe.SDK` (see
  `hideSystemUI()`, `hasSoftwareKeys()` for the pattern) or simply avoided.
- **`NotPipe.SDK`** is the API level (`Build.VERSION.SDK`, parsed as int, because
  `Build.VERSION.SDK_INT` does not exist on 1.5). Use it, never `SDK_INT`.
- **No diamond operators, no try-with-resources**; generics are fine, switch-on-String
  compiles down safely. Anonymous inner classes implement callbacks everywhere — keep it.
- **No `ACTION_OPEN_DOCUMENT`**: use `ACTION_GET_CONTENT` (Android <4.4) or the built-in
  `FilePickerDialog` (see SubscriptionsActivity.startImport for the fallback chain).
- **AsyncTasks are serial on Gingerbread** (one background thread): always cancel them in
  `onDestroy()` and guard `onPostExecute` with a destroyed flag.
- **512 MB heap reality**: `ImageLoader` decodes with `inPurgeable` on API <11 and uses
  only 3 decode threads on purpose; loading banners at ~480 px is deliberate. Do not
  "optimize" these numbers without testing on-device.
- **OpenCORE** (`NotPipe.SDK < 8`, Android <2.2) needs extra layout/reload hacks — search
  for `isOpencore` before touching video layout code.
- **HTTPS on 2.3 has no SNI** and often no working TLS 1.2: prefer plain http URLs, or
  use `Manager.httpDowngrade()` when a frontend advertises https.
- SSL verification is globally disabled (`SSLDisabler`) because the instances use
  self-signed/expired certs; this is intentional legacy behavior.

## Architecture map

```
app/src/main/java/io/github/gohoski/notpipe/
├── NotPipe.java          # Application: init order ConfigManager → Manager → SSLDisabler
├── MainActivity          # Popular tab + search + Subscriptions feed tab (FixedTabHost)
├── VideoActivity         # Video page: playback, fullscreen, tabs (related/comments),
│                         #   subtitles overlay, comment replies, quality picker
├── ChannelActivity       # Channel page: Popular/Videos/Playlists tabs (survives rotation)
├── ChannelPlaylistActivity # Videos of one public or local playlist
├── SubscriptionsActivity # Subscriptions manager + universal import/export
├── PlaylistsActivity     # Local playlists manager
├── SettingsActivity      # Instances editor + playback/feed settings
├── api/
│   ├── Manager           # Singleton: instance pools per capability, stateful fallback
│   │                     #   proxies (dynamic Proxy), dead-instance removal, caption
│   │                     #   winner memory, HQ-capable list (getHqInstances)
│   ├── Metadata          # Interface: search/video/comments/channels/subtitles/...
│   ├── VideoStream / Conversion / ChannelApi  # capability interfaces
│   ├── Invidious / Piped / Yt2009 / YtApiLegacy # implementations
│   └── RssFeed           # Invidious /feed/channel/{id} parser (subscriptions feed)
├── FeedManager           # PROCESS-WIDE feed refresh coordinator (survives rotation):
│   │                     #   3 workers, 30 s budget, RSS-first, disk cache
├── FeedLogic             # Pure feed logic: merge, staleness, (de)serialization
├── Subscriptions         # {id,name,avatar} entries in internal-storage JSON
├── Playlists             # {name, ids[]} entries in internal-storage JSON
├── VideoCache            # Last video + resolved stream (single-video memory cache)
├── config/               # SharedPreferences-backed Config + ConfigManager singleton
├── http/                 # HttpClient (timeout/UA/error bodies/gzip), HttpRequest+Builder
├── ui/                   # Adapters + custom views used from the layout XMLs:
│                         #   AdapterLinearLayout, PullToRefreshLayout, FixedTabHost,
│                         #   AspectRatioVideoView, AspectRatioImageView,
│                         #   EllipsizingTextView, FilePickerDialog, InstanceSection, ...
├── util/
│   ├── ImageLoader       # Global bitmap cache (Hashtable, 30 entries, 3 decoders)
│   ├── ChannelIconResolver # Process-wide channel-icon cache + background fallback
│   ├── ConnectivityMonitor # CONNECTIVITY_ACTION watcher: offline->online
│   │                     #   transitions re-trigger the failed screen
│   ├── InstancesUpdater  # Fetches the instance lists from a remote URL
│   ├── SubscriptionsImporter # Universal CSV/JSON/regex subscription parser
│   └── VttSubtitles      # Minimal WebVTT parser, binary-search cue lookup
└── data/                 # Immutable models (VideoInfo/Video/Comment/Channel/...)
```

### Things that are the way they are for a reason
- **NNJSON (`cc.nnproject.json`)** is a bundled third-party library — never modify it.
- **Offline handling**: `Utils.waitForConnection` waits at most 10 s for a
  transient drop, then throws `IOException("No internet connection")` (a
  bounded wait — the old endless one wedged the single Gingerbread
  AsyncTask thread behind an infinite spinner). `ConnectivityMonitor`
  (armed in `NotPipe.onCreate`) turns the next offline->online transition
  into `onConnectionRestored()` on the visible activity, which retries only
  the loads that FAILED — finished screens are never reloaded. Feed
  retries use reason `REASON_RECONNECT` (full re-check, silent UI).
- **Sticky winners**: the Metadata/ChannelApi/Popular fallback proxies
  remember the host of the last successful call (SharedPreferences
  `instance_cache`) and try it first on the next proxy creation. A dead
  remembered host just falls through to the random walk — same idea as
  the caption winner memory; keep both in sync.
- **HTTP gzip**: `HttpClient` sends `Accept-Encoding: gzip` and wraps
  `Content-Encoding: gzip` bodies in a `GZIPInputStream`. Servers that do
  not compress are unaffected. API 3-era HttpURLConnection does NOT add
  gzip by itself — do not remove the explicit header.
- **Channel page parallelism**: the channel metadata (AsyncTask) and the
  Popular-tab list (raw `ch-popular` thread) load side by side, because
  two AsyncTasks would serialize onto one worker on Gingerbread. The
  prefetch result parks in `popularVideos` until `setupTabs()` runs.
- **Instance pools**: Piped/Invidious serve only 360p (they ignore the quality
  argument); only yt2009/YtApiLegacy instances live in `hqInstances`. Quality selection
  must go through `VideoActivity.instanceServesQuality` / `Manager.getHqInstances`.
- **Feed refresh lives in static scope** (FeedManager), not in the Activity, so rotation
  on 2.3 cannot kill it. Observers re-attach in onResume; results park in
  `pendingResult` across the rotation gap.
- **Dead-instance handling** (`Manager.removeDeadInstance`) toasts from the main looper
  and prunes every pool by host.
- **Subscriptions/Playlists/feed cache are internal-storage JSON** (`openFileOutput`):
  uninstalling the app loses them; there is no SD-card copy except the CSV export.
- **Comment replies** carry an absolute `repliesUrl` plus a `backend` tag; the parse is
  routed back through an instance of the same frontend (`Manager.parseCommentReplies`).
- **Subtitles** are raced: several candidate URLs (advertised + every Invidious captions
  endpoint + http downgrades) are downloaded in parallel and the first WebVTT that
  parses wins; the winner is remembered in SharedPreferences.
- Layout XMLs exist in `layout/`, `layout-v4/`, `layout-large-land/` variants and the
  custom `ui/*` views are referenced by full class name from XML — renaming any of them
  breaks the resource merge.

## Code Style Guidelines

### Naming and formatting
- Packages `io.github.gohoski.notpipe.*` (lowercase), classes PascalCase, methods and
  fields camelCase, constants UPPER_SNAKE_CASE. 4-space indent, ~120 col lines.
- Keep comments short (1–3 lines): explain the WHY of a workaround, not the WHAT of the
  code. Every comment older than the fix it describes is a liability.

### Error handling
- Checked `IOException` for network work; `ContentUnavailableException` means "the
  content genuinely does not gone / private" and must NOT trigger instance switching.
- Background failures: `e.printStackTrace()` + return null; user-visible failures:
  `Toast` from the UI thread; per-row try/catch in list parsers (one bad row never
  kills the feed).

### Memory management
- `ImageLoader.clearCache()` + `System.gc()` when leaving heavy screens; `onLowMemory`
  overrides exist in every activity — keep them.

### Adding a feature
1. Model in `data/`, capability interface in `api/` if a frontend must expose it.
2. Implementation in each API class that can serve it; `throw new IOException(...)` in
   the ones that cannot (the Manager proxy falls through automatically).
3. UI in the matching Activity + strings in BOTH `values/strings.xml` and
   `values-ru/strings.xml` (`MissingTranslation` is disabled, but keep parity).
4. Reflection-guard anything above API 3 and test on the target device.
