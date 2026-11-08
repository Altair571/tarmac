# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay vehicle state (LIVI)

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).

`shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayVehicleState.kt`, `Opack.kt` and `AirPlayInfoPlist.displayPanels` port CarPlay's vehicle-state protocol from LIVI's `livi-cp` crate (`vehicle_state.rs` and `vehicle_state/`, `opack.rs`, `info.rs`): the `/info` entries, the OPACK message format, and the climate, seat and vehicle-resource accessories. LIVI is licensed under GPL version 3 or later, with an additional attribution term under section 7(b). Its licence text is in `docs/licenses/LIVI-GPL-3.0.txt` and its notice, with that term, in `docs/licenses/LIVI-NOTICE.txt`. These files are marked `GPL-3.0-or-later`, and Tarmac's About screen and README carry the attribution. See `docs/CARPLAY_ULTRA.md`.

## Android Auto receiver

The `androidauto/` module ports protocol handling (the USB accessory switch, AAP framing, the TLS handshake order, the head-unit services and the wireless Bluetooth setup exchange) from [Headunit for Android Auto](https://github.com/mikereidis/headunit) by Michael A. Reid, [anod/headunit](https://github.com/anod/headunit) and [open-headunit](https://github.com/andreknieriem/open-headunit). Those projects are licensed under AGPL version 3 or later; the module's files are marked `AGPL-3.0-or-later`, and the original notice and licence text are in `docs/licenses/OpenHeadunit-AGPL-3.0.txt`. Message field numbers were cross-checked against [aasdk](https://github.com/opencardev/aasdk) (GPL-3.0-or-later). The source tree contains no certificate or key from these projects. See `docs/ANDROID_AUTO.md` and "Experimental authentication data" below.

Android Auto is a trademark of Google LLC. No Google affiliation or endorsement is implied.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution. The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Software Opus microphone encoder

DiPlay vendors [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors. It encodes the CarPlay microphone on head units whose Android has no MediaCodec Opus encoder.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/main/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/main/java/org/concentus/README.md) identify the source commit.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

From 0.4.1 the public APK also includes the Android Auto head-unit certificate and key that [open-headunit](https://github.com/andreknieriem/open-headunit) publishes in `app/src/main/res/raw/cert` and `privkey` (subject `O=Google-Android-Reference`, issued by Google Automotive Link, valid to 2044). They are not licensed to this project and are not relicensed as project source code. They are bundled so Android Auto can connect; Google can stop accepting them at any time. The source archive does not contain them.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## BYD HUD maneuver icons

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were imported from BYDMate. Its PolyForm Noncommercial 1.0.0 terms and required notice are included alongside the assets. These files are separate from the project code license; upstream describes them as donor assets and their original provenance is not independently established. The validated DiLink5.1 windshield path uses factory turn codes rather than these images.

## Inter

Tarmac bundles the Inter typeface (`common/src/main/assets/fonts/Inter.ttf`), Copyright 2020 The Inter Project Authors
(https://github.com/rsms/inter), licensed under the SIL Open Font License 1.1. The licence text is in
[licenses/Inter-OFL.txt](licenses/Inter-OFL.txt).
