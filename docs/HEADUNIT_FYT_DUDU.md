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
- **Navigation to the cluster:** FYT forwards AMap's standard guidance broadcast on many CAN-box protocols.
  **Music to the cluster:** FYT's third-party-player interface (main command 23 with app ID 10) carries title, artist
  and album to about 90 CAN-box profiles. It needs no root, and FYT's own CarPlay never uses its text fields.

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

`com.syu.ms` and `com.syu.mcukey` read the steering keys from the MCU. Media keys reach the playing app through Android's
media-button path (`MediaFocusControl.dispatchMediaKeyEvent`), which Tarmac's media session already handles.
`FinalBsp` lists FYT's own button codes 220–243 (for example `KEY_CODE_VA` 233, `KEY_CODE_PHONE` 238), delivered through
`U_BUTTON_EVENT` (2) of the BSP module. Mapping the voice and phone keys needs a unit, so the FYT adapter maps no keys yet.

## Navigation and music to the instrument cluster or HUD

| What | TEYES (CC4) | FYT (UIS7870) |
|---|---|---|
| Turn-by-turn | `AUTONAVI_STANDARD_BROADCAST_SEND` `KEY_TYPE` 10001 → Hiworld VW and the third-UART nav box only | `AUTONAVI_STANDARD_BROADCAST_SEND` read by about 30 CAN-box protocols in `com.syu.ms` (`k0.*`, `module.canbus.*`; names obfuscated). They read `ICON`, `SEG_REMAIN_DIS`, `ROUTE_REMAIN_DIS`, `ROUTE_ALL_DIS`, `ROUTE_ALL_TIME`, `NEXT_ROAD_NAME`, `CAR_DIRECTION`, `ROUNG_ABOUT_NUM`, `EXTRA_STATE`. MXNavi's `com.mxnavi.mxnavi.TO_CTRL_TURNING_INFO` is also received. |
| Music (title, artist) | Only for TEYES's own sources (`updateSourceInfo` / `setSourceInfo` in many Raise, Hiworld, Simple and Dogen protocols). TEYES's framework layer does not read other apps' media sessions. | Through FYT's third-party-player interface: main command 23 with app ID 10 (see below), shown by about 90 CAN-box profiles. `com.syu.ms` reads no media sessions. |

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
- **Who shows it.** About 90 CAN-box profiles in `com.syu.ms` (`k0.*`, `module.canbus.*`) read `l3`, `m3`, `n3` and `o3`
  and send them to the car.
- **Why the cluster is blank with FYT's CarPlay.** It sends only `{10, …}` numbers through command 23 and never the
  text sub-commands.
- **Tarmac's test** (Settings › Advanced › Cluster and HUD tests) binds `com.syu.ms.toolkit` without root. It optionally
  sends `C_APP_ID {10}`, then `C_PLAY_INFO {10, 0} "Tarmac test"`, `{10, 1} "CarPlay"` and `{10, 2} "Tarmac", ""`.

**Raw CAN-box frames.** The canbus module (7) lists `C_CANBUS_FRAME_TO_MCU` / `C_CANBUS_HIDWORLD_TO_MCU` (1008),
`C_CANBUS_FRAME_TO_MCU_0X10` (1013) and `C_CANBUS_FRAME_TO_MTU` (1012).
- In this build of `com.syu.ms`, no handler compares against them and no switch table covers them, so they would be
  ignored. The constants live only in FYT's shared client library (`com.lsec.core.util.data.FinalCanbus`).
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

## Checking on the unit (adb)

```sh
adb shell getenforce
adb shell ls -lZ /dev/i2c-2 /dev/ttyS0 /sys/fytver/acc_on
adb logcat -s Lsec:D            # "received acc state:on/off" from LsecPower when the car sleeps and wakes
adb shell dumpsys activity broadcasts | grep -A3 'com.syu.ms'
```
