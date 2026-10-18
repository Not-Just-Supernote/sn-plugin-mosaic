# 卡片移动、宽高调整使用 AUTO

构建标记：`card-auto-redraw-20261018`。

- 单卡移动、子卡片独立移动、单卡改宽高以及含卡片的组合移动/尺寸调整使用 `CARD_ADJUST / MODE_DEFAULT = 7`。
- 保留运动预览、600ms 结束等待、恢复静态底色和内容重绘。结束与取消按对应原因释放模式请求。
- 上述操作结束时清理记录为 `requestRegal=false`，最终帧只记录 `redrawOnly=true`，不发送额外 UI Regal 指令。
- 旋转、纯笔迹变换、画布平移缩放仍使用各自既有流程。AUTO 模式下宿主自身是否使用 Regal 仍由宿主管理。
- 前一版连接几何防卡死与 DrawPath 3000ms 交接修复继续保留。

通过 `MOSAIC_USE_EXISTING_BUNDLE=1 MOSAIC_NDK_VERSION=27.0.12077973 bash ./buildPlugin.sh --with-logs` 构建；外层 ZIP、内层 APK、bundle/APK 一致性、插件 ID 和构建标记校验通过。已检查编译后的 `CARD_ADJUST` 枚举参数为 7，以及开始、结束、取消路径的模式配对。

产物：`build/outputs/plugin_mosaic_card_auto.snplg`

SHA-256：`9f081eb4eb26fff9b0ba8c36ac8d74c5e32d4a8043be9d25d2a15d643730b9dc`

已推送到：`/sdcard/mystyle/plugin_mosaic_card_auto.snplg`，设备 SHA-256 与本地一致。仅推送，由用户安装测试。
