An Android radio app that works like a real tuner dial. Turn the dial from
87.5 to 102.1 and it tunes to live Australian and international internet
radio streams: commercial, community, sport, talk, music, and more.

> Station streams and logos belong to their owners. See [Logos and streams](#logos-and-streams).

## Features

- **Tuner dial** with station groups (Sport, Talk, each state, New Zealand,
  UK, USA and more, plus internet-only stations).
- **Real frequency mode:** switch the display to each station's real on-air
  frequency (AM, FM, DAB+), with Band Lock to tune along one band.
- **Android Auto** support, with station logos and now-playing titles.
- **Home screen widgets** in three sizes.
- **Sleep timer**, presets, and live song titles where a station provides them.
- **Your own stations:** tap the pencil to add, edit, move or delete any
  station (test the stream before adding), set a logo, back up and restore,
  or reset to the defaults. Your changes stay on your phone.
- **Hidden diagnostic log:** hold the "DIGITAL TUNING" badge for 1.5 seconds
  to share a log file if you need help with a stream. It is only shared when
  you choose to send it.

## Install

Download the latest APK from the **Releases** page, open it on your phone, and
allow "install unknown apps" for the app you used to open it. Requires
Android 8.0 (API 26) or newer.

## How to use

**Tuning.** Turn or drag the dial to move through the stations. The arrows step
to the next or previous station. Station groups (Sport, Talk, each state, and so
on) sit side by side on the dial.

**Real frequency.** Switch the display to real mode to see each station's real
on-air frequency (AM, FM or DAB+). Touch the dial in real mode and **Band Lock**
switches on, so you tune along one band at a time.

**Shortcuts.**
- **Speakers:** tap the **right speaker** to jump to the next group of stations (Sport,
  Talk, NSW and so on). The **left speaker** goes back to the start of the current
  group, and a second tap goes to the previous group. The groups wrap round at either end.
- **Hold the dial still** to magnify it for fine tuning.
- **Hold the RADIO WAVEHOPPER badge** at the top to set the sleep timer (120, 60, 30, 10 minutes or off).
- **AU only:** the button beside BAND skips overseas stations.

**Add your own station.**
1. Tap the **pencil** icon to open the Edit screen, then **Add a station**.
2. Enter the name, pick a **category** and **band**, and paste the **streaming URL**.
3. Press **Test play** to check it works before saving.
4. The dial position is chosen for you. Tick the manual option if you want to pick it yourself.
5. In the NSW and ACT category, choose NSW or ACT under **Region**.

**Edit, move or delete.** Open the pencil, find the station (or use **Find** with
a dial number), and change it, move it to another category, or delete it. Deleting
and resetting ask you to tap twice, so you can't do it by accident. **Reset to
defaults** puts the original list back.

**Logos.** Logos show in Android Auto and the widgets. For your own station choose
**Upload from phone**, **Fetch logo from website**, or leave the default tile.

**Back up your stations.** Your added stations and logos are saved on the phone
and are lost if you uninstall the app. Before uninstalling, open the pencil, go to
**Backup** and use **Save to phone** (or **Share backup**). After reinstalling, use
**Import backup** to get them back.

**Updating.** Install the newer APK over the old one and your stations stay.
It must come from the same Releases page, because updates only install over
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
  pages for song titles.
- Every station plays straight from its own stream. There is no relay server.
- Your own stations and edits are merged with the built-in list on every
  update. If one of your stations lands on a dial spot a new built-in station
  now uses, yours is moved to the nearest free spot (AM, then FM, then DAB,
  then Internet) and nothing is deleted. Back up first from Edit > Backup.

## Logos and streams

Station names and logos are the property of their respective owners. They are
included only so listeners can recognise stations, and they are not covered by
this project's licence. If you own a logo or stream and want it removed or
changed, please open an issue and it will be taken out promptly.

Streams are provided by the stations themselves and can change or stop at any
time. Please only add and use streams you have the right to listen to.

<img width="1280" height="2856" alt="Screenshot_20261117-094938" src="https://github.com/user-attachments/assets/f54e32f9-2554-4b3e-96e0-6d251c730f3d" />
<img width="1280" height="2856" alt="Screenshot_20261117-095007" src="https://github.com/user-attachments/assets/8ca28d22-ed31-4bad-9085-ae1ed49ec2d5" />
<img width="1280" height="541" alt="Screenshot_20261117-095028" src="https://github.com/user-attachments/assets/fb568b9a-a3d4-4d6d-b217-9dcb80f89ecf" />
<img width="479" height="682" alt="Screenshot_20261117-095042" src="https://github.com/user-attachments/assets/ff6bb8d6-f70b-4776-a0bc-e0598edc7b4a" />
<img width="1280" height="2856" alt="Screenshot_20261117-094506" src="https://github.com/user-attachments/assets/536ffa11-3ba4-4b86-864f-167e6fa2118b" />
<img width="1280" height="2856" alt="Screenshot_20261117-094515" src="https://github.com/user-attachments/assets/57b0ed8c-753b-409c-b0cb-6bf5edc1facc" />
<img width="1280" height="2856" alt="Screenshot_20261117-094649" src="https://github.com/user-attachments/assets/2387e4fd-7bf8-44cf-8aec-c9302937986e" />
# Radio Wavehopper


## Licence

The code is released under the [MIT Licence](LICENSE).
