import { NativeModules } from 'react-native';
import { NativePluginManager } from 'sn-plugin-lib';
import { BOARD_FILENAME } from './boardStore';
import { IMAGE_DIR_NAME } from './imageStore';
import { NOTE_DIR_NAME } from './noteStore';
import { CONFIG_FILENAME } from './syncConfig';

export const MOSAIC_ARCHIVE_PATH = '/sdcard/EXPORT/mosaic/mosaic-backup.zip';


const ARCHIVE_ENTRIES = [BOARD_FILENAME, NOTE_DIR_NAME, IMAGE_DIR_NAME, CONFIG_FILENAME];

async function pluginDir(): Promise<string> {
  const path = await NativePluginManager.getPluginDirPath();
  if (!path) throw new Error('Mosaic plugin directory unavailable');
  return path;
}

export async function saveMosaicArchive(): Promise<string> {
  const module = (NativeModules as any).MosaicArchive;
  if (typeof module?.saveArchive !== 'function') throw new Error('MosaicArchive native module unavailable');
  return module.saveArchive(await pluginDir(), MOSAIC_ARCHIVE_PATH, ARCHIVE_ENTRIES);
}

export async function restoreMosaicArchive(): Promise<boolean> {
  const module = (NativeModules as any).MosaicArchive;
  if (typeof module?.restoreArchive !== 'function') throw new Error('MosaicArchive native module unavailable');
  
  return module.restoreArchive(await pluginDir(), MOSAIC_ARCHIVE_PATH, BOARD_FILENAME, ARCHIVE_ENTRIES);
}
