# Cambridge Connect Legacy

**[⬇ Download the app (APK)](https://github.com/EyalPrice/CambridgeConnectLegacy/releases/latest/download/CambridgeConnectLegacy.apk)** · **[Website](https://eyalprice.github.io/CambridgeConnectLegacy/)**

A free **Cambridge Connect alternative** for Android: a 64-bit replacement for the retired Cambridge Connect app.

**Unofficial** Android Wi-Fi remote for legacy Cambridge Audio network streamers:
**Minx Xi, NP30 and Stream Magic 6 (v1)**.

Cambridge Audio's original *Cambridge Connect* app was 32-bit only, and it has been retired. It can't be installed
on modern 64-bit-only phones. The newer StreamMagic app doesn't support these models.
This app fills that gap.

> Not made by, endorsed by or affiliated with Cambridge Audio.
> Cambridge Audio, Minx, Stream Magic and Cambridge Connect are trademarks of their owner and are used here only to
> describe compatibility.

## Install (Android 8 or newer)
1. On your phone, open the **[latest release](../../releases/latest)** and download `CambridgeConnectLegacy.apk`.
2. Open the downloaded file. If Android asks, allow your browser to "install unknown apps".
3. Your phone and stereo must be on the same Wi-Fi network.

## Use
- **Find stereo**: searches your Wi-Fi for UPnP devices. Tap your Cambridge Audio unit (marked ★), or type its IP
  address (found in the stereo's *Network config* menu).
- **Remote**: volume, mute and playback controls (when the stereo offers them), plus your own buttons.
- **Commands**: every command the stereo exposes. Run one, then tap **Pin to remote** to turn it into a button,
  for example to select an input.
  **Share this list** exports the full command list as text. It leaves out your IP address. Attaching it to a GitHub
  issue helps add built-in support for more models.

## How it works
These streamers are UPnP devices. The app discovers them with SSDP and reads their service descriptions.
It then sends standard UPnP SOAP commands over your local network only.
It has no accounts, no cloud connection and no tracking.

## Status
Early version, 0.1. Volume and playback use the standard UPnP services. Built-in input-switching buttons will be
added once the command lists from real units have been mapped.

## Build it yourself
Each push to `main` is built by GitHub Actions and published as a release. To build locally, run
`gradle assembleRelease` with Android SDK 34 installed. Without the release secrets, the APK is signed with your debug key.

## License
MIT, see [LICENSE](LICENSE).
