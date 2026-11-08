# Changelog

## 0.4.0

- **CarPlay Ultra (experimental, off by default; Settings → Advanced).** Ported from LIVI. The iPhone shows climate in the CarPlay dock, and with CarPlay Ultra chosen on the iPhone, its Climate, Radio and Vehicle apps.
  - TEYES and FYT units give CarPlay the car's climate and the unit's FM radio. With **Climate changes from the iPhone** on, allow-listed changes go back to the car.
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
