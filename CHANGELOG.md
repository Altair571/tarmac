# Changelog

## 0.5.0

- **A new look.** Tarmac's own screens are redrawn around one idea, a road at night and by day: asphalt-dark cards with a hairline edge, white and amber road paint, and your tint on the controls. Light mode is pale concrete with solid white cards.
  - **New app icon:** a "T" made of the horizon and a road's centre line running to it. The notification icon and the Tarmac car button use the same mark.
  - **Home:** the CarPlay card has a centre line under its status that streams while Tarmac connects; USB, Android Auto and Settings are three large tiles on a head unit; Play has its own disc. The version line under the cards is gone (it is in Settings → About).
  - **Settings:** the open category is marked by a selection that slides along the rail; Overview lists the categories in one card; switches take your tint; header controls are pills.
  - **Setup guide:** each step is a dash of the centre line, painted as you pass it.
  - **Connecting screen:** the cabin is now a still picture, modelled and lit in 3D and rendered ahead of time, in place of the animated drawing: a steering wheel with the driver's display behind it, a head unit built into the dash with its knob and keys, a windscreen between proper pillars, a centre mirror, and city lights beyond the glass. Night and day versions; right-hand drive turns the cabin round and keeps CarPlay's icon the right way. The status has the same streaming centre line as Home.
  - **Dialogs** are rounded cards with sentence-case buttons.
- **Motion.** Pages fade out as the next one slides in, forward from the end side and back from the start side. Switching between light and dark crosses over instead of cutting. Blocks that appear in place (the reconnect bar, Disconnect, extra options) push their neighbours aside. The status line, the connected tick and every button respond to a press or a change. Android's "Remove animations" turns all of it off.
- **Fixed:** on a head unit the soft glow behind Home and Settings was cut off along straight edges near the top and bottom of the screen, and it jumped back to its start each time a setting was changed.
- Typeface: titles use Inter's display cut, and figures use open digits that keep their width in the clock and in distances.

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
