# Cambridge Connect Legacy

**[⬇ Download the app (APK)](https://github.com/EyalPrice/CambridgeConnectLegacy/releases/latest/download/CambridgeConnectLegacy.apk)** · **[Website](https://eyalprice.github.io/CambridgeConnectLegacy/)**

A free **Cambridge Connect alternative** for Android. Works on old and new Android phones, 32-bit and 64-bit.

**Unofficial** Android Wi-Fi remote for legacy Cambridge Audio network streamers:
**Minx Xi, NP30 and Stream Magic 6 (v1)**.

These lovely stereos were controlled by Cambridge Audio's *Cambridge Connect* app. This community app lets owners
keep controlling them from any Android phone, old or new.

> Not made by, endorsed by or affiliated with Cambridge Audio.
> Cambridge Audio, Minx, Stream Magic and Cambridge Connect are trademarks of their owner and are used here only to
> describe compatibility.

## Install (Android 8 or newer)
1. On your phone, open the **[latest release](../../releases/latest)** and download `CambridgeConnectLegacy.apk`.
2. Open the downloaded file. If Android asks, allow your browser to "install unknown apps".
3. Your phone and stereo must be on the same Wi-Fi network.

## Use
- **Connect**: the app finds your stereo on your Wi-Fi by itself. If it doesn't, type the stereo's IP address
  (shown in the stereo's *Network config* menu).
- **Now Playing**: a real volume knob, play/pause, next and back. On internet radio, next and back (or a swipe
  across the station card) step through your presets.
- **Inputs**: one tap per input. Long-press a tile to rename it, move it, or add it to the home-screen widget.
- **Radio**: your presets, plus station search by name, country or genre. Save any find to a preset slot.
- **Sound**: bass, treble, balance, mute and phase. The app learns your stereo's limits as you use it.
- **Lock screen & widget**: control play, volume and inputs without opening the app. The phone's volume
  buttons work even when the screen is locked.
- **Settings**: stereo name, display brightness and language, firmware details and app updates.

## Report a problem
In the app, open **Settings → Report a problem → Create diagnostic report**, then **Share bug report** to save
or send the file. Then [open a bug report](../../issues/new?labels=bug) and attach the file. Network addresses and
most of the serial number are removed from the report.

## How it works
These streamers are UPnP devices. The app discovers them with SSDP and reads their service descriptions.
It then sends UPnP SOAP commands over your local network only.
It has no accounts, no cloud connection and no tracking.

## Status
Works well on a Minx Xi. NP30 and Stream Magic 6 (v1) owners: please report how it goes.
For safety, the app never sends firmware-update commands, and full power-off needs a long press and a confirmation.

## Build it yourself
Each push to `main` is built by GitHub Actions and published as a release. To build locally, run
`gradle assembleRelease` with Android SDK 34 installed. Without the release secrets, the APK is signed with your debug key.

## License
MIT, see [LICENSE](LICENSE).
