#!/usr/bin/env bash
# Build do APK sem Gradle: aapt + javac + dx + apksigner.
# Uso: SERVER=https://suporte.seudominio.com TOKEN=xxx APP_NAME="Meu Suporte" ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-../sdk/android-30.jar}"
SERVER="${SERVER:-https://suporte.exemplo.com}"
TOKEN="${TOKEN:-troque-este-token}"
APP_NAME="${APP_NAME:-TV Remote Agent}"
VERSION="${VERSION:-1.0}"
VCODE="${VCODE:-1}"
OUT=build; rm -rf $OUT; mkdir -p $OUT/gen $OUT/classes $OUT/res

# configs embutidas (o cliente só instala, sem digitar nada)
mkdir -p $OUT/gen/com/arilson/tvremote
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
cat > $OUT/gen/com/arilson/tvremote/Config.java <<J
package com.arilson.tvremote;
public final class Config { public static final String DEFAULT_SERVER = "$(esc "$SERVER")"; public static final String DEFAULT_TOKEN = "$(esc "$TOKEN")"; }
J
cat > $OUT/gen/com/arilson/tvremote/BuildInfo.java <<J
package com.arilson.tvremote;
public final class BuildInfo { public static final String VERSION = "$(esc "$VERSION")"; public static final int CODE = $VCODE; }
J
cp -r res/* $OUT/res/
python3 - "$APP_NAME" <<'PY'
import sys,re
p='build/res/values/strings.xml'; s=open(p,encoding='utf-8').read()
n=sys.argv[1].replace('&','&amp;').replace('<','&lt;').replace("'","\\'")
s=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">%s</string>'%n,s)
open(p,'w',encoding='utf-8').write(s)
PY
sed -e "s/android:versionCode=\"1\"/android:versionCode=\"$VCODE\"/" -e "s/android:versionName=\"1.0\"/android:versionName=\"$VERSION\"/" AndroidManifest.xml > $OUT/AndroidManifest.xml

aapt package -f -m -J $OUT/gen -M $OUT/AndroidManifest.xml -S $OUT/res -I "$ANDROID_JAR"
CP="$ANDROID_JAR:libs/okhttp-3.12.13.jar:libs/okio-1.17.6.jar"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$CP" -d $OUT/classes \
  $(find src $OUT/gen -name '*.java') 2>&1 | grep -v -E 'warning: \[options\]|^1 warning|^4 warnings|^3 warnings' || true
dalvik-exchange --dex --min-sdk-version=24 --output=$OUT/classes.dex $OUT/classes libs/okhttp-3.12.13.jar libs/okio-1.17.6.jar
aapt package -f -M $OUT/AndroidManifest.xml -S $OUT/res -I "$ANDROID_JAR" -F $OUT/unsigned.apk
# recursos do okhttp (publicsuffixes) precisam ir junto
(cd $OUT && aapt add unsigned.apk classes.dex >/dev/null)
mkdir -p $OUT/jres && (cd $OUT/jres && unzip -qo ../../libs/okhttp-3.12.13.jar 'okhttp3/internal/publicsuffix/*' && zip -qr ../unsigned.apk okhttp3)
zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
KS=keystore.jks
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -storepass tvremote -keypass tvremote -alias agent -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Remote Agent" 2>/dev/null
fi
apksigner sign --ks $KS --ks-pass pass:tvremote --key-pass pass:tvremote --ks-key-alias agent --out $OUT/agent.apk $OUT/aligned.apk
apksigner verify $OUT/agent.apk && echo "OK -> $OUT/agent.apk ($(du -h $OUT/agent.apk | cut -f1))"
