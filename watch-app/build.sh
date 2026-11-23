#!/usr/bin/env bash
# Builds the TapDrop app without Gradle on macOS or Linux and installs it on the watch.
# The same steps as build.ps1, which is the Windows version.
# Usage: watch-app/build.sh [--no-install] [--debuggable]
#   --debuggable  a build the tests in tests/ can drive (they read and write its
#                 settings); it is not copied into skill/
set -euo pipefail

no_install=false
debuggable=false
for arg in "$@"; do
    case "$arg" in
        --no-install) no_install=true ;;
        --debuggable) debuggable=true ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

root="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
    Darwin) host=macosx; default_sdk="$HOME/Library/Android/sdk" ;;
    Linux)  host=linux;  default_sdk="$HOME/Android/Sdk" ;;
    *) echo "Unsupported system: $(uname -s). On Windows use build.ps1." >&2; exit 1 ;;
esac
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$default_sdk}}"
bt="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35"
android_jar="$platform/android.jar"
adb="$sdk/platform-tools/adb"

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
done_() { printf '\033[32m%s\033[0m\n' "$*"; }
fail() { echo "$*" >&2; exit 1; }

# d8 and apksigner need Java 11 or newer. Use JAVA_HOME when set, else the java on PATH.
if [ -n "${JAVA_HOME:-}" ]; then jbin="$JAVA_HOME/bin"
elif [ "$host" = macosx ] && /usr/libexec/java_home -v 11+ >/dev/null 2>&1; then jbin="$(/usr/libexec/java_home -v 11+)/bin"
else jbin="$(dirname "$(command -v javac || fail 'No JDK found. Install one, for example Eclipse Temurin 17, or set JAVA_HOME.')")"
fi
version="$("$jbin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')"
[ "${version%%.*}" -ge 11 ] 2>/dev/null || fail "Java $version found; JDK 11 or newer is needed. Set JAVA_HOME."
export JAVA_HOME="$(dirname "$jbin")"
export PATH="$jbin:$PATH"

sha() { shasum -a "$1" "$2" | cut -d' ' -f1; }

# Fetches one SDK package from Google and checks it against the SHA-1 in Google's index.
get_sdk_package() {
    local file="$1" sha1="$2" target="$3"
    [ -d "$target" ] && return
    local dl; dl="$(mktemp -d)"
    say "Downloading $file from dl.google.com ..."
    curl -fsSL "https://dl.google.com/android/repository/$file" -o "$dl/$file"
    local got; got="$(sha 1 "$dl/$file")"
    [ "$got" = "$sha1" ] || fail "Checksum mismatch for $file (got $got)"
    unzip -q "$dl/$file" -d "$dl/x"
    mkdir -p "$(dirname "$target")"
    mv "$dl/x/$(ls "$dl/x" | head -1)" "$target"
    rm -rf "$dl"
    done_ "Installed $target"
}

case "$host" in
    macosx) get_sdk_package build-tools_r35_macosx.zip 93ab8ce91230e067b5add4bfa79919c52b27f072 "$bt" ;;
    linux)  get_sdk_package build-tools_r35_linux.zip  2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc "$bt" ;;
esac
get_sdk_package platform-35_r02.zip 0bb560a90a7a2cbd0dd8348224d518b638fe7949 "$platform"

# Fetches the libraries in libraries.txt into .libs, checked against their SHA-256,
# and keeps the class jar of each (an .aar holds it as classes.jar).
libs=()
while read -r url sha256; do
    case "$url" in ''|'#'*) continue ;; esac
    name="$(basename "$url")"
    jar="$root/.libs/${name%.*}.jar"
    if [ ! -f "$jar" ]; then
        mkdir -p "$root/.libs"
        dl="$(mktemp -d)"
        say "Downloading $name ..."
        curl -fsSL "$url" -o "$dl/$name"
        got="$(sha 256 "$dl/$name")"
        [ "$got" = "$sha256" ] || fail "Checksum mismatch for $name (got $got)"
        case "$name" in
            *.aar) unzip -q -p "$dl/$name" classes.jar > "$jar.part" ;;
            *)     cp "$dl/$name" "$jar.part" ;;
        esac
        mv "$jar.part" "$jar"
        rm -rf "$dl"
    fi
    libs+=("$jar")
done < "$root/libraries.txt"
classpath="$android_jar${libs[@]+$(printf ":%s" "${libs[@]}")}"

b="$root/build"
rm -rf "$b"
mkdir -p "$b/gen" "$b/classes" "$b/dex"

say "Compiling resources..."
"$bt/aapt2" compile --dir "$root/res" -o "$b/res.zip"
debug_mode=()
$debuggable && debug_mode=(--debug-mode)
"$bt/aapt2" link -o "$b/base.apk" -I "$android_jar" --manifest "$root/AndroidManifest.xml" \
    ${debug_mode[@]+"${debug_mode[@]}"} --java "$b/gen" --proguard "$b/aapt-rules.pro" --min-sdk-version 30 --target-sdk-version 34 "$b/res.zip"

say "Compiling Java..."
find "$root/src" "$b/gen" -name '*.java' > "$b/sources.txt"
javac --release 11 -encoding UTF-8 -classpath "$classpath" -d "$b/classes" "@$b/sources.txt"

say "Shrinking and converting to dex..."
(cd "$b/classes" && jar cf "$b/classes.jar" .)
# R8 keeps only the code reachable from the manifest's components (the rules aapt2
# wrote) and proguard.pro. Its warnings about Kotlin metadata are expected, so its
# output goes to build/r8.log and is shown only if it fails.
java -cp "$bt/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 30 --lib "$android_jar" \
    --pg-conf "$b/aapt-rules.pro" --pg-conf "$root/proguard.pro" --output "$b/dex" \
    "$b/classes.jar" ${libs[@]+"${libs[@]}"} > "$b/r8.log" 2>&1 || { cat "$b/r8.log" >&2; fail 'R8 failed'; }

say "Packaging and signing..."
cp "$b/base.apk" "$b/unsigned.apk"
(cd "$b/dex" && zip -q "$b/unsigned.apk" classes*.dex)
"$bt/zipalign" -f -p 4 "$b/unsigned.apk" "$b/aligned.apk"

# A throwaway debug key, kept beside the source so reinstalls keep the same signature.
ks="$root/debug.keystore"
if [ ! -f "$ks" ]; then
    keytool -genkeypair -keystore "$ks" -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
fi
apk="$b/tapdrop.apk"
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android --out "$apk" "$b/aligned.apk"
done_ "Built $apk ($(wc -c < "$apk" | tr -d ' ') bytes)"

# Keep the copies bundled with the shareable skill in step with the source.
repo="$(dirname "$root")"
skill="$repo/skill/tapdrop-watch"
if [ -d "$skill" ] && ! $debuggable; then
    mkdir -p "$skill/assets"
    cp "$apk" "$skill/assets/tapdrop.apk"
    cp "$repo/web/pay.html" "$skill/assets/pay.html"
    done_ "Updated skill assets"
fi

$no_install && exit 0

# Pick the watch by hardware type, so a phone connected at the same time is never the target.
dev=""
for serial in $("$adb" devices | awk -F'\t' '$2 == "device" { print $1 }'); do
    if "$adb" -s "$serial" shell getprop ro.build.characteristics | grep -q watch; then dev="$serial"; break; fi
done
[ -n "$dev" ] || fail 'No watch connected over adb. Reconnect it (see skill/tapdrop-watch), then run this again.'
say "Installing on $dev ..."
"$adb" -s "$dev" install -r "$apk"
"$adb" -s "$dev" shell am start -n dev.tapdrop/.MainActivity
done_ "TapDrop is open on the watch."
