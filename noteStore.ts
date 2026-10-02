import RNFS from 'react-native-fs';
import { NativePluginManager } from 'sn-plugin-lib';

export const NOTE_DIR_NAME = 'notes';
let directory: string | null = null;

export async function ensureNoteDir(): Promise<string> {
  if (directory !== null) return directory;
  const dir = `${await NativePluginManager.getPluginDirPath()}/${NOTE_DIR_NAME}`;
  if (!(await RNFS.exists(dir))) await RNFS.mkdir(dir);
  directory = dir;
  return dir;
}
export function notePathFor(noteRef: string | undefined): string {
  if (!directory || !noteRef) return '';
  return `${directory}/${noteRef}.png`;
}
