#!/bin/bash
# ============================================================================
# bootstrap_build.sh — Entorno de construcción durable para notPipe+ 0.4.1
# ============================================================================
# Reconstruye TODO el entorno de compilación bajo /home/z/my-project/buildenv/
# (que persiste entre sesiones) y compila+firma el APK desde
# /home/z/my-project/work/notPipe041. Es IDEMPOTENTE: lo que ya existe no se
# vuelve a descargar. Tras un borrado total del sandbox, una sola ejecución
# deja todo listo otra vez (~10-15 min desde cero; ~1 min con caché).
#
# Uso:  bash /home/z/my-project/scripts/bootstrap_build.sh
#
# Componentes (todo bajo buildenv/, nada en rutas volátiles):
#   jdk8u504-b01/       Temurin JDK 8 (Adoptium) — compilación y firma
#   gradle-3.3/         Gradle 3.3 — el que usa el proyecto (AGP 2.3.2)
#   gradle-home/        GRADLE_USER_HOME — caché durable de dependencias
#   android-sdk/        build-tools 25.0.0 + platform 25 + platform-tools
#   (el SDK y las dependencias se descargan de URLs permanentes de
#    Google/Adoptium/Gradle y del espejo maven legacy de gohoski)
# ============================================================================
set -e

BENV=/home/z/my-project/buildenv
PROY=/home/z/my-project/work/notPipe041
mkdir -p "$BENV" "$PROY"

# ---------- 1. JDK 8 (Temurin, Adoptium) ----------
if [ ! -x "$BENV/jdk8u504-b01/bin/javac" ]; then
    echo "[1/5] Descargando JDK 8 (Temurin)..."
    curl -sL "https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$BENV/jdk8.tar.gz"
    tar xzf "$BENV/jdk8.tar.gz" -C "$BENV"
    rm -f "$BENV/jdk8.tar.gz"
else
    echo "[1/5] JDK 8 ya presente."
fi
JDK8=$(ls -d "$BENV"/jdk8u* 2>/dev/null | head -1)
[ -x "$JDK8/bin/javac" ] || { echo "FALLO: JDK 8 no quedó instalado"; exit 1; }

# ---------- 2. Gradle 3.3 ----------
if [ ! -x "$BENV/gradle-3.3/bin/gradle" ]; then
    echo "[2/5] Descargando Gradle 3.3..."
    rm -rf "$BENV/gradle-3.3" "$BENV/gradle-3.3.tmp" "$BENV/gradle-3.3.zip"
    curl -sL "https://services.gradle.org/distributions/gradle-3.3-bin.zip" -o "$BENV/gradle-3.3.zip"
    # Extracción atómica: si el proceso muere a mitad, nunca queda un
    # directorio a medias — el mv final es una única operación de renombrado.
    unzip -q "$BENV/gradle-3.3.zip" -d "$BENV/gradle-3.3.tmp"
    mv "$BENV/gradle-3.3.tmp/gradle-3.3" "$BENV/gradle-3.3"
    rm -rf "$BENV/gradle-3.3.tmp" "$BENV/gradle-3.3.zip"
else
    echo "[2/5] Gradle 3.3 ya presente."
fi
# Sanidad: un Gradle corrupto (extracción interrumpida de una ejecución
# anterior) se auto-repara re-descargándolo.
if ! JAVA_HOME="$JDK8" "$BENV/gradle-3.3/bin/gradle" --version > /dev/null 2>&1; then
    echo "      Gradle corrupto — reparando..."
    rm -rf "$BENV/gradle-3.3" "$BENV/gradle-3.3.tmp"
    curl -sL "https://services.gradle.org/distributions/gradle-3.3-bin.zip" -o "$BENV/gradle-3.3.zip"
    unzip -q "$BENV/gradle-3.3.zip" -d "$BENV/gradle-3.3.tmp"
    mv "$BENV/gradle-3.3.tmp/gradle-3.3" "$BENV/gradle-3.3"
    rm -rf "$BENV/gradle-3.3.tmp" "$BENV/gradle-3.3.zip"
fi

# ---------- 3. Android SDK (build-tools 25, platform 25, platform-tools) ----------
if [ ! -x "$BENV/android-sdk/build-tools/25.0.0/aapt" ]; then
    echo "[3/5] Descargando build-tools 25.0.0..."
    curl -sL "https://dl.google.com/android/repository/build-tools_r25-linux.zip" -o "$BENV/bt25.zip"
    rm -rf "$BENV/bt25tmp" "$BENV/android-sdk/build-tools/25.0.0"
    mkdir -p "$BENV/android-sdk/build-tools" "$BENV/bt25tmp"
    unzip -q "$BENV/bt25.zip" -d "$BENV/bt25tmp"
    mv "$BENV/bt25tmp/android-7.1.1" "$BENV/android-sdk/build-tools/25.0.0"
    rm -rf "$BENV/bt25tmp" "$BENV/bt25.zip"
else
    echo "[3/5] build-tools 25.0.0 ya presente."
fi
if [ ! -f "$BENV/android-sdk/platforms/android-25/android.jar" ]; then
    echo "      Descargando platform 25 (android.jar)..."
    curl -sL "https://dl.google.com/android/repository/platform-25_r03.zip" -o "$BENV/plat25.zip"
    rm -rf "$BENV/plat25tmp" "$BENV/android-sdk/platforms/android-25"
    mkdir -p "$BENV/android-sdk/platforms" "$BENV/plat25tmp"
    unzip -q "$BENV/plat25.zip" -d "$BENV/plat25tmp"
    mv "$BENV/plat25tmp/android-7.1.1" "$BENV/android-sdk/platforms/android-25"
    rm -rf "$BENV/plat25tmp" "$BENV/plat25.zip"
fi
if [ ! -x "$BENV/android-sdk/platform-tools/adb" ]; then
    echo "      Descargando platform-tools..."
    curl -sL "https://dl.google.com/android/repository/platform-tools-latest-linux.zip" -o "$BENV/pt.zip"
    unzip -q "$BENV/pt.zip" -d "$BENV/android-sdk"
    rm -f "$BENV/pt.zip"
fi
mkdir -p "$BENV/android-sdk/licenses"
printf "\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$BENV/android-sdk/licenses/android-sdk-license"

# ---------- 4. Código fuente ----------
# El proyecto vive en work/notPipe041. Si no existe (borrado total), se puede
# reconstruir desde el zip del usuario o desde GitHub:
#   fork:  https://github.com/KevinRunforrestt/NotPipePlus  (0.4.0 base)
#   padre: https://github.com/gohoski/notPipe               (referencia)
if [ ! -f "$PROY/app/build.gradle" ]; then
    echo "[4/5] ATENCIÓN: falta el proyecto en $PROY"
    echo "      Restaurarlo desde el zip del código fuente (respaldo del usuario)"
    echo "      o clonar el fork y reaplicar CAMBIOS-0.4.1.md."
    exit 1
else
    echo "[4/5] Proyecto presente en $PROY"
fi

# ---------- 5. Compilar y firmar ----------
echo "[5/5] Compilando (Gradle 3.3 + JDK 8, caché durable en buildenv/gradle-home)..."
cd "$PROY"
echo "sdk.dir=$BENV/android-sdk" > local.properties
export JAVA_HOME="$JDK8"
export GRADLE_USER_HOME="$BENV/gradle-home"
# Salida a archivo: el JVM bifurcado de Gradle 3.3 a veces no cierra la
# tubería stdout y cuelga el comando aunque el build ya terminó.
"$BENV/gradle-3.3/bin/gradle" --no-daemon assembleRelease > "$PROY/build.log" 2>&1
BUILD_RC=$?
tail -5 "$PROY/build.log"
[ $BUILD_RC -eq 0 ] || { echo "FALLO el build (ver $PROY/build.log)"; exit 1; }

BT="$BENV/android-sdk/build-tools/25.0.0"
APK="$PROY/app/build/outputs/apk/notPipePlus-0.4.1-release-unsigned.apk"
"$BT/zipalign" -f 4 "$APK" "$APK.aligned"
"$JDK8/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$PROY/notpipeplus.keystore" --ks-pass pass:notpipeplus --key-pass pass:notpipeplus \
  --min-sdk-version 3 --v1-signing-enabled true --v2-signing-enabled false "$APK.aligned"
mv "$APK.aligned" "$PROY/notPipePlus-0.4.1-release.apk"
"$JDK8/bin/java" -jar "$BT/lib/apksigner.jar" verify "$PROY/notPipePlus-0.4.1-release.apk"
echo ""
echo "=================================================================="
echo " APK FIRMA DO: $PROY/notPipePlus-0.4.1-release.apk"
echo " md5: $(md5sum "$PROY/notPipePlus-0.4.1-release.apk" | cut -d' ' -f1)"
echo "=================================================================="
