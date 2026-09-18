

import { AppRegistry, Image, NativeModules } from 'react-native';
import App from './App';
import { PluginManager } from 'sn-plugin-lib';
import { attachBoardVisibilityListener, attachNoteTapListener } from './noteBridge';
import { registerDocSelectionListener, DOC_SELECT_BUTTON_ID } from './docSelectionBridge';

AppRegistry.registerComponent('me_laumss_mosaic', () => App);
PluginManager.init();


attachBoardVisibilityListener();

if (NativeModules.MosaicNoteShot === undefined) attachNoteTapListener();

registerDocSelectionListener();

const MOSAIC_ICON = Image.resolveAssetSource(require('./assets/icon.png')).uri;


PluginManager.registerButton(1, ['NOTE', 'DOC'], {
  id: 100,
  name: 'Mosaic',
  icon: MOSAIC_ICON,
  showType: 1,
});


PluginManager.registerButton(3, ['DOC'], {
  id: DOC_SELECT_BUTTON_ID,
  name: 'Mosaic',
  icon: MOSAIC_ICON,
  showType: 1,
});
