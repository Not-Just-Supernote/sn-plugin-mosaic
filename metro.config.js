const path = require('path');
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');




const reactRoot = path.resolve(__dirname, 'React');
const config = {
  cacheVersion: process.env.WITH_LOGS === '1' ? 'with-logs' : 'no-logs',
  watchFolders: [reactRoot],
  resolver: {
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
