# 恢复 A2，笔记内部保留 AUTO（已被后续版本取代）

2026-10-18，撤回 AUTO 方案及其后的实验。

- 外部画布平移缩放、卡片变换、Notipal 浮窗拖动和透明度滑条恢复 A2（4）。
- 笔记卡片内部双指滑动缩放使用 MODE_DEFAULT，即 AUTO（7），仅调用原有 applyNative/resetNative。
- 移除 MovementAutoRefresh、GestureFrameGate、FullUiAuto/Regal DOWN-MOVE 序列、首帧等待、MOVE 合并和换指 rebase；恢复原卡片运动配方。
- 保留 AUTO 试验前的最终帧 UI Regal 请求，以及手写、卡片扩展、图片卡片和横向建卡尺寸修正。

原实验代码与说明已移入 build/auto-rollback-* 快照。编译产物确认外部移动原因是 4、note-scroll 是 7；DEX 中无实验控制器和 FullUiAuto 入口。buildPlugin.sh 构建、外层与 NPK 完整性、新 APK 一致性及原 JS bundle 来源校验通过。

产物：`build/outputs/plugin_mosaic_a2_note_auto.snplg`
SHA-256：`718ac4666457746affa2d769d3e6ce7ec2414a48b164a708ebff326da72b68af`
