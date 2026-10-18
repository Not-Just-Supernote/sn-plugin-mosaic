# 按操作开启 Regal（v3）

2026-10-18，构建标记 `regal-operation-gate-20261018c`。

## 策略

- Mosaic 可见且持有窗口焦点时，默认关闭 Regal 总开关与 UI Auto Regal。普通绘制、书写和菜单操作不主动开启。
- 平移/缩放画布或笔记，以及卡片/选区移动、改尺寸、旋转结束后，等待原有 A2 释放和可见 tile 完成；在同一次最终稳定帧的 onDraw 中临时开启 Regal。A2 和运动配方参数不变。
- 使用这一帧原有重绘，不再额外补帧。
- 提交后等待屏幕空闲，再关闭 Regal；普通状态回到关闭。

仅关闭 `[9,0]` 只影响 UI Auto 分支，不能保证 `tcon_eink_regal` 全部停止。因此 v3 同时控制总开关：

| 状态 | HWC 1020 参数顺序 |
| --- | --- |
| 默认关 / 完成关 | `[9,0]`，然后 `[0,0,0,0]` |
| 操作最终帧开启 | `[1,0,0,0]`，然后 `[9,1]` |
| 退出交还宿主 | `[1,0,0,0]`，然后 `[9,1]` |

总开关调用等价于固件 `EinkManager.disableRegal(!enabled,0)`；UI 调用等价于文档应用 `HandWriteClient.setRegalStatus(enabled)`。

## 完成与中断

`RegalRefreshWindow` 在工作线程只读 `/dev/ebc` busy 状态，不写帧缓冲：

1. 预留 250ms 让最终帧提交。
2. 观察到 busy 后，连续三次 idle 才按空闲关闭。
3. 没观察到 busy 时至少保留 750ms，避免读取过早的 idle；不可读取也按这一等待下限关闭。
4. 3 秒期限负责收尾，日志明确区分 `panel-idle`、`idle-no-busy-observed`、`state-unavailable` 和 `deadline`，不将超时当作效果已完成。

新输入会关闭活动窗口。被打断的移动请求保留，输入结束后再等最终帧。旧线程结果带 generation，不能关闭后来新开启的窗口。

隐藏、失焦或分离时取消轮询/超时并恢复宿主已实测的“总开关开、UI 开”基线；重新回到 Mosaic 时重新应用默认关。固件没有对应 getter，这不是精确快照恢复任意旧状态。手动全刷的灰阶恢复继续由 `EinkGrayRestore` 负责。

## 构建与交付

```sh
MOSAIC_USE_EXISTING_BUNDLE=1 MOSAIC_NDK_VERSION=27.0.12077973 bash ./buildPlugin.sh --with-logs
```

本分支缺少部分 React 源文件，沿用设备原 0.1.198 bundle/资源，只重新构建原生 NPK。bundle SHA-256：`72e589e2d7b2f9db82367073f62fccb939d639d57e9250c7801bb71b8ed83327`。场景协议仍为 9。本机 NDK 27.1 目录不完整，以参数选择已安装的 27.0。

按用户要求仅推送安装包，由用户安装测试。以下日志才是本版应观察的状态过程：

```text
MosaicRegalGate: requested enabled=false reason=idle:...
MosaicRegalGate: requested enabled=true reason=movement:pan-zoom
MosaicEinkCleanup: final frame ... regalWindowOpened=true extraFrame=false
MosaicRegalGate: requested enabled=false reason=finished:...
```

还需用 HWC 实际开关日志及屏幕观察确认效果，不能用 `masterOk=true uiOk=true` 代替物理残影判断。

## 历史

v1 只增加灰阶帧；v2 在最终帧明确发 UI Regal 开启。两者实测中 Regal 原本已启用，v2 日志是 `uiauto_regal_on from 1 to 1`。用户随后要求默认关闭，只有指定操作刷新时开启，故 v3 增加总开关和完成后关闭策略。

原始逆向证据：`/Volumes/Lunar/SN/reverse/highlight-cleanup-analysis.md`；v1 实机记录：`/Volumes/Lunar/SN/reverse/mosaic-regal-execution-check.md`。

## v3 交付记录

- `buildPlugin.sh --with-logs` 构建成功；ZIP、内层 NPK、bundle 来源和编译后的操作入口校验通过。
- 本地包：`build/outputs/plugin_mosaic_regal_v3.snplg`，6,632,215 字节。
- 已推送到 `/sdcard/mystyle/plugin_mosaic_regal_v3.snplg`。
- 主机/设备 SHA-256 一致：`58a3d37b2fbbf1a5fe92d47067dbab0bc43cd661f256234758faf13a5904f517`。
- 尚待用户安装后验证开关时序与物理残影效果。
