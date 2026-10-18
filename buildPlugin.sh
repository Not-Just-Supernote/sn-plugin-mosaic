#!/usr/bin/env bash
# Unix build entry, adapted from the existing Mosaic shell pipeline.
# Like buildPlugin.ps1, package a fresh custom native APK and a separate JS bundle.
set -Eeuo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"
die() { echo "buildPlugin.sh: $*" >&2; exit 1; }
for tool in node zip; do command -v "$tool" >/dev/null || die "$tool is required"; done
for arg in "$@"; do
  case "$arg" in
    --with-logs) export WITH_LOGS=1 ;;
    *) die "unknown option: $arg" ;;
  esac
done
mkdir -p build/generated build/outputs
NAME=$(node -e 'process.stdout.write(require("./package.json").name)')
BUNDLE="build/generated/${NAME}.bundle"
if [[ "${MOSAIC_USE_EXISTING_BUNDLE:-0}" == 1 ]]; then
  [[ -s "$BUNDLE" ]] || die "MOSAIC_USE_EXISTING_BUNDLE=1 requires $BUNDLE"
  echo "Using existing bundle and assets: $BUNDLE"
else
  [[ -x node_modules/.bin/react-native ]] || die "run npm ci first"
  node_modules/.bin/react-native bundle --entry-file index.js --bundle-output "$BUNDLE" \
    --platform android --assets-dest build/generated --dev false
fi

# A plugin's bundle is loaded by the host, not from its native APK. Disabling
# bundle task registration also avoids Gradle provider errors caused by -x.
INIT_DIR=$(mktemp -d "$SCRIPT_DIR/build/native-package.XXXXXX")
INIT_FILE="$INIT_DIR/init.gradle"
ZIP_FILE="$SCRIPT_DIR/build/outputs/${NAME}.tmp.zip"
trap 'rm -f "$INIT_FILE" "$ZIP_FILE"; rmdir "$INIT_DIR"' EXIT
cat > "$INIT_FILE" <<'GRADLE'
gradle.beforeProject { project ->
    if (project.path == ':app') {
        def ndk = System.getenv('MOSAIC_NDK_VERSION')
        if (ndk) project.rootProject.ext.ndkVersion = ndk
        project.plugins.withId('com.facebook.react') {
            project.extensions.getByName('react').debuggableVariants.set(['debug', 'release'])
        }
    }
}
GRADLE
rm -f build/generated/app.npk
(cd android && bash ./gradlew :app:buildCustomApkRelease \
  -PreactNativeArchitectures=arm64-v8a -I "$INIT_FILE" --console=plain --max-workers=2)
APK="android/app/build/outputs/apk/release/app-release-custom.apk"
[[ -s "$APK" ]] || die "custom APK missing after Gradle build"
cp "$APK" build/generated/app.npk

node <<'NODE'
const fs = require('fs');
const path = require('path');
const config = JSON.parse(fs.readFileSync('PluginConfig.json', 'utf8'));
const list = fs.readFileSync('android/app/build/generated/autolinking/src/main/java/com/facebook/react/PackageList.java', 'utf8');
const excluded = new Set(['com.facebook.react.shell.MainReactPackage', 'com.ratta.supernote.pluginlib.PluginPackage']);
const packages = [...list.matchAll(/^import ([\w.]+);/gm)]
  .map(m => m[1])
  .filter(name => list.includes('new ' + name.split('.').pop() + '(') && !excluded.has(name));
config.reactPackages = [...new Set([...packages, 'me.laumss.mosaic.MosaicEinkPackage'])].sort();
config.nativeCodePackage = '/app.npk';
if (process.env.WITH_LOGS === '1') config.name += ' - Dev';
const icon = config.iconPath.replace(/^\.\//, '');
fs.copyFileSync(icon, path.join('build/generated', path.basename(icon)));
config.iconPath = '/' + path.basename(icon);
fs.writeFileSync('build/generated/PluginConfig.json', JSON.stringify(config, null, 2) + '\n');
NODE
rm -f "$ZIP_FILE"
(cd build/generated && zip -qr "$ZIP_FILE" .)
mv "$ZIP_FILE" "build/outputs/${NAME}.snplg"
echo "Plugin package generated: $SCRIPT_DIR/build/outputs/${NAME}.snplg"
