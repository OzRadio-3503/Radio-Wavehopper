An Android radio app that works like a real tuner dial. Tune the AM, FM,
DAB+ and Internet bands to live Australian and international internet radio
streams: commercial, community, sport, talk, music, and more.

> Station streams and logos belong to their owners. See [Logos and streams](#logos-and-streams).

## Features

- **Real-frequency dial (the default):** AM, FM, DAB+ and NET bands, with each
  station at its real on-air frequency. Prefer the original dial? Untick
  **Real frequency only** in Settings for the categories dial (station groups
  such as Sport, Talk, each state, New Zealand, UK, USA).
- **Android Auto** support, with station logos, song titles and its own
  station list if you want one.
- **Now Playing:** live song titles where a station sends them.
- **Home screen widgets** in three sizes.
- **Sleep timer**, including an optional default timer that starts whenever
  the radio plays on the phone.
- **Choose your stations:** show all stations, or only the ones you pick,
  separately for the phone and for Android Auto.
- **Your own stations:** add, edit or delete any station, test the stream
  first, and set a logo. Back up and restore, or reset to the defaults.
- **Rides out short outages:** if a station drops out, the app keeps trying
  to reconnect for up to 2 minutes.
- **Hidden diagnostic log:** hold the "DIGITAL TUNING" badge for 1.5 seconds
  to share a log file if you need help with a stream. It is only shared when
  you choose to send it.

## Install

Download the latest APK from the **Releases** page, open it on your phone, and
allow "install unknown apps" for the app you used to open it. Requires
Android 8.0 (API 26) or newer.

## How to use

**Tuning.** Tap **AM**, **FM**, **DAB+** or **NET** to pick a band, then turn or
drag the dial. The arrows step to the next or previous station in that band.

**Shortcuts.**
- **Speakers:** the **right speaker** jumps a third of the way along the band
  (start, 1/3, 2/3, then round again). The **left speaker** goes back to the start
  of the current third, and a second tap goes to the previous one. (On the
  categories dial they jump between station groups instead.)
- **Hold the dial still** to magnify it for fine tuning.
- **Hold the RADIO WAVEHOPPER badge** at the top to set the sleep timer (120, 60, 30, 10 minutes or off).
- **AU only:** skips overseas stations.
- **Shazam:** while a station is playing, tap it to identify the song.

**Settings.** Tap the **pencil** icon at the top right.
- **Stations to show:** Real frequency only on or off, and for the **phone**
  and **Android Auto** separately: **All stations** or **Only my chosen
  stations**. Use **Choose stations for the phone / car** to pick them, sorted
  by frequency or A to Z.
- **Add or edit a station:** enter the name, category, band and frequency (or
  DAB+ block), paste the **streaming URL**, and press **Test stream** to check it
  works. Enter the station's website to fetch its logo (with a preview), or upload
  one. Its place on the dial is chosen for you. To change a station, find it by
  name or frequency and edit or delete it.
- **Your stations:** the stations you've added.
- **Sleep timer:** a **default sleep** that starts each time the radio plays on
  the phone (not in Android Auto).
- **Backup & reset:** save or share a backup, import one, or reset to the
  defaults. Deleting and resetting ask you to tap twice, so you can't do it by
  accident.

**Android Auto.** Next and previous step through the stations in frequency
order. The double arrows jump between station groups, or a third of the way
through when the car shows only your chosen stations. The band button
switches between AM, FM, DAB+ & Internet and all bands, and the line under the
station name shows which band you're on for a few seconds.

**Back up your stations.** Your added stations, logos, lists and settings are saved
on the phone and are lost if you uninstall the app. Before uninstalling, open
Settings, go to **Backup & reset** and use **Save or share backup**. After
reinstalling, use **Import backup** to get them back.

**Updating.** Install the newer APK over the old one and your stations and settings
stay. It must come from the same Releases page, because updates only install over
apps signed with the same key.

## Build it yourself

1. Open the project in **Android Studio** (File > Open, select this folder).
2. Let Gradle sync finish (it downloads what it needs).
3. Run on a phone with USB debugging, or use
   **Build > Generate Signed Bundle / APK** to make an installable APK.

Package id: `com.billybobs.radiotuner` (kept so existing installs update in place).

The page is bundled as `app/src/main/assets/index.html` and runs in a WebView;
playback runs natively in `PlaybackService` (phone) and `RadioLibraryService`
(Android Auto). `NOTES.md` has the hard-won details about streams, Android Auto
and the station list, and is worth reading before changing playback code.

## Privacy

- Stations, edits and logos you add are stored only on your phone.
- Nothing is uploaded by the app. It connects to the radio streams you play,
  to station websites to fetch logos, and to some stations' "now playing"
  pages for song titles. Most song titles come from the stream itself.
- Every station plays straight from its own stream. There is no relay server.
- Your own stations and edits are merged with the built-in list on every
  update. If one of your stations lands on a dial spot a new built-in station
  now uses, yours is moved to the nearest free spot (AM, then FM, then DAB,
  then Internet) and nothing is deleted. Back up first from Settings > Backup & reset.

## Logos and streams

Station names and logos are the property of their respective owners. They are
included only so listeners can recognise stations, and they are not covered by
this project's licence. If you own a logo or stream and want it removed or
changed, please open an issue and it will be taken out promptly.

Streams are provided by the stations themselves and can change or stop at any
time. Please only add and use streams you have the right to listen to.


## Screenshots

<p>
  <img width="250" alt="image" src="https://github.com/user-attachments/assets/8e66e95b-0378-4cb3-a04c-23d8f269cc98" />
  <img width="250" alt="image" src="https://github.com/user-attachments/assets/4be3f4cf-a117-434a-8658-eccddd819a89" />
  <img width="250" alt="image" src="https://github.com/user-attachments/assets/b887f641-2ab5-4580-82a2-8ee93a6852bb" />
  <img width="420" alt="image" src="https://github.com/user-attachments/assets/e46ec17b-e33b-414d-aa56-57ff0c2aff2d" />
</p>


## Licence

The code is released under the [MIT Licence](LICENSE).
