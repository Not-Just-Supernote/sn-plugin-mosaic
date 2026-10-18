# 恢复原 A2 手势模式

2026-10-18：笔记内部双指滑动缩放也恢复 A2（4）。外部画布与卡片变换同样使用 A2。AUTO 试验后添加的控制器、首帧等待和换指逻辑保持撤回。

buildPlugin.sh 构建通过；编译后的 note-scroll 调用确认传入 4。包与 NPK 完整性、新 APK 一致性、原 bundle 来源校验通过。

产物：`build/outputs/plugin_mosaic_a2.snplg`
SHA-256：`703eefc221a00c6342e72d3fc9554f13512e6841fef04c48d55974c6ad2e587f`
