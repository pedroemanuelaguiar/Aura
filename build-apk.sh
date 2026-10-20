#!/usr/bin/env bash
# Monta o APK do Farmando Aura usando as ferramentas do Android que já vêm no GitHub Actions.
set -euo pipefail

VC="${1:-1}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
JAR="$PLAT/android.jar"
echo "SDK: $SDK | build-tools: $BT | plataforma: $PLAT"

W=build
rm -rf "$W" farmando-aura.apk
mkdir -p "$W/res/mipmap-xxxhdpi" "$W/assets" "$W/compiled" "$W/gen" "$W/classes" "$W/dex"

# Ícone e arquivos do app
cp icon-192.png "$W/res/mipmap-xxxhdpi/ic_launcher.png"
cp apk-index.html "$W/assets/index.html"
cp anton.woff2 figtree-*.woff2 "$W/assets/"

# Recursos e manifesto
"$BT/aapt2" compile --dir "$W/res" -o "$W/compiled/res.zip"
"$BT/aapt2" link -o "$W/base.apk" -I "$JAR" \
  --manifest AndroidManifest.xml -R "$W/compiled/res.zip" -A "$W/assets" \
  --java "$W/gen" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VC" --version-name "1.$VC"

# Código Java
javac -source 8 -target 8 -bootclasspath "$JAR" -nowarn -d "$W/classes" \
  MainActivity.java $(find "$W/gen" -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output "$W/dex" $(find "$W/classes" -name '*.class')
(cd "$W/dex" && zip -q ../base.apk classes.dex)

# Alinhar e assinar (a mesma chave sempre, para as atualizações instalarem por cima)
"$BT/zipalign" -f -p 4 "$W/base.apk" "$W/aligned.apk"
"$BT/apksigner" sign --ks aura.keystore --ks-pass pass:farmandoaura --key-pass pass:farmandoaura \
  --out farmando-aura.apk "$W/aligned.apk"
"$BT/apksigner" verify farmando-aura.apk
ls -la farmando-aura.apk
