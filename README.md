# NTM Reimagined Arms

An addon for HBM's Nuclear Tech Mod (NTM-NEXT) on Minecraft 26.2, NeoForge. It
changes how NTM's guns feel and hit: the player's arms hold the gun, recoil
moves the camera and the gun, bullets meet mobs by their models and blocks by
their shapes and materials, bullets and beams slow down in water and go
through glass by their own rules, and smoke clouds fill the space around them.
It also adds the GL-54 grenade launcher with its gas grenades, and AI soldiers
that fight from cover.
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
- A shotgun aimed throws its shot tighter, and a slug carries farther.
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
- A 12 Gauge Electroshock Shell for NTM's 12 gauge shotguns (all but the
  Shredder), made in NTM's ammo press, at the tier of NTM's 10 gauge and 12
  gauge magnum shells: a capacitor, a copper coil, smokeless powder and an
  advanced buckshot casing give six. One round, about 2 damage. A
  hit shocks for 2 seconds, which then wear off smoothly over 3 more: the HUD
  (other mods' too, not the chat or menus) glitches and shakes, the view
  shakes and drifts on its own, and whoever was hit, an AI soldier or any mob
  too, walks 80 % slower (a player's view narrows by a fifth) and
  spreads their shots wide. Then 3 seconds of immunity. The shooter sees a
  spark at the crosshair.

### Hits

- Mobs and players are hit by their models: a head hit does 1.75 times the
  damage, an arm or a leg half. Worn armour, skulls and pumpkins are part of the
  model. This covers vanilla mobs, NTM's mobs and baby mobs. F3+B shows the
  outlines.
- Hits on moving mobs count by the pose the shooter saw.
- A player hit by an NTM gun's bullet, pellet or beam is neither thrown back
  nor stopped short, and their view does not tilt: the damage, the hurt
  sound and the red of the hit stay. Blasts, melee hits, mobs, arrows and
  falls push and tilt as ever. The server may give both back.
- NTM's turrets and armed mobs (the hunter chopper, the cyber crab and others)
  hit by the same rules as a player's gun.
- FBI agents and test dummies have ten hitbox parts: head, body, upper arms,
  forearms, thighs and shins, each where it is drawn, in whatever pose the
  mob is in (crouched, leaning out, lying).
- The head and limb multipliers come after armour: armour takes its share of
  the whole bullet first, then the part hit scales what gets through.
- An explosion hurts each body part by itself: rays go from the blast to every
  part, so behind a low wall only what sticks out is hit, and a player lying
  with the feet to a grenade takes most of it on the legs. This works for
  hand grenades, grenade-launcher rounds and rockets.
- A damage cap keeps a player from dying to one click: a click (all the
  pellets and rounds a shooter fires in one tick) takes at most 60 % of max
  health, 80 % on the head, and one shooter at most 80 % in any 14 ticks.
  Slow sniper rifles (25 ticks or more between shots) still kill with a head
  shot. Nuclear blasts, digamma, fire, falling, radiation, gas, melee,
  vanilla blasts and blasts of more than 60 raw damage (a mine, C4, TNT) are
  not capped. Mobs as targets only with `damageCapMobs`.
- NTM's combat armour sets take bullets, beams and blasts by one formula
  with a rating per set: a hit far under the rating loses most of itself, one
  far over it gets nearly all through, and none is ever stopped to 0. A piece
  alone protects its share of the body. These sets have no vanilla armour
  points, so vanilla's armour does not come on top. DNS is not invulnerable:
  it takes hits by its rating like the rest.
- What a player carries on top of the armour is cut down to about twice as
  long a fight at most: the shield mod gives 10 and a shield flask 2 (at
  most 16 in all), and the shield comes back slower; Med-X gives Resistance
  I for 60 s and needs plastic; the super stimpak heals its 25 HP over 12 s;
  an armour insert takes at most a tenth off a bullet.
- Euphemium stays invulnerable, but every bullet, beam and blast wears down
  a pool of 150 (as much as it would do to a DNS-class set). The emptied
  pool leaves the wearer open to hits for 15 s, without the set's
  Regeneration and Resistance; then the pool fills back over 60 s. The
  tomb's radon gas takes the invulnerability off while the wearer is in it.
- Armour sets' abilities fit their tier: Speed, Jump Boost and Strength are
  capped by the set's tier, bismuth is a tier 6 set, and VATS and thermal
  vision show no players (FAU and DNS thermal and AMAT Penance's thermal
  sight: the ones in sight within 16 blocks).
- Guns' damage by gun and ammo (the balance's table, one line a pair): each
  pair's shot does NTM's damage times its multiplier. A few guns change: Heretic is a semi-automatic
  shotgun with 24 rounds, AM180 fires slower with 60, the AMAT Penance,
  Subtlety and MAS-36 fire faster, Morning Glory hits twice as hard,
  Aberrator holds 9 rounds and Eyes of the Tempest 8 a gun.
- The balance is one server switch: `balance = arms` (the default) or
  `balance = ntm`, NTM's own damage, armour, spread, range, blasts,
  abilities and recipes (the mod's other rules, such as the damage cap, keep
  their own keys). Its numbers are fixed in the mod.

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
- A blast punches a hole in a smoke cloud that closes again in 2 to 3 seconds;
  bullets do not disturb smoke.
- Overlapping clouds are drawn as one smoke, without flicker, and a smoke
  grenade that bursts inside a cloud feeds that cloud.

### GL-54 and gas

- The GL-54 is a grenade launcher of the mod's own, with high-explosive,
  fragment, smoke and gas grenades (mustard, chlorine, acid, phosgene).
- Bullets that fly through a gas cloud thin it along their path, and a blast
  blows the gas around it away.
- Gas that lies for long at one place poisons the ground for good: nothing
  grows there, grass and leaves turn a sick yellow-brown and mobs do not spawn
  on it. A magazine of mustard fired at one place is enough; chlorine and acid
  need two, phosgene three.

### Gun mechanisms

- Five gun mechanisms NTM has no item for: steel, high-speed steel, desh,
  ferrouranium and technetium steel. They are cast in the crucible's mechanism
  mold like NTM's gunmetal, weapon steel and saturnite ones, melt back down,
  and show in JEI and NTM's parts tab.
- A mechanism stands for a gun's tier, as in NTM, on eight steps: steel,
  gunmetal, high-speed steel, desh, ferrouranium, weapon steel, technetium
  steel, saturnite. A gun takes the step of its tier and material: steel for
  the flare gun, Grease Gun, light revolver, Maresleg and Charge Thrower;
  high-speed steel for AM180, Congo Lake, the flamethrower and Liberator;
  desh for the carbine, heavy revolver, Panzerschreck, SPAS-12 and UZI;
  ferrouranium for AMAT, the auto-shotgun, M2 and Quadro; technetium steel
  for Lag, the minigun, the missile launcher, the GL-54 and Tesla Cannon
  (saturnite with the balance's recipe).
- A B-side takes the step above its base gun's: desh for Atlas, Henry
  Lincoln, the Maresleg pair and Topaz, ferrouranium for the UZI pair.
  Gunmetal and weapon steel mechanisms no longer fit these recipes; the
  server key `gunMechanismRecipes` turns NTM's recipes back on.

### AI soldiers

- NTM's FBI agents and test dummies fight as a player does, with NTM's guns.
  Operators bring them in with `/armsai spawn fbi|dummy [count] [gun]` (and
  `team <name>` at the end); `/armsai clear` removes them. They fight in the
  game's teams.
- Their armour and rounds are yours to choose. After the gun add, in any
  order, `armor <set>` (a full set by its name: `steel`, `t51`, `hev`, `sec`,
  `bj`, `dns` and the rest, or `none`), `armor <slot>=<item> …` for single
  pieces (`armor chest=hbm:hev_plate legs=steel`; slots `head`, `chest`,
  `legs`, `feet`) and `ammo <round>` (one of the rounds the gun takes, such as
  `r556_ap`; the bot reloads with it too). The names complete as you type.
  `/armsai kit <targets> [armor …] [ammo …]` changes bots that already stand,
  for example `/armsai kit @e[tag=armsai,distance=..20] armor hev ammo
  r556_ap`.
- They see and hear as a person does, run to cover, crouch behind low walls,
  run low past them, lean out round a corner, fire blind over cover and lie
  down in the open. They aim at what they see of you, never miss on purpose
  and take the gun's recoil.
- Bots of one side fight as a squad: when you vanish behind cover one goes
  round the side while the others fire to keep your head down, and a bot
  leaves cover only while another covers it. They throw grenades, run from
  grenades thrown at them, including a player's, and do not turn their heads
  to the sounds of animals.
- Fire suppresses them as it does you: bullets passing close, hitting their
  cover or bursting near pin a bot down, and a squad kept under fire loses
  heart, holds back and falls back (server key `aiSuppression`).
- A bot with a machine gun is its squad's base of fire: it stays in place and
  keeps your cover under long bursts while a rifleman goes round the side
  (server keys `aiRoles`, `aiBaseMagazine`).
- They open wooden doors, fence gates and NTM's plain doors on their way; an
  iron door still keeps them out (server key `aiDoors`).
- They go into a room together: beside the doorway first, out of the line
  through it, then in at once, each to its own side (server key `aiEntry`).
  One of them steps out before the doorway first and throws a grenade in,
  then steps back behind the wall; they go in after it bursts. It is thrown
  only where the burst reaches none of their own (server keys
  `aiEntryGrenade`, `aiEntryGrenadeFilling`).
- They search a building room after room, two to a room, and do not go back
  into a room already checked (server key `aiClearing`). Into the next room
  inside they go as into the first: from beside the doorway, together (with
  `aiEntry` on).
- They tell a cave from a building by what its walls, floor and roof are
  made of (server key `aiRoomsShell`; the block lists are settings too).
- They move their bodies: breathing, flinching at hits, aiming, reloading,
  throwing, signalling with a hand, crouching, leaning and lying with the gun.
  Arms and legs bend at the elbows and knees, and the armour they wear is
  drawn on them.
- Bots can learn from your fights: `/armsai record on|off` (operators) records
  how you fight, and `/armsai course` lays out a ground to fight on
  (`/armsai course clear` removes it). Recording is off by default.
- `/armsai debug` shows what the bots think; `/armsai info` shows how many
  there are and what they cost.
- `/armsai debug rooms` shows the buildings round you as the bots read
  them: rooms, corridors, doorways with the places a squad stacks up at,
  windows, stairs, and what is under a roof but no building. `/armsai debug
  walls` adds what the walls are made of, `/armsai debug dump` gives it in
  numbers.

### Other

- Heavy guns slow the player down: 6 % with a heavy gun in hand and 12 % with
  a super-heavy one (miniguns, the Fat Man, the Tesla Cannon, the Ma Deuce,
  the MK108, the Quad and others). A gun's description shows its kind (pistol,
  submachine gun, rifle, shotgun, minigun, launcher...), its weight class and
  the slowdown.
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
  spread, beams, water, lying down, the sprint delay, gas, suppression, AI
  soldiers, the balance: `arms` or `ntm`). They are sent to connected
  players. The mod does not create this file: write the keys you
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
| `suppressionDarken`, `suppressionMuffle` | `1` | How much the screen darkens and the other sounds go down under fire, 0 to 1 (the server sets the most) |
| `shockGlitch` | `1` | How much the HUD glitches and shakes when a shock shell hits you, 0 to 1 (the server sets the most and the least) |
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
| `modelHitboxesHelmet` | `true` | A shot on a helmet is a shot in the head: a layer's helmet and an NTM suit's own helmet model (T-51, HEV and the rest), shown as the head in F3+B; false: as X949 |
| `modelHitboxesSuits` | `true` | A shot on an NTM suit's own model, or on armour drawn as a layer, is a shot in the part its piece covers: the chest's collar and breastplate the body, the shoulder pads, sleeves, legs and boots the limb; false: as X953 |
| `clientHits` | `true` | A player's bullet hits what that player's game saw it hit; off: the server's view decides |
| `cameraScaleMin` | `0` | Camera recoil players cannot go below |
| `spreadStand`, `spreadMove`, `spreadSneak`, `spreadCrawl`, `spreadAim` | `1`, `1.5`, `0.75`, `0.5`, `1` | Spread by stance |
| `balance` | `arms` | The balance: `arms`, the mod's; `ntm`, NTM's own. `arms`: NTM's combat sets take bullets, beams and blasts by the mod's armour formula, and a gun with its ammo kills a set in the time of the mod's ladder (by the tiers, the gun's role and the range), launchers' rounds and hand grenades on the same ladder; the kit (the shield, Med-X, the super stimpak, the inserts), the pools of euphemium, DNS and FAU, the sets' abilities, a steadier aim and less recoil in power armour, the shotguns' aimed choke and the balance's recipes. `ntm`: none of it, NTM's damage, armour, spread, range, blasts, abilities and recipes; the mod's rules that are not the balance (`damageCap`, the living spread, `bulletSpeed`, `modelHitboxes`) keep their own keys. Its numbers are fixed in the mod: no other key sets them, an old file's `balance*` keys are not read |
| `sprintFireDelay` | `true` | No shot right after a sprint; `sprintFireTime` per gun |
| `prone`, `proneWithoutGun` | `true` | Lying down; lying without a gun in hand |
| `proneCrawlNoFire`, `proneCrawlSpeed` | `true`, `0.25` | No shot while crawling faster than this many blocks a second |
| `hbm\:gun_drill.proneCrawlNoFire`, `hbm\:gun_charge_thrower.proneCrawlNoFire` | `false` | The drill and the charge thrower work while crawling |
| `beamRules`, `beamWater`, `laserGlass` | `true` | Beams through blocks, in water, lasers through glass |
| `suppression` | `true` | Suppression under fire: bullets past and hitting near and blasts near darken the screen and muffle the other sounds |
| `suppressionPass`, `suppressionImpact`, `suppressionBlast` | `1` | Each source's force; `0` leaves it out |
| `suppressionPassRange`, `suppressionImpactRange`, `suppressionBlastRange` | `3.5`, `8`, `14` | How far from the head each counts, blocks (a big blast farther, up to three times) |
| `suppressionRecovery` | `5` | Seconds from full suppression to none |
| `suppressionDarken`, `suppressionMuffle` | `1` | The most darkening (40 % at the edges) and muffling (the world's sounds to 40 %) players get |
| `suppressionFlinch`, `suppressionFlinchDeg` | `false`, `0.22` | A jolt of the view at each event, 0.11 to 0.66 degrees at the default |
| `shock` | `true` | A shock shell's hit shocks (`false`: it only does its damage) |
| `shockSeconds`, `shockImmunity` | `2`, `3` | How long a shock lasts at full (it then wears off over 3 more seconds), and the seconds after it when another shell does not shock |
| `shockSpread` | `0.12` | The spread added to a shocked one's shots (NTM's units: buckshot's is 0.035; about 7 degrees) |
| `shockGlitch`, `shockGlitchMin` | `1`, `0.5` | The most the HUD glitches, and the least share of it a player may turn their own down to |
| `shockCamera`, `shockShake` | `45`, `0.6` | How fast the view drifts on its own at most (degrees a second), and how much it shakes (degrees) |
| `gunHitKnockback`, `gunHitTilt` | `false` | `true`: a player hit by an NTM gun's bullet or beam is thrown back as in vanilla; their view tilts with the hit |
| `bulletBreakConcrete` | `false` | Bullets wear and break concrete |
| `bulletPierceMachines` | `false` | Bullets pierce machines |
| `bulletMachineGlass` | `true` | Machine windows are glass |
| `waterBallistics` | `true` | Bullets slow down and skip in water |
| `waterFuze` | `true` | Explosives meet water by their fuze |
| `npcShots` | `true` | The old bullet of the hunter chopper and the cyber crab hits by the mod's rules |
| `blastParts` | `true` | NTM's blasts hit each body part by its own distance and line of sight, head and limb multipliers after the armour |
| `blastPartsEven` | `true` | A blast's part multipliers divided by the creature's mean, so a creature in the open takes about NTM's damage |
| `blastPartRays` | `3` | Rays from each part for a blast's line of sight, 1 to 9 |
| `damageCap` | `true` | The damage cap against players: one click and any 14 ticks of one shooter |
| `damageCapClick`, `damageCapHead` | `0.6`, `0.8` | The most of a player's max health one click takes, on the body and on the head |
| `damageCapWindow`, `damageCapWindowTicks` | `0.8`, `14` | The most one shooter takes in any so many ticks |
| `damageCapSniperDelay`, `damageCapSniperHead` | `25`, `1.0` | A gun that waits this many ticks between shots takes up to this much with a head click (not a pellet of buckshot or stone shot) |
| `damageCapMobs` | `false` | Mobs and AI bots are capped too |
| `damageCapBlasts` | `true` | NTM's blasts are capped (not those over 60 raw damage: a mine, C4); off: none of them is |
| `damageCapAnyBlast` | `true` | Any NTM blast's hit counts in its click, whatever its class (the Tesla Cannon's electric blast); false: as X865 |
| `gunMechanismRecipes` | `true` | Guns take the gun mechanism of their tier, on eight steps: the mod adds steel, high-speed steel, desh, ferrouranium and technetium steel mechanisms, cast in NTM's mechanism mold; off: NTM's recipes (after `/reload` or a restart) |
| `spectatorNoGuns` | `true` | Spectators cannot use NTM's guns |
| `smoke` | `true` | Smoke clouds |
| `smokeBlocksMobSight` | `false` | Mobs lose a target behind thick smoke |
| `smokeHole` | `true` | A blast punches a hole in a smoke cloud |
| `gl54` | `true` | The GL-54 works |
| `gl54.rangeSmoke` | `true` | The GL-54's laser range finder does not measure through thick smoke: the range stays as it was; `false`: it sees through |
| `gasStir` | `true` | Bullets and blasts stir gas clouds |
| `gasPoison` | `true` | Gas that lies for long poisons the ground |
| `gasCoughBlocksAim` | `true` | Aiming is shut while chlorine makes the player cough |
| `aiSkill` | `regular` | How well AI soldiers shoot: `recruit`, `regular`, `veteran` |
| `aiRadius` | `128` | AI soldiers think only this far from a player, 32 to 256 |
| `aiRecoil` | `1` | Recoil of AI soldiers' guns; `0` for none |
| `aiThreads` | `2` | Background threads that work out where AI soldiers can hide, 1 to 8 |
| `aiImitation` | `false` | AI soldiers decide with a model trained on recorded fights |
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
- `/armsai` (operators): bring in, remove and inspect AI soldiers (`spawn`,
  `clear`, `ammo`, `info`, `debug`), give them armour and rounds (`spawn …
  armor … ammo …`, `kit`), record fights (`record on|off`), lay out a
  practice ground (`course`).

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
