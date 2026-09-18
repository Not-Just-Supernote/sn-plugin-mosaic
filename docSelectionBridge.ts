import { DeviceEventEmitter } from 'react-native';
import { PluginDocAPI, PluginManager } from 'sn-plugin-lib';



const LOG = '[MosaicDocText]';
export const DOC_TEXT_EVENT = 'MosaicDocText';

export const DOC_SELECT_BUTTON_ID = 101;

let pendingText: string | null = null;


export function consumePendingDocText(): string | null {
  const text = pendingText;
  pendingText = null;
  return text;
}


export function registerDocSelectionListener(): void {
  try {
    PluginManager.registerButtonListener({
      onButtonPress: (event: { id?: number } | undefined) => {
        if (event?.id !== DOC_SELECT_BUTTON_ID) return;
        void (async () => {
          try {
            const res: any = await PluginDocAPI.getLastSelectedText();
            const text = res?.success && typeof res.result === 'string' ? res.result.trim() : '';
            if (!text) {
              console.log(`${LOG} empty selection`);
              return;
            }
            pendingText = text;
            DeviceEventEmitter.emit(DOC_TEXT_EVENT, text);
            console.log(`${LOG} selected ${text.length} chars`);
          } catch (err) {
            console.log(`${LOG} getLastSelectedText failed: ${err}`);
          }
        })();
      },
    } as any);
    console.log(`${LOG} doc selection listener registered`);
  } catch (err) {
    console.log(`${LOG} registerButtonListener failed: ${err}`);
  }
}
