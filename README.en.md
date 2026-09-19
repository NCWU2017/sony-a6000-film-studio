# 胶片工坊 / Film Studio

[中文](README.md) · **English** · [日本語](README.ja.md)

An unofficial in-camera film-look experiment for the **Sony a6000 / ILCE-6000**. This repository adapts and validates [ukiki0718-netizen/sony-a5100-film-studio](https://github.com/ukiki0718-netizen/sony-a5100-film-studio) for the A6000. It references the hardware color-processing approach in [bonyback1's Ricoh mod](https://github.com/bonyback1/sony-pmca-ricoh-mod) and uses [Fujifilm's publicly available GFX ETERNA 55 LUTs](https://www.fujifilm-x.com/global/support/download/lut/) as color-research references for photographs and experimental video.

**Version: 0.2.0-alpha; on-camera version: 0.2a; app name: 胶片工坊.** Documentation is available in three languages; the camera UI is currently primarily Chinese.

**Renamed to Film Studio (胶片工坊), combining ten Fujifilm-reference and five upstream Ricoh/street-style presets, fifteen in total.** Menu labels use 富士 / 理光 prefixes. The package and signing certificate are retained for an in-place update from 富士风格. This repository build has been installed and launched on a **Sony a6000 / ILCE-6000**, and look switching produces visible real-time color changes in preview. Saved still/video behavior should still be verified per version. [0.1.3-alpha](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/releases/tag/v0.1.3-alpha) remains available for rollback.

<a id="compatibility"></a>

## Camera compatibility

**The Sony a6000 / ILCE-6000 has been tested by this repository and is its primary target. This app does not work with every Sony camera.**

| Model | Status in this project |
| --- | --- |
| **a6000 / ILCE-6000** | **Primary target of this repository; installed, launched and confirmed to change preview color in real time** |
| a5100 / ILCE-5100, firmware 1.10 | Primary target of the upstream original project; more extensive upstream validation exists |
| a6300, a6500 | PMCA candidates listed upstream; untested in this repository |
| a7, a7R, a7S, a7 II, a7R II, a7S II | PMCA candidates listed upstream; this version is untested |
| RX100 III/IV/V, RX10 II/III, RX1R II, HX90 | PMCA candidates listed upstream; this version is untested |
| a6400, a6700, a7 III, a7C | Do not support the PlayMemories Camera Apps installation platform required here |
| Other models or firmware | Not assessed; a similar model name does not establish compatibility |

Candidates come from the [upstream model list](https://github.com/bonyback1/sony-pmca-ricoh-mod/blob/7c565898562c73c5073c54dfc831c8c3df9c24cf/README.md), not tests of this project's added video and strength features. PMCA is the on-camera app platform required here; MTP or phone remote control alone does not establish PMCA support.

**The current video menu is inherited from the original a5100 implementation and includes no 4K choices.** It does not promise every native format, frame rate or bitrate on other models. Successful installation must be followed by separate checks of preview, look selection, JPEG persistence, recording start/stop, saved-video playback and color reset after exit. Actual colors may differ across models. Reports should identify model, firmware, app version and exactly what was tested.

## Download and installation

**[Download APK: 0.2.0-alpha](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/releases/download/v0.2.0-alpha/FilmStudio-0.2.0-alpha-movie.apk)** · [Release notes and checksum files](https://github.com/ukiki0718-netizen/sony-a5100-film-studio/releases/tag/v0.2.0-alpha)

Download `FilmStudio-0.2.0-alpha-movie.apk`, then follow the [English installation guide](docs/INSTALL.en.md) to connect and install. No local compilation is required. **Code → Download ZIP contains source, not the installer.**

This is an unofficial experimental release. **A6000 installation, startup and real-time look changes have been verified in this repository**; the more extensive A5100 history comes from the upstream original project. The APK contains Sony base-app material and parameters fitted from publicly available Fujifilm LUTs. A separate grant to adapt and redistribute those third-party materials has not been established. Publication does not represent Sony/FUJIFILM permission or guarantee immunity; [license scope](LICENSING.md) distinguishes the rights in each part. Original official LUT files and signing private keys are not distributed.

→ **[Complete English installation guide](docs/INSTALL.en.md)**: inputs → local build → first-time connection → Wi-Fi ADB installation → camera controls → updates and troubleshooting.

With your own lawfully built APK and Wi-Fi ADB already enabled:

```sh
adb connect CAMERA_IP:5555
adb -s CAMERA_IP:5555 install -r output/FilmStudio-0.2.0-alpha-movie.apk
```

**IP address and privacy:** `CAMERA_IP` is a placeholder. Replace it with the current IP shown on your own camera in Tweak → Developer; do not type the placeholder literally or copy someone else's address. Keep the `:5555` port. Public instructions use a placeholder; hide or remove actual IP addresses before sharing screenshots or logs.

First-time users also need the preparation steps in the guide.

### Does the APK recipient need to compile anything?

**No. A signed APK can be installed through the documented procedure; runtime compatibility still depends on the camera and environment.** Recipients do not need Python, Java, Apktool or the private signing key. The local build chapters are for modifying or generating an APK yourself; doing so does not itself resolve third-party permissions.

## Features

- Ten Fujifilm official-LUT reference looks: PROVIA, Velvia, ASTIA, CLASSIC CHROME, REALA ACE, PRO Neg. Std, CLASSIC Neg., ETERNA, ETERNA BLEACH BYPASS and ACROS.
- Five upstream Ricoh/street styles: GR Positive Film, Negative Film, High Contrast B&W, Moriyama Daido Style and Cross Process. Community presets, not official Ricoh LUTs.
- Press the center button to select a look in still preview or movie standby.
- MENU page 1 →「滤镜强度」(filter strength): **30%, 50%, 70%, 100%**. Starts at 100%; shared by stills and video and saved through normal app exit.
- MENU page 1 →「拍照／录像模式」(still/movie mode) → movie P/A/S/M, then「录像文件格式」(format) and「录像帧率／画质」(frame rate/quality). Choices follow the camera's supported XAVC S, AVCHD and MP4 profiles and current PAL/NTSC system.
- MOVIE starts/stops recording. Look and strength stay fixed during recording.
- White balance remains available on MENU page 4. The app uses STD/Standard with contrast, saturation and sharpness at zero as its baseline; native Portrait/Vivid Creative Styles are not stacked in this app.
- Separate package `com.yuki.imaging.app.pictureeffectplus`, allowing coexistence with the original Ricoh mod.

For portraits, compare 30% and 50% first. Strength reduces both the color matrix and tone curve; it does not detect faces or automatically repair skin tones. **ACROS, Ricoh High Contrast B&W and Moriyama style retain some color below 100%. Use 100% for monochrome.**

## Evidence and limits

This repository has verified installation, startup and real-time look changes on a **Sony a6000 / ILCE-6000**. The detailed 0.1.x save/video records below come from the upstream original project's tests on **a5100, firmware 1.10, Android 2.3.7 / API 10**. Other models are not promised to work unless explicitly documented.

- 0.1.1: all ten look selections applied; PROVIA color and ACROS monochrome JPEGs saved; an ACROS XAVC S 1080p59.94 clip saved and fully decoded.
- 0.1.2: the user confirmed format/quality menus were usable. Every encoded format has not been inspected.
- 0.1.3: installation, startup and default-look application verified; the user gave general confirmation of the new controls. Every look/strength/format combination has not been tested in saved media.

**In-app playback currently lists photographs only.** Exit to native playback and choose the appropriate XAVC S, AVCHD or MP4 view to see movies. See [validation notes](docs/VALIDATION.md).

This is not a complete port of Fujifilm's in-camera Film Simulation. F-Log2/F-Gamut LUTs cannot be applied directly to ordinary Sony imagery. The fitting process uses WDR-709 as a proxy neutral reference, producing a 3×3 matrix and a common 1024-point curve. The a6000 has not yet received a complete camera-specific color calibration; grain and sensor response are not simulated, and some looks have substantial approximation error.

## License, ownership and sources

Original project contributions use **[PolyForm Noncommercial 1.0.0](LICENSE)**. This is noncommercial source-available software, not an OSI open-source license. Commercial use is not granted under this license; its exact scope and exceptions are controlled by the original text. Third-party licenses remain separate and their existing rights are not revoked.

**Official Fujifilm LUTs and associated material belong to Fujifilm and their respective rights holders. Referencing them is not official authorization.** This project does not represent Sony, FUJIFILM or Ricoh. Attribution, noncommercial terms and disclaimers do not replace permission or guarantee immunity.

- [License scope and ownership, three languages](LICENSING.md)
- [Third-party notices and modifications](NOTICE)
- [Sources](docs/SOURCES.md)
- [Rights inquiries](docs/RIGHTS.md)

Except where applicable law requires otherwise, the software is provided as is without promises of compatibility, color accuracy or non-infringement. Back up your card and test with disposable footage. Nothing here excludes liability that cannot lawfully be excluded.
