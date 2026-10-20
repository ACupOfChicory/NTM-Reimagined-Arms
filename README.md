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

`./build.sh` compiles the mod into `ntm-gunfeel-<version>.jar` (version from `neoforge.mods.toml`). It compiles against
`libs/` when that folder is there, otherwise against the libraries of a local Prism Launcher NeoForge 26.2 instance.
JDK 25: `$JDK`, else Prism's runtime, else `javac` from `PATH`.

`libs/` needs no game install, so the mod also builds on a machine that cannot run Minecraft:

    python3 tools/fetch-libs.py --ntm <url or path of the NTM NEXT jar>

downloads the Minecraft 26.2 client jar, NeoForge and its libraries, and JUnit into `libs/` (about 120 MB, not in git).

## Tests

`./test.sh` checks what can be checked without starting the game:

1. the mod compiles against Minecraft, NeoForge and NTM NEXT;
2. `tools/MixinCheck.java`: every mixin fits the class it changes: target classes and methods exist, `@At`
   calls and fields really occur in the target method, `@Inject`/`@Redirect`/`@ModifyArg` handler signatures,
   `@Shadow`/`@Accessor` members, and the mixin config lists exactly the classes in the mixin package. These are the
   mistakes Mixin otherwise reports only by crashing at launch, typically after an NTM update;
3. the JUnit tests in `test/`: recoil curves, config keys and their round trip through the config file, and the
   files in `res/` (JSON, translations in every language, sound files and their credits, duplicate keys), the bullet
   mark textures and the `decal` rules that pick them.

## Bullet marks

Holes, ricochet scratches and the scorch of explosive rounds come in several styles; the hit block's material picks
one through the `decal` lines in `res/ntmgunfeel-bullets.txt` (stone and concrete chip, glass cracks, soft ground
gets a soft hole, metal and wood keep the first set of holes). A line in `config/ntm-gunfeel-bullets.txt` changes it,
e.g. `decal minecraft:obsidian hole=shatter`. The textures are black with alpha only, so one suits every block.

## License

Copyright (C) 2026 ACupOfChicory

The code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Sounds under `res/assets/ntmgunfeel/sounds/` come from freesound.org and keep their own licenses (CC0 1.0 or
CC BY 3.0/4.0); authors and links are in [CREDITS.txt](res/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
