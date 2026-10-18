# 连接中的子卡片：独立移动与尺寸边界

2026-10-18，构建标记 `connected-card-limits-20261018`。

此版本在真实压感笔迹上触发了 ANR，已被 `connected-card-safe-20261018` 替代。下文保留首次实现与交付记录；当前区域检查实现和回归结果见 [崩溃与手写交接修复](connected-card-safe.md)。

## 操作规则

- 选中连接组合后，拖动子卡片内部或长按子卡片，可以只移动这张卡片；其他卡片保持原位。组合移动手柄与组合空白区域仍用于整组移动。
- 子卡片边手柄调整宽高。自身文字和笔迹的尺寸下限继续生效。
- 单卡移动或尺寸调整同时检查所有现有活动连接。即将断桥、或桥形变化将裁掉桥上现有可见笔迹时，预览停止在允许的位置；可以向反方向退回。
- 桥上笔迹保持世界坐标，不能被子卡片边缘吞掉。子卡片自身内容随子卡片移动。
- 松手按相同受限坐标提交，不执行会改变连接状态的自动吸附。变化经 `BoardHistory.Change` 提交，保留撤销和保存流程。

## 实现

`ConnectedCardConstraints` 在手势开始时捕获邻居位置、活动连接，以及桥上笔迹与当前开放桥面的交集。使用 `NeckGeometry.build` 的实际曲线轮廓，排除两张卡片覆盖的部分后，检查受保护笔迹是否仍完全位于桥内。断开距离使用 160 世界坐标单位以内的 0.25 单位余量，防止卡片宽高浮点转换在松手后造成断开；原本已在余量区域内的连接保留其起始距离。

指针目标从上一合法预览分段探测，遇到不合法位置时二分收敛到边界。单卡独立移动期间，覆盖层重绘连接预览并保持桥上笔迹位置；实际选区仍保持组合选区。此规则用于单卡编辑，整组选区的既有变换流程保留。

## 验证与产物

通过 `MOSAIC_USE_EXISTING_BUNDLE=1 MOSAIC_NDK_VERSION=27.0.12077973 bash ./buildPlugin.sh --with-logs` 构建；沿用现有 JS bundle，重新构建原生 APK/NPK。

外层插件和内层 APK ZIP 完整性、配置、bundle 一致性、APK 一致性和原生构建标记已校验通过。

- 本地产物：`build/outputs/plugin_mosaic_connected_limits.snplg`
- SHA-256：`80a5d06f9423f26776cee5cc92c5e3e37ee9d441b5fd79cc7713f158e60720a2`
- 目标路径：`/sdcard/mystyle/plugin_mosaic_connected_limits.snplg`
- 几何测试：本次 APK 在设备独立 `app_process` 中运行 `tools/ConnectedCardResizeCheck.java`（8 项）、`tools/ConnectedBridgeLimitsCheck.java`（15 项），分别输出 `CONNECTED_RESIZE_CHECKS_PASSED=8`、`BRIDGE_LIMIT_CHECKS_PASSED=15`。涵盖内容尺寸、断桥距离、桥面笔迹、反向退回、独立位移、大幅拖动和多连接共同限制；未打开或改写用户白板。
- 测试用例额外用实际开放桥面的 Path 差集验证笔迹初始可见，以及目标几何确实会裁切；桥中部在部分缩高/位移下仍能保留笔迹，不能仅凭卡片高度推断应当受限。
- 已推送到上述设备路径，主机与设备 SHA-256 一致。安装和手指/笔交互验收由用户执行。
