# 卡片拖动 AUTO，套索保持 A2

2026-10-18。

- 长按选卡或进入套索：LASSO 持有 A2。
- 卡片实际位移才启用 CARD_MOVE / AUTO（7）。未移动、连接约束阻止位移时仍为 A2。
- 松手/取消：立即回到持有的 A2。调整宽高一直使用 A2，结束后也保持。
- 取消选区、自动工具回退、延迟 settle 不释放 LASSO；手动工具选择释放或保持相应模式。处理 long-press 只显示套索但 arbiter base 未改变时的显式选笔。
- 不修改全局刷新优先级；只在 BoardPresentation 的 CARD_MOVE 活跃时临时优先于 LASSO。其他移动模式保持 A2。

验证：buildPlugin.sh 构建通过；本次 APK 在独立 Android app_process 中执行 tools/CardAutoLassoRefreshCheck.java，21 项检查通过。包含实际 BoardPresentation 模式状态与 BoardInteractionController 手动工具策略、无状态变化的选笔、松手/取消、尺寸调整、延迟 settle、退出释放。进程未创建插件窗口、未初始化 React 刷新模块、未打开/改写用户白板，因此不验证物理屏幕效果。

产物：build/outputs/plugin_mosaic_card_auto_lasso_a2.snplg
SHA-256：b20328b25f2f14432bba039316339c7f050d82a4861b5051c8abec0e8bbe8f8c
