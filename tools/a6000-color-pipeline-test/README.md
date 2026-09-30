# A6000 Color Pipeline Test

用于 Sony A6000 的受控色卡标定 APK。它专门采集三类尚缺的实机响应数据：

1. 全局 Saturation：0 / -3 / +3
2. RGBMatrix：Identity / G→R 64 / R→B 64
3. SelectedColor：off、固定红色参考、Saturation 0/63、Phase ±30、Range 1/63，以及第二通道蓝色参考

## 安全策略

- 启动时先读取并备份 Saturation、RGBMatrix、ColorSelect mode 和可读取的 SelectedColor 通道。
- 每个测试步在写参数前先恢复公共 baseline，避免多个变量叠加。
- DELETE：立即恢复启动时的参数。
- MENU：恢复参数后退出。
- 所有 apply/readback/capture 写入 `/LUTS/COLORTEST.LOG`。
- 照片自动保存到 `/DCIM/COLORTEST/`，文件名与测试步骤一致。

> SelectedColor 原模式如果不是 `off`，CameraEx 没有公开“哪些通道当前 enabled”的 getter。测试工具会保存所有可读通道并在退出时尽力恢复；因此建议运行测试前不要让另一个 SelectedColor/Picture Effect app 保持活动状态。

## 操作

- LEFT / 拨轮1逆时针：上一步
- RIGHT / 拨轮1顺时针：下一步
- CENTER：写入当前测试参数并 readback
- S1：AF
- S2：拍摄并自动保存 JPEG
- DELETE：恢复原参数
- MENU：恢复并退出

每一步先按 CENTER，看到 `APPLY OK` 再按快门。

## 15 个测试步骤

| # | 文件名 | 设置 |
|---|---|---|
| 01 | SAT_0 | Saturation=0 |
| 02 | SAT_M3 | Saturation=-3 |
| 03 | SAT_P3 | Saturation=+3 |
| 04 | MTX_IDENTITY | 1024 单位矩阵 |
| 05 | MTX_G2R_64 | R'=960R+64G |
| 06 | MTX_R2B_64 | B'=64R+960B |
| 07 | SC_OFF | SelectedColor=off |
| 08 | SC_CH0_RED_BASE | Ch0: Phase=90 Range=33 Sat=25 |
| 09 | SC_CH0_SAT_0 | Ch0 Sat=0 |
| 10 | SC_CH0_SAT_63 | Ch0 Sat=63 |
| 11 | SC_CH0_PHASE_M30 | Ch0 Phase=60 |
| 12 | SC_CH0_PHASE_P30 | Ch0 Phase=120 |
| 13 | SC_CH0_RANGE_1 | Ch0 Range=1 |
| 14 | SC_CH0_RANGE_63 | Ch0 Range=63 |
| 15 | SC_CH1_BLUE_SAT_63 | Ch1: Phase=330 Range=30 Sat=63 |

SelectedColor 采用 0-based channel 编号。Sony PictureEffectPlus 的已知实现使用 channel 0/1，因此这里不采用早期草案里的 ch2/ch3。

## 拍摄条件

建议 M 档、固定 ISO/光圈/快门、固定 WB、DRO OFF、同一灯光和机位。测试期间不要移动色卡。

## Build

该 App 只通过 Java reflection 调用 Sony CameraEx，因此编译时不需要 Sony proprietary stub JAR。GitHub Actions 使用 stock Android SDK 生成 Android 2.3/API10 目标 APK。
