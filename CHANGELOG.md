# Changelog

## 0.4.1

- **Setup guide.** Back and Next stay on screen on short head-unit screens, and the steps are named under the progress bar. The last step lists what is still missing, such as an iPhone or the car hotspot's name and password, before it says you're ready. Features are grouped into tested and experimental, and the experimental hotspot repair stays in Settings.
- **Android Auto identity in the release APK.** It now bundles the head-unit certificate that open-headunit publishes, as release APKs already bundle a CarPlay identity, so Android Auto no longer stops at "head-unit identity missing". Google can stop accepting it at any time.
- **Guidance and calls on the driver's side (experimental, off by default; Settings → Advanced → Video and audio).** CarPlay navigation prompts and calls, and Android Auto guidance, play only from the speakers on the driver's side. A head unit that mixes these sounds to mono plays them as before, and Android Auto calls are not changed. Not yet tried in a car.

## 0.4.0

- **CarPlay Ultra (experimental, off by default; Settings → Advanced).** Ported from LIVI. The iPhone shows climate in the CarPlay dock, and with CarPlay Ultra chosen on the iPhone, its Climate, Radio and Vehicle apps.
  - TEYES and FYT units give CarPlay the car's climate. With **Climate changes from the iPhone** on, allow-listed changes go back to the car.
  - The unit's FM stations are passed to the Radio app, but its screen stays blank for now: iOS wants a car maker's theme for it.
  - The Vehicle app shows the car data Tarmac reads (charge or fuel, range, odometer, tyres, outside temperature), read only.
  - None of this has run on a head unit in a car yet.
- New connecting screen: a car's cabin with the CarPlay icon on its centre screen, laid out for head units.
- Landscape Home fits one screen, with a large album cover above the song.
- CAN-box card for TEYES and FYT units (Settings → Vehicle), and, on rooted FYT units, an experimental switch that sends the voice and call keys to CarPlay.

## 0.3.0: first Tarmac test release

- New name, icon and package (`com.altair571.tarmac`). It installs alongside DiPlay.
- iOS-style Home and Settings, with a Now Playing card, the next turn, accent colours and a calm album-colour background.
- CarPlay music and Apple Maps turns appear in Android notifications.
- Runs on phones and tablets as well as head units.
- Experimental Android Auto, wired and wireless, with an option to use the car's GPS.
- Head-unit adapters for TEYES and FYT/DUDU; dashboard map on second displays; turns to the car's cluster and HUD; reversing-camera hand-off; learned steering-wheel keys.
- Faster wireless connects and reconnects, and lighter protocol logging.
- Bluetooth auto-connect is on by default; where Android blocks a background start, a notification offers to connect.

Tarmac is based on DiPlay 0.2.22. For DiPlay's history, see the [upstream project](https://github.com/shihabal3amri/DiPlay).
