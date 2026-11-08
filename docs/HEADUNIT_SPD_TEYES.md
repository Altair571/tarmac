# TEYES "SPD" head units (CC4 firmware analysis, November 2026)

This note records what Tarmac can learn from a TEYES CC4 head unit and how it does so without writing to the
car. Everything below was read out of the vendor's public OTA image. Nothing from the image is in this
repository, and nothing was run from it.

## Short answer

- **"SPD" is TEYES's own software layer, not Unisoc.** The CC4 image is Qualcomm SM6115/SM6225 (`ro.board.platform=bengal`,
  `qti_dynamic_partitions`, `abl.elf`) running Android 14 (SDK 34). TEYES adds `/spd`, `/spdproject` and `/spddisk`
  partitions, `com.spd.*` system apps and a `com.spd.system` layer inside `framework.jar`.
- **Start with the car:** TEYES writes `Settings.System acc_state` on every ACC change. On ACC on it opens
  the app chosen as its "starting app" (`Settings.System SETTING_LUNCH_FIRST_APP`).
- **Steering-wheel keys** reach apps as ordinary Android `KeyEvent`s injected from virtual input devices
  (`deviceId` 8192–8304).
- **Reverse:** TEYES writes `Settings.System carletter_reserve_state` (the vendor's spelling). The camera is a
  `com.spd.dvr` overlay window that covers every app on its own.
- **Vehicle data:** the exported, unprotected `com.spd.carinfo` service answers read-only getters for the CAN box's
  values (speed, gear, outside temperature, range, state of charge…).
- **Apple authentication chip:** TEYES's built-in CarPlay uses the chip on `/dev/i2c-2`. The node is mode `0777`, but
  SELinux labels it `device`, and an ordinary app (`untrusted_app`) may not open that label. On a stock unit with
  SELinux enforcing, Tarmac cannot use the chip.

## Firmware

| | |
|---|---|
| Archive | `GX_501_Overseas_25934.5485.1C0D5CB.93F2E68.20260922.181315-ota-img.7z`, 3,829,452,140 bytes, SHA-256 `088dd4c052bbb6164c95e759aff5910fa0521fd1165ec0cf7f2d2b2c97e29cd8` |
| Contents | `spd.img` (7.4 GB), `sm6115.img`, `sm6225.img` (Qualcomm boot and modem blobs for each SoC variant), `mcu.spd` (64 KB Cortex-M image) |
| Container | TEYES "ANDROID!" multi-image: 0x30-byte header, 72-byte records (name, 64-bit offset, size), footer brand `TEYES`, codename `bengal_515`, tag `GX`. SPD Forge's `validate --md5` passes. |
| `spd.img` records | `spd.img` (sparse ext4, mounted at `/spd`), `super.img`, `persist`, `vbmeta_system`, `vbmeta`, `recovery`, `spddisk`, `abl.elf`, `boot`, `vendor_boot`, `init_boot`, `spdproject` (sparse ext4), `dtbo` |
| super | LP metadata 10.2, groups `qti_dynamic_partitions_a/_b`; `system`, `system_ext`, `product`, `vendor`, `odm`, `system_dlkm`, `vendor_dlkm` (all ext4) |
| Build | `qti/qssi/qssi:14/UKQ1.241213.001/neillee09221813:user/release-keys`, incremental `25934.5485.1C0D5CB.93F2E68.20260922.181315`, `ro.platform.id=GX`, `ro.software.owner=TEYES` (`/spd/build.prop`) |
| Build fields an app sees | `MANUFACTURER=QUALCOMM`, `BRAND=qti`, `DEVICE=bengal_515`, `BOARD=bengal`, `HARDWARE=qcom`. These are generic Qualcomm values, so detection uses the TEYES packages instead. |

Extraction used macOS `bsdtar` for the 7z archive, plus small read-only readers for the TEYES container, sparse
images, LP metadata and ext4. APK zip CRCs and SPD Forge's footer MD5 table both verify. Manifests were read with
`aapt2 dump xmltree`. Dex files were read only for their string tables, `static final` constants and the names of
called methods. No code was decompiled or copied.

## Platform detection

The `com.spd.*` system packages identify the platform. Tarmac checks for these, which are installed as system apps
with `sharedUserId=android.uid.system`:

| Package | Path | Role |
|---|---|---|
| `com.spd.carinfo` | `/spd/app/CarInfoService` | CAN-box decoder and vehicle data service |
| `com.spd.setting` | `/spd/app/SettingService` | settings service |
| `com.spd.spdsettings` | `/spdproject/app/TEYESettings` | TEYES Settings app (starting app, key learning) |
| `com.spd.dvr` | `/spd/app/SpdDvrService` | cameras, reverse overlay, `SpdExternalService` |
| `com.alink.carplay` | `/spd/app/CarPlay_Teyes` | built-in CarPlay ("alink") |
| `com.alink.androidauto`, `com.alink.connect`, `com.alink.bluetooth` | `/spd/app/…` | built-in Android Auto, connection manager, link Bluetooth helper |

`framework.jar` carries `com.spd.system.service.SpdService` (servicemanager name `spd`), `SpdManager`, `PowerHandler`,
`KeyHandler`, `McuKeyPolicy` and `CarletterInterface`. The MCU link is `/dev/ttyHS2` at 115200 baud
(`/spd/config/systemconfig.xml`), owned by the root daemon `/spd/bin/SpdHalServer` (`spd.Hal`).

## ACC, sleep and wake

The unit suspends instead of booting. `PowerHandler.onSleepChange` and `HandlerManager.handleAccOnOff` do the
following on ACC changes:

| What | Where it is visible | Readable by Tarmac |
|---|---|---|
| ACC state, `1` on and `0` off (the framework passes `1` at boot completion) | `Settings.System acc_state`, written with `notifyChange` (`CarletterInterface.sendAccState`) | Yes. It is a vendor key, not a `Settings.System` field, so the Android 12+ read restriction does not apply. A `ContentObserver` works while the process lives. |
| Starting app opened on ACC on | `Settings.System SETTING_LUNCH_FIRST_APP` (package). Set in TEYES Settings › starting app (`com.spd.scene.SettingsAdapterStartingAppSet`). Skipped when `persist.spd.lunch.first.app.boot.only=true`. | Readable. Writing it is TEYES Settings' job. |
| Processes killed on ACC off | `BlackListKill.killBlackList` spares `/spd/config/processInfo.xml` entries, the navigation app (`SETTING_NAVI_APP`), the starting app, the top activity (with `spd.notify.top.activity.inkill`), the audio-focus holder and `com.spd.*` | — |
| Wake time | system property `wakeup.real.time` | — |
| Link apps told about ACC | `com.alink.bluetooth.action.ACC` (extra `state`, `setPackage(com.alink.bluetooth)`), `com.zjinnova.zlink.action.POWER_ON/OFF` (`setPackage(com.zjinnova.zlink)`), property `rw.zlink.hu.acc` | No. The broadcasts are package-targeted. |

Tarmac's integration:
- It observes `acc_state` and opens CarPlay on the off-to-on edge when "Open after the car starts" is on.
- It shows whether Tarmac is the TEYES starting app. When it is, TEYES both keeps Tarmac alive across sleep and opens it on wake.

## Steering-wheel keys

- `McuKeyPolicy` turns MCU key frames into `KeyEvent`s and injects them with `InputManager.injectInputEvent`
  (flags `FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`, source keyboard). The device IDs come from
  `com.spd.system.utils.KeyDef`: `SPD_SCREEN` 8192, `SPD_PAN1-3` 8208–8210, `SPD_SWC1/2` 8224/8225,
  `SPD_TUNE1-3` 8240–8242, `SPD_CAN_PANEL1/2` 8256/8257, `SPD_CAN_SWC1/2` 8272/8273, `SPD_CAN_TUN1/2` 8288/8289,
  `SPD_SPEECH_ADAPTER` 8304.
- Standard keycodes are used where Android has one: media next 87, previous 88, play/pause 85, call 5, end call 6,
  zoom 168/169. TEYES's own keys start at 360 (`KeyDef.USER_KEYCODE_*`), for example voice 362, phone on 424, phone off 425,
  navi 374. Learned keys are mapped through `/spd/config/swc_config.xml` (`key_id` `0x2000n` → `key_code`; the CC4 default maps
  SWC keys 1–4 to 383, 384, 87 and 88) and TEYES Settings' key learning (`com.spd.spdsettings.KeyLearnActivity`).
- When a TEYES app owns the audio source, `SpdService` also delivers keys over `ISpdServiceCallback.sendKeyCode`. That
  path is for vendor apps only.

Tarmac's integration:
- Media keys already reach CarPlay through the key dispatch and the media session.
- The TEYES adapter adds the voice key (362 and `KEYCODE_VOICE_ASSIST`) → Siri.
- It adds call/phone-on (5, 424) → answer and end-call/phone-off (6, 425) → hang up.
- The adapter uses only events from TEYES's virtual devices, and only while a CarPlay session is running.

## Reverse and cameras

| What | Where |
|---|---|
| Reverse state | `Settings.System carletter_reserve_state` (`CarletterInterface.sendReverseState`, called from `SpdService.sendZjReversingStatus`) |
| Link apps | `com.alink.bluetooth.action.REVERSE` (package-targeted), `com.zjinnova.zlink.action.BACKCAR_START/STOP` (package-targeted) |
| Camera UI | `com.spd.dvr` draws `BackingCameraWindow` / `CameraWindowBase` overlays and writes `Settings.System com.360.ui.show`. 360° view: `com.spd.avm`. |
| Camera settings | `SETTING_REVERSE_CAMERA_DELAY`, `SETTING_REVERSE_CAMERA_EXIT_DELAY`, `SETTING_REVERSE_MIRROR`, `SETTING_CARINFO_CAR_REVERSE_TYPE` (all `Settings.System`) |
| External API | `com.spd.dvr` exports `com.spd.external.SpdExternalService` (action `com.spd.external.SpdExternalService`, no permission). It offers `ISpdExternalService`: `registerExternalCallback`=1, `unRegiserExternalCallback`=2, `registerCarInfoCallback`=3, `unregisterCarInfoCallback`=4, `getCarInfo`=5, `getSpdDeviceState`=6, `sendMessageToSpdDevice`=7. Tarmac does not use it. |

Tarmac's integration:
- It observes `carletter_reserve_state`.
- While reversing, it does not open or raise CarPlay, and auto-start waits until reverse ends.
- Other features can subscribe to the same signal, for example a future CarPlay screen-borrow request.

## Night and illumination

TEYES writes `Settings.System carletter_night_mode` and sets property `rw.out.dark`, and sends
`com.zjinnova.zlink.action.OUT_DARK_START/STOP` to ZLink only. Tarmac reads `carletter_night_mode` for diagnostics.

## Vehicle data (CAN box)

`com.spd.carinfo` exports `com.spd.carinfo.service.CarInfoService`:
- **Binding:** action `com.spd.carinfo.service`, package `com.spd.carinfo`, `exported=true`, no permission. The
  implementation logs the caller and does not check it.
- **Interface:** `com.spd.carinfo.ICarInfoAidlInterface`.

| Transaction | Code | Arguments → result |
|---|---|---|
| `registerCallback` | 1 | `String package, String[] ids, ICarInfoAidlCallback` |
| `unregisterCallback` | 2 | |
| `enterSource` / `exitSource` | 3 / 4 | changes the unit's audio source. **Never called.** |
| `setInt` / `setFloat` / `setString` / `setBundle` | 5 / 6 / 7 / 8 | writes to the car. **Never called.** |
| `getInt` | 9 | `int what, int arg` → `int` |
| `getFloat` | 10 | `int what, int arg` → `float` |
| `getString` | 11 | `int what, int arg` → `String` |
| `getBundle` | 12 | → `Bundle` |

- **Callback:** `com.spd.carinfo.ICarInfoAidlCallback`. `onCarInfoIntChanged`=1 carries `(int what, int value, int arg)`;
  float=2, string=3, bundle=4.
- **Keys** are `CarInfo$<Group>.ID * 10000 + n`, from `CarInfo$Instruments`, `$Doors`, `$ReverseAndAVM`, `$Battery`,
  `$AirCondition`, `$Lighting` and others. Keys Tarmac reads:

| Key | Value |
|---|---|
| `Instruments.INSTANTANEOUS_SPEED` | 100043 |
| `Instruments.ENGINE_TACHOMETER` | 100042 |
| `Instruments.REMAIN_FUEL` | 100045 |
| `Instruments.MILEAGE` | 100044 |
| `ReverseAndAVM.GEAR` | 140080 |
| `ReverseAndAVM.REVERSE` | 140011 |
| `AirCondition.OUT_TEMPERATURE` | 30023 |
| `Battery.SOC` | 160029 |
| `Battery.ENDURANCE` | 160002 |

Units and the "unknown" value depend on the CAN-box decoder (`/spd/config/CarModel.xml` lists Raise, Hiworld,
Luzheng, XBS and others). Tarmac treats negative values as unknown and shows the raw numbers so they can be checked
on a car.

## Navigation to the instrument cluster or HUD

TEYES forwards AMap's standard guidance broadcast to the car through the CAN box. It has no navigation API of its
own. The receiver is `com.spd.carinfo.extra.AmapNaviManager`, inside `com.spd.carinfo`.

**Receiver.** It is registered at runtime (not in the manifest) for action `AUTONAVI_STANDARD_BROADCAST_SEND` when a
CAN-box protocol that uses it opens. It runs in a system-UID process, so the receiver is expected to be exported to
every sender; confirm with a test broadcast on the unit.

**`KEY_TYPE` values it parses:**

| `KEY_TYPE` | Extras read |
|---|---|
| 10001 (guidance) | `NEW_ICON` (int), `SEG_REMAIN_DIS` (int), `SEG_REMAIN_DIS_AUTO` (String), `NEXT_ROAD_NAME`, `ROUTE_REMAIN_TIME` (int), `ROUTE_REMAIN_TIME_AUTO`, `ROUTE_REMAIN_DIS` (int), `ROUTE_REMAIN_DIS_AUTO`, `ETA_TEXT`, `CUR_ROAD_NAME`, `EXIT_DIRECTION_INFO` (Strings), `CAMERA_SPEED` (int) |
| 10008 (current position) | `EXTRA_NAME` |
| 10019 (heartbeat / work state) | `EXTRA_STATE`. Each heartbeat re-arms a timer, and `HiworldVWComp.onAmapWorkState` clears the cluster for one state value (constant 40). |
| 13012 (lanes) | `EXTRA_DRIVE_WAY`: JSON with `drive_way_enabled`, `drive_way_size` and `drive_way_info[]` of `drive_way_number` / `drive_way_lane_Back_icon` |

- It sends `AUTONAVI_STANDARD_BROADCAST_RECV` with `KEY_TYPE` 10008 and `EXTRA_SHOW_TYPE` 1 to ask for the current position.
- Guidance is not gated on the chosen navigation app. It only observes `Settings.System SETTING_NAVI_APP`
  (`com.autonavi.amapauto`) to report whether AMap is the chosen app.

**Who receives the guidance.** Only two consumers in this firmware call `AmapNaviManager.setCallback` (when their device opens):

| Consumer | Covers | How it reaches the car |
|---|---|---|
| `protocol.afterload.volkswagen.hiworld.HiworldVWComp` | VW on the Hiworld CAN box | turn icon (`formatNaviTurnIcon`), distances, ETA, road names → `txNaviInfo` to the cluster |
| `extra.NaviInfoBoxManager` | an add-on navigation box on a third UART, with dashboard styles Benz (1), Porsche (2), Mini (3) | `TX_NAVI_INFO` 224 and `TX_DASH_UI_STYLE` 226 frames. Created only on SM6115/SM6225 units by `CarInfoManage.setThirdUartDeviceModel`, chosen with `Settings.System SETTING_THIRD_UART_BOX`. |

**Not a guidance path:**
- The other protocols' `naviZoomIn`/`naviZoomOut` (XCarBus Benz/Audi/Porsche/Land Rover, Hiworld Ford and others) only
  send AMap zoom commands (`AUTONAVI_STANDARD_BROADCAST_RECV`, `EXTRA_TYPE`/`EXTRA_OPERA`) when the car's knob turns.
- `SimpleGMCar`'s `txNaviControlInfo`/`txNaviHostStatus` handle GM's own navigation status.
- `framework.jar`'s `SpdService` also listens for `AUTONAVI_STANDARD_BROADCAST_SEND`, but only for `KEY_TYPE` 10016,
  AMap asking for day/night. It answers with the illumination state (`sendAutoMapLightStatus`).

**Setting.** There is no separate "navigation to dashboard" switch in `com.spd.carinfo`. Forwarding follows the chosen
CAN-box model (`/spd/config/CarModel.xml`) and, for the add-on box, `SETTING_THIRD_UART_BOX`.

**Music.** TEYES sends track text to the car only for its own audio sources:
- Many Raise, Hiworld, Simple and Dogen protocols send it through `updateSourceInfo` / `setSourceInfo`, fed by
  `SpdService`'s source tracking (`SourceInfoManager` via `SpdManager.registerSpdServiceCallback`).
- Nothing in TEYES's framework layer reads another app's media session, and the alink broadcasts (`alink.intent.action.CP_*`)
  carry only status and call state.
- So no supported path exists for Tarmac's now-playing to reach the cluster. FYT is the same; see
  [HEADUNIT_FYT_DUDU.md](HEADUNIT_FYT_DUDU.md).

**Track text from other apps, including TEYES's own CarPlay.**
- `com.yf.teyesspotify` (a notification listener for a fixed list of players: Spotify, YouTube Music, Yandex Music, VK and
  others, plus a list fetched from a server) re-broadcasts their metadata as `com.fyt.launcher.spotify.music`. Only the
  home-screen `MusicWidget` receives that broadcast; `com.spd.carinfo` and the framework do not.
- Alink CarPlay sends only status and call state (`alink.intent.action.CP_STATUS` and `CP_PHONE`).
- So no app's music reaches the CAN box on a stock unit.

**The way in, which needs root.** The source service `spd` (`com.spd.system.aidl.ISpdService`) is labelled
`system_api_service`, so only system and privileged apps may find it; with root a helper can. The path is:
1. `syncSourceInfo(String pkg, DataWrapper)` = 7. `DataWrapper` wraps one parcelable `SourceInfo` subclass; `MediaInfo`
   carries 4 strings and 8 ints, including the device ID. Media is device 1 (`/spd/config/systemconfig.xml`), Bluetooth 7,
   radio 9 and phone call 8.
2. `SourceManagerHandler.syncSourceInfo` stores it by package and forwards it to `com.spd.carinfo` through `sendCommonToSouce`.
3. The CAN-box protocol's `updateSourceInfo` builds the frames; for example Raise `setSourceInfo` / `setSourceInfoUSB`
   send UTF-16LE or ASCII text.
4. `enterSource(String pkg)` = 22 makes a package the current source, which can switch the unit's audio.

Record layouts, read from `framework.jar`:
- `OtherMediaInfo` (TEYES's slot for third-party players, default device ID 14): `packageName`, `playTitle`,
  `playArtist`, `playAlbum`, `playTime`, `playTotalTime`, `playIndex`, `playTotalNumber`, `playState`.
- `MediaInfo` (device ID 1): `playFileName`, `playTitle`, `playArtist`, `playAlbum`, `usb1State`, `usb2State`, `playType`,
  `playIndex`, `playTotalNumber`, `playTime`, `playTotalTime`, `playState`.

Both are preceded by the `SourceInfo` device ID and are sent as `DataWrapper` → `writeParcelable`.

**The test in Tarmac.** Settings › Advanced › "Cluster and HUD tests (experimental)" offers the track test on TEYES
when `su` exists. `TeyesTrackTextTool` runs as root through `app_process` and:
- optionally calls `enterSource(Tarmac)`;
- sends one `syncSourceInfo(Tarmac, OtherMediaInfo or MediaInfo)` with "Tarmac test / CarPlay / Tarmac";
- prints TEYES's log lines about it: source manager, `CarInfoManage`, the car protocol's `updateSourceInfo` /
  `setSourceInfo`, and any crash.

Settings shows that log. Try other media (14) first; fall back to media (1) and the active source if nothing appears.

**CAN-box brands.** The test reaches every brand, because TEYES's CAN-box service runs the protocol for the box chosen in
TEYES settings. Whether the cluster shows the text depends on that protocol's `updateSourceInfo`:

| Box brand | Protocols in this firmware | With `updateSourceInfo` | Read the track title or artist | Makes with title support |
|---|---|---|---|---|
| Oudi | 96 | 46 | 31 | BMW, Ford, Luxgen, Porsche, Subaru, Suzuki, Volvo, Wuling, Baojun, Benz, Chery, GAC, Geely… |
| Raise | 75 | 43 | 24 | Ford, Wuling, Baojun, Beijing, Changan, Chery, DFAC, Great Wall, Honda, Mazda, Nissan, Renault… |
| Hiworld | 115 | 57 | 19 | Ford, Opel, PSA, Benz, DFAC, Honda, Nissan, Toyota, Volkswagen |
| Luzheng | 39 | 20 | 16 | Ford, Porsche, Subaru, Benz, Honda, Maserati, Nissan, Toyota, Volkswagen |
| Simple (XBS) | 40 | 22 | 13 | Ford, Mitsubishi, PSA, Benz, Honda, Mazda, Nissan, Toyota, Volkswagen |
| Binary | 18 | 14 | 8 | Ford, Qoros, Honda, JMC, Nissan, Toyota |
| Sanwu, CarWisdom, Orange, Ruidawei, Keyichuang | 1–4 each | 1–2 each | 1 each | Ford, Nissan, Benz, Toyota |
| Dogen, XCarBus, BSJ, XinBus | 8–29 each | 8–14 each | 0 | source and status only, no text |

"Read the track title or artist" means the protocol's `updateSourceInfo` reads `playTitle` or `playArtist` before sending.
Whether it uses device ID 1, 14 or both is in each protocol's switch; the test shows it in the log.

**Guidance test.** The same Settings card sends about ten seconds of AMap guidance with no root: state 8 (start), five
updates to a left turn onto "Tarmac Street" carrying both `NEW_ICON` and `ICON` and an `ETA_TEXT` in AMap's
"预计HH:mm到达" form, then state 9 (end). With root, the card shows TEYES's log lines about it (`AmapNaviManager`,
`HiworldVWComp`, `NaviInfoBoxManager`). It appears on the cluster only with a Hiworld VW CAN box or the third-UART
navigation box.

**For Tarmac:** a generic `AUTONAVI_STANDARD_BROADCAST_SEND` sender (`KEY_TYPE` 10001 with the extras above, plus 10019
work state) feeds TEYES clusters on Hiworld VW and on the third-UART navigation box with no TEYES-specific adapter. Other
CAN-box protocols in this firmware have no turn-by-turn path.

## Built-in CarPlay and the Apple authentication chip

| What | Evidence |
|---|---|
| Chip bus | `/spd/etc/alink/config.json` has `"i2cPort": "2"`, read by `com.alink.carplay` (`Custom$Config.getI2cPort`). `libcarplayplugin.so` opens `/dev/i2c-%s` with `I2C_SLAVE_FORCE`. |
| Node permissions | `ueventd.rc`: `/dev/i2c-2 0777 system system`. No `file_contexts` entry, so the label is `device`. |
| SELinux | Only `system_app`, `SpdHalServer`, `early_preview` and `MFiServer` may open `device` character nodes. `untrusted_app` may not. No `setenforce` or `androidboot.selinux` is set, and the build type is `user`. |
| `spd.MFi` service | `plat_service_contexts` labels `spd.MFi` as `spdmfi_service`. Only the `MFiServer` domain may add or find it, and this firmware ships no `MFiServer` binary or init service. Not usable. |
| `spd.Hal` | `ISpdHalService` has generic device I/O (`openDevice`/`readDevice`/`writeDevice` 1–4, `openCciDevice`… 102–105). Only `audioserver`, `system_server` and `early_preview` may find it. Not usable, and it could reach the MCU. |
| Wired CarPlay USB | `/spd/etc/init/carplay.rc`: `sys.usb.config=carplay` builds an `iap2` + `ncm` gadget and switches `4e00000.ssusb` to peripheral. `nocarplay` switches back to host. Properties `vendor.alink.usb_vbus`, `persist.alink.disable_usb`, `persist.alink.auto_connect` (shipped as `0`). |
| Auto-start of TEYES CarPlay | `Settings.System allowAutoBootCarplay`, `allowAutoStartCarplayByBT`, `allowAutoBootAndroidAuto` |

What this means for Tarmac:
- **Direct I2C** (`MfiTarget.I2C` with `/dev/i2c-2`) works only where SELinux lets Tarmac open the node, for example a
  unit running permissive. Tarmac probes this with `open()`/`close()` and no bus traffic, and offers the chip only when
  the probe succeeds.
- **Running Tarmac as `system_app`** would need Tarmac signed with the unit's platform key and
  `sharedUserId=android.uid.system`. SPD Forge can re-key a firmware with your own platform key, but that is a separate,
  risky build and is not done here.
- **Rooted units.** When the probe reports "blocked" and an `su` binary exists, Settings offers "Open the chip with
  root". Tarmac then starts its own `RootI2cHelper` class through `su -c "CLASSPATH=<apk> app_process …"`, the same
  `app_process` pattern as the BYD helpers. The helper opens `/dev/i2c-N` with Tarmac's JNI and relays each transaction
  over its standard input and output (`RootI2cTransport`). Nothing on the system changes: no `chmod`, `chcon` or
  `setenforce`. The choice is saved as `MfiTarget.I2C` with `mfi_i2c_root`, and the helper is started at each CarPlay
  connection.
- **The chip is shared with TEYES CarPlay.** Two clients interleaving challenge and response transactions can break
  authentication for both. Keep TEYES CarPlay off (no auto-start, not opened) while Tarmac uses the chip. Tarmac warns when
  `allowAutoBootCarplay` is on.

## Audio

- Standard Qualcomm primary output (`/vendor/etc/audio_policy_configuration.xml`). There are no AAOS bus devices,
  so normal usages and streams apply.
- `spd.use.audio.codec.chip=SI479X` (Skyworks tuner/DSP).
- `SpdService-AudioHandler` drives amplifier mute GPIOs 38 and 58 and a separate navigation I2S path
  (`NAVI_AUDIO_I2S_OPEN`), and it requests audio focus for `com.spd.power` during power changes.
- Properties: `persist.spd.request.focus=true`, `persist.spd.use.internal.equalizer=true`, `service.media.resume.mode=0`.
- `Settings.System SETTING_REVERSEMUTE_SCALE` sets how far media is lowered while reversing.
- `/spd/config/processInfo.xml` lists apps kept alive for audio. Tarmac needs no audio hook.

## What must never be touched

- `/dev/ttyHS2` (MCU), `spd.Hal`, and the `CarInfoService` set and source calls.
- `SpdExternalService.sendMessageToSpdDevice`.
- Any `Settings.System` key. Tarmac only reads them.

## Checking on the unit (adb)

```sh
adb shell getenforce                                  # Enforcing on a stock unit
adb shell settings get system acc_state               # 1 with ACC on
adb shell settings get system carletter_reserve_state # changes while reversing
adb shell settings get system SETTING_LUNCH_FIRST_APP # the TEYES starting app
adb shell settings get system allowAutoBootCarplay    # 1 when TEYES CarPlay starts itself
adb shell ls -lZ /dev/i2c-2 /dev/ttyHS2               # expect crw-rw-rwx … u:object_r:device:s0
adb shell cat /spd/etc/alink/config.json              # i2cPort
adb logcat -s CarInfoService                          # Tarmac's getInt calls show up here
```

In Tarmac, Settings › Connection › TEYES head unit shows the platform, the starting-app state and the chip probe.
Settings › Vehicle › TEYES car signals shows ACC, reverse and the CAN-box values live.
