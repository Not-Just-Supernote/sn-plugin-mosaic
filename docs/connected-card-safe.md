# 连接卡片崩溃与手写交接修复

2026-10-18，构建标记 `connected-card-safe-20261018`。

## 卡片调整导致宿主退出

设备 ANR `anr_2026-10-18-18-33-27-586` 显示：主线程在 `ConnectedCardConstraints.capture` 合并桥上笔迹时停在 `Path.op`，Skia 原生堆栈为 `SkCoincidentSpans::collapsed` / `SkOpCoincidence::markCollapsed`，并持有 `BoardEngine.lock`。输入等待超过 15007ms，随后宿主退出。首次交付的矩形测试未覆盖复杂压感笔迹的曲线运算。

修复以 `Region.setPath` 扫描转换和整数区域运算替代所有连接约束中的 `Path.op`；每条笔迹分别转成遮罩后做区域合并。遮罩相对桥的起始包围盒定位，精度为 0.25 世界坐标单位，并加一格余量覆盖取整误差。只保护原来可见的桥上内容。构建遮罩时不持有场景锁。单卡移动、尺寸限制、整组移动以及撤销保存语义保留。

## DrawPath 的 3000ms 交接

对照 Git HEAD：`INK_HANDOFF_IDLE_MS` 始终是 3000，并未改为零。日志仍能看到 `ink handoff idle: finalize`，但也记录了笔已落下后继续执行 `rearm+400`、`rearm+900` 的完整服务重配置。工作区还增加过每次落笔强制重发笔配置，并移除了原有接触期间的颜色更新等待。

- 恢复原先仅颜色变化时更新配置，并恢复接触期间的等待。
- `holdsSystemInk` 覆盖接触、待提交以及完整的 3000ms 交接窗口。悬停和排队的 rearm、重新 configure、背景同步均等待窗口结束；结束后通过已有 settled 回调补发。
- 保留最后一笔后停笔 3000ms 的定时器。连续书写重新计时；工具切换和显式刷新仍可通过原有流程结束交接。

## 验证

`buildPlugin.sh --with-logs` 构建，沿用当前 JS bundle、重新构建原生 APK/NPK。测试在设备独立 `app_process` 中运行，不安装插件、不打开或改写用户白板：

- `ConnectedCardResizeCheck`：8 项内容尺寸检查通过。
- `ConnectedBridgeLimitsCheck`：15 项连接和笔迹边界检查通过。
- `ConnectedSceneReplayCheck`：读取用户当前白板副本，2 张连接卡、4 条桥上真实压感笔迹；192 次移动/尺寸调整通过。最终包单次限制计算 P95 为 11ms、最大 19ms；冷启动首次构建遮罩（含笔迹轮廓初始化）最大 387ms，笔迹轮廓已初始化时最大 153ms。仍有手势开始时的遮罩构建开销，未宣称完全无延迟。
- `InkHandoffTimingCheck`：10 项检查通过。实际 Android Handler 定时器下，在第 1 秒追加第二笔，第 3.2 秒仍保持系统笔迹，第 4.2 秒确认已交接；在等待期间调用悬停、rearm 和 configure，测试 Binder 调用计数始终为零。

上面验证了几何、真实笔迹计算和交接时序；安装后的触控手感与墨水屏外观仍需用户验收。

## 交付

- 本地：`build/outputs/plugin_mosaic_connected_safe.snplg`
- 设备：`/sdcard/mystyle/plugin_mosaic_connected_safe.snplg`
- SHA-256：`424cc1c8e2e358cba273de31fc561984439dac51c3b17fd2cb59c8e01f9a74b1`

外层插件 ZIP、内层 APK、插件配置、bundle 一致性和原生标记验证通过；推送后校验设备 SHA-256。仅推送，由用户安装。
