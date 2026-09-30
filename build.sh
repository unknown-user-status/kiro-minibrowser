#!/data/data/com.termux/files/usr/bin/bash
# MiniBrowser APK build script (Termux, no gradle)
set -e
cd "$(dirname "$0")"
rm -rf build/obj build/dex build/gen
mkdir -p build/{obj,dex,gen}

echo "── 1/6 aapt2 compile+link ──"
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I build/android.jar \
  --manifest AndroidManifest.xml -R build/res.zip \
  --java build/gen --auto-add-overlay

echo "── 2/6 javac ──"
javac -source 8 -target 8 -Xlint:-options \
  -bootclasspath build/android.jar -classpath build/android.jar \
  -d build/obj \
  build/gen/com/kiro/minibrowser/R.java \
  src/com/kiro/minibrowser/*.java

echo "── 3/6 d8 ──"
d8 --output build/dex $(find build/obj -name "*.class") 2>/dev/null

echo "── 4/6 package ──"
cp build/base.apk build/minibrowser-unsigned.apk
(cd build/dex && zip -q ../minibrowser-unsigned.apk classes.dex)

echo "── 5/6 zipalign ──"
zipalign -f 4 build/minibrowser-unsigned.apk build/minibrowser-aligned.apk

echo "── 6/6 sign ──"
[ -f debug.keystore ] || keytool -genkeypair -v -keystore debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/minibrowser.apk build/minibrowser-aligned.apk
cp build/minibrowser.apk ~/minibrowser.apk

echo ""
echo "✅ APK: ~/minibrowser.apk ($(du -h ~/minibrowser.apk | cut -f1))"
apksigner verify --verbose ~/minibrowser.apk 2>&1 | grep -E "Verif" | head -3
