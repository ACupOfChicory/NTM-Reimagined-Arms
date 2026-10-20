#!/bin/sh
# Addon for HBM NTM guns. Server: hold-to-aim. Client: smooth per-frame camera recoil, spring model recoil,
# gun moved toward the screen centre, off-hand item hidden. NTM itself untouched.
#
# Class path: libs/ from tools/fetch-libs.py when it is there, otherwise the local Prism Launcher instance.
# JDK: $JDK, else Prism's runtime, else javac from PATH. The jar name follows the version in neoforge.mods.toml.
set -e
cd "$(dirname "$(readlink -f "$0")")"
VERSION=$(sed -n 's/^version *= *"\(.*\)"/\1/p' res/META-INF/neoforge.mods.toml)
OUT=ntm-gunfeel-$VERSION.jar
P=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher

if [ -n "$JDK" ]; then J=$JDK/bin/
elif [ -x "$P/java/java-runtime-epsilon/bin/javac" ]; then J=$P/java/java-runtime-epsilon/bin/
else J=
fi

if [ -d libs/minecraft ]; then
    if ! ls libs/mods/*.jar >/dev/null 2>&1; then
        echo "no NTM NEXT jar in libs/mods/ (see tools/fetch-libs.py)" >&2
        exit 1
    fi
    CP=$(find libs/minecraft libs/neoforge libs/mods -name '*.jar' | sort | tr '\n' ':')
else
    L=$P/libraries
    M=$P/instances/NTM-NeoForge-26.2-private/minecraft/mods
    CP=$L/com/mojang/minecraft/26.2/minecraft-26.2-client.jar
    CP=$CP:$L/net/neoforged/neoforge/26.2.0.90/neoforge-26.2.0.90-universal.jar
    CP=$CP:$L/net/neoforged/fancymodloader/loader/11.0.16/loader-11.0.16.jar
    CP=$CP:$L/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar
    CP=$CP:$(ls $M/ntm-next-neoforge-*.jar):$(ls $L/net/neoforged/bus/*/bus-*.jar | tail -1)
    CP=$CP:$L/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar
    CP=$CP:$L/net/neoforged/mergetool/2.0.7/mergetool-2.0.7-api.jar:$L/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar
    CP=$CP:$L/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar:$L/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar
    CP=$CP:$L/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar
    CP=$CP:$L/io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar:$L/io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar
    for d in $L/org/jspecify $L/org/joml; do
        for j in $(find $d -name '*.jar' 2>/dev/null); do CP=$CP:$j; done
    done
fi

rm -rf build/jar && mkdir -p build/jar
"${J}javac" --release 25 -proc:none -nowarn -cp "$CP" -d build/jar $(find src -name '*.java')
cp -r res/. build/jar/
rm -f "$OUT" && "${J}jar" cf "$OUT" -C build/jar .
echo "built $OUT"
