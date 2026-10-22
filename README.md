# NTM-NEXT Reimagined Arms

Addon for HBM's Nuclear Tech Mod (NTM-NEXT, NeoForge 26.2) that makes its guns feel like a modern
shooter mod without new animations and without touching the NTM jar. Mixins only.

Client:
- per-frame camera recoil with a sharp kick, short hold and settle, plus a light roll shake;
- gun model kick per shot: muzzle rise, push back, random yaw and roll, pivoting on the grip;
- gun moved toward the screen centre and closer in hip fire;
- the player's arms holding the gun with both hands, placed on the gun's own geometry;
- off-hand item hidden while holding a gun;
- corner ammo HUD: gun silhouette rendered from the NTM model, magazine count, reserve, durability.

Server: hold right click to aim instead of toggling.

The same jar goes on the client and the server.

## Settings

`config/ntm-gunfeel.properties`, re-read while the game runs. Hand positions per gun:
`/gunfeel grip right|left <x> <y> <z>`, `/gunfeel grip show`, `/gunfeel grip reset`
(z = stock 0 to muzzle 1; x, y across the gun there, y 0 = underside).

## Build

`./gradlew build` (JDK 25) builds the mod into `versions/26.2/build/libs/ntm-gunfeel-<version>.jar` (the version
comes from `neoforge.mods.toml`) and runs every check below. Minecraft and NeoForge come through Gradle
(ModDevGradle, without decompiling and recompiling Minecraft, so it also builds on a small ARM board). NTM NEXT is not
on any maven: its jar goes in `libs/mods/`, under the name given in `stonecutter.properties.toml`.

Stonecutter keeps one source for several Minecraft versions: `settings.gradle.kts` lists them,
`stonecutter.properties.toml` holds what each one builds against, and `//? if` comments in the code pick the lines
for each. For now there is only 26.2. `./gradlew runClient` starts the game with the mod and NTM (not tried yet; NTM
also needs the crankshaft mod on the client).

`./gradlew runDumpBlocks -PnoAssets` starts a dedicated server with the mod and NTM that writes what the game knows
about every block (id, classes, sound, hardness, blast resistance, shape, tags...) to
`versions/26.2/build/blockdump/blocks.tsv` and stops before any world: about 2 minutes on an Orange Pi, no client,
no game assets (`-PnoAssets` skips their download). The bullet material tests run on the copy in
`src/test/blockfacts/`; dump again and replace it when NTM or Minecraft is updated.

## Tests

`./gradlew build` checks what can be checked without starting the game:

1. the mod compiles against Minecraft, NeoForge and NTM NEXT;
2. `mixinCheck` (`tools/MixinCheck.java`): every mixin fits the class it changes: target classes and methods exist,
   `@At` calls and fields really occur in the target method, `@Inject`/`@Redirect`/`@ModifyArg` handler signatures,
   `@Shadow`/`@Accessor` members, and the mixin config lists exactly the classes in the mixin package. These are the
   mistakes Mixin otherwise reports only by crashing at launch, typically after an NTM update;
3. the JUnit tests in `src/test/java`: recoil curves, config keys and their round trip through the config file, and
   the files in `src/main/resources` (JSON, translations in every language, sound files and their credits, duplicate
   keys), the bullet mark textures and the `decal` rules that pick them;
4. `PinnedTest`: what the player sees and feels, pinned in `src/test/pinned/*.txt` bit for bit: the recoil curves, the
   recoil strength, every config default, the mark kinds, the ammo menu colours and a hash of every resource file.
   A build change or a clean-up must leave them all as they are. When a change is meant (new textures, a retuned
   curve), `PIN_UPDATE=1 ./gradlew test` rewrites them and the `.txt` diff goes into the same commit.

`./gradlew jarDiff -Pbaseline=<old jar>` (`tools/JarDiff.java`) tells whether two builds run the same: the bytecode of
every class without debug info, and every other file byte for byte. javac's debug flags change the code itself (`-g`
keeps constant locals), so the build sticks to javac's default `-g:source,lines`. The move from the old `build.sh`
to Gradle was checked this way: the same jar, 198 entries, only the manifest names Stonecutter.

## Bullet marks

Holes, ricochet scratches and the scorch of explosive rounds come in several styles; the hit block's material picks
one through the `decal` lines in `src/main/resources/ntmgunfeel-bullets.txt` (stone and concrete chip, glass cracks, soft ground
gets a soft hole, metal and wood keep the first set of holes). A line in `config/ntm-gunfeel-bullets.txt` changes it,
e.g. `decal minecraft:obsidian hole=shatter`. The textures are black with alpha only, so one suits every block.

## License

Copyright (C) 2026 ACupOfChicory

The code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Sounds under `src/main/resources/assets/ntmgunfeel/sounds/` come from freesound.org and keep their own licenses (CC0 1.0 or
CC BY 3.0/4.0); authors and links are in [CREDITS.txt](src/main/resources/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
