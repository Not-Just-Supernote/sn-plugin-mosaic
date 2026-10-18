# A2 与最终帧 Regal，取消延迟的第二次重绘

构建标记：`card-a2-regal-final-20261018`。替代 `card-auto-redraw-20261018`。

- 单卡及含卡片的组选区移动、尺寸调整恢复 `CARD_ADJUST / A2 = 4`。
- 正常结束与取消都对 CARD_ADJUST 使用 `release(..., linger=false)`，不再为该操作安排 600ms 的 settle 重绘。
- 松手的同一 UI 事件中提交最终几何、恢复静态外观并释放 A2；原有内容失效请求合并到最终位置的绘制中。最终位置本身仍必须绘制。
- 正常结束恢复 `CardTrailCleanup.schedule("card-transform")` 的 UI Regal 请求，在可见图块已就绪且快刷模式已释放的最终内容帧发送，不单独增加清理帧。
- 旋转、纯笔迹变换、画布平移缩放继续原有流程。此前的连接防卡死与 3000ms 手写交接修复保留。

使用 `buildPlugin.sh --with-logs` 构建。设备安装和肉眼刷新效果由用户验证。

构建通过，ZIP/APK 完整性、bundle/APK 一致性、构建标记已校验；编译后 CARD_ADJUST 的模式值确认为 4。

已推送：`/sdcard/mystyle/plugin_mosaic_card_a2_regal.snplg`。

主机/设备 SHA-256 一致：`63b56364d25eef62c3300884732e68d243bb78bc1559238e569c0d4393d5e755`。尚未安装。
