import { memo } from 'react';
import { requireNativeComponent, StyleProp, ViewProps, ViewStyle } from 'react-native';


export type MosaicBoardViewProps = ViewProps & {
  
  deviceType?: number;
  
  touchEnabled?: boolean;
  
  notesDirectory?: string;
};

const NativeMosaicBoardView = requireNativeComponent<MosaicBoardViewProps>('MosaicBoardView');

function sameStyle(a: StyleProp<ViewStyle> | undefined, b: StyleProp<ViewStyle> | undefined): boolean {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  return a.every((value, index) => value === b[index]);
}

const MosaicBoardViewNative = memo(NativeMosaicBoardView, (previous, next) => (
  previous.deviceType === next.deviceType
  && previous.touchEnabled === next.touchEnabled
  && previous.notesDirectory === next.notesDirectory
  && sameStyle(previous.style, next.style)
));

export default MosaicBoardViewNative;
