# NTM Reimagined Arms

Addon for HBM's Nuclear Tech Mod (NTM-NEXT, NeoForge 26.2) that makes its guns feel like a modern
shooter mod without new animations and without touching the NTM jar. Mixins only.

Client:
- per-frame camera recoil with a sharp kick, short hold and settle, plus a light roll shake;
- gun model kick per shot: muzzle rise, push back, random yaw and roll, pivoting on the grip;
- gun moved toward the screen centre and closer in hip fire;
- the player's arms holding the gun with both hands, placed on the gun's own geometry;
- off-hand item hidden while holding a gun;
- corner ammo HUD: gun silhouette rendered from the NTM model, magazine count, reserve, durability.

Server: hold right click to aim instead of toggling; living bullet spread: it grows with the shooter's speed and in
the air, blooms in a long burst, sneaking and crawling tighten it (`spread*` in `ntmgunfeel-server.properties`).

The ammo menu shows the hovered ammo's damage from the gun in hand (× projectiles for shot), a head hit and a whole shot
into T-51 power armour, with the difference to the loaded ammo. The same numbers for every gun and ammo:
`python3 review/ammo/make_page.py` writes `review/ammo/page.html`.

The same jar goes on the client and the server, and both need it: a client without the mod cannot join a server
with it, nor a client with it a server without it (NeoForge turns them away). Different mod versions connect while
their network protocol (`GunFeelMod.PROTOCOL`) is the same; the chat then says the versions differ.

## Other NTM versions

The NTM versions the mod is tested with are `ntmTested` in `neoforge.mods.toml` (a recommendation: NeoForge loads the
mod with any NTM). With another one, before anything is applied, the mod checks each of its mixins into NTM and what
its code calls in NTM (`ntmgunfeel-ntm-api.txt`, made by `NtmApiTest`) and turns off the systems that no longer fit
(`compat/Feature.java` maps mixins and classes to systems) instead of crashing; mixins into Minecraft and Flywheel are
left as they are. The client then shows a warning on the title screen once a launch, with the systems turned off
("Don't show for this version" remembers the NTM version in `config/ntm-gunfeel-compat.properties`); a server only
writes it to its log. On joining a server the chat says when the server's mod or NTM version differs.
`/gunfeel status` lists the versions, the tested range, the mixins not applied and the systems off.
`python3 tools/spoof_ntm.py` makes a copy of the NTM jar with another version (and a renamed method) to try it.

## Settings

`config/ntm-gunfeel.properties`, re-read while the game runs. Hand positions per gun:
`/gunfeel grip right|left <x> <y> <z>`, `/gunfeel grip show`, `/gunfeel grip reset`, `/gunfeel status`
(z = stock 0 to muzzle 1; x, y across the gun there, y 0 = underside).

Quality presets: `quality = low | medium | high | realism` (or `/gunfeel quality <preset>`) sets the number of bullet
holes, how long they stay and the share of hit particles at once; `high` is the default, `realism` keeps five times
high's holes five times longer. A value changed by hand in the file stays as it is.

| | low | medium | high | realism |
|---|---|---|---|---|
| `holeMax` | 100 | 250 | 500 | 2500 |
| `holeMaxMachines` | 30 | 75 | 150 | 750 |
| `holeLife`, s | 10 | 20 | 30 | 150 |
| `particleScale` | 0.25 | 0.5 | 1 | 1 |

### Server and player settings

Each player's own: `config/ntm-gunfeel.properties` (camera, gun model, arms, HUD, quality) and the drawing lines of
`config/ntm-gunfeel-bullets.txt` (hit, decal, hole, whizz, fly).

The server's: `config/ntm-gunfeel-server.properties` (bullet speed, spread, reaim times, `cameraScaleMin`), the lines
of `config/ntm-gunfeel-bullets.txt` that decide what a bullet does (break, material, path, ricochet, entity, kill) and
the bullet shapes beyond the mod's. The server sends them to each player who joins and again within two seconds of a
change. The player's game keeps them in memory while connected and drops them on leaving, so they never mix with
another server's or the player's own files.

In single player and on a LAN host the game is its own server and goes by its own files. LAN guests go by the host's.

`cameraScaleMin` (0 = off): camera recoil the players can't go below. Their `cameraScale` is raised to it, and their
own up and sideways curves and a shorter `cameraTime` don't count. The shake, the gun model and the arms stay theirs.

`bulletBreakConcrete` (false = off): bullets wear concrete down and break it, vanilla concrete after about 1000 damage
and NTM's after about 8000, counting only what passes 20 per bullet (rifles chip it, machine guns don't).

## Build

`./gradlew build` (JDK 25) builds the mod into `versions/26.2/build/libs/reimagined-arms-X<build>-mc26.2.jar` (the
build number is counted from git, see `CLAUDE.md`) and runs every check below. Minecraft and NeoForge come through Gradle
(ModDevGradle, without decompiling and recompiling Minecraft, so it also builds on a small ARM board). NTM NEXT is not
on any maven: its jar goes in `libs/mods/`, under the name given in `stonecutter.properties.toml`.

Stonecutter keeps one source for several Minecraft versions: `settings.gradle.kts` lists them,
`stonecutter.properties.toml` holds what each one builds against, and `//? if` comments in the code pick the lines
for each. For now there is only 26.2. `./gradlew runClient` starts the game with the mod and NTM (not tried yet; NTM
also needs the crankshaft mod on the client).

`./gradlew runDumpBlocks -PnoAssets` starts a dedicated server with the mod and NTM that writes what the game knows
about every block (id, classes, sound, hardness, blast resistance, shape, tags...) to
`versions/26.2/build/blockdump/blocks.tsv` and stops before any world: about 2 minutes on an Orange Pi, no client,
no game assets (`-PnoAssets` skips their download). Next to it, `guns.tsv`: every NTM gun with every ammo it takes
(damage, projectiles, armour piercing, spread). The bullet material tests run on the copy in `src/test/blockfacts/`,
the ammo menu tests on the one in `src/test/gundata/`; dump again and replace them when NTM or Minecraft is updated.

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

`./gradlew runCheckSettings -PnoAssets` (`SettingsCheck`) starts a dedicated server with NTM, no client and no world,
and checks the server's settings for the players on real blocks and tags: the payload through the network codec,
the server's rule kinds over the player's, the player's drawing lines kept, server numbers and shapes, and all of it
gone on leaving. The result is in `versions/26.2/build/settingscheck/result.txt`, the run fails if a check does. About
two minutes on the Pi. What only a real client shows is in `review/INGAME-CHECKS.md`.

`./gradlew runCheckBullets -PnoAssets` (`BulletCheck`) starts a dedicated server with a small flat world (two chunks
around, about 1 GB of memory) and fires real NTM bullets at real blocks, ticked by hand on the server: the gaps of
fences, closed and open gates and walls flown through and their posts, rails and bars hit; a plank pierced with the
damage its rule takes and a door far less, concrete, stone bricks, an iron door and sandbags stopping a rifle round,
sandbags letting a heavy one through and torn by rifle rounds but not by weak ones, concrete broken only with
`bulletBreakConcrete`, a thin rail stopping a very weak one; NTM's wooden ceiling, roof, scaffold and barrier met by
their boards with the gaps flown through; glass and panes
broken by 10 damage over one or two bullets, the bullet flying on; `path ... hitbox` and `path ... through` in the
server's rules file. The result is in `versions/26.2/build/bulletcheck/result.txt`; about three minutes on the Pi.

## Bullet marks

Holes, ricochet scratches and the scorch of explosive rounds come in several styles; the hit block's material picks
one (stone and concrete chip, glass cracks, soft ground gets a soft hole, metal and wood keep the first set of holes),
the same material that picks the hit sound, the ricochet and how far a bullet goes through. A `decal` line in
`config/ntm-gunfeel-bullets.txt` changes one block, e.g. `decal minecraft:obsidian hole=shatter`. The textures are
black with alpha only, so one suits every block.

## How bullets meet blocks

The mod works out how a bullet meets each block: by its hitbox (most blocks), by the pixels of its texture (grates,
bars, NTM's chain-link fence: a clear pixel lets it through), by the cells of its 3D model (NTM's scaffold, chains,
doors, lanterns; its wooden trench blocks by their boards, joined with their neighbours), or by the posts and rails of a fence, gate or wall (any mod's: the gaps are flown through, not the
1.5 block high hitbox). A `path` line in `config/ntm-gunfeel-bullets.txt` makes any block go another way, e.g.
`path somemod:railing fence` or `path somemod:solid_fence hitbox`; a `hole` line changes how its holes are drawn
(`hole minecraft:ice glass hitbox`). The file's header explains each. The server and each player read their own file.

How far a bullet goes through depends on the block's material and the length of its path through it: wood, glass and
wool hardly stop it (buckshot goes through a wooden wall, a door is thinner still), a sandbag wall stops every rifle
round, half a block of stone or concrete the plain rounds, a block of concrete, metal or steel everything. Only the
heavy armour-piercing .50 rounds get through stone, half a block of concrete or sandbags. The numbers are in
`Materials.Material`; `MaterialsTest.cover` pins what stops what.

Of these, bullets break planks, wooden doors and the like, glass, and sandbags: a sandbag counts only the damage above 4
per bullet, so a rifle tears it in about 18 hits and a machine gun's light rounds barely at all. A `break` line's
`min=` sets that threshold for any block. Stone and metal never break; concrete only with `bulletBreakConcrete`.

## License

Copyright (C) 2026 ACupOfChicory

The code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Model hitbox code derives in part from Countered's Accurate Hitboxes (MIT). The
author, source link and license are in
[third-party/accurate-hitboxes.txt](third-party/accurate-hitboxes.txt), also
included in the jar under `META-INF/licenses/accurate-hitboxes.txt`.

Sounds under `src/main/resources/assets/ntmgunfeel/sounds/` come from freesound.org and keep their own licenses (CC0 1.0 or
CC BY 3.0/4.0); authors and links are in [CREDITS.txt](src/main/resources/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
