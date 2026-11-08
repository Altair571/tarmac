# FYT head units with DUDU OS (UIS7870 firmware analysis, November 2026)

This note records what Tarmac can learn from an FYT head unit and how. The unit analysed runs DUDU OS on a Unisoc
UIS7870. Everything below was read out of the public firmware package. Nothing from it is in this repository, and nothing
was run from it. The TEYES counterpart is [HEADUNIT_SPD_TEYES.md](HEADUNIT_SPD_TEYES.md).

## Short answer

- **This is the Unisoc platform.** `UNISOC/uis7870sc_2h10_nosec/uis7870sc_2h10:13`, Android 13 (SDK 33), with FYT's
  `com.syu.*` system apps in `/odm/app` and DUDU OS (`com.dudu.autoui`) on top.
- **ACC.** `com.syu.ms` sends the broadcast `com.syu.ms` with int extra `cmd.acc`: `1` on, `0` off. It is not targeted at a
  package and needs no permission, so any app can receive it with a runtime receiver.
- **ACC, reverse and lights through IPC.** `com.syu.ms` exports a toolkit service any app can bind. Its main module sends
  `U_ACC_ON` (50), `U_BACKCAR` (12) and `U_LAMPLET` (4) to registered callbacks.
- **Apple authentication chip.** FYT's CarPlay opens `/dev/i2c-N`, normally `/dev/i2c-2`, from a root daemon. The nodes are
  `0660 system:system`, so an ordinary app is refused by file permissions alone, whatever SELinux does. **Tarmac cannot use
  the native chip on a stock FYT unit.**
- **Navigation to the cluster:** FYT forwards AMap's standard guidance broadcast on 20 CAN-box protocols (117 of FYT's
  1,312 named profiles).
  **Music to the cluster:** FYT's third-party-player interface (main command 23 with app ID 10) carries title, artist
  and album on 57 protocols (343 named profiles). It needs no root, and FYT's own CarPlay never uses its text fields.
- **CAN-box profile:** the canbus module reports the chosen profile's ID and its own feature flags. Tarmac shows them,
  with whether the profile carries guidance and track text.
- **Wheel keys:** `com.syu.ms` forwards the voice and phone keys to FYT's own CarPlay only while `sys.carlink.type` is
  set. On a rooted unit Tarmac sets it during CarPlay (Settings › Vehicle, experimental). See [Wheel keys](#wheel-keys).

## Firmware

| | |
|---|---|
| Archive | `Global_DUDU7870_2609291559_2609181112.7z`, 3,027,337,496 bytes, SHA-256 `cfc449dc7dbae42c80fe3bbd675b80ae788256db3455e0b3228a1c9bf35c85f2` |
| Contents | `6318_1.zip` (signed A/B OTA: `payload.bin`), `lsec6318update` (2.6 MB opaque updater, not analysed), `Factory Reset/AllAppUpdateA13.bin` (app bundle; the readme says it replaces the voice assistant), `Factory Reset/config.txt` and `updatecfg.txt` (`formatdata`), `DUDUAUTO` (196-byte encrypted marker), `readme.txt` |
| OTA | `ota-type=AB`, `pre-device=uis7870sc_2h10`, `post-fotaversion=DUDU7870_2609291559_2609181112`, security patch 2023-03-05 |
| Partitions | `system`, `system_ext`, `product`, `vendor`, `odm` (1.7 GB, FYT and DUDU apps), `vendor_dlkm`, plus Unisoc modem, TrustOS and boot images. Filesystems are EROFS, LZ4 with zero padding. |
| Build | `ro.build.type=user`. No `androidboot.selinux` in the boot or vendor_boot command lines. |

Extraction used macOS `bsdtar`, a read-only payload reader and a read-only EROFS reader. APK and jar zip CRCs verify.

## Platform detection

FYT's apps are system apps with `sharedUserId=android.uid.system` and `targetSdkVersion=24`, in `/odm/app/190000000_com.syu.*`:

| Package | Role |
|---|---|
| `com.syu.ms` | the core "module service": MCU link, ACC, reverse, sound, CAN box, steering keys |
| `com.syu.canbus` | car-info screens for each CAN-box protocol |
| `com.syu.mcukey` | MCU and steering keys |
| `com.syu.carlink` | built-in CarPlay and Android Auto; its native libraries run in `CarplayService` |
| `com.syu.settings`, `com.syu.carui`, `com.syu.us_a13`, `com.syu.cs`, `com.syu.ss`, `com.syu.ps` | settings, car UI and support apps |
| `com.dudu.autoui`, `com.wow.fyt7862.*`, `com.duduos.ota_update` | DUDU OS launcher, market, OTA |

`framework.jar` carries `com.syu.ipc` (`IRemoteToolkit`, `IRemoteModule`, `IModuleCallback`, `FinalBsp`, `RemoteModuleBsp`)
and `android.source.SyuPrivate`. `services.jar` carries `com.android.server.LsecService` (`syu` in servicemanager) and
`com.android.server.power.LsecPower`. The MCU UART is opened by `com.syu.ms` (`/dev/ttyS0`).

Tarmac recognises FYT when `com.syu.ms` and `com.syu.canbus` are installed as system apps.

## `com.syu.ms` IPC

- **Services.** `app.ToolkitService` (action `com.syu.ms.toolkit`) and `app.ModuleService` (actions
  `com.syu.ms.main` … `com.syu.ms.gestrue`). Both are exported through their intent filters with target SDK 24, and
  neither needs a permission.
- **`com.syu.ipc.IRemoteToolkit`.** Transaction 1 is `getRemoteModule(int module)` → `IRemoteModule`.
- **Module codes** (from `ModuleService`): main 0, radio 1, bt 2, dvd 3, sound 4, ipod 5, tv 6, canbus 7, tpms 8, dvr 9,
  steer 10, customer 11, obd 12, test 13, can.up 14, amp 15, emitter 16, gsensor 17, gesture 18.
- **`com.syu.ipc.IRemoteModule`:**
  - `cmd(int, int[], float[], String[])` = 1. This sends commands to the MCU. **Never called.**
  - `get(int, int[], float[], String[])` = 2.
  - `register(IModuleCallback, int code, int update)` = 3.
  - `unregister(IModuleCallback, int code)` = 4.
- **`com.syu.ipc.IModuleCallback`.** `update(int code, int[] ints, float[] flts, String[] strs)` = 1.
- **Main-module update codes** (`com.lsec.core.util.data.FinalMain`, readable in `com.syu.av` and `com.syu.carui`):
  - `U_MCU_ON` 1, `U_LAMPLET` 4 (illumination), `U_BACKCAR` 12, `U_RADAR` 13, `U_RADAR_*` 14–21;
  - `U_BACKCAR_RADAR` 22, `U_BACKCAR_TRACK_ENABLE` 23, `U_BACKCAR_MIRROR` 24, `U_BACKCAR_TYPE` 47;
  - `U_ACC_ON` 50, `U_HOST_BACKCAR_ENABLE` 65.

## ACC, sleep and wake

| Signal | Where | Readable by Tarmac |
|---|---|---|
| broadcast `com.syu.ms`, int extra `cmd.acc` (`1` on, `0` off) | sent by `com.syu.ms` with `Context.sendBroadcast` (no package, no permission). `LsecPower$AccReceiver` in system_server listens for it. | Yes, with a runtime receiver |
| main module `U_ACC_ON` (50) | toolkit callback | Yes |
| `/sys/fytver/acc_on` | mode `0666`, label `sysfs_lsec` | No: no app domain may read `sysfs_lsec` |
| properties `persist.lesc.carui.accstate`, `sys.fyt.sleeping`, `sys.lsec.sleep_when_acc_off` | `system_prop` / `lsec_sys_prop` | No: only `system_app` and FYT domains may read them |

The unit sleeps at ACC off (`LsecPower.accOff`/`accOn`, airplane mode while asleep with `ro.fyt.sleep_airplane`).

## Reverse and cameras

- Main module `U_BACKCAR` (12) over the toolkit callback.
- System server's `syu` service has a BSP module (`MODULE_CODE_BSP` 0) whose `U_BACK_CAR` = 4. SELinux lets
  `untrusted_app` find `syu_service`, but finding it needs the hidden `ServiceManager.getService`, so Tarmac uses the
  toolkit instead.
- `sys.car.reverse` (property `system_prop`, not readable by apps) drives `/sys/lsecdcamctl/fyt_car_reverse`.
- `ro.fyt.backcar_*` properties hold the camera options.
- The camera apps are `com.syu.av`, `com.syu.doublecamera`, `com.syu.frontvideo`, `com.syu.rightcamera` and
  `com.syu.fourcamera2` (360°, in `/lsecfs/360`).

## Built-in CarPlay and the Apple authentication chip

| What | Evidence |
|---|---|
| Daemon | `lsec.carplay.rc`: `sys.fyt.carplay=1` starts `CarplayServer` = `/system/bin/cps.sh` as root with `seclabel u:r:shell:s0`. It runs `/system/bin/CarplayService` (or `CarplayService_nb`) with `LD_LIBRARY_PATH=/data/data/com.syu.carlink/lib`. |
| MFi code | `com.syu.carlink`'s `libcarplayplugin_r14g.so` and `libcps_*.so`: `/dev/i2c-%1d`, the literal `/dev/i2c-2`, `GetI2cIndexAddressFromKernel`, `mfi_scan_address`, "Using MFi Auth v.%d chip", `I2C_SLAVE` |
| Node permissions | `ueventd.rc`: `/dev/i2c-* 0660 system system`. `odm_file_contexts`: `/dev/i2c-0…6` → `lsi2c_device`. Policy lets only `nativecamera` open `lsi2c_device`. |
| USB | `iap2` + `ncm` gadget, `/sys/devices/platform/soc/*/ls_carplay`, `/dev/carlink_iap` |
| Services | `CarplayService` and `lsnatives` (`LsNativeServer`, user `system`) as `lsec_service`. No app domain may find `lsec_service`. |

What this means for Tarmac:
- **Tarmac cannot open the chip.** It is not `system` and not in group `system`, so `open("/dev/i2c-2")` fails with `EACCES`
  from file permissions. That holds even if a unit runs SELinux permissive.
- **Local ADB cannot reach it either.** Its shell user is uid 2000, which those permissions also refuse.
- **FYT's own daemon** reaches the chip only because it runs as root. Its `shell` label has no `lsi2c_device` rule, so the
  shipped units probably run SELinux permissive, but this is not confirmed.
- **The only routes** would be a root process or Tarmac signed as `android.uid.system` with FYT's platform key. Neither
  is available to Tarmac.
- **Tarmac's probe** opens and closes `/dev/i2c-2` with no bus traffic, reports "blocked", and keeps Tarmac's own
  identity.
- **Rooted FYT units** can use the chip through Tarmac's root helper (Settings › Connection › FYT head unit › "Open the
  chip with root"). The helper runs as root, which passes the `0660 system` check. Whether SELinux lets the `su` domain
  open `lsi2c_device` depends on the root manager; Magisk's `su` domain may open any node. The setup is described in
  [HEADUNIT_SPD_TEYES.md](HEADUNIT_SPD_TEYES.md#built-in-carplay-and-the-apple-authentication-chip).
- **The bus index** comes from the kernel (`GetI2cIndexAddressFromKernel`), so a unit can differ from `/dev/i2c-2`. Check
  with `adb shell ls /dev/i2c-*` and the CarPlay log.

## Wheel keys

`com.syu.ms` and `com.syu.mcukey` read the steering keys from the MCU. `com.syu.ms` passes each key through a chain of
handlers (`c.e` subclasses); the first one that takes the key stops it.

**What FYT's own CarPlay and Android Auto app does.** `com.syu.carlink` does not read keys itself:
- It binds `com.syu.ms.toolkit` and registers for main-module update **133** (`o.e.d(0, …)`, with codes 0, 1, 4, 43, 50,
  60, 133, 138, 166–168, 186, 197).
- `com.syu.ms`'s link handler (`h1.a$c.e`, log "TO CarLink keyCode") forwards a key as update 133 `{keyCode}`. It does so
  only while the system property `sys.carlink.type` is above 0. `com.syu.carlink` sets that property itself
  (`SystemProperties.set`) while a phone session runs.
- Forwarded keys: 5 call, 6 end call, 85 play/pause, 87 next, 88 previous, 126 play, 127 pause, 574 home, 575 back,
  576 voice, 587/588 phone (FYT's codes; 574–576 and 587/588 are FYT's own).
- While a CarPlay call rings, 588 or 575 is sent as 5 (answer); during a call, 587 or 574 is sent as 6 (hang up). This
  happens when FYT's "call action" option is 1.
- `com.syu.carlink` turns them into its own codes (`CarLinkService.onHandle`: 5→0x1003, 6→0x1004, 576→0x1005, …) and
  sends them to the phone. Its Android Auto keymap (`f.k.a`) sends 0x1005 as `KEYCODE_SEARCH`, the voice key.
- It also registers on the `syu` system service's BSP module, for update 8 (audio focus) only. The BSP `U_BUTTON_EVENT`
  (2) codes in `FinalBsp` (220–243) are not used for this.

**Without a link session** (`sys.carlink.type` 0), `com.syu.ms` handles the keys itself:
- Media keys go to the playing app as media-button events (`AudioManager.dispatchMediaKeyEvent`). Tarmac's media session
  already receives them.
- The voice key opens the voice app chosen in FYT's settings (`o1.u.P0`). For Google it sends `VOICE_COMMAND`; for any
  other choice it opens that app's launcher activity.
- The phone keys open FYT's Bluetooth phone.

**Other readers of `sys.carlink.type`.**
- `u0.a.i`: when the current third-party player (`persist.sys.syu.thirdplayer`) changes to an app other than
  `com.syu.carlink`, `com.syu.ms` sets the property back to 0.
- `module.sound.C7604.a` changes the audio path for type 3 only while channel 15 plays and FYT's own CarPlay call state
  (`u0.i.r3`) is set. Tarmac never sets that call state.
- With type 1 or 2, keys 5 and 6 go to FYT's Bluetooth phone when it has a call. With type 3 they are forwarded
  unchanged.
- The voice-app launcher (`o1.u.b1`) does nothing while the type is above 0. Some CAN-box protocols act on it only
  while `com.syu.carlink` is in front.

**Tarmac on a rooted unit** (Settings › Vehicle › FYT car signals › "Voice and call keys with root (experimental)",
off by default; `FytKeyLink`):
- While a CarPlay session runs, Tarmac's root helper (`FytKeyGateTool`, through `app_process`) holds `sys.carlink.type`
  at 3. It checks once a second, because `com.syu.ms` may clear it.
- Tarmac registers for main update 133 without asking for the current value, so the last forwarded key is not replayed.
  The helper starts only after that registration succeeds, and stops if `com.syu.ms` goes away, so forwarded keys never
  go unanswered.
- **Sent to CarPlay:** 576 opens Siri; 5 and 588 answer; 6 and 587 end the call. The 587/588 pairing is inferred from
  `com.syu.ms`'s call handling and needs a unit.
- **Given back:** media keys go to the playing app as media buttons (`AudioManager.dispatchMediaKeyEvent`), as
  `com.syu.ms` does. Home (574) and back (575) are injected as root (`InputManager.injectInputEvent`, Android key 3 and
  4).
- **Ending the link:** it stops when the session ends or disappears, or when the driver turns the switch off. The helper
  then puts back the previous value. It also does so when its input closes or Tarmac's process is gone, so a crash
  cannot leave the unit's keys taken.
- The root manager asks when the switch is turned on, while parked, rather than at the next connection.

## CAN-box profile

The profile chosen in FYT's factory settings lives in the canbus module (7) of `com.syu.ms`. Tarmac reads it without
root (Settings › Vehicle › FYT CAN box).

| What | Where |
|---|---|
| Profile ID | update `U_CANBUS_ID` (1000), `{id}`. The ID packs the protocol (low 16 bits) and the car variant (high 16 bits). `FinalCanbus` names 1,312 of them, for example `CAR_WC1_DaZhong` = 1. 0 means no CAN box. |
| Profile's own flags | `U_EXIST_DOOR` 1004, `U_EXIST_AIR` 1007, `U_EXIST_PANORAMA` 1009, `U_EXIST_TEMP_OUT` 1012 (also `U_DRIVER_ON_RIGHT` 1003, `U_EXIST_CAR_RADIO` 1016, `U_ORI_CARBACK` 1021, `U_EXIST_CAR_DVR` 1034) |
| Change notice | broadcast `com.fyt.changecanbusid`, int extra `canbusid` |
| System property | `ro.fyt.canbus_id` |

`register(callback, code, 1)` asks for the current value at once, as `com.syu.carlink` does. FYT keeps no profile names
that other apps can read, so Tarmac shows the two numbers and the ID.

**Which profiles carry guidance and track text.** `k0.wt.i0(int id)` builds the protocol object for an ID: it switches
on the protocol number, then on the variant. Tarmac's table (`HeadUnitCanTables`) comes from running that method on
every path, read-only, with each protocol class checked for:
- **guidance**: it reads AMap's extras (`SEG_REMAIN_DIS`, `ROUNG_ABOUT_NUM` or the action name). 117 of the 1,312
  named IDs do.
- **track text**: it reads the third-party player's title or artist (`DataMain.l3` / `m3`). 343 of the named IDs do.

The table holds only IDs. It cannot tell whether a particular car's cluster shows the result, so Settings points to the
tests in Advanced.

## Navigation and music to the instrument cluster or HUD

| What | TEYES (CC4) | FYT (UIS7870) |
|---|---|---|
| Turn-by-turn | `AUTONAVI_STANDARD_BROADCAST_SEND` `KEY_TYPE` 10001 → Hiworld VW and the third-UART nav box only | `AUTONAVI_STANDARD_BROADCAST_SEND` read by about 30 CAN-box protocols in `com.syu.ms` (`k0.*`, `module.canbus.*`; names obfuscated). They read `ICON`, `SEG_REMAIN_DIS`, `ROUTE_REMAIN_DIS`, `ROUTE_ALL_DIS`, `ROUTE_ALL_TIME`, `NEXT_ROAD_NAME`, `CAR_DIRECTION`, `ROUNG_ABOUT_NUM`, `EXTRA_STATE`. MXNavi's `com.mxnavi.mxnavi.TO_CTRL_TURNING_INFO` is also received. |
| Music (title, artist) | Only for TEYES's own sources (`updateSourceInfo` / `setSourceInfo` in many Raise, Hiworld, Simple and Dogen protocols). TEYES's framework layer does not read other apps' media sessions. | Through FYT's third-party-player interface: main command 23 with app ID 10 (see below), shown by 57 protocols (343 named profiles). `com.syu.ms` reads no media sessions. |

**Navigation.** A single sender of AMap's standard broadcast feeds the clusters on both platforms with no
platform-specific code:
- send `KEY_TYPE` 10001 with both `ICON` (FYT) and `NEW_ICON` (TEYES), plus the distance, road and time extras;
- send 10019 `EXTRA_STATE` when guidance starts and ends.

Which cars show it depends on the CAN-box model the driver chose. Tarmac only sends the public broadcast; the vendor's
service decides what reaches the CAN bus.

**Music on FYT: the third-party-player interface.** FYT does take another app's track text, through its main
module (code 0). Command 23 (`C_PLAY_INFO`) is handled by `u0.d.y2(int[] ints, float[], String[] strs)`:

| `ints` | `strs` | Effect |
|---|---|---|
| `{appId, 0}` | `{title}` | stores the title (`DataMain.l3`), sends `U_ID3_TITLE` (49) and `U_PLAY_STATUS` (74) |
| `{appId, 1}` | `{artist}` | stores the artist (`m3`), sends 74 |
| `{appId, 2}` | `{album, …}` | stores the album (`n3`), sends 74 |
| `{appId, 8}` | `{text}` | stores a further text field (`o3`), sends 74 |
| `{appId, 3…11}` | none | play state, times and indexes (`z2`, `B2`, `A2`, `b3`, `W2`, `j0`, `c2`, …) |

- **Gate.** `appId` must be the current app (`DataMain.D`). Command 0 (`C_APP_ID`, `{appId}`) makes an app current. It is
  FYT's audio-channel switch (it logs "UI切的通道"), so it can move the unit's audio.
- **App IDs.** `FinalMain.APP_ID_*`: `THIRD_PLAYER` 10 (FYT's own CarPlay uses it), `AUDIO_PLAYER` 8, `BTAV` 3, `RADIO` 1,
  and so on.
- **Who shows it.** About 90 protocol classes in `com.syu.ms` (`k0.*`, `module.canbus.*`) read `l3`, `m3`, `n3` and
  `o3` and send them to the car; 57 of them are reachable from FYT's named profiles (see [CAN-box profile](#can-box-profile)).
- **Why the cluster is blank with FYT's CarPlay.** It sends only `{10, …}` numbers through command 23 and never the
  text sub-commands.
- **Tarmac's test** (Settings › Advanced › Cluster and HUD tests) binds `com.syu.ms.toolkit` without root. It optionally
  sends `C_APP_ID {10}`, then `C_PLAY_INFO {10, 0} "Tarmac test"`, `{10, 1} "CarPlay"` and `{10, 2} "Tarmac", ""`.

**Raw CAN-box frames.** The canbus module (7) lists `C_CANBUS_FRAME_TO_MCU` / `C_CANBUS_HIDWORLD_TO_MCU` (1008),
`C_CANBUS_FRAME_TO_MCU_0X10` (1013) and `C_CANBUS_FRAME_TO_MTU` (1012).
- **Correction (9 Nov 2026):** they are not ignored. The canbus module's command handler (`k0.xt.N2`) has a
  packed-switch from 1008. Its targets:
  - 1008 sends `[0xE3, ints…]` to the MCU;
  - 1012 sends `[0x91, ints…]`;
  - 1013 sends `[0x10, ints…]`;
  - 1042 sends OBD raw bytes.

  An earlier reading missed the switch. Tarmac never sends any canbus-module command. Canbus `cmd(1000, {id})`
  switches the CAN-box protocol and is off limits too.
- Root opens no other route. `com.syu.ms` owns the MCU serial port (`/dev/ttyS0`), and writing to it alongside FYT would
  interleave bytes on a link FYT is using.
- For reference, from TEYES's Raise Toyota (RAV4) protocol:
  - track-text body: `0x99, 0x22, source, field`, then the text in UTF-16LE (at most 30 bytes), zero-padded to 36 bytes;
  - source codes: USB `0x84`, SD `0x86`, Bluetooth `0x85`, AUX `0x87`, FM/AM `0x81`/`0x82`, phone `0x05`;
  - field numbers: 2 title, 3 artist, 4 album;
  - source change: `0x85, 0x02, 0x20, 0x05`;
  - TEYES's IO layer adds the Raise header (`0x2E`) and checksum.

**Guidance test.** The same Settings card sends about ten seconds of AMap guidance: state 8 (start), five updates to a
left turn onto "Tarmac Street" carrying both `ICON` and `NEW_ICON`, then state 9 (end). It needs no root on either unit.
With root, the card also shows the unit's log lines about it.

## Climate

CarPlay's climate (the CarPlay Ultra experiment, [CARPLAY_ULTRA.md](CARPLAY_ULTRA.md#climate-from-the-head-unit)) reads
FYT's shared climate codes and changes them through FYT's voice-assistant command. All of it was read from
`com.syu.ms`, `com.syu.canbus` and `com.syu.carui`; none of it has run on a car.

**Reading, canbus module (7).** Callbacks registered with `update` = 1 get the current value at once:

- The module itself sends codes 1000 and up.
- Below 1000, the protocol sends the value; 606 of 707 protocol implementations do.
- The cache resets to 0 when the protocol changes, so a replayed 0 can mean "not reported yet".

| Code | `FinalCanbus` name | Tarmac reads |
|---|---|---|
| 1000 | `U_CANBUS_ID` | `variant << 16 \| protocol` |
| 1007 | `U_EXIST_AIR` | the protocol's own "has climate" flag; 421 protocol classes set it to 1 |
| 10, 11, 13, 53, 62, 65 | `U_AIR_POWER`, `_AC`, `_AUTO`, `_ACMAX`, `_SYNC`, `_FRONT_DEFROST` | 0 off, otherwise on |
| 12 | `U_AIR_CYCLE` | 0 fresh air, otherwise recirculation (as FYT's popups show it) |
| 21 | `U_AIR_WIND_LEVEL_LEFT` | the fan; negative hides it, and the top level depends on the protocol (6, 7, 8 or 15) |
| 27, 28 | `U_AIR_TEMP_LEFT`, `_RIGHT` | target temperature; see below |
| 29, 30 | `U_AIR_SEAT_HOT_LEFT`, `_RIGHT` | seat heating 0–3 |

About 490 of FYT's 497 per-protocol climate popups read these numbers. Tarmac uses them only when both of these hold:

- `U_EXIST_AIR` is 1;
- the protocol is not one that moves climate to other numbers: 336, 423, 442 (KSW Audi Q5), or ID 65979 (SGMW,
  protocol 443 variant 1).

About 34 protocols use the range for car settings; they are expected to report `U_EXIST_AIR` 0. That was not
checked protocol by protocol.

**Temperatures.** All protocols share three markers: −3 is HI, −2 is LO and −1 is no value. The number's scale
varies by protocol:

- tenths of a degree (WC1 VW, WC2 Focus, XP1 GM);
- half degrees with 254 for LO and 255 for HI (MQB);
- the raw CAN byte, or an index.

Tarmac shows a number only in tenths: 150–330 on half-degree steps, and not 255, which is MQB's HI. Otherwise
the driver's target does not decode, and Tarmac describes no climate for that car rather than show a wrong one.
`persist.fyt.reversetemp` can swap left and right on some protocols; Tarmac cannot read it.

**Changing, main module (0) `cmd(20 = C_VA_CMD, {vaCode, value})`.** This is FYT's voice-assistant interface.
`u0.g.b` hands it to the current protocol's handler, with no app-ID gate. About 108 protocol classes implement it;
the rest drop it silently. Main update 72 (`U_AIR_CONTROL_SUPPORT_CMD`) lists the codes the active protocol
accepts, and Tarmac sends only codes on that list.

| VA code | Tarmac sends | For |
|---|---|---|
| 34 `VA_CMD_AIR_AC_ON` | 0 or 1 (absolute) | A/C |
| 36 `VA_CMD_AIR_FRONT_DEFROST_ON` | 0 or 1 | front defrost |
| 38 `VA_CMD_AIR_CYCLE_TYPE` | 1 inside, 0 outside | recirculation |
| 39 `VA_CMD_AIR_BLOW_LEVEL` | −1 one step up, −2 one step down | fan |
| 41, 42 `VA_CMD_AIR_TEMP_LEFT` / `_RIGHT` | 1 up, 0 down | target temperature |

FYT has no shared command for MAX A/C or seat heating, so Tarmac refuses those. Each command is checked against the
car's next reading, and sending stops at the first one the car does not answer, as on TEYES. Never used:

- the `_DIRECT` temperature codes 55 and 56, whose values are raw on some protocols;
- every canbus-module command.

FYT's own climate popup may appear over CarPlay whenever the climate changes, unless `persist.fyt.showduduair` is
set.

## Radio

CarPlay's Radio app (the CarPlay Ultra experiment, [CARPLAY_ULTRA.md](CARPLAY_ULTRA.md#radio-from-the-head-unit))
plays FYT's own tuner: the radio module (1) of `com.syu.ms` (`w0.i`), through the same toolkit.

Two packages are not the tuner:

- `com.syu.radio` is only a launcher stub for DUDU's packed media app.
- `com.syu.carradio` shows the car's own radio over the CAN box.

**Reading** (callbacks with `update` = 1 send the current value at once):

| Code | Payload |
|---|---|
| 0 `U_BAND` | AM1 0, AM2 1, FM1 65536, FM2 65537, FM3 65538 |
| 1 `U_FREQ` | FM in 10 kHz (8750 = 87.5 MHz), AM in kHz |
| 4 `U_CHANNEL_FREQ` | one update per preset: `{index, freq}`; FM presets are `65536 + (band − 65536) × 6 + slot` |
| 14 `U_RDS_CHANNEL_TEXT` | one update per preset: `{index}` and the station name |
| 26 / 13 | RDS PS / radiotext |
| main 0 `U_APP_ID` | 1 while the radio is FYT's current app, which is the only time it is heard |

**Commands** Tarmac sends:

| Command | Code and ints |
|---|---|
| select a preset | 7 `{index}` |
| tune a frequency | 13 `{3, f}` |
| change band | 11 `{65536}` |
| next / previous preset | 0 / 1 |
| make the radio current (play, and after each tune) | main `C_APP_ID {1}` |
| pause | main `C_APP_ID {10}`, back to the third-party player FYT's CarPlay uses |

Notes:

- **Never sent:** scan, save, search, area, sensitivity, antenna, power and the pass-through codes (9, 10, 8, 12,
  14–27, 32–34), which rewrite presets or the region or touch the audio path.
- **Tuner type and sleep:** the unit picks a tuner type (`sys.fyt.radio_type`). With the ARM-side tuner, commands are
  dropped while the MCU, ACC or screen is off.
- **DAB:** none in this firmware.

## Checking on the unit (adb)

```sh
adb shell getenforce
adb shell ls -lZ /dev/i2c-2 /dev/ttyS0 /sys/fytver/acc_on
adb logcat -s Lsec:D            # "received acc state:on/off" from LsecPower when the car sleeps and wakes
adb shell dumpsys activity broadcasts | grep -A3 'com.syu.ms'
```
