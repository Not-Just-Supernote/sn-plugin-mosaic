import { memo } from 'react';
import { requireNativeComponent, StyleProp, ViewProps, ViewStyle } from 'react-native';


export type MosaicBoardViewProps = ViewProps & {
  
  penWidth?: number;
  
  deviceType?: number;
  
  touchEnabled?: boolean;
};

const NativeMosaicBoardView = requireNativeComponent<MosaicBoardViewProps>('MosaicBoardView');

function sameStyle(a: StyleProp<ViewStyle> | undefined, b: StyleProp<ViewStyle> | undefined): boolean {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  return a.every((value, index) => value === b[index]);
}

const MosaicBoardViewNative = memo(NativeMosaicBoardView, (previous, next) => (
  previous.penWidth === next.penWidth
  && previous.deviceType === next.deviceType
  && previous.touchEnabled === next.touchEnabled
  && sameStyle(previous.style, next.style)
));

export default MosaicBoardViewNative;
