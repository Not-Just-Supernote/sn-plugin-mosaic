# 连接组合内子卡片的尺寸调整

2026-10-18，构建标记 `connected-card-resize-20261018`。

本文记录首次交付。后续的子卡片独立移动和连接几何限制见 [连接限制](connected-bridge-limits.md)；下述“内部拖动仍移动整组”已被后续版本替代。

- 锁定连接仍扩展整组选区、一起移动。选中组内连接成员后，子卡片的边上增加独立尺寸手柄，手指和笔都可以操作。
- 组合外框的缩放手柄和操作按钮优先；子卡边框命中其次，内部拖动仍移动整组。被上层卡片遮住的边手柄不显示或响应。
- 子卡尺寸修改仍通过 `BoardHistory.Change("resize")` 提交，原有撤销和保存流程继续生效；完成后重新计算组合外框。
- 宽高下限同时覆盖文字布局和手写包围盒。手写下限用卡片本地坐标的 right/bottom 计算，从左/上边缩小时也不会因内容跟随卡片原点移动而被裁掉。
- 子卡使用边手柄独立调宽高，不触发角点的字号缩放。图片和笔记卡保留原有尺寸规则；笔记卡仍左右调宽并维持比例。

## 验证

`buildPlugin.sh --with-logs` 构建通过。沿用当前 `build/generated` 中的独立 JS bundle，只重新构建原生 NPK。

`tools/ConnectedCardResizeCheck.java` 在独立 Android app_process 中对本次 APK 执行 8 项检查：右、左、下、上四边内容限制，空卡缩小，等比尺寸容纳笔迹，多行文字高度，以及锁定连接组仍保持扩展绑定。结果：`CONNECTED_RESIZE_CHECKS_PASSED=8`。检查进程不打开或改写用户白板；字体在运行前显式初始化。

安装包和内层 NPK ZIP、配置与原生构建标记校验通过。

- 本地：`build/outputs/plugin_mosaic_connected_resize.snplg`
- 设备：`/sdcard/mystyle/plugin_mosaic_connected_resize.snplg`
- 主机/设备 SHA-256：`977541b4e2b92aeafa01958156f1556bbbcd72d191e7c1439cdd69d3cda49269`

由用户安装后验证实际手指/笔命中和视觉预览。原有工作区的连接手势、刷新等其他改动保持在当前状态，本次未回退它们。
