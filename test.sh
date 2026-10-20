#!/bin/sh
# Everything that can be checked without starting Minecraft, in the order it would break:
#   1. the mod compiles against Minecraft, NeoForge and NTM NEXT (build.sh);
#   2. the mixins fit the classes they change (tools/MixinCheck.java), which Mixin itself only reports by crashing;
#   3. the unit tests in test/ (JUnit): pure logic, config round trips, the files in res/.
# Needs libs/ from tools/fetch-libs.py. Exit code 0 = all passed.
set -e
cd "$(dirname "$(readlink -f "$0")")"
P=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher
if [ -n "$JDK" ]; then J=$JDK/bin/
elif [ -x "$P/java/java-runtime-epsilon/bin/javac" ]; then J=$P/java/java-runtime-epsilon/bin/
else J=
fi
if [ ! -d libs/minecraft ]; then
    echo "libs/ missing: run tools/fetch-libs.py first" >&2
    exit 1
fi

echo "== build"
./build.sh

CP=$(find libs/minecraft libs/neoforge libs/mods -name '*.jar' | sort | tr '\n' ':')
ASM=$(find libs -path '*org/ow2/asm/*' -name 'asm-*.jar' | sort | tr '\n' ':')
JUNIT=$(ls libs/test/junit-platform-console-standalone-*.jar | tail -1)

echo "== mixins"
"${J}java" -cp "$ASM" tools/MixinCheck.java build/jar "$CP"

echo "== unit tests"
rm -rf build/test && mkdir -p build/test
"${J}javac" --release 25 -proc:none -nowarn -cp "build/jar:$CP:$JUNIT" -d build/test $(find test -name '*.java')
"${J}java" -jar "$JUNIT" execute --disable-banner --disable-ansi-colors --details=summary \
    --class-path "build/test:build/jar:$CP" --scan-class-path build/test
