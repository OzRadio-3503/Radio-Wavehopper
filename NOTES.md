# Billy Bob's Radio Tuner — notes for anyone changing the code

Read this before changing anything — person or AI assistant. Much of what
makes the app work is not obvious from the code, and several "tidy-ups" that
look harmless would quietly bring old bugs back. Build instructions are in
`README.md`.

Last updated: 25 Nov 2026 (v1.2.1, dial layout 8). Several older sections below have been
corrected in place; the newest behaviour is summarised in section 10.

---

## 1. How the app fits together

There are **three players** that share one station list.

| Part | File | What it is |
|---|---|---|
| Phone screen | `app/src/main/assets/index.html` | The whole tuner UI (dial, presets, volume, sleep timer, real-frequency toggle) in one HTML page, shown full-screen in a WebView. The same file is also the **web version**. |
| Phone audio | `PlaybackService.java` | Plays the phone's audio. Uses **classic ExoPlayer 2.18.2** (`com.google.android.exoplayer2.*`) with OkHttp. Also owns the notification, lock-screen controls and sleep timer. |
| Android Auto | `RadioLibraryService.java` | A separate player for the car. Uses **Media3 1.3.1** (`androidx.media3.*`) with OkHttp. Handles the car's station list, search, voice search and resume. |
| Bridge | `MainActivity.java` | Hosts the WebView and exposes `window.AndroidBridge` to the page (`playStation`, `pauseStation`, `getVolume`/`setVolume`, `setSleepTimer`, `identifySong` → opens Shazam, and so on). |
| Shared | `StationLibrary.java` | The station list the two native players use, the group list (`GROUPS`), and the frequency-migration maps. |
| Android Auto | `StationArtworkProvider.java`, `LogoFetcher.java`, `StationWebsites.java` | Station logos for the car (see section 4). |
| Shared | `StreamHeaders.java` | Picks the User-Agent sent to each stream host (see rule 3). |
| Shared | `LiveResume.java` | Decides when "play" must reconnect to live (see rule 5). |

- **Inside the app**, the page never plays audio itself. It calls `AndroidBridge.playStation(freq, name)`, and `PlaybackService` plays.
- **On the website** there is no `AndroidBridge`, so the page falls back to its own `<audio>` element. The page checks this with `isNativeApp = !!window.AndroidBridge`.
- **Two ExoPlayer libraries:** the phone and the car really do use different ones, on purpose. Classic ExoPlayer was kept for the phone during the StreamTheWorld investigation. Don't mix their imports. Moving the phone onto Media3 is possible, but it's a deliberate project, not a drive-by change.

---

## 2. The station list (read before adding, removing or moving a station)

### It exists twice, and both copies must match

- **`index.html`** has the `stations` array: `{freq:87.5, name:"talkSPORT", real:"1089 AM", url:"..."}`.
- **`StationLibrary.java`** has `getStations()`: `stations.add(new Station(87.5, "talkSPORT", "1089 AM", "..."));`.

Same frequency, same name, same real frequency, same URL, same order, in both files. If they differ, the phone dial and the car disagree about what's on a frequency.

### Dial rules

- The dial runs **87.50 to 102.10**. Stations started on 0.1 steps; since the two-decimal dial (see below) a station can sit between two others (e.g. 87.55), so adding one no longer means removing one. 159 built-in stations as of 1.2.1.
- **Within each group, stations are in real-frequency order:** AM stations first (lowest to highest), then FM, then DAB only, then Internet only. Keep that order when adding or moving a station.
- **Groups go up the dial in this order:**
  1. Sport
  2. Talk
  3. NSW (and ACT)
  4. Victoria
  5. Queensland
  6. South Australia
  7. Western Australia
  8. Tasmania
  9. Northern Territory
  10. Overseas and national stations by country: Australia (national), New Zealand, Pacific, Singapore, South Africa, UK, France, Germany, USA, each its own group (the headings are just the country name)
  11. Internet only

  Each group starts with a `// ---- Group ----` comment line, in both files.
- **The group list exists twice too:** `STATION_GROUPS` in `index.html` (for the speakers) and `GROUPS` in `StationLibrary.java` (for Android Auto's group buttons and the logo colours). Each entry is the group's name and its **first station's frequency**. If stations move, or a group is added, removed or renamed, update both lists to match the headings. The page warns in the browser console if a group start isn't on a station.
- **`hls:true`** is only in the page, and every `.m3u8` station needs it. The website plays HLS through hls.js only when this flag is set; without it, the stream goes to a plain `<audio>` element, which most desktop browsers can't play. The native players in the app detect HLS by themselves, so missing it only breaks the website.
- **`real`** is in both files: the page's `real:` and the third argument of Java's `Station(...)`. It's the station's real-world frequency, shown on the phone when you tap the frequency display, and in Android Auto in each station's title. Use the main analogue frequency (`"873 AM"`, `"104.1 FM"`), or `"DAB only"` / `"Internet only"`.
- **Optional extras on a `Station`:**
  - `mimeType`: set `"application/x-mpegURL"` when an HLS stream's URL doesn't end in `.m3u8`, such as a relay link. SEN 1170 has it pinned, which is harmless.
  - `forceMediaPlayer`: a legacy fallback, currently unused.
- **`stations_list.txt`** is a generated, human-readable copy. Regenerate it after changes; never edit it by hand as a source.

### Moving stations needs a "layout" migration

Presets (page `localStorage` `radio_presets`) and the car's last station (`SharedPreferences` `RadioAutoPrefs` → `last_station_id`, e.g. `station_88.5`) are saved **by frequency**. If stations shift, saved values must move too, or presets land on the wrong station.

Each reshuffle gets a new layout number. The current layout is **8**.

| Layout | Change |
|---|---|
| 1 → 2 | Dial reorganised into groups |
| 2 → 3 | Guerilla Cricket added at 88.0; RNZ Concert removed |
| 3 → 4 | SEN Fanatic added at 88.1; China Radio International removed |
| 4 → 5 | talkSPORT and talkSPORT 2 added at 88.2 and 88.3; World Radio Network and Double J removed |
| 5 → 6 | SKY News Radio moved to 88.9 to group with Australian talk/news; international talk shifted up |
| 6 → 7 | Stations reordered within sections by real frequency (AM/FM/DAB/Internet) |
| 7 → 8 | Pop 91.3 moved from Internet only into USA as 91.3 FM (KMHS, Coos Bay, Oregon) |

To add layout 9:
1. Make the station changes in **both** files, and update both group lists if any group's first station changed.
2. Build an **old → new frequency map** covering only the stations that moved.
3. **Page** (`migratePresets()` in `index.html`):
   - Add `const L8_TO_L9 = {...}`.
   - Change the early-return check from `'8'` to `'9'`.
   - Add `if (from < 9) move(L8_TO_L9);` after the existing steps. Each step only runs for older layouts, so every preset moves exactly once.
   - Save `radio_layout` as `'9'`.
4. **Java:**
   - In `StationLibrary`, add `LAYOUT8_TO_LAYOUT9` and `layout9FreqFor()`.
   - In `RadioLibraryService.migrateSavedStation()`:
     - Bump the `>= 8` check and the `putInt(KEY_LAYOUT, 8)` to 9.
     - Wrap the existing `layout8FreqFor` call in `if (layout < 8)`.
     - Add the `layout9FreqFor` step.
5. **Check the defaults** still point at the intended stations:
   - The default presets `[100.2, 102.1, 101.5]` appear several times in `index.html`.
   - The first-launch station is `render(90.3, ...)`, which is 2BRW.
6. A removed station's preset simply stays on its frequency and lands on whatever station is there now. That's accepted.

A small station change that doesn't shift anything doesn't need a new layout: swapping a URL, fixing a `real:` label, or renaming a station.

---

## 3. Stream rules (hard-won — don't undo)

1. **StreamTheWorld / Triton** (`*.streamtheworld.com`, `*.tritondigital.com`; 2GB, Power 100, talkSPORT and others) must get the **honest app User-Agent**, `BillyBobsRadioTuner/1.0 (Linux; Android …)`. With a fake Chrome UA, they cut the stream after about 2 seconds of audio.
   - `StreamHeaders.userAgentFor(uri)` picks the UA. Every other host gets a browser UA, because some hosts reject anything else.
   - The UA is added per request with `ResolvingDataSource`. **Never call `setUserAgent()` on the OkHttp data-source factory.** It silently overrides the per-host UA. This already caused one false test result.
2. **HLS needs the HLS module.** It's `exoplayer-hls` (classic) and `media3-exoplayer-hls` (car). Without it, `.m3u8` stations crash with "No suitable media source factory found". `setMediaItem` is wrapped in try/catch so one bad station can't crash the app.
3. **Live streams "end" when the server drops them.** Both players reconnect instead of moving on, with the reconnects capped. The phone reconnects on `STATE_ENDED`; the car reconnects on an `AUTO` media-item transition (it runs in `REPEAT_MODE_ALL`).
4. **Behind the live window:** live HLS stations only keep a short stretch of audio on the server. If playback falls behind it (a long stall, slow network), ExoPlayer fails with `ERROR_CODE_BEHIND_LIVE_WINDOW`. Both players catch that in `onPlayerError` and jump back to live with `seekToDefaultPosition()` + `prepare()`, at most 5 times a minute. The car must not treat it as a dead station and skip to the next one.
5. **Stall watchdog:** both players reconnect a stream that says it's playing but isn't progressing. It only runs while `playWhenReady` is true, and the car gives a new station a grace period before the first audio.
6. **MP3 file-header ID3 is disabled** (`Mp3Extractor.FLAG_DISABLE_ID3_METADATA`) on the phone. It was a workaround from the StreamTheWorld investigation; it's left in place because it does no harm. It doesn't affect song titles, which come from Icecast/ICY `StreamTitle` and HLS ID3 frames (see section 10).
7. **Plain `http://` streams** are allowed (`usesCleartextTraffic="true"` in the manifest). The **web version** served over https can't play them, though. Browsers block mixed content, so prefer `https://` links where a station offers one.
8. **No relay:** the old relay server (`billybobtuner.onrender.com`) is gone; every station URL is direct. The page's website-only fallback uses `corsproxy.io`.
9. **Merging user data:** built-in edits and hides are keyed by the built-in's original frequency and sealed with its name (`names`), so if a built-in moves, saved edits follow it by name. User-added stations are checked for duplicate URLs and clashing dial spots (`mergeUserStations()` in index.html, `StationLibrary.addUserStations()` in Java). Saved data is never destroyed by this.

## 4. Android Auto rules

- **Never call `player.stop()` in `RadioLibraryService` to reconnect.** `stop()` puts the player in IDLE, which Android Auto reads as "finished": the car leaves Now Playing and drops back to the station list. Reconnect with `seekToDefaultPosition(index)` + `prepare()` + `play()`, as `reconnectCurrentStation()` does.
- **Search:**
  - `onSearch` must call `session.notifySearchResultChanged(...)`, or the car spins forever.
  - Results are paged in `onGetSearchResult`.
  - The search text is normalised, so number words become digits ("two GB" → "2GB").
- **Resume after the car restarts:** the last station is saved in `RadioAutoPrefs`/`last_station_id`. `onPlaybackResumption()` and `onCreate()` restore the full playlist at that station.
- **Car takes over:** when the car starts playing, any phone sleep timer is cancelled (`cancelPhoneSleepTimer()`).
- **Group buttons:** Now Playing has two extra buttons, previous group and next group, next to the standard skip buttons. They're Media3 custom commands (`CMD_PREV_GROUP`/`CMD_NEXT_GROUP`), allowed in `onConnect`, shown with `setCustomLayout`, handled in `onCustomCommand` → `jumpGroup()`. They land on a group's first station and wrap round, and previous group works like "previous track" (start of this group first, then the group before), the same as the phone's left speaker. Like everything in the car, they move with a seek, never `stop()`.
- **Stream metadata in the car is filtered, not raw:** streams' "now playing" tags used to change the session metadata every few seconds and made Android Auto's Now Playing flash. Now `ForwardingPlayer.getMediaMetadata()` returns the station's own metadata plus at most one cleaned song line (`onStreamSong` → `SongText`, no more often than every 20 s, `SONG_MIN_GAP_MS`), and `CarQuietListener` drops loading flips and repeat timeline/metadata updates before they reach the car. Don't pass raw stream metadata through.
- **Second line in the car:** the station's group name (Sport, Talk, NSW (and ACT) ...), replaced by the song title when the stream sends one, and for 5 s after the band button by the band name ("AM band", "FM band", "DAB+ & Internet band", "All bands").
- **Steering-wheel hold:** the session gets the player through a `ForwardingPlayer`. Its `seekForward()` (fast forward) and `seekBack()` (rewind) change band, forward and back round All → AM → FM → DAB+ & Internet (`stepBand()`, shared with the band button), with the band name shown for 5 s. (Before 1.2.2 they jumped groups/thirds like the screen buttons, which still do.) Many cars send these on a long press of next and previous. Fast forward and rewind are always offered, even though live radio can't seek. Every next, previous, fast forward and rewind the car sends is logged (tag `RadioLibraryService`), to see what a particular car does.
- **Station logos:** every station item has an artwork address from `StationArtworkProvider.uriFor(station)` (`content://com.billybobs.radiotuner.artwork/station/<name>.png`).
  - If `app/src/main/assets/logos/<slug>.png` exists, it's used, centred on white. The slug is the station name in lower case, with anything that isn't a letter or digit turned into `-`: `2GB Sydney` → `2gb-sydney.png`, `SEN 1170` → `sen-1170.png`. Logos are keyed by name, so they survive reshuffles.
  - Otherwise, the station's **real logo from its website**, if one has been found. `StationWebsites.java` lists each station's site, by name. `LogoFetcher.java` runs on the phone in the background, starting when Android Auto connects. It reads the site's declared icons (apple-touch-icon, then the largest icon, then a roughly square og:image), then `/apple-touch-icon.png`, then Google's favicon service. It takes the first image at least 96 px across and keeps it in the app's cache. A station with no usable logo is retried after a week; found logos are refreshed after a month. When new logos arrive, the car's station list is refreshed, and the logo address's `v=` value changes so Android Auto doesn't reuse the old tile. To fix a wrong or poor logo, correct the site in `StationWebsites.java`, or override it with an `assets/logos/` file.
  - Otherwise a tile is drawn: the station name on its group's colour.
  - **Logos show no frequency**, because Android Auto already shows the real frequency in each station's title. The logo address changes with `STYLE_VERSION` and the group, so the car picks up new images.
  - Logos are cached in the app's cache folder. Change `STYLE_VERSION` if you change the drawn design.
  - The provider is exported because Android Auto, a separate app, has to open the images. It only ever serves station images.

## 5. Resume = live, like a real radio (`LiveResume.java`)

- **The rule:** after a pause, a phone call, or the car switching off, pressing play must give what's on **now**, not where it stopped. Without this:
  - Plain streams (like 2SM) replay their queued-up backlog, which can be an hour old.
  - HLS stations (ABC, SEN, BBC) carry on from the paused point in the station's rewind window, permanently behind.
- **The trigger:**
  - Going quiet for 5 seconds or more triggers a jump to live on resume. Quiet means `playWhenReady` false, playback suppressed by a call or navigation, or the player IDLE.
  - Pauses shorter than that resume instantly.
  - A new station resets the timer.
- **How each player jumps:**
  - The **phone** jumps by re-setting the media item, so it gets a brand-new connection.
  - The **car** jumps with `seekToDefaultPosition`, never `stop()` (see section 4).
- **The web version** doesn't have this yet.

## 6. Other features, and where they live

- **Pause on disconnect:** both players use `setHandleAudioBecomingNoisy(true)`. When Bluetooth, the car or headphones disconnect, the radio pauses instead of switching to the phone's speaker. Pressing play resumes live.

- **Volume slider:** inside the app, it drives the phone's media volume (`AudioManager.STREAM_MUSIC`), through `getVolume`/`setVolume`. It follows hardware volume keys via a ContentObserver. On the web, it sets the `<audio>` volume.
- **Sleep timer:**
  - Hold the RADIO WAVEHOPPER badge to cycle 120 / 60 / 30 / 10 minutes / off. The timer itself is native, in `PlaybackService`, with a 15-second fade, saved as `RadioPrefs`/`sleep_end_at`.
  - The page only shows it: a 10-second message, then a 30-second countdown at the end, when holding the badge extends the time.
- **Real frequency only** (Settings, **on by default**, `userData.realOnly`): the phone always uses the real AM/FM/DAB+/NET dial with Band Lock on, the BAND button is hidden, and the speakers jump in thirds of the band. Unticked, the old behaviour below applies.
- **Real-frequency toggle** (only with Real frequency only unticked): tap the frequency display. The choice is remembered in `localStorage` `show_real_freq`.
- **Real-band dial:** in real-frequency mode the dial window redraws as the station's real band, and flips band as you step between stations:
  - **FM:** 88–108 MHz, needle on the real FM frequency.
  - **AM:** 531–1701 kHz, labelled 6–17 (hundreds of kHz) like a car radio.
  - **DAB only:** a DAB+ Band III block scale (5–13), needle on the station's block. The block is the page-only `dab:` field, e.g. `dab:"9C"`. Sydney's ABC/SBS stations are on 9C, Nova's on 9B (SKY News Radio's 9B is inferred from its Nova link), UK national BBC on 12B, Sound Digital (talkSPORT 2) on 11A, and Germany's second national multiplex mostly on 5D. A DAB station without a `dab:` value shows the scale with no needle.
  - **Internet only:** a stream panel ("INTERNET RADIO") instead of a dial.
  - Every station on that band shows as a dot at its real frequency. Internet stations show as evenly spaced dots, in alphabetical order, along the stream panel. The code is `updateDial()` and `buildBand()` in `index.html`.
- **Band Lock** (the BAND padlock beside the presets): only in real-frequency mode; greyed out otherwise.
  - **Off** (the default at every launch): everything works as before. The arrows go through Billy Bob's dial in section order (e.g. through NSW/ACT), and touching the real dial goes back to the standard dial.
  - **On:** tuning follows real frequencies within the current station's band, across all sections. AM by kHz, FM by MHz, DAB+ by block (5A to 13F), Internet alphabetically; stations on the same frequency or block go alphabetically (`realOrder()`). The arrows (buttons, keys, headset next/previous) move one station per press and stop at each end of the band. Dragging the real dial or the stream panel tunes along the band and snaps to the nearest station (first alphabetically on a shared frequency).
  - Turned off by the speakers (which then jump by section, as before) and by leaving real-frequency mode. Presets still tune anything; the lock follows that station's band.
  - In real-frequency mode the digits roll like an odometer in the direction of travel (`rollReadout()`); Internet station names slide in instead.
  - **Band strip:** while locked, an AM | FM | DAB+ | NET strip under the dial jumps straight to a band. Each band opens on the last station tuned in it (kept in `band_last`), or its first station.
  - **Counter:** in the dial's corner, e.g. "FM · MHz · 12 of 61" (on the Internet panel: "INTERNET · 3 of 12"); counts only Australian stations when AU only is on.
  - The strip sits inside the dial window, and spacing tightens while it shows, so the song title still fits on short screens (checked down to 360×740 and a 564×690 Chromebook window).
  - Phone and web only: Android Auto keeps browsing by section.
- **Region:** every station has a `cc` code: the state for Australian stations (NSW, ACT, VIC, QLD, SA, WA, TAS, NT, or AU for national ones), otherwise the country (NZ, UK, US, CA, IE, ZA, SG, FJ, NC, FR, DE). It shows after the frequency on the phone ("MHz · NSW", "REAL · kHz AM · NSW") and in the Android Auto title ("2SM 1269 (1269 AM · NSW)"). Java keeps the same codes in `StationLibrary.Regions` (by station name) — **add both when adding a station**.
- **AU only** (the button beside BAND): skips overseas stations with the arrows, the dial, the speakers (sections with no Australian station are skipped) and the band strip, in every mode. Overseas dots are dimmed. Presets still tune anything. Remembered after closing (`au_only`). Phone and web only.
- **Dial drag:** each station passed gives a light buzz (needs the VIBRATE permission, now in the manifest). Hold your finger still on the dial for about 0.4 s to magnify it 6x: the window shows a close-up and the dial moves 6 times slower under your finger. Works on Billy Bob's dial and, with Band Lock on, the real AM, FM and DAB+ dials.
- **Speakers = group jumps** (categories dial; with Real frequency only they jump in thirds of the band, `goToBandThird()`; with "Only my chosen stations" in thirds of the list): tap the right speaker for the next group. The left speaker works like "previous track" on a CD player: from partway through a group it goes back to that group's first station, and from the first station it goes to the previous group. Either way it lands on a group's first station, wraps round at either end, and flashes the group name on the display. On a keyboard, PageUp and PageDown do the same. The code is `goToGroupStep()` in `index.html`, using `STATION_GROUPS`.
- **Song ID:** `identifySong` opens Shazam. The button shows only while a station is actually playing; it hides on pause (from anywhere) and on errors.

## 7. Saved state (what survives an update)

| Where | Key | What |
|---|---|---|
| Page `localStorage` | `radio_presets` | Preset frequencies |
| Page `localStorage` | `radio_layout` | Which dial layout the presets match (currently `'8'`) |
| Page `localStorage` | `show_real_freq` | Real-frequency toggle (categories dial only) |
| Page `localStorage` | `au_only`, `band_last` | AU only, last station per band |
| `UserStations` JSON (`user_stations_v1` on the web) | `added`, `hidden`, `edited`, `names`, `lists`, `activeList`, `carList`, `realOnly`, `sleepDefault` | Your stations and edits, the phone and car station lists, Real frequency only, default sleep |
| `RadioPrefs` | `sleep_end_at`, `last_station_title`, `is_playing` | Phone player state |
| `RadioAutoPrefs` | `last_station_id`, `station_layout` | Car's last station and its layout number |

All of these survive an update installed over the top. Android's "Clear storage" wipes them.

## 8. Known limits and open items

- **Geo-blocking:**
  - **87.9 BBC Radio 5 Sports Extra** only plays from a UK address. Common VPN addresses (e.g. M247) are blocked; Getflix Smart DNS was being looked at.
  - **88.1 SEN Fanatic** refuses Australian addresses in the browser and VLC, but has played in the app. Check that it still does with the VPN off.
- **talkSPORT** may replace live Premier League commentary outside the UK.
- **Nothing can be built or test-played from a sandbox** with no network access to stream hosts. A change to playback code is only proven once it's been built and played on a phone and in the car.

## 9. Checklist before handing back a change

- [ ] Station changes are made in **both** `index.html` and `StationLibrary.java`. Frequencies run 87.50 to 102.10 with no duplicates.
- [ ] If anything moved, there's a new layout migration on the page **and** in Java, and the default presets and first-launch station have been checked.
- [ ] `STATION_GROUPS` (page) and `GROUPS` (Java) still match the group headings.
- [ ] `stations_list.txt` has been regenerated.
- [ ] No `setUserAgent()` on the data-source factory, and no `stop()` in the car's reconnect paths.
- [ ] The page still loads with no JavaScript errors.
- [ ] Build the APK, then test the phone, the car and at least one HLS and one plain station.

## Screen-off dropouts (Wi-Fi lock)

- Some stations (KBRW 680 AM from Alaska, for one) dropped out every so often once the screen went off, recovered, then dropped again. The CPU wake lock was held, but Wi-Fi was left to drop into power-save, which starves a live stream from a distant server; the stall watchdog then reconnected and the cycle repeated.
- `PlaybackService` now holds a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY` on Android 10+, `FULL_HIGH_PERF` before) alongside the wake lock, taken when playback starts and released on pause/stop, for both ExoPlayer and the MediaPlayer path. `RadioLibraryService` (Android Auto) uses Media3's `setWakeMode(C.WAKE_MODE_NETWORK)`, which does the same. No new permission is needed (WAKE_LOCK covers it).
- On mobile data this makes no difference; if a station still drops with the screen on, the fault is the station's server.

## Two-decimal dial (4 Nov 2026)

- Frequencies now show with two decimals (88.70, 102.10), so a station can sit halfway between two others (87.55) without moving anything already on the dial. No layout migration was needed: every existing station kept its frequency, so saved presets and Android Auto's last station are unaffected. Arrows still step one station at a time.
- Added: **SEN 1116** (87.55, 1116 AM · VIC, `http://13.54.221.214:8000/sen.mp3`), **WNYC** (88.65, 820 AM · US, `https://fm939.wnyc.org/wnycfm`), **2UE 954** (90.05, 954 AM · NSW, LiSTNR HLS `https://sa46.scastream.com.au/live/2ue_128.stream/playlist.m3u8?dist=listnr-web`). 150 stations.
- The readout font was trimmed slightly so "102.10" fits on narrow phones (checked to 320 px wide).
- Android Auto search accepts "88.7", "88.70" and "88.65".

## Home-screen widget
`RadioWidgetProvider` (also `RadioWidgetSmallProvider`, a 2x1, and `RadioWidgetTinyProvider`, a 1x1 tap-to-play-pause tile; the 2x1 with just logo + play/pause; 4x1, resizable sideways): station logo, name, "On air / Paused · real freq · region", and
previous / play-pause / next. Tapping the logo or name opens the app.
- State comes from `RadioPrefs` (`last_station_title`, `last_station_sub`, `last_station_freq_bits`, `is_playing`);
  `PlaybackService.updateState()` refreshes the widget on every change.
- Play after the process was killed replays the last station (`playSavedStation`).
- Previous/next: app open = the page decides (Band Lock, AU only); app closed = steps through the normal dial order, wrapping.
- Buttons start the service with `getForegroundService`, so the service calls `ensureForeground()` first.
- Not compiled in the sandbox (no Android SDK) - build and test on the phone.
- Widget play/pause sends `ACTION_TOGGLE`; the service decides from the player's real state (playing -> pause, anything else ->
  fresh reconnect + audio focus), so it still works after another app (e.g. Podcast Addict) has taken the audio.

## Hidden diagnostic log
`DiagLog` keeps a capped (~300 KB) text log in the app's files dir: station plays, ExoPlayer state/isPlaying/errors,
buffer level, screen on/off, Doze, network changes, stall reconnects, and a 15 s heartbeat while playing.
Export: hold "DIGITAL TUNING" (under the title) for ~1.5 s -> share sheet (FileProvider, cache/shared/).
Page calls `AndroidBridge.shareLog()`; no-op on the web version.


## Name
The app is now called **Radio Wavehopper** (label, widgets, badge, notification, page title, log header).
The internal package id stays `com.billybobs.radiotuner` so updates install over existing copies.
Voice command phrase: "play <station> on Radio Wavehopper" (the old "on Billy Bob's Radio" still works).

## Logos notice
Station logos belong to their owners; they are included to identify the stations and will be removed on request.

## Edit screen: your own stations (stage 1: Add)
- Pencil icon (top right of the radio) opens the Edit screen; Back or CLOSE returns to the radio.
- Add form: name, category (existing blocks only), region (under MORE), band (Internet/FM/AM/DAB+), streaming URL with a TEST STREAM button. The dial slot is always automatic (hundredth steps, e.g. 90.04, placed by AM/FM/DAB/Internet order inside the category); the old "choose it myself" option was removed in 1.1.0.
- Saved on the phone only: `UserStations.java` (SharedPreferences "UserStations", key "data", JSON `{"added":[...]}`) via the bridge methods `getUserStations`, `saveUserStations`, `testStream`. On the website it uses localStorage `user_stations_v1`.
- `StationLibrary.getStations()` = built-in list + user stations, sorted by dial frequency, so the phone player, widgets and Android Auto see them. Android Auto reads the list when its service starts (next car connection).
- The page merges `userData.added` into `stations` before the ruler is built; closing the Edit screen after a change reloads the page.
- Test play sends `EXTRA_TEST_URL` to PlaybackService; it is not saved as the last station.
- Coming: stage 2 Edit/Delete for any station and Reset to defaults (the JSON will gain `hidden` and `edited`), stage 3 logos, stage 4 backup.

## Edit screen stage 2: edit, delete, reset
- "Edit a station": FIND by name or real frequency (e.g. "2GB" or "873"), or THIS ONE for the station tuned now. (Dial numbers are no longer shown in Settings.) Works for built-in and your own stations. Your own stations also have EDIT in the list.
- Built-in edits/removals are stored as overrides in the same JSON: `hidden` (dial keys like "87.80") and `edited` (key -> {name, real, cc, url, dab}). The built-in list in code is never changed. `StationLibrary.getStations()` applies them via `UserStations.applyOverrides`; the page applies them before building the dial.
- Built-in stations keep their dial slot. Group-start stations (the first station of each block) can be edited but not deleted, because the group buttons need them. Renaming a built-in drops its bundled logo (the drawn tile or website logo is used).
- RESET TO DEFAULTS (tap twice) clears added, hidden and edited and reloads.

## Edit screen stage 3: logos
- In the app, the Edit form has a Logo box: UPLOAD LOGO (system picker), the logo is fetched automatically when a STATION WEBSITE is entered, with a preview, (same finder as the built-in logo fetcher, `LogoFetcher.findLogo`), or USE DEFAULT. A preview shows the current logo. The logo is saved when the station is saved (Add or Save Changes); renaming a station moves its logo; deleting or Reset removes it.
- Stored only on the phone: `filesDir/userlogos/<slug>.png` (`UserLogos.java`), square on white. It beats bundled, website-fetched and drawn logos in `StationArtworkProvider`, so Android Auto and the widgets use it. Android Auto may show it the next time the car connects.
- Bridge: `pickLogo`, `fetchLogo` (result back through `window.__whLogoResult`), `saveUserLogo`, `getUserLogo`, `clearUserLogo`, `renameUserLogo`. The logo box is hidden on the plain website.
- Notice: the Edit screen says logos belong to their owners; keep the same wording in the README for GitHub (logos are the property of their owners; removed on request).

## Edit screen stage 4: backup
- (Since 1.1.0 there is one SAVE OR SHARE BACKUP button; backups also hold lists, Real frequency only and default sleep.) SAVE TO PHONE uses the system save dialog (ACTION_CREATE_DOCUMENT, `saveBackup`, result via `__whBackupDone`) so it can go into Files/Downloads. SHARE BACKUP (previously EXPORT) writes `radiowavehopper-backup.json` (cache/shared, FileProvider) and opens the share sheet (Drive, Files, email...). It holds `added`, `hidden`, `edited` and `logos` (name -> PNG data URL). On the website it downloads the file instead.
- IMPORT BACKUP opens a file picker (`pickBackup`), checks the file in the page (`cleanBackup`: only http(s) URLs, valid band text, slots that fit the dial and don't clash, known built-in keys, image data URLs only) and shows a summary. REPLACE WITH THIS BACKUP then swaps everything on the phone (stations, edits, removals and custom logos) and reloads.
- Files that aren't Radio Wavehopper backups are refused; entries that don't fit are skipped and counted.

## Edit screen: moving stations
- Editing a station can now change its category and band. When either changes, the station gets a new dial slot in the chosen category, placed by the usual AM/FM/DAB/Internet order. Renaming or changing the URL alone keeps its slot.
- Built-in moves are saved in the override as `edited[<original key>].freq`; `UserStations.applyOverrides` applies it and `StationLibrary.getStations()` always re-sorts. The first station of each block cannot be moved or deleted.
- Backups keep the moved slot (cleaned the same way as added stations).
- Order inside a category is still AM, FM, DAB, Internet by real frequency; Internet-only is alphabetical. Name is now only the tie-breaker between equal real frequencies. Renaming an Internet-only station re-slots it only if it is no longer in alphabetical place.

## Logo fetching start-up
- `LogoFetcher.prefetchAll` now also runs when the app opens (MainActivity), not only when Android Auto starts, so the widgets get real logos after a fresh install. When a logo arrives for the station shown on the widget, the widget redraws (`RadioWidgetProvider.refresh`).
- FM frequencies typed on the Edit screen keep one decimal ("88" is stored as "88.0 FM"), matching the built-in stations.

## Logo tiles without the white frame
- `StationArtworkProvider.composeOnWhite` now looks at the logo's outer edge. If it is mostly one solid, non-white colour (a black, green or red tile), the square is filled with that colour and the logo fills it, so there is no white border. Logos with a transparent or white edge, or a photo/gradient edge, keep the white square as before. `STYLE_VERSION` was bumped (4) so cached tiles are redrawn.
- `trimToSolidTile`: a logo that is a solid-coloured tile inside a white/transparent margin (SEN) is trimmed to the tile, which then fills the square. Only done when the trimmed edge is solid; otherwise the logo is left as it was. `STYLE_VERSION` is 5.
- Add/Edit: when the category is NSW (and ACT) the Region dropdown always shows, offering only NSW or ACT. Both stay in the same dial block.

---

## 10. Changes in 1.0.5 – 1.2.1 (Nov 2026)

### Settings (pencil screen)
- Sections: STATIONS TO SHOW, ADD OR EDIT A STATION, YOUR STATIONS, SLEEP TIMER, BACKUP & RESET (collapsible `<details>`).
- **Two lists only:** "My phone stations" (`PHONE_LIST`) and "My car stations" (`CAR_LIST`). Phone: All stations / Only my chosen stations (`activeList` '' or PHONE_LIST). Car: Same as phone / All stations / Only my chosen stations (`carList` '', '*' or CAR_LIST). Category ticks and hidden categories are gone (`hiddenGroups` is always `[]`).
- **Migration** (`twoLists()` in script 0, shared via `window.__wh`): the old car list becomes the car list, every other old list merges into the phone list. Data already in two-list form is left alone.
- An empty chosen list means all stations (`StationLibrary.showing()`).
- The add form's "also choose it for the phone/car" ticks only show for a side set to its chosen stations.

### Real frequency only (default on)
- See section 6. Widget/notification next/previous step within the current band and wrap (`PlaybackService.widgetStep`, `StationLibrary.realBand`/`realFreqValue`). The band label under the dial sits on its own line (`#realRuler` 88 px).

### Song titles
- Phone: `onMetadata` reads `IcyInfo` and HLS `TextInformationFrame` (TIT2/TPE1) → `SongText` → `NativeControls.onNativeTrack`. Car: see section 4.
- `SongText.java` cleans titles: HTML entities, `[+]` → `&`, duplicate halves, artist == title, junk (Asset Start/Stop, timestamps, `song_spot`, `MediaBaseId`), the station's own name or slogan, Title Case for ALL CAPS (with a `KEEP_CAPS` list).
- Now Playing box: always shown with Real frequency only ("Unavailable" when nothing is sent), two lines max.

### Default sleep timer
- `sleepDefault` (10/30/60/120 or 0) starts on any phone play (app, widget, notification), never in Android Auto (`maybeStartDefaultSleep`, 20 s grace on pause).

### Phone and car handing over
- `RadioLibraryService.carConnected()` asks `content://androidx.car.app.connection` (`CarConnectionState` 2 = projection), cached 2 s; needs the `<queries><provider android:authorities="androidx.car.app.connection"/>` manifest entry. Leaving the car calls `handBackToPhone()`, which saves the car's station as the phone's last station.
- **Play after a drive:** if the saved station has moved on from the one the phone player still has loaded, Play (widget, notification, lock screen, Bluetooth) plays the saved one (`savedStationMovedOn()`).
- **Car start station:** the phone's last station if it is in the car's stations, otherwise the car's own last station (`combinedLastStationIndex()`).
- **App screen resync:** `MainActivity.onResume` sends the current/saved station to `NativeControls.onAppResume(name)`, which moves the dial (or, if already playing, updates the display) without re-tuning.
- `MainActivity` is `singleTask`, and `instance` is set again in `onResume`, so the player's callbacks always reach the visible screen (the old "stuck on Connecting…" bug).

### Android Auto controls
- One band button cycling AM → FM → DAB+/Internet → Normal (four-block icon; host shows icons only, plain white mono-tint vectors). Next/previous follow frequency order; double arrows jump groups, or thirds when the car shows only its chosen stations.
- Stations are reported as live (`C.TIME_UNSET` duration, position 0, not seekable), so no progress counter.

### Outages
- **Phone dropout retry:** a station that was playing and then fails to reconnect is retried every 10 s for up to 2 min (`retryAfterDropout()`, log lines `DROPOUT …`), with "Signal lost — reconnecting…" on screen. A station that fails on first tuning still errors at once. The car player is not covered yet.

### Station Finder (`tools/station-finder.html`)
- A stand-alone page, not part of the app: search Radio Browser, test a stream, copy what the station editor needs. Opened as a local file in Chrome (GitHub shows the source, not the page). Linked from the README under "Finding a stream URL".

### Open ideas (not built)
- Backup stream per station (e.g. ABC Sport when the ABC Sport DAB relay is down).
- More `SongText` clean-ups: XLFM "Duplicate", 3PBS "NOW: … | pbsfm.org.au", 5WOW slogan.
- Letter icons (AM/FM/DAB/ALL) on the car's band button.
- Google Play closed testing (needs an .aab and the existing signing key).

