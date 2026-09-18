import { I18nManager } from 'react-native';

export type Locale = 'zh' | 'en';

const STRINGS = {
  whiteboardName: { zh: '白板 {number}', en: 'Whiteboard {number}' },
  whiteboard: { zh: '白板', en: 'Whiteboard' },
  lasso: { zh: '套索', en: 'Lasso' },
  eraser: { zh: '橡皮', en: 'Eraser' },
  touch: { zh: '手触', en: 'Touch' },
  setWhiteboard: { zh: '设白板', en: 'Set Board' },
  deleteWhiteboard: { zh: '删白板', en: 'Delete Board' },
  snapshot: { zh: '截图', en: 'Snapshot' },
  edit: { zh: '编辑', en: 'Edit' },
  deleteCard: { zh: '删除卡片', en: 'Delete Card' },
  split: { zh: '拆分', en: 'Split' },
  sizeLevelHalf: { zh: '½ 邻卡', en: '½ neighbor' },
  sizeLevelDouble: { zh: '2× 邻卡', en: '2× neighbor' },
  sizeLevelQuad: { zh: '4× 邻卡', en: '4× neighbor' },
  close: { zh: '关闭', en: 'Close' },
  touchHint: {
    zh: '画矩形并在终点停笔建卡；卡片内向外划线创建关联卡片；单指拖卡，长按调整尺寸，双指移动画布',
    en: 'Draw a rectangle and hold at the endpoint to create a card; draw outward from a card to link a new one; drag with one finger, resize with a long press, pan with two fingers',
  },
  touchDisabledHint: {
    zh: '画矩形并在终点停笔建卡；卡片内向外划线创建关联卡片；手触已关闭',
    en: 'Draw a rectangle and hold at the endpoint to create a card; draw outward from a card to link a new one; touch is off',
  },
  einkRefresh: { zh: 'E-ink 刷新：{mode}', en: 'E-ink refresh: {mode}' },
  hostRefresh: { zh: '宿主普通模式', en: 'Host normal mode' },
  enabled: { zh: '开启', en: 'On' },
  standby: { zh: '待机', en: 'Standby' },
  jumpToRegion: { zh: '跳到{direction}方向的内容区域', en: 'Jump to content on the {direction}' },
  directionLeft: { zh: '左', en: 'left' },
  directionRight: { zh: '右', en: 'right' },
  directionUp: { zh: '上', en: 'top' },
  directionDown: { zh: '下', en: 'bottom' },
  zoomOut: { zh: '缩小', en: 'Zoom out' },
  zoomDefault: { zh: '缩放到 100%', en: 'Zoom to 100%' },
  zoomIn: { zh: '放大', en: 'Zoom in' },
  editCard: { zh: '编辑卡片', en: 'Edit Card' },
  back: { zh: '返回', en: 'Back' },
  save: { zh: '保存', en: 'Save' },
  splitParent: { zh: '拆分母卡', en: 'Split Parent Card' },
  splitHint: {
    zh: '点一行设锚点 ▲，再点另一行圈出区间；点选中区间可取消；已拆出的行不可再选。',
    en: 'Tap one line to set ▲, then another to select a range. Tap a selected range to clear it. Split lines stay locked.',
  },
  splitLocked: { zh: '已拆', en: 'Split' },
  clearSelection: { zh: '清除选择', en: 'Clear Selection' },
  splitChildren: { zh: '拆出 {count} 张子卡', en: 'Create {count} Child Cards' },
  whiteboardSwitcher: { zh: '白板切换', en: 'Whiteboard Switcher' },
  captureToNote: { zh: '截图插入笔记', en: 'Snapshot into note' },
  notes: { zh: '笔记卡片', en: 'Note cards' },
  notesEmpty: { zh: '暂无笔记卡片', en: 'No note cards yet.' },
  quickAccess: { zh: '快速访问', en: 'Quick access' },
  removeQuickAccess: { zh: '移除快速访问', en: 'Remove quick access' },
  noteName: { zh: '笔记 {number}', en: 'Note {number}' },
  whiteboardSwitcherEmpty: { zh: '暂无白板，请先在画布上「设白板」', en: 'No whiteboards yet. Select “Set Board” on the canvas first.' },
  mosaicPermissionNeeded: {
    zh: 'Mosaic 需要文件读写和删除权限才能启动，请在权限弹窗中允许后重试。',
    en: 'Mosaic needs file read, write, and delete permission to start. Allow it in the permission dialog, then retry.',
  },
  acknowledge: { zh: '知道了', en: 'OK' },
  syncImportAsk: {
    zh: '网页导入了一份白板 JSON，是否加载到本机？',
    en: 'The web imported a board JSON. Load it on this device?',
  },
  syncImportYes: { zh: '是', en: 'Yes' },
  syncImportNo: { zh: '否', en: 'No' },
  sync: { zh: '同步', en: 'Sync' },
  translucent: { zh: '半透明', en: 'Translucent' },
  accent: { zh: '强调色', en: 'Accent' },
  defaultCard: { zh: '默认卡', en: 'Default' },
  convertToNote: { zh: '转为笔记', en: 'Convert to note' },
  recognizeCard: { zh: '识别为文字卡', en: 'Recognize to card' },
  thick: { zh: '粗细', en: 'Thick' },
  template: { zh: '模板', en: 'Template' },
  shape: { zh: '图形', en: 'Shape' },
} as const;

type StringKey = keyof typeof STRINGS;

function detectLocale(): Locale {
  try {
    const identifier = I18nManager.getConstants().localeIdentifier;
    if (identifier?.toLowerCase().startsWith('zh')) return 'zh';
  } catch {
    
  }
  return 'en';
}

const locale: Locale = detectLocale();

export function t(key: StringKey, params?: Record<string, string | number>): string {
  let text: string = STRINGS[key][locale] ?? STRINGS[key].en;
  if (params !== undefined) {
    for (const [name, value] of Object.entries(params)) {
      text = text.replace(new RegExp(`\\{${name}\\}`, 'g'), String(value));
    }
  }
  return text;
}
