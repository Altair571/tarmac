# Android Auto receiver

The app can project an Android phone with Android Auto, next to CarPlay. The head unit (or any
Android device that can be a USB host) plays the part of an Android Auto car: the phone renders
the Android Auto interface, encodes it as H.264 and streams it with audio over USB or Wi-Fi; the
head unit shows the picture, plays the sound and sends touches, the microphone and car sensors back.

This page records the research behind the implementation, the approach chosen, how each part of
the protocol is handled, and what is not done or not verified yet.

Status: **wired (USB) and wireless Android Auto are implemented and unit-tested, but neither has
run against a real phone yet.** Wireless needs a real phone and car to verify; section 9 lists what.

## 1. Existing open-source receivers

| Project | Language | Licence | Last push | Notes |
| --- | --- | --- | --- | --- |
| [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) (formerly *Headunit Revived*) | Kotlin + C (libusb, FFmpeg) on Android | AGPL-3.0-or-later | Nov 2026, active | The maintained Android receiver. Wired and wireless (Wi-Fi Direct, hotspot, helper app, dongles), H.264/H.265, AAC, microphone, GPS, steering keys. 685 Kotlin files (about 250,000 lines with its generated protobuf classes) tied to its own app, settings and dependency graph. |
| [anod/headunit](https://github.com/anod/headunit) | Kotlin, Java and C on Android | AGPL-3.0 | Dec 2019 | Kotlin rewrite of the original; open-headunit's direct parent. |
| [mikereidis/headunit](https://github.com/mikereidis/headunit) | Java + C on Android | AGPL-3.0-or-later ("GPLv3 Affero") | Apr 2018 | The original Android head unit by Michael Reid, 2011–2015. Every Android receiver above descends from it. |
| [opencardev/aasdk](https://github.com/opencardev/aasdk) (fork of f1xpl/aasdk) | C++ (Boost.Asio, libusb, OpenSSL, protobuf) | GPL-3.0-or-later | Jun 2026 | Protocol library for Linux head units. Best written-down reference for messages (`aasdk_proto`). |
| [f1xpl/openauto](https://github.com/f1xpl/openauto) | C++ / Qt on Raspberry Pi | GPL-3.0 | Dec 2024 | Head-unit application on top of aasdk (Crankshaft). |

### Licence compatibility

This repository is GPL-3.0 and already contains AGPL-3.0 parts (the DiAuto UI in `TarmacActivity.kt`).
GPL-3.0 section 13 and AGPL-3.0 section 13 explicitly allow combining code under the two licences in
one work; each part keeps its own licence, and the AGPL network-interaction terms apply to the
combination. So:

- porting open-headunit/headunit code is allowed if the ported files stay **AGPL-3.0-or-later**, with
  the original copyright notices;
- aasdk and openauto (GPL-3.0) are compatible as well, but they are C++ and serve here only as a
  protocol reference.

The new `androidauto/` module is therefore marked AGPL-3.0-or-later, credits Michael Reid, the
anod/headunit and open-headunit contributors, and carries their licence notice in
`docs/licenses/OpenHeadunit-AGPL-3.0.txt`.

### Decision

Port the protocol core of open-headunit into a small separate module, instead of vendoring the
whole project or wrapping aasdk:

- **Not vendored wholesale.** open-headunit is an app, not a library. Its protocol code depends on its
  own `App`, `Settings`, logging, connection arbiter and wireless stack, on native libusb and FFmpeg
  builds, on Conscrypt and on protobuf-java. Untangling it would add a large share of its 250,000 lines that
  duplicate what this app already has (USB permission handling, Wi-Fi Direct and hotspot management,
  media notifications).
- **Not aasdk over JNI.** It needs Boost, libusb, OpenSSL and protobuf cross-compiled for every ABI,
  and its USB layer does not fit Android's `UsbManager` permission model.
- **Ported:** the AOA accessory strings and request sequence, the AAP frame and fragment rules, the
  version / TLS / auth-complete handshake, the channel numbers, the service-discovery shape, and the
  focus, ack and ping behaviour. Message field numbers come from open-headunit's `.proto` files and
  were cross-checked against `aasdk_proto`.
- **Protobuf without a dependency.** The head unit needs about twenty small proto2 messages. A
  ~150-line hand-written encoder/decoder (`androidauto/.../proto/Proto.kt`) replaces protobuf-java
  and generated code, so the module adds no third-party dependency.
- **TLS from the platform.** Android's own `SSLEngine` (Conscrypt inside the OS) handles TLS 1.2 on
  every supported Android version (minSdk 24), so the bundled Conscrypt that open-headunit ships for
  Android 4.x is not needed.

## 2. USB: the AOA mode switch

A phone that is plugged in first enumerates with its own vendor and product ID (MTP, PTP, ADB or
charging only). Android Auto only talks over the **Android Open Accessory** (AOA) interface, so the
head unit, as USB host, asks the phone to switch:

1. `GET_PROTOCOL` — vendor IN control request 51; a phone answers with an AOA version ≥ 1.
2. `SEND_STRING` — vendor OUT request 52 for indexes 0–5: manufacturer `Android`, model
   `Android Auto`, description `Android Auto`, version `2.0.1`, URI, serial. The manufacturer and
   model strings are what makes the phone hand the accessory to Android Auto.
3. `START` — vendor OUT request 53. The phone drops off the bus and comes back as Google
   `18D1:2D00` (accessory) or `18D1:2D01` (accessory + ADB).

The head unit then needs USB permission again for the new device, claims interface 0 and uses its
two bulk endpoints as a byte pipe.

In the app:

- `androidauto/src/main/res/xml/android_auto_usb_filter.xml` lets Android offer the app when an
  Android phone or an accessory-mode device is plugged in. It lists the accessory IDs and the
  vendor-class interfaces a phone exposes (MTP and ADB). PTP is left out on purpose: an iPhone has a
  PTP interface, and listing it would put a second app chooser in front of CarPlay.
- `AndroidAutoUsb` decides in code (Apple devices, hubs and mass storage are refused) and performs
  the switch. `AndroidAutoProjection` then wakes on Android's attach broadcast for the accessory
  instead of polling at a fixed interval (a 250 ms cap covers firmware that sends no broadcast).
- **Starting with no tap.** `AndroidAutoUsbAttachedActivity` holds the `USB_DEVICE_ATTACHED` filter
  for both the phone and the accessory it becomes. Android starts it itself when a matching device is
  plugged in, whether the app is in front, in the background or not running, so no background-start
  restriction applies. The first time, Android asks which app should handle the phone and then the
  accessory; with this app and "Always" chosen, later plug-ins switch and project without a tap. A
  short wait after re-enumeration lets that system grant land, so the driver does not get a second,
  app-side permission prompt for the same device.
- `UsbAccessoryStream` implements the existing `BlockingDuplexByteStream` interface from `shared/`
  with bulk transfers, so the AAP session does not care which transport carries it.

## 3. AAP: framing and handshake

Every message is a frame:

```
[channel u8][flags u8][length u16 BE]([total length u32 BE])[payload]
flags: 0x01 FIRST, 0x02 LAST, 0x04 CONTROL (control message on a service channel), 0x08 ENCRYPTED
```

The 4-byte total length is present only on the first fragment of a fragmented message (FIRST
without LAST). After decryption a message is `[type u16 BE][proto2 payload]`; video and audio data
messages carry raw media instead, with an 8-byte timestamp for type 0.

Handshake, in order:

1. Head unit → phone, plaintext: `VERSION_REQUEST` (type 1), major 1, minor 1.
2. Phone → head unit: `VERSION_RESPONSE` (type 2): major, minor and a status. Only status −1
   (no compatible version) is a refusal.
3. TLS handshake, carried in plaintext `ENCAPSULATED_SSL` frames (type 3). **The head unit is the TLS
   client** and presents its certificate as a client certificate; the phone checks that it chains to
   Google's *Automotive Link* CA. The head unit does not verify the phone's certificate.
4. Head unit → phone, plaintext: `AUTH_COMPLETE` (type 4) with status 0.
5. Everything after this is encrypted with TLS application records (flag 0x08).
6. Phone → `SERVICE_DISCOVERY_REQUEST`; head unit → `SERVICE_DISCOVERY_RESPONSE` listing its
   services, each with the channel the phone will open for it.
7. For every service the phone sends `CHANNEL_OPEN_REQUEST` on that channel; the head unit answers
   `CHANNEL_OPEN_RESPONSE` status 0.

During the session the head unit answers `PING_REQUEST`, `AUDIO_FOCUS_REQUEST` (always granted, or
the phone keeps sound on itself), `NAV_FOCUS_REQUEST` (projected) and `BYEBYE_REQUEST`.

Implementation: `aap/AapFrame.kt` (frame codec and fragment reassembly), `aap/AapTls.kt`
(`SSLEngine` handshake and record wrap/unwrap), `aap/AapSession.kt` (state machine and dispatch).

## 4. The head-unit certificate

This is the same kind of problem as CarPlay's MFi identity. Android Auto only accepts a head unit
whose TLS client certificate is signed by Google's *Google Automotive Link* certificate authority;
car makers get one per head-unit model. Nobody outside that programme can create a new one.

Every open-source receiver therefore ships a certificate and private key taken from a real product:

- open-headunit: subject `O=Google-Android-Reference`, issued by `Google Automotive Link`, valid
  2014-07-08 to 2044-07-07 (`app/src/main/res/raw/cert` and `privkey`);
- aasdk / openauto: subject `O=JVC Kenwood`, issued by `Google Automotive Link`
  (`cert/headunit.crt` and `headunit.key`).

Google can stop accepting either at any time, and neither is licensed to this project.

Rules for this repository, as for the MFi identity:

- **No certificate or private key is ever committed or pushed.** The module contains no identity.
  CI and ordinary builds are identity-free; Android Auto then stops at a clear "head-unit identity
  missing" message.
- The identity lives in the git-ignored local folder `local-auth/android-auto/` as
  `certificate.pem` (X.509) and `private-key.pem` (PKCS#8 `BEGIN PRIVATE KEY` or PKCS#1
  `BEGIN RSA PRIVATE KEY`). `/local-auth/`, `*.pem` and `*.key` are all in `.gitignore`.
- Install it with `scripts/android_auto_identity.py <certificate> <private key>`. The script checks
  that the key belongs to the certificate, warns when the issuer is not Google Automotive Link,
  prints only the certificate's subject, issuer and dates (never the key), writes the two files with
  mode 600 and refuses any destination git would track.
- It reaches the APK in one of two ways:
  1. **Build time**, the same way as the MFi identity: a personal build picks up `local-auth/` by
     itself when it holds a complete identity, or takes the folder named by `DIPLAY_AUTH_ASSETS_DIR`.
     The `rejectBundledCredentials` check in `mobile/build.gradle.kts` allows exactly
     `android-auto/certificate.pem` and `android-auto/private-key.pem` next to the MFi files, needs
     each identity complete or absent, and still rejects any other credential file.
  2. **Run time, debug builds**, without rebuilding (debug application id
     `com.altair571.tarmac.hudtest`):
     ```sh
     adb push local-auth/android-auto/certificate.pem local-auth/android-auto/private-key.pem /data/local/tmp/
     adb shell run-as com.altair571.tarmac.hudtest sh -c 'mkdir -p files/android-auto && cp /data/local/tmp/certificate.pem /data/local/tmp/private-key.pem files/android-auto/'
     adb shell rm /data/local/tmp/certificate.pem /data/local/tmp/private-key.pem
     ```
  `HeadUnitIdentity` looks in the app's private `files/android-auto/` first, then in the APK assets.
- Public release APKs from 0.4.1 bundle the open-headunit identity above, the same way they bundle the
  CarPlay identity (see `docs/THIRD_PARTY_NOTICES.md`). Any other identity stays personal: an APK built
  with one must not be shared.

## 5. Services the head unit announces

| Channel | Service | What the app does |
| --- | --- | --- |
| 1 | Sensor source: driving status, night mode, and location when chosen | Sends "unrestricted" when the sensor channel opens (there is no speed source yet) and the night state. With "GPS source · Head unit", also the head unit's GPS (see Sensors). |
| 2 | Video sink, H.264 Baseline | `MediaCodec` decodes into a `SurfaceView`; 800×480, 1280×720 or 1920×1080 at 30 or 60 fps, chosen from the screen. |
| 3 | Input source: touch screen + keys | Touches are scaled from the view to the video size and sent with every pointer. Media and navigation keys are announced. |
| 4 | Audio sink, speech (guidance) 16 kHz mono PCM | `AudioTrack` with navigation-guidance attributes. |
| 5 | Audio sink, system 16 kHz mono PCM | `AudioTrack` with sonification attributes. |
| 6 | Audio sink, media 48 kHz stereo PCM | `AudioTrack` with media attributes. |
| 7 | Microphone source, 16 kHz mono PCM | Announced only when the app has the microphone permission; `AudioRecord` runs while the phone asks for it (Assistant, voice replies). |
| 9 | Media playback status | Title, artist, album and play state are shown in an Android media notification (same pattern as `CarPlaySystemNotifications.kt`). |

Not announced yet: Bluetooth (calls stay on the phone's own Bluetooth link to the car, which works
if the phone is paired with the head unit), navigation status for a cluster, H.265, AAC audio,
GPS location, vehicle speed, rotary controllers.

Every video and audio data message is acknowledged with a media `ACK` so the phone's flow-control
window (`max_unacked`, announced in the media `CONFIG` reply) keeps moving.

### Video

The phone first sends a codec-config message (SPS/PPS), then access units. `AndroidAutoVideo`
keeps latency down where open-source receivers usually lose it:

- `MediaCodec` in **asynchronous mode** on a display-priority thread. A frame is queued the moment it
  and a free input buffer both exist, and each decoded frame is released to the screen at once:
  nothing waits for a dequeue timeout or a polling interval.
- `KEY_LOW_LATENCY` on Android 11+ when the decoder reports the low-latency feature, and
  `KEY_PRIORITY` real-time everywhere.
- A **`SurfaceView`**, which the system compositor shows without the GPU copy a `TextureView` costs.
  Android Auto draws a fixed size with margins outside the screen's shape; the view is made larger
  than the screen by exactly those margins and centred, so they fall outside it.
- The decoder is created when the connection starts, while the phone is still switching to accessory
  mode, so it is ready for the first frame.
- If the decoder falls more than 60 frames behind, the backlog is dropped rather than shown late.

When the projection screen goes to the background, the head unit sends
`VIDEO_FOCUS_NOTIFICATION` *native*; on return it sends *projected* and the phone sends a new key
frame. The last codec config is kept so the restarted decoder can begin before that.

### Audio

Three sinks so guidance and system sounds can mix over music the way the phone expects; each plays
on its own `AudioTrack` thread so a full buffer never stalls the session. The head unit holds
Android audio focus for media while the media sink plays, and always tells the phone it has focus,
or the phone keeps the sound on its own speaker. Guidance is not yet ducked against other apps.

### Touch and keys

`MotionEvent` coordinates are scaled to the video coordinate space (the announced touch screen is
the video size), offset by half the margin. `DOWN`, `MOVE`, `UP`, `POINTER_DOWN` and `POINTER_UP`
are sent with all pointers. While the Android Auto screen is in front, Back, the D-pad and the head
unit's media keys go to Android Auto; the notification's previous, play/pause and next buttons work
from anywhere.

### Sensors

Android Auto requires a driving-status sensor. It is reported as unrestricted. Wiring the car's
speed and parking brake into restricted mode (as BYD vehicle data already allows for CarPlay) is a
follow-up.

**Location.** With Settings → "GPS source · Head unit" and precise location allowed, the head unit
announces the LOCATION sensor beside driving status and night. When the phone starts it, the head
unit streams its own position as location events:

- timestamp, latitude and longitude × 10⁷, accuracy in mm, and altitude (cm), speed (mm/s) and
  bearing (× 10⁶) when the fix has them; a fix without accuracy is sent as 100 m, since the field is
  required;
- every new fix at once, and the newest fix again each second.

Fixes come from CarPlay's `AndroidCarPlayLocationProvider` in `shared/` (GPS, then fused on Android
12+, then network). It gained a fix callback and a public `latestFix()` rather than being copied.
With "Phone", the default, the sensor is not announced and the phone uses its own GPS. The choice
applies at the next connection, because the sensor list is part of service discovery.
`markReconnectNeeded()` is not used for it: that marks CarPlay's session, and its bar reconnects
CarPlay, not Android Auto. The settings card asks for location permission only when "Head unit" is
chosen. The foreground service then also takes the `location` type, so the feed continues with the
projection screen in the background.

## 6. Wireless Android Auto and Bluetooth auto-start

Wireless Android Auto runs the same AAP session (sections 3–5) over TCP instead of USB.
`AapSession` takes any `BlockingDuplexByteStream`, so only the way to the stream is new. It follows
open-headunit's *Native* mode, which talks to Android Auto's own wireless protocol without a helper
app (open-headunit: `NativeAaHandshakeManager`, `WppFraming`, `WppMessages`, `WppHandshakeSession`
and `wireless.proto`).

### How a connection is made

`wireless/AndroidAutoWireless.kt` runs one attempt:

1. **Bluetooth record.** `listenUsingRfcommWithServiceRecord` publishes an RFCOMM record with
   Android Auto's UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`. Android Auto on a paired phone looks
   for it on the head unit when its hands-free link connects, and opens it. The record goes up
   first, before the Wi-Fi is ready, because the phone looks only once.
2. **Wi-Fi.** At the same time the network for the phone comes up through the managers CarPlay
   already uses (`shared/.../network/`). It is the network wireless CarPlay is set up with in
   Settings → Connection: the car hotspot (`ManualHotspotManager`), an existing Wi-Fi
   (`ExistingWifiManager`), or a Wi-Fi Direct group (`WifiP2pGroupManager`), which is also the
   choice when the car hotspot or Wi-Fi was never filled in (`wireless/AndroidAutoWifiNetwork.kt`,
   which reads CarPlay's preferences, as the driving side does).
3. **TCP.** A server socket listens on port 5288, as head units use, or on any free port when
   5288 is taken; the start request names the port.
4. **Setup exchange** over the RFCOMM socket (`wireless/WifiSetup.kt`), framed as
   `[length u16 BE][type u16 BE][proto]`, where the length counts the proto only:

   | Type | Direction | Message | Fields used |
   | --- | --- | --- | --- |
   | 1 | head unit → phone | `WifiStartRequest` | ip_address 1, port 2, start_request_reason 3 (0, a user request) |
   | 2 | phone → head unit | `WifiInfoRequest` | empty |
   | 3 | head unit → phone | `WifiInfoResponse` | ssid 1, key 2, bssid 3, security_mode 4 (WPA2 personal = 8), access_point_type 5 |
   | 7 | phone → head unit | `WifiStartResponse` | status 3; non-zero refuses the start request |
   | 6 | phone → head unit | `WifiConnectStatus` | status 1 (0 joined), error_message_hint 2 |
   | 8 / 9 | either | ping / pong | the pong echoes the ping's payload |

   All five `WifiInfoResponse` fields are always sent, with an empty BSSID where none is known:
   other implementations' schema marks them required, and a phone drops a message with one
   missing. The access-point type is *dynamic* for every network, so the phone does not keep a
   BSSID that may move. A phone already on the network skips types 2 and 3. Statuses are read as
   plain int32 (a phone answers −8 on some), and an unreadable one never counts as a refusal.
   Optional types 4/5 (`WifiVersionRequest`, which also offers the phone a TCP-only path for later
   connections) are not sent; aa-proxy-rs's dongle does not send them either.
5. **Session.** The phone joins, connects to the port, and `TcpByteStream` hands that socket to
   `AapSession`. The RFCOMM channel stays open, answering pings only, until the session ends:
   Android Auto starts the exchange over when the channel closes while the head unit is still
   connected over Bluetooth, and credentials sent into a live session drop it.

Timeouts: 90 s for the phone to open the record (time to turn the phone's Bluetooth off and on),
20 s for the Wi-Fi, 15 s for the phone's first reply, 30 s to join after the credentials, then 20 s
for its TCP connection. Each failure has its own reason on screen (`AndroidAutoFailure`):
Bluetooth off or not allowed, no phone, Wi-Fi not started, phone could not join, or CarPlay running.
Wireless Android Auto does not start while CarPlay holds a session (`AndroidAuto.carPlayActive`,
set by `CarPlayBackgroundSession`), because both need the head unit's Wi-Fi.

The foreground service starts as soon as an attempt begins, so the process stays up while the
phone takes its time. Android 12+ allows that from a Bluetooth broadcast. Android 14 refuses the
microphone service type to a service started from the background, so it then runs as a connected
device only, and the microphone works once the screen has been opened.

### Starting it

- **By hand:** *Connect wirelessly* on the Android Auto screen (shown where no cable is plugged
  in, on a device that cannot be a USB host, and after a wired attempt ends), or in Settings. It
  asks once for Nearby devices, and Location or Nearby Wi-Fi as the network needs, the same set as
  wireless CarPlay (`AndroidAutoSettingsTest` keeps the two in step).
- **Bluetooth auto-start:** `wireless/AndroidAutoBluetoothReceiver.kt`, separate from CarPlay's
  `PhoneBluetoothReceiver`. On `ACL_CONNECTED` it starts wireless Android Auto when all of these
  hold (`AndroidAutoBluetoothStart.decide`, unit-tested):
  - Settings → Advanced → Android Auto (experimental) → *Start when a known phone connects over
    Bluetooth* is on (off by default);
  - the phone is one that completed wireless Android Auto here before: its address is remembered
    once a wireless session gets through TLS, and *Forget known phones* clears the list. An iPhone
    or a headset never matches, so CarPlay's receiver and this one never act on the same device;
  - no Android Auto or CarPlay session runs, and the receiver did not start one in the last 15 s
    (a car's wake-up brings several connection events).

  It publishes the record at once, then opens the Android Auto screen. On Android 10+ without
  *display over other apps*, where Android ignores an activity start from the background, it posts
  a high-priority "Start Android Auto" notification instead; its tap or action opens the screen,
  which shows the connection already under way.

### Android Auto 17.4 and newer

open-headunit's README notes that Android Auto 17.4 and newer stop *legacy* wireless triggers
(its Self mode and the Wireless Helper app) from launching projection. That restriction is about a
helper app on the phone asking Android Auto to start. The Bluetooth exchange above is the one real
head units and wireless dongles use, and open-headunit's Native mode and aa-proxy-rs still use it
with current releases, but it is not verified here (section 9).

Not done, from open-headunit's Native mode: a stand-in hands-free record and service-level
connection for devices without their own hands-free profile (a tablet), "poking" a phone over its
hands-free record when it looked for the record before it existed, Bluetooth modules outside
Android's stack (ZLink, FYT, HCT), and the TCP-only reconnect that `WifiVersionRequest` offers.

## 7. Connection speed and stability

Time from plug-in to the first picture is measured on every connection. The timeline (permission,
accessory, open, version, TLS, first frame) is logged under the `Tarmac-AndroidAuto` tag, and the last
total is shown in Settings. What keeps it short:

- **Work overlaps.** The decoder, the audio sinks and the TLS context are created while the phone is
  still switching to accessory mode.
- **No fixed sleeps.** Re-enumeration wakes on the attach broadcast. The only waits are bounded and
  end early: the attach broadcast (250 ms cap), and Android's own grant for a just-attached
  accessory (at most 600 ms, checked every 50 ms).
- **TLS session resumption.** The TLS context is kept for the process, keyed by the head-unit
  certificate, and every engine uses the same peer key. A reconnect can therefore resume the last
  session with an abbreviated handshake: no certificate exchange and no RSA work.
  `AapTlsTest.secondConnectionResumesTheSession` shows this between two JSSE engines; whether phones
  allow resumption is not verified.
- **Input never waits on the UI thread.** Touches, keys and focus changes are written to USB from
  their own ordered thread.

Stability:

- **Automatic reconnect** (Settings, on by default). When a session fails while the phone stays
  plugged in, the app connects again after 1, 2, 4, 8 and 15 seconds. A session that ran 15 seconds
  resets the count. Failures that need the driver are not retried: a declined USB prompt, a phone
  without Android Auto over USB, a rejected identity, an incompatible version. Unplugging, the
  phone closing Android Auto and the driver pressing Close stop it too.
- **Plain failure reasons on screen**, one for each case in `AndroidAutoFailure`. A phone that
  hangs up during the TLS handshake is reported as having rejected the identity, which is what that
  means in practice.
- **Link watchdog.** The head unit pings every 2 s; 20 s without a byte from the phone ends the
  session as "stopped responding" (and reconnects).

## 8. Using it

- **Home → Android Auto** opens the projection screen. If an Android phone is plugged in, it asks for
  USB permission, switches the phone to accessory mode and connects.
- **Plugging in an Android phone** starts Android Auto by itself (see section 2), unless that is
  turned off in Settings.
- **Connect wirelessly** (on the Android Auto screen or in Settings) waits for a paired phone over
  Bluetooth and connects it over Wi-Fi (see section 6). On the phone, wireless Android Auto must be
  on in Android Auto's settings. If the phone was already connected over Bluetooth, turn its
  Bluetooth off and on so it looks for the head unit again.
- **A known phone connecting over Bluetooth** starts wireless Android Auto when that is turned on.
- **Settings → Advanced → Android Auto (experimental)** has:
  - the identity status and the last connection time, an Open button and Connect wirelessly;
  - start when an Android phone is plugged in, reconnect automatically, start when a known phone
    connects over Bluetooth, and forget the known phones;
  - picture size (automatic, 800 × 480, 1280 × 720, 1920 × 1080), screen density (automatic or
    120–320 dpi) and frame rate (30 or 60 fps);
  - guidance and system sounds on their own streams or on the media stream;
  - night mode: follow Android, always day or always night;
  - GPS source: phone (default) or head unit.

  The driving side is CarPlay's own setting, Vehicle → Right-hand drive, which Android Auto follows,
  so the driver sets it once (`AGENTS.md`: a setting must not appear in two categories). Per
  `AGENTS.md` an experimental card belongs in Advanced; it can move to Connection once it has been
  verified on real phones.
- For USB, the device running the app must be a **USB host**. A head unit is; a phone or tablet
  needs a USB OTG cable or adapter and must be the host side. For wireless it needs Bluetooth with
  a hands-free profile the phone connects to (a head unit has one) and Wi-Fi that can host the
  phone's network.
- On the phone: install Android Auto; on Android 10 and newer it is part of the system.

## 9. Limits and what is not verified

Verified here: the module builds, its lint has no errors, and its unit tests pass:

- the frame codec and reassembly, the proto codec and the message encoders;
- PEM parsing (PKCS#8 and PKCS#1), USB device classification, and the screen geometry with touch
  scaling and density;
- the reconnect policy and failure mapping;
- the location sensor declaration and the location event encoding;
- TLS session resumption and record round trips;
- the wireless setup codec (framing, every message's fields, statuses including negative ones,
  security modes, credentials from each kind of network) and the whole setup exchange against a
  simulated phone: credentials, a repeated request, pings, a refused start, a refused or missing
  join, a silent phone, an early TCP connection, and the held channel;
- the phone's TCP stream over loopback;
- the Bluetooth start decision (toggle, known phones, address case, busy, the 15 s burst guard,
  Android 10+ without overlay) and the receiver end to end: the screen opens once, or the
  "Start Android Auto" notification is posted;
- that wireless uses CarPlay's network and asks for the same permissions as wireless CarPlay, and
  that it sees a CarPlay session.

`AapSessionTest` runs a whole session against a simulated phone over an in-memory link:

- version, then a real TLS 1.2 handshake in which the phone requires and receives the head unit's
  client certificate (a throwaway certificate made at test time);
- auth complete, service discovery and channel opens;
- sensor events, including the phone starting the location sensor and receiving a fix;
- media setup, a fragmented video frame and its ack;
- ping and goodbye.

The app's tests check the Settings card's category, that Home opens the Android Auto screen, and
that the driving side follows the Vehicle setting. The debug APK contains the Android Auto activity,
USB filter, Home row and Settings card.

**Not verified without a phone and head unit:**

- the AOA switch on real phones, and the prompts around re-enumeration: whether Android's
  "open with" grant and the app's own permission prompt can both appear on some head units;
- whether a current Android Auto release accepts the TLS handshake with either public certificate,
  and whether phones resume TLS sessions;
- the service-discovery response being accepted (Android Auto rejects some combinations silently);
- H.264 decoding on head-unit decoders, the low-latency path, picture margins and density;
- audio routing, focus and stutter; microphone and Assistant;
- that Android Auto navigates with the head unit's location events;
- touch accuracy on screens whose shape differs from the video size;
- reconnects, disconnects, screen-off and the projection screen going to the background;
- real plug-in-to-picture times.

**Wireless, not verified without a phone and a car** (each needs a real phone, and most a real
head unit with its own Bluetooth hands-free profile):

- that a current Android Auto release opens the RFCOMM record and completes the setup exchange
  with this head unit (Android Auto 17.4+ in particular);
- the timing: whether the record is up before the phone looks for it when the receiver starts it
  from `ACL_CONNECTED`, and whether turning the phone's Bluetooth off and on is enough otherwise;
- that the phone joins each kind of network (Wi-Fi Direct group, car hotspot, existing Wi-Fi) with
  the BSSID and security mode sent, and on which bands;
- that the phone reaches port 5288 and the AAP session, TLS and media work over Wi-Fi, and how the
  link holds up while driving;
- that the held RFCOMM channel keeps the phone from starting over during a session;
- the receiver on a real car: the screen opening with *display over other apps*, the notification
  without it, the foreground service from a Bluetooth broadcast on Android 12+, and the microphone
  on Android 14 when the screen was never opened;
- that a head unit's own Android Auto, if it has one, does not hold the same record.

Known limits:

- one phone at a time, and landscape only;
- no H.265 or AAC;
- driving status is always unrestricted;
- night mode follows Android's day/night or the driver's fixed choice, not CarPlay's night mode;
- no Bluetooth service announcement;
- wireless needs the head unit's own hands-free profile, one network at a time, and no CarPlay
  session beside it;
- a USB phone plugged in while a wireless attempt waits does not take over; close it first.
