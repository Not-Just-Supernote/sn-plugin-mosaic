import RNFS from 'react-native-fs';
import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';



const LOG = '[MosaicRecognize]';
const CLIP_LASSO_FILE = '/sdcard/EXPORT/mosaic/clip_lasso.json';

const SIDE_MARGIN = 100; 
const MOSAIC_PRESSURE = 1000; 
const MOSAIC_THICK_FACTOR = 20; 
const MOSAIC_THICK_MIN = 100;
const MOSAIC_THICK_MAX = 900;

type ClipStroke = { penStyle?: number; width?: number; pts?: number[] };


function mosaicPenToSdk(objType: number): number {
  switch (objType) {
    case 0: 
    case 16: return 16;  
    case 14: 
    case 15: return 15; 
    case 17: 
    case 11: return 11; 
    default: return 16;
  }
}

function validPageSize(value: any): value is { width: number; height: number } {
  return Number.isInteger(value?.width) && value.width > 0
    && Number.isInteger(value?.height) && value.height > 0;
}


async function currentPageSize(): Promise<{ width: number; height: number } | null> {
  try {
    const fileRes: any = await PluginCommAPI.getCurrentFilePath();
    const pageRes: any = await PluginCommAPI.getCurrentPageNum();
    if (fileRes?.success && typeof fileRes.result === 'string'
      && pageRes?.success && typeof pageRes.result === 'number') {
      const sizeRes: any = await PluginFileAPI.getPageSize(fileRes.result, pageRes.result);
      if (sizeRes?.success && validPageSize(sizeRes.result)) {
        console.log(`${LOG} page size=${sizeRes.result.width}x${sizeRes.result.height}`);
        return { width: sizeRes.result.width, height: sizeRes.result.height };
      }
      console.log(`${LOG} getPageSize failed res=${JSON.stringify(sizeRes)}`);
    } else {
      console.log(`${LOG} current file/page unavailable file=${JSON.stringify(fileRes)} page=${JSON.stringify(pageRes)}`);
    }
  } catch (err) {
    console.log(`${LOG} current page size failed: ${err}`);
  }
  return null;
}

async function readClipStrokes(): Promise<ClipStroke[] | null> {
  try {
    if (!(await RNFS.exists(CLIP_LASSO_FILE))) return null;
    const json = JSON.parse(await RNFS.readFile(CLIP_LASSO_FILE, 'utf8'));
    const strokes = Array.isArray(json?.strokes) ? (json.strokes as ClipStroke[]) : null;
    return strokes && strokes.length > 0 ? strokes : null;
  } catch (err) {
    console.log(`${LOG} read clip strokes failed: ${err}`);
    return null;
  }
}


async function strokesToElements(
  strokes: ClipStroke[],
  pageSize: { width: number; height: number },
): Promise<any[] | null> {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of strokes) {
    const p = s.pts ?? [];
    for (let i = 0; i + 1 < p.length; i += 3) {
      if (p[i] < minX) minX = p[i];
      if (p[i] > maxX) maxX = p[i];
      if (p[i + 1] < minY) minY = p[i + 1];
      if (p[i + 1] > maxY) maxY = p[i + 1];
    }
  }
  if (!(maxX >= minX)) return [];
  const bw = Math.max(1, maxX - minX);
  const bh = Math.max(1, maxY - minY);
  const fit = Math.min(1, (pageSize.width - 2 * SIDE_MARGIN) / bw, (pageSize.height - 2 * SIDE_MARGIN) / bh);

  const elements: any[] = [];
  for (const s of strokes) {
    const createRes: any = await (PluginCommAPI as any).createElement(0);
    if (!createRes?.success || !createRes.result) {
      console.log(`${LOG} createElement failed`);
      return null;
    }
    const el: any = createRes.result;
    const androidPoints: any[] = [];
    const pressures: number[] = [];
    const p = s.pts ?? [];
    for (let i = 0; i + 2 < p.length; i += 3) {
      const pageX = SIDE_MARGIN + (p[i] - minX) * fit;
      const pageY = SIDE_MARGIN + (p[i + 1] - minY) * fit;
      
      
      
      
      androidPoints.push({ x: Math.round(pageX), y: Math.round(pageY) });
      pressures.push(Math.max(1, Math.round((p[i + 2] ?? 1) * MOSAIC_PRESSURE)));
    }
    if (androidPoints.length < 2 || !el.stroke) continue;
    el.thickness = Math.max(
      MOSAIC_THICK_MIN,
      Math.min(MOSAIC_THICK_MAX, Math.round((s.width ?? 2) * fit * MOSAIC_THICK_FACTOR)),
    );
    el.stroke.penColor = 0;
    el.stroke.penType = mosaicPenToSdk(s.penStyle ?? 0);
    const okPts = await el.stroke.points.setRange(0, androidPoints.length - 1, androidPoints);
    const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
    if (!okPts || !okPrs) {
      console.log(`${LOG} setRange failed`);
      return null;
    }
    elements.push(el);
  }
  return elements;
}


export async function recognizeLassoText(): Promise<string | null> {
  const strokes = await readClipStrokes();
  if (strokes === null) {
    console.log(`${LOG} no lasso strokes to recognize`);
    return null;
  }
  const pageSize = await currentPageSize();
  if (pageSize === null) {
    console.log(`${LOG} recognition skipped because the actual page size is unavailable`);
    return null;
  }
  const elements = await strokesToElements(strokes, pageSize);
  if (!elements || elements.length === 0) {
    console.log(`${LOG} no elements built`);
    return null;
  }
  try {
    const res: any = await PluginCommAPI.recognizeElements(elements, pageSize);
    if (res?.success && typeof res.result === 'string' && res.result.trim()) {
      console.log(`${LOG} recognized ${res.result.length} chars from ${elements.length} strokes`);
      return res.result.trim();
    }
    console.log(`${LOG} recognizeElements empty/failed: ${res?.error?.message}`);
    return null;
  } catch (err) {
    console.log(`${LOG} recognizeElements threw: ${err}`);
    return null;
  }
}
