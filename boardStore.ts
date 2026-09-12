import RNFS from 'react-native-fs';
import { NativePluginManager } from 'sn-plugin-lib';
import {
  decodeBoard,
  encodeBoard,
  type BoardDoc,
} from './React/src/boardFormat';
import { base64ToBytes, bytesToBase64 } from './React/src/base64';

const BOARD_FILENAME = 'board.mosaic';

async function resolveBoardPath(): Promise<string> {
  return `${await NativePluginManager.getPluginDirPath()}/${BOARD_FILENAME}`;
}

async function readBoardFile(path: string): Promise<BoardDoc | null> {
  if (!(await RNFS.exists(path))) return null;
  const base64 = await RNFS.readFile(path, 'base64');
  return decodeBoard(base64ToBytes(base64));
}

export async function loadBoard(): Promise<BoardDoc | null> {
  return readBoardFile(await resolveBoardPath());
}

export async function saveBoard(doc: BoardDoc): Promise<void> {
  const path = await resolveBoardPath();
  await RNFS.writeFile(path, bytesToBase64(encodeBoard(doc)), 'base64');
}
