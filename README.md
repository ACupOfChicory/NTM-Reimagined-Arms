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
- Guns' damage by gun and ammo (the balance's table, one line a pair, which a
  server can replace with its own file): each pair's shot does NTM's damage
  times its multiplier. A few guns change: Heretic is a semi-automatic
  shotgun with 24 rounds, AM180 fires slower with 60, the AMAT Penance,
  Subtlety and MAS-36 fire faster, Morning Glory hits twice as hard.

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
  for Lag, the minigun, the missile launcher and Tesla Cannon (saturnite with
  the balance's recipe).
- A B-side takes the step above its base gun's: desh for Atlas, Henry
  Lincoln, the Maresleg pair and Topaz, ferrouranium for the UZI pair.
  Gunmetal and weapon steel mechanisms no longer fit these recipes; the
  server key `gunMechanismRecipes` turns NTM's recipes back on.

### AI soldiers

- NTM's FBI agents and test dummies fight as a player does, with NTM's guns.
  Operators bring them in with `/armsai spawn fbi|dummy [count] [gun]` (and
  `team <name>` at the end); `/armsai clear` removes them. They fight in the
  game's teams.
- They see and hear as a person does, run to cover, crouch behind low walls,
  run low past them, lean out round a corner, fire blind over cover and lie
  down in the open. They aim at what they see of you, never miss on purpose
  and take the gun's recoil.
- Bots of one side fight as a squad: when you vanish behind cover one goes
  round the side while the others fire to keep your head down, and a bot
  leaves cover only while another covers it. They throw grenades, run from
  grenades thrown at them, including a player's, and do not turn their heads
  to the sounds of animals.
- They move their bodies: breathing, flinching at hits, aiming, reloading,
  throwing, signalling with a hand, crouching, leaning and lying with the gun.
  Arms and legs bend at the elbows and knees, and the armour they wear is
  drawn on them.
- Bots can learn from your fights: `/armsai record on|off` (operators) records
  how you fight, and `/armsai course` lays out a ground to fight on
  (`/armsai course clear` removes it). Recording is off by default.
- `/armsai debug` shows what the bots think; `/armsai info` shows how many
  there are and what they cost.

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
  spread, beams, water, lying down, the sprint delay, gas, AI soldiers). They are sent to
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
| `clientHits` | `true` | A player's bullet hits what that player's game saw it hit; off: the server's view decides |
| `cameraScaleMin` | `0` | Camera recoil players cannot go below |
| `spreadStand`, `spreadMove`, `spreadSneak`, `spreadCrawl`, `spreadAim` | `1`, `1.5`, `0.75`, `0.5`, `1` | Spread by stance |
| `powerArmorSpread.<set>` | from `0.8` (T-51b) to `0.4` (FAU, DNS) | Movement and burst spread in power armour and Trenchmaster, as a share, by the chestplate's set |
| `powerArmorSpread` | `0.5` | The same for a power armour chestplate with no line of its own |
| `powerArmorRecoil.<set>` | from `0.8` (T-51b) to `0.4` (FAU, DNS) | Recoil in power armour and Trenchmaster, as a share, by the chestplate's set; the players' clients go by the server's |
| `powerArmorRecoil` | `0.5` | The same for a power armour chestplate with no line of its own |
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
| `blastParts` | `true` | NTM's blasts hit each body part by its own distance and line of sight, head and limb multipliers after the armour |
| `blastPartsEven` | `true` | A blast's part multipliers divided by the creature's mean, so a creature in the open takes about NTM's damage |
| `blastPartRays` | `3` | Rays from each part for a blast's line of sight, 1 to 9 |
| `damageCap` | `true` | The damage cap against players: one click and any 14 ticks of one shooter |
| `damageCapClick`, `damageCapHead` | `0.6`, `0.8` | The most of a player's max health one click takes, on the body and on the head |
| `damageCapWindow`, `damageCapWindowTicks` | `0.8`, `14` | The most one shooter takes in any so many ticks |
| `damageCapSniperDelay`, `damageCapSniperHead` | `25`, `1.0` | A gun that waits this many ticks between shots takes up to this much with a head click (not a pellet of buckshot or stone shot) |
| `damageCapMobs` | `false` | Mobs and AI bots are capped too |
| `damageCapBlasts` | `true` | NTM's blasts are capped (not those over `balanceExplRawLimit` raw damage); off: none of them is |
| `damageCapAnyBlast` | `true` | Any NTM blast's hit counts in its click, whatever its class (the Tesla Cannon's electric blast); false: as X865 |
| `balanceArmor` | `true` | NTM's combat sets take bullets, beams and blasts by the balance's formula; off: NTM's threshold and resistance |
| `balanceS.<set>` | from `8` (steel) to `280` (euphemium) | Each set's rating; `0`: NTM's numbers for that set. Starmetal is a combat set of tier 6 with bismuth's `96` (`balanceS.starmetal` `0`: NTM's joke set, as X875) |
| `balanceTau.<set>` | from `1` (steel) to `8.5` (euphemium) | Each set's tier in the formula: how much of a weak hit still gets through and how much of a strong one is stopped |
| `balanceEnShare` | `0.75` | The share of the rating beams and lasers meet |
| `balanceFleshKnee`, `balanceFleshMax` | `8`, `14` | One hit's damage on a set is squeezed past the first towards the second |
| `balanceArmorBlasts` | `true` | Blasts go by the formula too; off: blasts by NTM's threshold and resistance |
| `balanceExplRawLimit` | `60` | A blast of more raw damage (a mine, C4) is not squeezed and not capped |
| `balanceBlastPower` | `3` | A weapon's blast (grenade, 40 mm, rocket) hits a combat set as this many times its damage, never more than with no armour; 2.7: as X865, 1: as X858 |
| `balanceBlastPowerLow`, `balanceBlastPowerRocketHigh` | `1.7`, `2.2` | The power on sets of tier 4 and below, and of a rocket's direct hit on tier 7 (RPA, CMB, Schrabidium); at most `balanceBlastPower`; all three `2.7`: as X865 |
| `balanceBlastRocketHighFrom` | `30` | `balanceBlastPowerRocketHigh` only for a rocket of more raw damage than this (Missile Launcher, Stinger); Panzerschreck's round hits tier 7 as a 40 mm shell of its damage; `0`: every rocket, as X873 |
| `balanceBlastLowFloor` | `0.9` | A weapon's blast on a set of tier 4 and below passes it as if its power were at least this share of the set's rating: a gunpowder grenade takes 4 hits on tier 4 at 1 block, a DEMO grenade or a 40 mm shell's splash 5 to 7, never fewer than on T-51b; a blast under 7 raw damage (its far edge) gets the floor in its share of 7; `0.83` with `balanceBlastFloorShare` false: as X875; `0`: as X873 |
| `balanceBlastFloorShare` | `true` | That floor lifts only the share of the blast that passes, its flesh stays the blast's own, so no tier 4 set holds a weak blast worse than T-51b; false: the floor's flesh too, as X875 |
| `balanceBlastTrenchTier` | `4` | The tier Trenchmaster takes a weapon's blast at (its power and the floor above): NTM gives it HEV's explosion resistance, so it holds a blast like HEV; `6`: as X877 (3 hits of MK108's or a rocket's direct blast against HEV's 4) |
| `balanceBulletBlastCap` | `true` | An explosive bullet's blast is squeezed and capped at any damage, not taken for a mine past `balanceExplRawLimit`; false: as X865 |
| `balanceIrBeams` | `true` | An infrared beam (IR capacitors) is a beam: the armour formula's and the cap's; false: NTM's fire numbers, as X865 |
| `balanceElectricPowered` | `1.5` | An electric hit (Tesla Cannon) on a powered chestplate counts this many times; 5: NTM's, as X865 |
| `balanceRoflFloor` | `0.18` | The least share of a hit the dineutronium ingot set (and starmetal with `balanceS.starmetal` `0`) leaves |
| `balanceGuns` | `true` | Guns' damage by the balance's table (`config/ntm-gunfeel-balance-guns.tsv` replaces the mod's; the players get the server's); off: NTM's damage and guns |
| `balanceArmorFloor` | `true` | The table's floors (`floor=`, `from=` in a pair's extra column): the armour formula's least share times the floor on sets of that tier and up, so armour-piercing rounds, slugs, explosive and flechette shells beat the same gun's soft rounds against heavy armour; false: no floors, as X873 |
| `balanceRecipes` | `true` | The balance's recipes for the diesel suit, Tesla Cannon, Lasrifle, Tau and Med-X; off: NTM's (after `/reload` or a restart) |
| `gunMechanismRecipes` | `true` | Guns take the gun mechanism of their tier, on eight steps: the mod adds steel, high-speed steel, desh, ferrouranium and technetium steel mechanisms, cast in NTM's mechanism mold; off: NTM's recipes (after `/reload` or a restart) |
| `abilities.mobilityCap` | `true` | A full set's Speed and Jump Boost by its tier: up to tier 6 II and I at most, from tier 7 III and II |
| `abilities.strengthCap` | `2` | A full set's Strength at most this level; `0`: NTM's |
| `abilities.bismuth` | `true` | Bismuth as a tier 6 set: Speed III, Jump Boost II, Regeneration I, night vision, two dashes; off: NTM's |
| `abilities.esp.players` | `tier8` | VATS and the sets' thermal vision show no players, FAU and DNS thermal and AMAT Penance's thermal sight the ones they see within the range; `off`: none; `all`: NTM's |
| `abilities.esp.playerRange` | `16` | How far FAU and DNS thermal vision and Penance's thermal sight show players |
| `balance.shield` | `true` | The player's shield by the keys below; off: NTM's (the mod 25, a flask 5, up to 100 of flasks, back after 60 ticks at 0.005·t) |
| `balance.shield.mod` | `10` | What the shield mod (australium III) gives |
| `balance.shield.flask` | `2` | What a shield flask gives; no more flasks once they alone reach the cap |
| `balance.shield.cap` | `16` | The most shield of flasks and the mod together |
| `balance.shield.delay` | `100` | Ticks after the last hit before the shield comes back |
| `balance.shield.rate` | `0.002` | Then rate·t a tick, t the ticks since |
| `balance.medx` | `true` | Med-X gives Resistance I for 60 s; off: III for 4 min |
| `balance.superStimpak` | `true` | The super stimpak heals its 25 HP over 12 s; off: at once |
| `balance.inserts` | `true` | An armour insert takes at most 1 − `balance.inserts.bullets` off a bullet; off: NTM's |
| `balance.inserts.bullets` | `0.9` | The least share of a bullet an insert leaves |
| `balance.vanilla0` | `true` | The combat sets of `balanceS.*` have no vanilla armour points and toughness (counts when a piece is put on again); off: theirs |
| `balance.fbiArmor` | `10` | With `balance.vanilla0`, NTM's FBI agent's own vanilla armour on top (it wears Security) |
| `balance.immune.mode` | `pool` | Euphemium's invulnerability with a pool; `ntm`: every hit called off |
| `balance.immune.pool` | `150` | What bullets, beams and blasts on the set wear down before it opens (each as much as on a DNS-class set); the owner's choice, `2000`: as X865 |
| `balance.immune.window` | `15` | Seconds the emptied pool leaves the wearer open, without the set's Regeneration and Resistance |
| `balance.immune.hold` | `10` | For euphemium, DNS and FAU: a shot, IR beam or blast in the window (not melee) keeps it open at least this many seconds after it, so a gun that keeps hitting is not sent back to a full pool; `0`: the window is its own length, as X873 |
| `balance.immune.refillPause` | `5` | For euphemium, DNS and FAU: seconds after the last hit on the pool before a worn-down pool starts to come back; with it and `balance.immune.hold` at `11` the Lever Action Rifle and Lincoln's Repeater (reload 9.5 s) take DNS in 140 to 260 s, not 400 to 3500 |
| `balance.immune.recover` | `60` | Seconds the pool takes to fill back after the window (the set invulnerable meanwhile), or a worn-down pool after `balance.immune.refillPause` without a hit |
| `balance.immune.dns.mode` | `pool` | DNS with euphemium's round: bullets, beams and blasts on the full charged set called off while its pool holds; `formula`: by its rating only; `ntm`: every hit but a blast called off, blasts down to 0.001 |
| `balance.immune.dns.pool` | `40` | What hits wear down before DNS opens (each as much as it would do to DNS itself, S 186); the owner's choice, `500`: as X865, `120`: a middle way |
| `balance.immune.dns.window` | `15` | Seconds the emptied pool leaves DNS open to its rating |
| `balance.immune.dns.recover` | `60` | Seconds DNS's pool takes to fill back after the window, or a worn-down pool after `balance.immune.refillPause` without a hit |
| `balance.immune.fau.mode` | `pool` | FAU the same; `formula`: by its rating only (NTM gives FAU no invulnerability) |
| `balance.immune.fau.pool` | `45` | FAU's pool (each hit as much as on FAU itself, S 168); the owner's choice, `450`: as X865, `100`: a middle way |
| `balance.immune.fau.window` | `15` | Seconds the emptied pool leaves FAU open |
| `balance.immune.fau.recover` | `60` | Seconds FAU's pool takes to fill back |
| `spectatorNoGuns` | `true` | Spectators cannot use NTM's guns |
| `smoke` | `true` | Smoke clouds |
| `smokeBlocksMobSight` | `false` | Mobs lose a target behind thick smoke |
| `smokeHole` | `true` | A blast punches a hole in a smoke cloud |
| `gl54` | `true` | The GL-54 works |
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
  `clear`, `ammo`, `info`, `debug`), record fights (`record on|off`), lay out a
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
