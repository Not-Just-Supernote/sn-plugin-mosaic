import RNFS from 'react-native-fs';
import { PluginManager } from 'sn-plugin-lib';


export const MOSAIC_INBOX_DIR = '/sdcard/EXPORT/mosaic/inbox';

const FILE_READ_PERMISSION = 'plugin.permission.FILE:READ';
const FILE_WRITE_PERMISSION = 'plugin.permission.FILE:WRITE';
const INTERNET_PERMISSION = 'plugin.permission.INTERNET';
const GRANTED_PERMISSION_STATUSES = new Set([1, 2]);

let importPermissionFlow: Promise<boolean> | null = null;

async function ensurePermission(permission: string, description: string): Promise<boolean> {
  try {
    const current = await PluginManager.hasPermission(permission);
    console.log(`[MosaicImport] permission has ${permission} status=${current}`);
    if (GRANTED_PERMISSION_STATUSES.has(current)) return true;
    const requested = await PluginManager.requestPermission(permission, description);
    const granted = GRANTED_PERMISSION_STATUSES.has(requested);
    console.log(`[MosaicImport] permission request ${permission} status=${requested} granted=${granted}`);
    return granted;
  } catch (error) {
    console.log(`[MosaicImport] permission ${permission} failed: ${String(error)}`);
    return false;
  }
}


export function ensureMosaicImportPermissions(): Promise<boolean> {
  if (importPermissionFlow !== null) return importPermissionFlow;
  importPermissionFlow = (async () => {
    const readGranted = await ensurePermission(
      FILE_READ_PERMISSION,
      '读取 Inkling 截图并插入 Mosaic 卡片',
    );
    if (!readGranted) return false;
    const writeGranted = await ensurePermission(
      FILE_WRITE_PERMISSION,
      '完成 Mosaic 卡片导入并清理截图文件',
    );
    return writeGranted;
  })().finally(() => {
    importPermissionFlow = null;
  });
  return importPermissionFlow;
}

export function ensureSyncNetworkPermission(): Promise<boolean> {
  return ensurePermission(INTERNET_PERMISSION, '连接 Mosaic 网页端同步白板');
}

export type PendingMosaicImageCard = {
  id: string;
  imagePath: string;
  createdAt?: number;
  source?: string;
};

export type PendingMosaicImageCardClaim = {
  request: PendingMosaicImageCard;
  claimPath: string;
  originalPath: string;
};

async function ensureInbox(): Promise<void> {
  if (!(await RNFS.exists(MOSAIC_INBOX_DIR))) {
    await RNFS.mkdir(MOSAIC_INBOX_DIR);
  }
}


export async function claimPendingMosaicImageCard(): Promise<PendingMosaicImageCardClaim | null> {
  await ensureInbox();
  const entries = await RNFS.readDir(MOSAIC_INBOX_DIR);

  
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith('.json.processing')) continue;
    const restored = entry.path.slice(0, -'.processing'.length);
    try {
      await RNFS.moveFile(entry.path, restored);
    } catch {
      
    }
  }

  const listing = await RNFS.readDir(MOSAIC_INBOX_DIR);
  const candidates = listing
    .filter(entry => entry.isFile() && entry.name.endsWith('.json'))
    .sort((a, b) => a.mtime && b.mtime ? a.mtime.getTime() - b.mtime.getTime() : a.name.localeCompare(b.name));
  if (listing.length > 0) {
    
    console.log(`[MosaicImport] inbox entries=${listing.length} requests=${candidates.length} names=${listing.map(entry => entry.name).join(',')}`);
  }

  for (const entry of candidates) {
    const claimPath = `${entry.path}.processing`;
    try {
      await RNFS.moveFile(entry.path, claimPath);
      const raw = await RNFS.readFile(claimPath, 'utf8');
      const parsed = JSON.parse(raw) as Partial<PendingMosaicImageCard>;
      if (typeof parsed.imagePath !== 'string' || parsed.imagePath.length === 0) {
        await RNFS.moveFile(claimPath, entry.path);
        continue;
      }
      return {
        request: {
          id: typeof parsed.id === 'string' && parsed.id.length > 0
            ? parsed.id : entry.name.replace(/\.json$/, ''),
          imagePath: parsed.imagePath,
          createdAt: typeof parsed.createdAt === 'number' ? parsed.createdAt : undefined,
          source: typeof parsed.source === 'string' ? parsed.source : undefined,
        },
        claimPath,
        originalPath: entry.path,
      };
    } catch {
      
      try {
        if (await RNFS.exists(claimPath)) await RNFS.moveFile(claimPath, entry.path);
      } catch {
        
      }
    }
  }
  return null;
}

export async function completePendingMosaicImageCard(
  claim: PendingMosaicImageCardClaim,
): Promise<void> {
  
  
  try {
    if (await RNFS.exists(claim.claimPath)) {
      await RNFS.moveFile(claim.claimPath, `${claim.originalPath}.done`);
    }
  } catch {
    try {
      if (await RNFS.exists(claim.claimPath)) await RNFS.unlink(claim.claimPath);
    } catch {
      
    }
  }
  
  
  try {
    if (await RNFS.exists(claim.request.imagePath)) await RNFS.unlink(claim.request.imagePath);
  } catch {
    
  }
}

export async function releasePendingMosaicImageCard(
  claim: PendingMosaicImageCardClaim,
): Promise<void> {
  try {
    if (await RNFS.exists(claim.claimPath)) {
      await RNFS.moveFile(claim.claimPath, claim.originalPath);
    }
  } catch {
    
  }
}
