

import { AppRegistry, Image, NativeModules } from 'react-native';
import App from './App';
import { PluginManager } from 'sn-plugin-lib';
import { attachBoardVisibilityListener, attachNoteTapListener } from './noteBridge';

AppRegistry.registerComponent('me_laumss_mosaic', () => App);
PluginManager.init();


attachBoardVisibilityListener();

if (NativeModules.MosaicNoteShot === undefined) attachNoteTapListener();


PluginManager.registerButton(1, ['NOTE', 'DOC'], {
  id: 100,
  name: 'Mosaic',
  icon: Image.resolveAssetSource(require('./assets/icon.png')).uri,
  showType: 1,
});
