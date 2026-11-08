# NTM Reimagined Arms

An addon for HBM's Nuclear Tech Mod (NTM-NEXT) on Minecraft 26.2, NeoForge. It
changes how NTM's guns feel and hit: the player's arms hold the gun, recoil
moves the camera and the gun, bullets meet mobs by their models and blocks by
their shapes and materials, bullets and beams slow down in water and go
through glass by their own rules, and smoke clouds fill the space around them.
Builds for other NTM versions are listed under [Ports](#ports).

Builds are on the [Releases](https://github.com/ACupOfChicory/NTM-Reimagined-Arms/releases)
page. They are alphas: expect bugs and back up your world.

## Features

### Holding and moving

- Both of the player's arms hold the gun and follow NTM's animations: reloads,
  inspects, pumps and levers. The arms follow the skin (normal or slim) and
  wear what the player wears on the arms: armour with its dye, trim and glint,
  NTM's suits, the skin's sleeve layer and other mods' arm models.
- Pistols and revolvers are held from the hip a little right of the middle and
  aimed along their sights, small on the screen.
- The gun bobs in step with walking, takes a sprint pose while sprinting, lags
  behind sharp turns and dips on a jump and a landing.
- Aim with the right mouse button, for as long as it is held. NTM aims with the
  middle button and puts the gun's second action (a mode switch, Tau's charge)
  on the right one; the mod swaps them. Guns that do not aim (SPAS-12, akimbo
  pairs) keep their second action on the right button.
- Right after a sprint a gun fires only once its sprint time has passed
  (0.08 s for pistols and revolvers, 0.1 s for submachine guns, 0.15 s for
  rifles, 0.19 s for heavy weapons). A shot pressed in that time goes off when
  it ends, if the button is still held. While a fire button is held or the gun
  aims, the player stops sprinting.
- X lies down and gets up: the player crawls as under a trapdoor, with half the
  spread and half the recoil, and the view turns up to 45° and down to 30°.

### Recoil and spread

- Camera recoil, a spring kick of the gun model and a share of NTM's own recoil
  animation, each with its own strength.
- Camera climb: part of each kick stays, so a burst climbs and drifts to the
  side, building up over the first shots. Set per gun.
- Spread depends on stance and movement: standing, walking, sprinting,
  sneaking, lying, jumping and aiming, and it grows over a held-down burst.
- NTM's power armour (T-51, AJR, AJRO, RPA, NCRPA, BJ, Envsuit, HEV, FAU, DNS)
  halves recoil and the spread that walking, jumping and a burst add. The
  chestplate is enough.
- A bullet flies where the player looked in the frame of the click, and a burst
  follows the camera.

### Ammo and HUD

- An ammo counter in the bottom-right corner instead of NTM's.
- Tap Y to lock the gun's ammo type: it keeps that type, also once empty. Hold
  Y for an ammo wheel: point at a type with the mouse and left-click to reload
  with it; letting go of Y without a click changes nothing. Under the type you
  point at, the wheel shows its damage from the gun in hand, on a head hit and
  against armour, next to the loaded one.

### Hits

- Mobs and players are hit by their models: a head hit does 1.75 times the
  damage, an arm or a leg half. Worn armour, skulls and pumpkins are part of the
  model. This covers vanilla mobs, NTM's mobs and baby mobs. F3+B shows the
  outlines.
- Hits on moving mobs count by the pose the shooter saw.
- NTM's turrets and armed mobs (the hunter chopper, the cyber crab and others)
  hit by the same rules as a player's gun.

### Bullets and blocks

- Tracers in the colours of NTM's rounds, and bullets that whizz past.
- Bullets pierce, ricochet, sound and leave holes by the block's material and
  its drawn shape: through fence gaps and the empty cells of machines, through
  glass and thin wood with a loss, not through a solid wall.
- Windows on NTM's machines are glass: a bullet pierces them and leaves a hole
  visible from both sides.
- Blocks wear and break under fire: glass, pumpkins, cactus, bamboo and
  others. A server can let bullets break concrete and pierce machines.
- Impact effect quality presets: low, medium, high (the default) and realism.

### Beams

- NTM's beams (laser guns, the Tesla Cannon, Lacunae, Tau, N.I.4.N.I., the
  Aberrator, the Shredder, the laser grenade) and the beams of the Tauon and
  Maxwell turrets and the Tesla arcs go through blocks as a bullet of the same
  damage would. Turrets see and hit a target only where their beam gets
  through.
- Lasers go through glass of any kind without breaking it or losing damage.
- Beams lose damage in water and go out after a few blocks of it.

### Water

- Bullets slow down in water and lose damage and piercing; hollow points open
  and stop sooner. At a shallow angle a bullet skips off the surface.
- A shot under water has no muzzle flash but a gas bubble, and sounds duller.
  The round is seen flying, sinking and lying on the bottom.
- Splashes follow the water: a crown on still water, a fan on a slope, a burst
  through a waterfall, with rings, foam and bubbles.
- Explosive rounds, grenades and rockets meet water by their fuze: they go off
  on the surface, skip at a shallow angle, or sink and go off on the bottom. A
  blast in water reaches farther for mobs in the water.

### Smoke

- Smoke clouds fill the space around them, stay out of walls, shut doors and
  water, and go in through a door that opens. They are drawn as a volume, and
  inside one the screen goes grey. Operators let them out with `/gunsmoke`.

### Other

- A player in spectator mode cannot use NTM's guns (NTM itself allows it).
- The off-hand item is hidden while an NTM gun is held, and switching to or
  from a gun is instant.

## Installation

Put the same jar in the `mods` folder of the client and of the server. The mod
is needed on both sides, and a client and a server connect only with the very
same build.

Requirements for the 26.2 build:

- Minecraft 26.2 with NeoForge;
- NTM-NEXT (`hbm`), tested with 1.0.3, and what it needs (CrankShaft 1.6.0
  or newer on the client).

With another NTM version the mod loads, but parts whose NTM interfaces changed
are turned off with a warning. `/gunfeel status` lists the loaded versions and
what is turned off.

## Controls

The keys are in Controls under "NTM Reimagined Arms".

| Key | Action |
| --- | --- |
| Right mouse button (held) | Aim |
| Middle mouse button | The gun's second action (mode switch, Tau's charge) |
| Y (tap) | Lock the ammo type |
| Y (hold) | Ammo wheel; left-click takes the type pointed at |
| X | Lie down / get up |
| O | Gun editor for the gun in hand: recoil, position and the arms editor (labels in Russian) |
| F3+B | Hitbox outlines |

## Settings

Settings are in the `config` folder:

- `ntm-gunfeel.properties`: the player's own settings (recoil, sway, arms,
  HUD, effects). The file's header describes the keys.
- `ntm-gunfeel-guns.properties`: per-gun settings, as
  `hbm\:gun_g3.walkSway = 0`; the same key in `ntm-gunfeel.properties`
  applies to every gun. With a gun in hand,
  `/gunfeel set <key> <value>`, `/gunfeel get <key>` and `/gunfeel unset <key>`
  change its keys.
- `ntm-gunfeel-server.properties`: the server's gameplay rules (bullets,
  spread, beams, water, lying down, the sprint delay). They are sent to
  connected players. The mod does not create this file: write the keys you
  want as `key = value`. Every key and its default is described in
  `ntmgunfeel-server.properties` inside the mod jar.
- `ntm-gunfeel-bullets.txt`: what bullets do to blocks (breaking, piercing,
  materials) and how hits look and sound. The server's lines decide what
  happens; a player's own file decides how it is drawn. The file's header
  describes the rules.

Often used client keys (`ntm-gunfeel.properties`):

| Key | Default | What it does |
| --- | --- | --- |
| `arms` | `true` | Draw the arms holding the gun |
| `armArmor` | `true` | Draw what the player wears on those arms |
| `cameraScale` | `0.55` | Camera recoil strength |
| `modelKick` | `1` | Gun model kick strength |
| `adsRecoil` | `0.7` | Recoil while aiming, as a share |
| `powerArmorRecoil` | `0.5` | Recoil in NTM's power armour, as a share |
| `proneRecoil` | `0.5` | Recoil while lying, as a share |
| `proneHold` | `false` | Lie only while X is held |
| `walkSway`, `sprintPose` | `1` | Walk bob and sprint pose; `0` turns them off |
| `pistolHold`, `aimHold` | `1` | Pistol hold at the hip and in the aim; `0` turns them off |
| `aimOnRightButton` | `true` | Aim with the right button |
| `clickAim` | `true` | Bullets fly where the click was aimed |
| `cornerHud` | `true` | Corner ammo counter instead of NTM's |
| `ammoMenu` | `steel` | Look of the ammo wheel: `steel`, `petals`, `bubbles`, `ntm`, `glass`, `arc` |
| `quality` | `high` | Impact effects: `low`, `medium`, `high`, `realism` |
| `hitVolume` | `2` | Loudness of hit sounds, 0 to 4 |
| `hipX`, `hipY`, `hipZ` | `0.6`, `1`, `0.75` | Gun place at the hip; on pistols a share of their own place |
| `tracers` | `true` | Tracers |
| `bulletHoles` | `true` | Bullet holes and marks |
| `waterSplash` | `true` | Splashes, rings, foam and bubbles on water |
| `gunWeightTooltip` | `true` | A gun's tooltip names its weight class and how much it slows |
| `smokeQuality` | `high` | Smoke clouds: `off`, `slices`, `low`, `medium`, `high`, `ultra` |
| `smokeMaxClouds`, `smokeDistance` | `8`, `128` | How many clouds are drawn and how far away |
| `enabled` | `true` | The mod's client side as a whole |

`/gunfeel quality <preset>` also changes the impact-effect preset; a limit set
by hand in the file overrides the preset. With an Iris shader pack smoke is
drawn as `slices`.

Often used server keys (`ntm-gunfeel-server.properties`):

| Key | Default | What it does |
| --- | --- | --- |
| `bulletSpeed` | `1.6` | Bullet speed multiplier |
| `modelHitboxes` | `true` | Hits by the model, with head and limb zones |
| `modelHitboxesWorn` | `true` | Worn armour, skulls and pumpkins are part of the model |
| `cameraScaleMin` | `0` | Camera recoil players cannot go below |
| `spreadStand`, `spreadMove`, `spreadSneak`, `spreadCrawl`, `spreadAim` | `1`, `1.5`, `0.75`, `0.5`, `1` | Spread by stance |
| `powerArmorSpread` | `0.5` | Movement and burst spread in power armour, as a share |
| `sprintFireDelay` | `true` | No shot right after a sprint; `sprintFireTime` per gun |
| `prone`, `proneWithoutGun` | `true` | Lying down; lying without a gun in hand |
| `proneCrawlNoFire`, `proneCrawlSpeed` | `true`, `0.25` | No shot while crawling faster than this many blocks a second |
| `hbm\:gun_drill.proneCrawlNoFire`, `hbm\:gun_charge_thrower.proneCrawlNoFire` | `false` | The drill and the charge thrower work while crawling |
| `beamRules`, `beamWater`, `laserGlass` | `true` | Beams through blocks, in water, lasers through glass |
| `bulletBreakConcrete` | `false` | Bullets wear and break concrete |
| `bulletPierceMachines` | `false` | Bullets pierce machines |
| `bulletMachineGlass` | `true` | Machine windows are glass |
| `waterBallistics` | `true` | Bullets slow down and skip in water |
| `waterFuze` | `true` | Explosives meet water by their fuze |
| `npcShots` | `true` | The old bullet of the hunter chopper and the cyber crab hits by the mod's rules |
| `spectatorNoGuns` | `true` | Spectators cannot use NTM's guns |
| `smoke` | `true` | Smoke clouds |
| `smokeBlocksMobSight` | `false` | Mobs lose a target behind thick smoke |
| `gunWeightSlow`, `gunWeightHeavySpeed`, `gunWeightSuperHeavySpeed` | `true`, `0.94`, `0.88` | Heavy and super-heavy guns in hand slow the player a little |

## Commands

- `/gunfeel status`: versions and what is turned off.
- `/gunfeel set|get|unset <key>`: the gun-in-hand settings.
- `/gunfeel quality <preset>`: impact-effect quality.
- `/gunfeel hitvolume <0..4>`: loudness of hit sounds.
- `/gunfeel clearholes`: remove the bullet holes on your screen.
- `/gunfeel settings`: the settings of the gun in hand.
- `/gunsmoke` (operators): let out smoke clouds, clear them, show how many
  there are.

If something goes wrong, the mod writes its log to `logs/ntm-gunfeel-debug.log`;
attach it to a bug report.

## Compatibility

- CrankShaft and Flywheel: model hits work with Flywheel on and off.
- Iris shader packs: smoke is drawn as slices.
- Punchy! and Punchy! Moves: they leave NTM's guns to this mod.

## Ports

Other builds live on their own branches and are released next to the 26.2 one.
They do not have every feature of the 26.2 build.

| Branch | For | Requirements |
| --- | --- | --- |
| `port/1.7.10-forge` | HBM's Nuclear Tech Mod on Minecraft 1.7.10 | Forge, Java 8 or newer, UniMixins; tested with NTM 1.0.27 X5863 |
| `port/1.12.2-ce` | NTM: Community Edition on Minecraft 1.12.2 | Forge 14.23.5.2859 (Java 8) or Cleanroom, one jar for both; MixinBooter; tested with NTM CE 2.6.1.0 |
| `port/1.21.1-de` | NTM: Decentralized Edition on Minecraft 1.21.1 | NeoForge 21.1; tested with NTM DE 1.0.0-beta.14 |
| `port/continuum` | NTM Continuum on Minecraft 26.1 | NeoForge 26.1.2, Golem Core; no builds released |

## Building

Use JDK 25. Put the NTM jar at the path given by `deps.ntm` in
`stonecutter.properties.toml`, then run `./gradlew build` (`-PnoAssets` skips
downloading the game's assets on a machine without a game client). The jar is
written to `versions/26.2/build/libs/`. `./gradlew runClient` starts a
development client.

The build number is counted from Git history in `build.gradle.kts` and is
never set by hand. The build also runs the tests, resource checks,
compatibility checks and mixin checks; the test sources are in `src/test/`.

## License

Copyright (C) 2026 ACupOfChicory

Unless a file or section says otherwise, original code written for this project
is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version
(GPL-3.0-or-later). See [LICENSE](LICENSE).

Model hitbox code derives in part from Countered's Accurate Hitboxes (MIT). The
author, source link and license are in
[third-party/accurate-hitboxes.txt](third-party/accurate-hitboxes.txt), also
included in the jar under `META-INF/licenses/accurate-hitboxes.txt`.

Weapon turn sway in `WeaponSway.java` is adapted from Superb Warfare by
Mercurows and contributors under GPL-3.0-only. That file remains GPL-3.0-only;
the or-later grant above applies to this project's original code, not that
adapted code. The combined mod, which includes this GPL-3.0-only component, is
distributed under GPL-3.0. Source revision, changes and the license text are
included in [third-party/superb-warfare.txt](third-party/superb-warfare.txt)
and packaged under `META-INF/licenses/`.

The jumping sway, the run / walk / idle choice, their smoothing filter and the
crawl (its key, the player's state on both sides, the view's limits) in
`src/main/java/acup/gunfeel/tacz/` are copied from TaCZ (Timeless and Classics
Zero) by MCModderAnchor and contributors under GPL-3.0. Those files remain
GPL-3.0-only, like the Superb Warfare code above. Source revision, changes and
the license text are in [third-party/tacz.txt](third-party/tacz.txt) and
packaged under `META-INF/licenses/`. No TaCZ assets are included.

The GL-54 grenade launcher's placeholder model is a modified derivative of
"Low-Poly XM-29 OICW" by TastyTony, licensed under CC BY 4.0. The source link,
license link and list of changes are in
[third-party/gl54-model.txt](third-party/gl54-model.txt), also packaged under
`META-INF/licenses/`. The model is not endorsed by its original author.

The GL-54's grenade and magazine textures are by Nuclear GrandFather, who
allows their use and modification in NTM Reimagined Arms and its forks with
credit to the author; see [third-party/gl54-textures.txt](third-party/gl54-textures.txt).

AI mobs' idle, flinch, throw and wave clips are carried over from Quaternius'
Universal Animation Library 1 and 2 and Kay Lousberg's KayKit Character
Animations, both CC0; see [third-party/bot-animations.txt](third-party/bot-animations.txt).

The jar bundles XZ for Java 1.10 (0BSD), which unpacks the machine shapes
shipped in the mod; its notice is in [third-party/xz.txt](third-party/xz.txt)
and packaged under `META-INF/licenses/`.

The 1.7.10 port also bundles stub classes from JvmDowngrader
(LGPL-2.1-or-later) and a shaded copy of JOML (MIT). Their notices are in
`port/1.7.10/third-party/` on that branch and packaged in its jar.

Sounds under `src/main/resources/assets/ntmgunfeel/sounds/` come from
freesound.org and keep their own licenses (CC0 1.0 or CC BY 3.0/4.0); authors
and links are in
[CREDITS.txt](src/main/resources/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
