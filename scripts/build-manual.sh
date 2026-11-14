#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 无 Gradle 构建脚本：直接用 Android SDK 自带的 aapt2 / javac / d8 / zipalign /
# apksigner 打出可安装的 debug APK。适用于没装 Gradle、也没有 Android Studio
# 的机器（本项目没有 gradle wrapper）。
#
# 用法：
#   ./scripts/build-manual.sh              # 产物输出到 releases/
#   PLATFORM=android-35 ./scripts/build-manual.sh
#   ./scripts/build-manual.sh --keep       # 保留中间产物便于排查
#
# 依赖：
#   - Android SDK（platforms/android-34、build-tools/*、platform-tools）
#     SDK 路径取自 $ANDROID_HOME，否则读 local.properties 的 sdk.dir
#   - 任意 JDK 17+（javac 用 --release 17 编译，较高版本 JDK 也能用）
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

# ---- 1. 定位 SDK -----------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || {
    echo "错误: 找不到 Android SDK。请设置 ANDROID_HOME 或在 local.properties 写 sdk.dir=..." >&2
    exit 1
}

BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
BT=${BT%/}
[ -n "$BT" ] && [ -x "$BT/aapt2" ] || { echo "错误: 找不到 build-tools（需要 aapt2）" >&2; exit 1; }

PLATFORM="${PLATFORM:-android-34}"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "错误: 缺少 $ANDROID_JAR（用 sdkmanager 安装 $PLATFORM）" >&2; exit 1; }

# ---- 2. 从 build.gradle.kts 读取版本与 SDK 等级 ----------------------------
GRADLE_FILE="$ROOT/app/build.gradle.kts"
val() { sed -n "s/.*$1 = //p" "$GRADLE_FILE" | head -1 | tr -d ' "'; }
MIN_SDK=$(val minSdk)
TARGET_SDK=$(val targetSdk)
VERSION_CODE=$(val versionCode)
VERSION_NAME=$(val versionName)
NAMESPACE=$(val namespace)

echo "SDK        : $SDK"
echo "build-tools: $BT"
echo "platform   : $PLATFORM"
echo "package    : $NAMESPACE  v$VERSION_NAME ($VERSION_CODE)  min$MIN_SDK target$TARGET_SDK"

# ---- 3. 准备临时工作目录 ---------------------------------------------------
WORK=$(mktemp -d "${TMPDIR:-/tmp}/tvguard-build.XXXXXX")
cleanup() { [ "$KEEP" = "1" ] && { echo "中间产物保留在: $WORK"; return; }; rm -rf "$WORK"; }
trap cleanup EXIT

MAIN="$ROOT/app/src/main"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

# ---- 4. 资源编译 + 链接（生成 R.java） ------------------------------------
# AGP 8 把包名放在 build.gradle.kts 的 namespace 里，manifest 中没有 package 属性；
# aapt2 要求 manifest 里有 package，因此注入一份临时 manifest。
sed "s|<manifest \(.*\)>|<manifest \1 package=\"$NAMESPACE\">|" \
    "$MAIN/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

"$BT/aapt2" compile --dir "$MAIN/res" -o "$WORK/res.zip"
"$BT/aapt2" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/AndroidManifest.xml" \
    -R "$WORK/res.zip" \
    --java "$WORK/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay

# ---- 5. javac 编译源码 -----------------------------------------------------
find "$WORK/gen" "$MAIN/java" -name "*.java" > "$WORK/sources.txt"
javac --release 17 -encoding UTF-8 -Xlint:-options \
      -cp "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"

# ---- 6. d8 转 dex ----------------------------------------------------------
find "$WORK/classes" -name "*.class" > "$WORK/classes.txt"
# shellcheck disable=SC2046
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$WORK/dex" $(cat "$WORK/classes.txt")

# ---- 7. 打包 + 对齐 + 签名 -------------------------------------------------
(cd "$WORK/dex" && /usr/bin/zip -q -u "$WORK/base.apk" classes.dex)
"$BT/zipalign" -p -f 4 "$WORK/base.apk" "$WORK/aligned.apk"

KS="${HOME}/.android/debug.keystore"
if [ ! -f "$KS" ]; then
    echo "未找到 debug.keystore，生成一个（密码/别名均为 Android 默认约定）"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

OUT_DIR="$ROOT/releases"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/tv-guard-v${VERSION_NAME}-debug.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT" "$WORK/aligned.apk"

echo
echo "✅ 构建完成: $OUT"
ls -lh "$OUT"
echo "SHA-256: $(shasum -a 256 "$OUT" | awk '{print $1}')"
