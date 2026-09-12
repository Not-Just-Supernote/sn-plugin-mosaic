import { NativeModules } from 'react-native';
import RNFS from 'react-native-fs';
import { NativePluginManager } from 'sn-plugin-lib';
import { CARD_DEFAULTS } from './React/src/boardFormat';
import { clampCardSize, MIN_IMAGE_CARD_SIZE } from './React/src/cardGeometry';
import { generateId } from './React/src/id';
import type { Card } from './React/src/types';



const IMAGE_DIR_NAME = 'images';
const IMAGE_FILE_PREFIX = 'img-';
const IMAGE_FILE_EXT = '.png';

const IMAGE_CARD_DEFAULT_LONG_SIDE = 480;

export type ImportedImage = {
  
  imageRef: string;
  
  width: number;
  height: number;
  
  bytes: number;
};

type MosaicImageModule = {
  
  importImage(
    source: string,
    destPath: string,
    maxLongSide: number,
  ): Promise<{ width: number; height: number; bytes: number }>;
};

const MosaicImage = NativeModules.MosaicImage as MosaicImageModule | undefined;

let imageDir: string | null = null;
let imageDirPromise: Promise<string> | null = null;

async function resolveImageDir(): Promise<string> {
  const dir = `${await NativePluginManager.getPluginDirPath()}/${IMAGE_DIR_NAME}`;
  if (!(await RNFS.exists(dir))) await RNFS.mkdir(dir);
  return dir;
}


export function ensureImageDir(): Promise<string> {
  if (imageDir !== null) return Promise.resolve(imageDir);
  if (imageDirPromise === null) {
    imageDirPromise = resolveImageDir().then(dir => {
      imageDir = dir;
      return dir;
    }).catch(error => {
      imageDirPromise = null;
      throw error;
    });
  }
  return imageDirPromise;
}


export function imagePathFor(imageRef: string | undefined): string {
  if (!imageRef || imageDir === null) return '';
  return `${imageDir}/${imageRef}`;
}

export function imageAvailable(): boolean {
  return MosaicImage !== undefined;
}


export async function importImage(source: string, maxLongSide = 0): Promise<ImportedImage> {
  if (MosaicImage === undefined) throw new Error('MosaicImage native module unavailable');
  const dir = await ensureImageDir();
  const imageRef = `${IMAGE_FILE_PREFIX}${generateId()}${IMAGE_FILE_EXT}`;
  const result = await MosaicImage.importImage(source, `${dir}/${imageRef}`, maxLongSide);
  console.log(`[MosaicImage] imported ref=${imageRef} ${result.width}x${result.height} bytes=${result.bytes} from=${source}`);
  return { imageRef, width: result.width, height: result.height, bytes: result.bytes };
}

export function collectImageRefs(cards: Card[]): Set<string> {
  const refs = new Set<string>();
  for (const card of cards) {
    if (card.kind === 'image' && card.imageRef) refs.add(card.imageRef);
  }
  return refs;
}


export async function pruneOrphanImages(referenced: Set<string>): Promise<number> {
  const dir = await ensureImageDir();
  const entries = await RNFS.readDir(dir);
  let removed = 0;
  for (const entry of entries) {
    if (!entry.isFile() || referenced.has(entry.name)) continue;
    try {
      await RNFS.unlink(entry.path);
      removed += 1;
    } catch {
      
    }
  }
  if (removed > 0) console.log(`[MosaicImage] pruned orphan images=${removed} kept=${referenced.size}`);
  return removed;
}


export function imageCardSizeFor(
  natural: { width: number; height: number },
  longSide = IMAGE_CARD_DEFAULT_LONG_SIDE,
): { width: number; height: number } {
  const w = Math.max(1, natural.width);
  const h = Math.max(1, natural.height);
  const ratio = Math.max(longSide, MIN_IMAGE_CARD_SIZE) / Math.max(w, h);
  return clampCardSize(w * ratio, h * ratio, 'image');
}


export function createImageCard(args: {
  imageRef: string;
  natural: { width: number; height: number };
  x: number;
  y: number;
  zIndex: number;
  content?: string;
}): Card {
  const size = imageCardSizeFor(args.natural);
  return {
    ...CARD_DEFAULTS,
    tags: [],
    id: 'card-' + generateId(),
    kind: 'image',
    imageRef: args.imageRef,
    content: args.content ?? '',
    x: args.x,
    y: args.y,
    width: size.width,
    height: size.height,
    zIndex: args.zIndex,
    sourceType: 'manual',
    createdAt: new Date().toISOString(),
  };
}
