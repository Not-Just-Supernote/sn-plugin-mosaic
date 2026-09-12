import RNFS from 'react-native-fs';
import { NativePluginManager } from 'sn-plugin-lib';

export interface MosaicSyncConfig {
  serverUrl: string;
  boardId: string;
}

const CONFIG_FILENAME = 'sync-config.json';

async function configPath(): Promise<string> {
  const pluginDir = await NativePluginManager.getPluginDirPath();
  return `${pluginDir}/${CONFIG_FILENAME}`;
}

export async function loadSyncConfig(): Promise<MosaicSyncConfig> {
  const path = await configPath();
  if (!(await RNFS.exists(path))) return { serverUrl: '', boardId: '' };
  const parsed = JSON.parse(await RNFS.readFile(path, 'utf8')) as Partial<MosaicSyncConfig>;
  return {
    serverUrl: parsed.serverUrl?.trim() ?? '',
    boardId: parsed.boardId?.trim() ?? '',
  };
}

export async function saveSyncConfig(config: MosaicSyncConfig): Promise<void> {
  await RNFS.writeFile(await configPath(), JSON.stringify(config), 'utf8');
}
