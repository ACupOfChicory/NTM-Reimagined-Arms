# Changelog

## X597

- Precise hitboxes on a server: a client can no longer fill the server's memory
  or its cache of model meshes. What one player may add to the cache is bounded
  (1024 meshes, 131072 triangles, a mesh turned away counting too), and so is
  what all players add together (3072 meshes, 1048576 triangles); a mesh over
  the bound, or one whose id is not the hash of its triangles, is dropped
  quietly, without an error in the log each time, and the entity whose pose
  uses it keeps its plain box. An honest client stays far below the bound.
- `/gunsmoke` on a server with smoke off names the server's file,
  `config/ntm-gunfeel-server.properties`.

## X594

- `/gunaim` is gone. With the aim key held, the aim comes back 0.3 seconds
  before a reload or an inspect ends (0.6 before the inspect of An Old Classic
  and the Sacred Dragon), as it did by default; these times are fixed in the
  mod now.
- ⚠ Breaks compatibility: the `<gun>.reaimReload` and `<gun>.reaimInspect` keys
  in `ntm-gunfeel-server.properties`, which `/gunaim` wrote, are no longer
  read.

## X592

- `beamWater` (server, on; with `beamRules`): beams lose damage in water and
  then go out. Every beam and arc `beamRules` covers (the guns', the Tauon and
  Maxwell turrets', the Tesla arcs) keeps exp(-s / `beamWaterLength`) of its
  damage after s blocks of water, and is gone where less than `beamWaterFade`
  is left: it ends there and hits nothing behind. With the defaults (2 and
  0.1), 1 block of water leaves 0.61 of the damage, 3 blocks 0.22, and a beam
  is gone after 4.6 blocks. A falling stream counts 0.15 of its path, as for
  bullets. A turret or a Tesla coil does not see a target its beam would not
  reach through water. Off: beams go through water untouched, as in NTM.
- `laserGlass` (server, on): lasers go through glass of any kind (vanilla
  glass and panes, NTM's glass, wired glass, laminate, machine windows)
  without breaking, wearing or holing it and without losing damage. With
  `beamRules`, these are the beams of the Laser Pistol, Pew Pew, Laser Rifle,
  Morning Glory and Lacunae (the infrared rounds too), the laser grenade, the
  Shredder's plasma beam (its slow drops come out where the beam stops and
  meet glass as bullets do) and the Maxwell turret; and always the flying laser
  shots of NTM's mobs: the masked man's bolt, tracer and orb, BOT Prime's and
  the UFO's bolt and laser. The Tesla Cannon and arcs, Tau, the Tauon turret,
  the N.I.4.N.I. and the Aberrator still meet glass as bullets do. A Maxwell
  without damage upgrades now sees and hits through glass.

## X589

- NTM's power armour (T-51, AJR, AJRO, RPA, NCRPA, BJ, Envsuit, HEV, FAU, DNS)
  now also halves the living spread: what walking, sprinting, jumping and a
  held-down burst add to a shot's spread. Standing still the gun is as
  accurate as without it; sneaking, crawling and aiming lower it as before. The
  chestplate is enough, charged or not. Server key `powerArmorSpread` in
  `ntm-gunfeel-server.properties` (0.5; 1 turns it off). Walking with a G3
  from the hip, for example, now spreads 2.0 degrees instead of 2.6, and a
  held-down burst 0.36 instead of 0.72.

## X587

- Lying down on X (TaCZ's crawl): a press lies down, the next gets up
  (`proneHold = true`: only while the key is held). The player takes vanilla's
  crawling pose, as under a trapdoor: a box 0.6 high, a third of the walking
  speed, the model lying, and bullets meet that model. Only on the ground; a
  jump, water, a ride or gliding gets the player up. With an NTM gun or
  without one. The key is in Controls ("NTM Reimagined Arms"); NTM keeps C for
  its jetpack and Z for its magnet. X also loads a creative hotbar with a
  number key: pressed that way it lies down too, as TaCZ's C does with its
  save.
- Lying, the spread is half the standing one (`spreadCrawl` 0.55 → 0.5, as
  TaCZ's lie against its stand), and crawling along adds no walking spread;
  this also holds crawling under a trapdoor.
- Lying, all recoil is halved (`proneRecoil`, client, 0.5 as TaCZ's crawl;
  1 = off): the camera, the gun model and NTM's recoil animation. With power
  armor both count; on a server with `cameraScaleMin`, together no lower than
  half.
- Lying, the view turns up to 45 degrees and down to 30 (`proneMaxUp`,
  `proneMaxDown`, server). Server keys `prone` and `proneWithoutGun` (both
  on) turn lying down off, or allow it only with a gun in hand.

## X584

- `beamRules`: a beam (a gun's, a turret's or a Tesla arc) stopped after
  meeting blocks 64 times no longer wears the blocks bullets fly through
  ("through") that lie past where it stopped. This came with X582, and only
  where the server's rules have bullets wear such blocks (the built-in rules
  wear none of them).

## X583

- The server now turns a kept pose of NTM's hunter chopper and plastic bag by
  the heading they were drawn at, not by their body. The game does not send a
  mob's body to the server, and a mob that stands keeps it up to 50 degrees
  off its heading. Since X478, the model of such a mob was then turned by that
  gap as well, and a standing chopper's ended up about 2 blocks off where it
  was drawn.

## X582

- `beamRules`: a beam that meets blocks more than 64 times on its way
  (pierced, broken, or a turret's own; a fence's post and rail count twice)
  now stops where it met the last of them. Before, it flew on through
  everything past them as if nothing were there, and a turret could see and
  hit a target behind them.

## X581

- `beamRules` (server, on) now also covers the Tauon and Maxwell turrets and
  the Tesla arcs (the Tesla coil, the Tesla and taint crabs, the Tesla armor).
  They see a target, and hit it, where their beam gets through blocks as a
  bullet of the same damage would: through fence gaps, through glass they can
  pierce. The target gets what is left of the damage, and blocks on the way
  wear and break by the bullet rules. A turret's shot counts where it meets the
  model (head x1.75, limbs x0.5). A Maxwell without damage upgrades is too weak
  to pierce anything. Before, any block with a box hid a target from them, and
  from the arcs even grass, flowers and torches.

## X580

- `beamRules` (server, on): NTM's beams (laser guns, Tesla Cannon, Lacunae,
  Tau, N.I.4.N.I., Aberrator, shredder shells, the laser grenade) now go
  through blocks where a bullet of the same damage would: through fence gaps
  and the empty cells of machines, piercing glass and thin wood with the same
  loss, and wearing and breaking what bullets wear and break. A beam stops on a
  block's shape, not its box, and is drawn up to where it stops. A creature
  behind a pierced block takes what is left, and blocks behind the creature a
  beam hits are left alone. Beams leave no holes and still go through water. A
  beam with an effect of its own (the Tesla Cannon's blast, the fire of the
  infrared and the Aberrator's black round, the shredder's burst) pierces
  nothing and goes off on the first block, as an explosive round. Folly's beam
  and Tau's charged shot go through blocks as before. Before, the first block
  with a box stopped every other beam.

## X577

- Dedicated servers: rounds now meet NTM's big machines by their shape as
  drawn, as in single player. Before, the server never read the machine shapes
  shipped in the jar (an error about `ClientLevel` in its log) and met the
  machines by NTM's boxes. A server with an NTM version the mod is not tested
  with no longer fails to start on the warning about it, and other warnings
  of the mod's no longer throw that error on a server.
- `npcShots`: the hunter chopper's and the cyber crab's old bullet that hits
  a creature still recovering from a hit now counts the head as more than the
  body and a limb as less, as on a fresh creature. Before, in a burst a head
  hit could do less than a body hit, and a limb hit more.
- `npcShots`: when the head and limb zones fail on the old bullet's hit, it
  hits as in NTM, with a warning in the log once. Before, the error could
  stop the server.

## X572

- A round that meets water on the edge of its surface and a side now also
  comes in through the surface when the rounding of its flight puts the side a
  hair after the surface. Before, such a round still came in through the side.
- `armArmor`: the three failures that leave an armour out of the arm are now
  counted in frames. A frame counts once, whether the armour failed on one arm
  or on both, and a frame in which it does not fail starts the count anew.
  Before, it was left out after one and a half frames, and a draw on the
  other arm in the same frame kept a broken armour in. Leaving the world while
  an armour failed no longer carries its count into the next world.

## X570

- `armArmor`: armour from another mod that fails while it is drawn on the arm
  no longer moves the armour drawn after it, and no longer crashes the game
  when it undoes more of its placing than it did. After three failures in a
  row with the same armour on, the arm leaves that armour out until you leave
  the world, with a warning in the log. An armour model that fails half drawn
  no longer leaves a part of itself on the arm; armour on the arms turns off
  after it, as before.
- A round that meets water exactly on the edge of its surface and a side now
  comes in through the surface: a round at a shallow angle skips off, and a
  fuze takes it for a hit on the water. Before, it came in through the side, as if it had
  hit a wall of water.

## X566

- A grenade or rocket that water stopped is now seen as it sinks and lies on
  the bottom, pointing the way it flew. Before, the client often did not draw
  it there at all, and when it did, one that flew east or west lay nose back.
- An error with the rounds seen in water or on the bottom is written to the
  log once and turns them off until the world or dimension is left. Before, it
  could crash the game.
- A grenade, rocket or mortar shell fired under water now leaves a gas bubble
  at the muzzle, as a bullet does. Before, it had no bubble and, as under water
  the muzzle flash is hidden, nothing showed the shot.

## X560

- An incendiary or phosphorus rocket that hits a creature in water in its
  first three ticks spares it, as NTM's rocket does in the air. Before, it
  went off there; only the grenade spares just its shooter.
- A phosphorus pellet or another round that does not set alight under water
  now hits a creature in water as a plain round of its kind does, with the
  damage water takes off. Before, it skipped that and hit as hard as in the
  air. b75_inc's small blast in water now goes through the same steps.
- An explosive round that goes off on the water surface no longer sets off
  another one sinking under the line it came down along. Before, its path was
  counted on under the surface, where the game still moves it in the tick it
  goes off.
- A rocket, grenade or explosive round whose tick ends exactly on the water
  surface goes off there in the next tick. Before, it sank as if it had
  started under water: a rocket fired 30° down that met the surface after
  exactly four blocks did this.
- Water now counts more of NTM's explosives as going off on impact: the
  turret's shells, the Daybreaker's flame, the cluster submunition, and the
  masked man's and the UFO's rockets and bolts. Before, water took them for
  plain rounds. The flame of a flamethrower, the Daybreaker's too, lays no
  lingering fire in water.
- The 5.56 mm incendiary rounds no longer set a creature alight in water, as
  the other incendiary rounds already did not.

## X555

- On a client, a tracer slowed by water stops where the server's bullet
  stopped also when the stop comes late. Before, it ran on past the bullet to
  where the client had flown it by then.
- A tracer that water stops or skips right at the start of a tick ends there,
  instead of flying on through the water.
- A tracer also stops where the server's bullet hit a creature when the stop
  comes before the client begins the part of the tracer that water or a
  ricochet goes on in. Before, that part flew on through the creature.
- A tracer that ricochets off a block in water flies on at the speed and
  damage the bullet has where it bounced, as the server's bullet does, not at
  those it had when that tick began.

## X548

- Leaving a world for the main menu no longer keeps that world in memory until
  the next one is joined: `armArmor` held on to the player of the last world.

## X547

- The hunter chopper's and the cyber crab's bullets now hit where they meet a
  creature's model, as gun bullets do: 1.75 times the damage in the head, half
  in an arm or a leg. Before they always struck the middle of the body.
- The hunter chopper's bullets now follow the mod's block rules: they break
  glass and fly on through it, and leave a hole with sparks and sound where
  they stop in a block. The new server setting `npcShots` (on by default) turns
  both changes off.
- The Jeremy turret's 240 mm shells now leave holes where they hit a block. NTM
  draws them as a model, not as a tracer, so the mod did not follow their path.
- Checked: the bullets of NTM's turrets (Sentry, Chekhov, Friendly, Jeremy) and
  of its armed mobs (the masked man's minigun, the taint crab, any mob holding
  an NTM gun) hit heads and limbs, go through glass and iron bars and leave
  holes exactly as the same round fired by a player.

## X544

- An explosive round that water stopped goes off where it sinks to only if it
  gets there within NTM's life of the round. When that ends first, the round is
  gone without a blast, as NTM removes any round in the air: most rifle and
  shotgun HE rounds live 1.5 seconds and sink about 1.3 blocks in that time,
  grenades for the 40 mm launchers live 10 seconds. The X493 notes said such a
  round went off on NTM's timer; NTM has no timed fuze for bullets.
- Explosive pellets fired straight down while standing in water, which water
  stops inside the shooter, no longer hit the shooter from inside and vanish.
  With the feet on the bottom they sink and go off on it; in deeper water they
  are gone without a blast when NTM's life of the round ends.

## X543

- Rounds seen in water are now sized to the casings NTM drops, not six times
  their real size: a bullet is as big next to its calibre's casing as the real
  bullet next to its case. A .50 BMG bullet went from 0.35 to 0.08 of a block
  (its casing is 0.135), a 5.56 mm one from 0.14 to 0.04, a 7.62 mm one from
  0.17 to 0.05, a 9 mm one from 0.09 to 0.05, buckshot from 0.05 to 0.014. The
  size is read from NTM's casing of each round. Rounds NTM drops no casing for
  (.357 Magnum, stones) are twice their real size.
- New setting `waterBodyScale` (1 by default, 0.25 to 4): the size of the
  rounds seen in water, on top of the above.

## X541

- A client and a server now need the very same build of the mod to connect.

## X539

- Smoke clouds: the operator command `/gunsmoke` lets out a dense cloud that
  fills the open space around it, about a 6-block half sphere in the open and
  farther along a corridor. It stays out of walls, shut doors and water, goes
  in through a door that opens, stays dense for about 22 seconds and is gone
  about 7 seconds after it starts to thin. The server and each client run the
  same simulation, so a cloud costs little traffic, and a player who comes
  later sees it as it is. A smoke grenade that lets one out will come later.
- Clouds are drawn as a volume that walls and mobs cut and the sky's clouds do
  not show through; inside one the screen goes grey. Client keys in
  `ntm-gunfeel.properties`: `smokeQuality` (`off`, `slices`, `low`, `medium`,
  `high` by default, `ultra`), `smokeMaxClouds` (8) and `smokeDistance` (128
  blocks). With an Iris shader pack the clouds are drawn as translucent slices.
- Server keys: `smoke` (on) lets the clouds out; `smokeBlocksMobSight` (off)
  makes mobs lose a target behind thick smoke.
- `/gunsmoke` (operators) lets out a cloud where you look or a row of them,
  clears them and shows how many there are and what they cost the server.
- ⚠ Breaks compatibility: the network protocol is now 10 (smoke packets);
  client and server need the same build.

## X517

- Water rings are white again in daylight, not grey. They were shaded like a
  mob's skin, and from above both faces of a ring got only the dim side light;
  now they are lit like particles, by the world's light alone. At noon a ring
  from above went from 103 to about 200 of 255.
- A round lying on the bottom of the water falls when the block under it is
  broken. Before, a round that had come to rest stayed hanging in the water.
- The big water particles of a shot no longer blink in their first ticks. The
  water dust of a splash showed at full strength until the first game tick
  and then dropped to a fifth of it; it now fades in smoothly. The gas bubble
  of a shot under water now moves out from the muzzle as it swells, instead of
  swelling in place for a tick and then catching up.

## X515

- Long NTM guns now bob as you walk and take the sprint pose: the
  Panzerschreck, the FIM-92 Stinger, the Tesla Cannon, the Folly and the Zebra
  Rifle were held still, as the mod took their size for a measuring error.
- Explosive pellets of one shot (12 and 10 gauge HE) no longer set each other
  off, in the air or in water. In the air bullets pass each other as in NTM:
  an explosive goes off at the touch of another bullet only when both have
  been through water on their way or sink in it.

## X510

- The arms holding a gun now wear what the player wears on the arms seen from
  outside: vanilla armour with its dye, trim and enchantment glint, NTM's
  armour (its 3D suits included), the skin's sleeve layer, and what other mods
  draw on the player's arms. It follows the arm's pose, thickness and cut, on
  pistols and rifles, at the hip and aiming. Key `armArmor` in
  `ntm-gunfeel.properties` (on by default).
- NTM's power armour (T-51, AJR, AJRO, RPA, NCRPA, BJ, Envsuit, HEV, FAU, DNS)
  halves recoil: the camera kick, the gun model's kick and NTM's own recoil
  animation. The chestplate is enough, charged or not. Key `powerArmorRecoil`
  (0.5; 1 turns it off). On a server that sets a camera recoil minimum the
  camera keeps at least half.

## X504

- F3+B: the hitbox outline of an animated mob no longer lags behind its model.
  It now shows the pose of this frame, of the last frame, or one younger than
  1/60 s. Before, a mob whose outline takes long to build (glyphids, mask man,
  tesla crab and other NTM mobs) could show a pose several frames old while
  it walked. The outline is still built in the background; a frame waits for
  it only when the one on screen would be too old. So F3+B with walking NTM
  mobs is now more expensive above 60 FPS: 10 or 30 of them at a 144 FPS cap
  went from 137 to about 105 FPS. At 60 FPS, and without F3+B, nothing
  changes.

## X498

- Bullets meeting water now splash the way it moves: a crown and spray on still
  water, a fan downstream on a slope, a burst through a waterfall, a low spray
  on a thin sheet. Rings lie on the surface and drift with the current, foam
  and bubbles rise and pop at the surface, and a skipping round splashes at
  each bounce. Nothing sinks under the surface or hangs over air, and a ring
  goes away when its water does. Off with `waterSplash = false`.
- Under water a shot has no muzzle flash. It throws a gas bubble at the muzzle
  that swells, shrinks, bounces and breaks into rising bubbles, its size set by
  the calibre. The round itself is visible in the water with a fading light
  trail, and hollow and soft points open into a mushroom as they slow.
- A round the water stopped sinks, tumbles and lies on the bottom for a while
  (on your screen only; a round that hit a block is gone, as before). Keys:
  `waterBodyMax = 400` (0 = none), `waterBodyLife = 30` (seconds).
- A blast at water shows it: on the surface a column of water with spray, rings
  and foam; under it a burst of bubbles that pushes sunk rounds aside.

## X493

- Works with Punchy! and Punchy! Moves: Punchy! no longer animates NTM guns in
  first person, and in third person Punchy! Moves keeps the arm pose NTM gives
  a gun (aiming, akimbo, left hand). Nothing changes without those mods or with
  `arms` off. Thanks to irislgtm (#2).
- Explosive NTM rounds, grenades and rockets meet water by their fuze. Hitting
  it steeper than 10° they go off on the surface; shallower they skip and go
  off at the next thing they touch. Fired under water they fly, slow down like
  any bullet and go off on a block or a mob. One that water stopped sinks at
  most a block a second and goes off where it lands, if NTM's life of the round
  lasts that long (see X544).
- In water an explosive round goes off at the first touch of anything, another
  bullet or a sinking explosive included; only pellets of one shot leave each
  other alone. In the air bullets do not touch each other.
- A blast with its centre in water reaches 1.5 times as far for mobs in the
  water, and a mob above it takes half. Blocks break as before.
- Under water an incendiary 75 bolt gives a weak blast, about a quarter of the
  explosive bolt's, and no fire; phosphorus and flares do not burn, and fire
  grenades and rockets only explode.
- Fast bullets (rifles, machine guns) now really slow down in water as they
  fly; before, they kept full speed up to the point where they stopped.
- Keys in `ntm-gunfeel-server.properties`: `waterFuze`, `waterFuzeSkipAngle`,
  `waterBlastRadius`.
- ⚠ Breaks compatibility: the network protocol is now 9 (a new packet for
  blasts at water); client and server need the same build.

## X488

- NTM bullets slow down in water, and their damage and piercing fall with the
  square of the speed; a hole in a block behind water comes out smaller. A
  pistol bullet goes through about 2.4 blocks of water, a rifle bullet 1.2, an
  armour-piercing one 1.7, a shotgun slug 2.7, buckshot 0.6, and 10 gauge DU,
  ten uranium mini-bullets, 1.3. Hollow and soft points open in water and stop
  sooner: a 9 mm JHP after 1.3 blocks, an SP after 1.8. A falling stream barely
  slows a bullet; lava and a flamethrower's fire stop where they meet.
- At a shallow angle (under 6°, 10° for grenades and rockets) a bullet skips
  off water, at most twice a flight, losing a third of its speed. A shot from
  under water follows the same rules and sounds duller and lower.
- Keys in `ntm-gunfeel-server.properties`: `waterBallistics`,
  `waterStopFraction`, `waterRangeScale`, `waterSkipAngle`, `waterMuffle`.

## X483

- A bullet mark on glass with no see-through pixels (NTM reinforced laminate
  and its pane, stained and tinted glass, their panes) is now visible from the
  other side too, through the glass, for as long as it lasts. Before, with
  holeCache on (the default), it showed from the back only during the last 3
  seconds while it fades. From the front, the translucent edge of such a mark
  no longer goes twice as dark (it did with holeCache off and, with it on, in
  the last 3 seconds).
- New setting ntmRenderFixes (on): on NTM Decentralized Edition, reinforced
  laminate and its pane, block and item, are drawn translucent, like their
  texture. NTM DE itself draws them opaque. Takes effect after a resource
  reload (F3+T); changes nothing on NTM for 26.2.

## X479

- A player in spectator mode no longer fires NTM guns or hurts mobs: the server
  does not accept gun keys from them (fire, aim, reload) or ammo choice, and
  takes the gun away as if it were removed from the hand: a reload, a burst or
  a power armour strike begun before the mode change does not go through, and
  held keys are released. Back in a normal mode, the player takes the gun out
  again. The client does not send their key presses, and they have no gun HUD:
  no NTM ammo counter (or the mod's corner panel), no NTM crosshair, no scope,
  no ammo wheel. This is a bug in NTM itself: it lets a spectator fire even
  without the mod. To go back to NTM's behaviour: `spectatorNoGuns = false` in
  `ntm-gunfeel-server.properties`.

## X478

- The server turns the outdated pose of the Hunter Chopper and the Plastic Bag
  by their heading (yRot), as NTM draws them, not by the body. The chopper,
  aiming while standing still, turns its heading while the body stays put; the
  bag's body follows the heading only in water. A turret bullet or a check
  without a shooter looked for them where they were before the turn.

## X477

- On pistols ("pistol at the hip" on) the right arm on screen is now the same
  size as the left, at the hip and in the aim: the arm's thickness takes into
  account not only how deep the hand is, but also which side of the arm faces
  the eye. In X472 the right arm at the hip still came out at 0.86–0.92 of the
  left.

## X475

- The server turns a mob's outdated pose the way NTM's renderer turns the mob:
  the Balls-O-Tron (head and segments) and the Plastic Bag the opposite way, by
  their own turn, the FBI Drone not at all, the Martian Invasion Ship together
  with its spin. Before, they were turned by the body, like vanilla mobs, and a
  turret bullet or a check without a shooter looked for the model where it was
  not. Glyphids, Mask Man, the Hunter Chopper, crabs and vanilla mobs are
  turned as before.
- The Tesla Crab and the Taint Crab do not lose their model while they shoot
  lightning: the discharge arcs (and the beams of the Martian Invasion Ship and
  the Meltdown Elemental) are no longer counted as part of the mob. Before,
  with the arcs the pose came out too big and was thrown out whole, and the
  crab was hit only by its overall box.
- Without Flywheel, the Cyber Crab's head (its shell) follows the shell's
  cubes, as with Flywheel: the legs under the shell are no longer counted as
  the head (x1.75). The X471 fix did not reach it: the shell cubes of NTM's old
  model are numbered in their own way.

## X472

- On pistols ("pistol at the hip" on) both arms are now the same size on
  screen, at the hip and in the aim, on any pistol and with any grip: the
  farther the hand is from the eye, the thicker the arm. Before, the right
  arm came out at 0.6–0.7 of the left, on long pistols (Target Pistol, LAG,
  pepperbox, laser pistols) both arms were one and a half times smaller than
  on revolvers, and in the aim the arms shrank even more. Now on all pistols
  the arms are what the right arm was on the Heavy Revolver at the hip; on
  revolvers the left arm will get smaller. Turn it off with the "Pistol arms
  by depth" switch on the "Both arms" page of the arm editor (key
  `armPistolScale`, per gun). While the hand's depth sets the thickness,
  "Thickness limit" is grey, and "Thickness base" is grey when "Arm length"
  is 0. A pistol with an NTM scope is held in its own way, and the rule does
  not apply to it. Long guns are not affected.
- New slider "Aim thickness" on the "Arm" page of the arm editor (keys
  `armAimScaleRight`, `armAimScaleLeft`, per gun): each arm's own thickness
  when the gun is aimed; between the hip and the aim the thickness changes
  smoothly. 0 is the same as "Thickness", which is the default.

## X471

- Without Flywheel, a head made of several cubes no longer takes in what lies
  between them: the top of the back and chest under the Zombie Villager's
  skull, the body next to the head of the panda, polar bear, cow, mooshroom and
  copper golem, the spikes of the guardian and elder guardian, the nautilus's
  jaws. Before, a shot there counted as a head shot (x1.75). The same for
  limbs: the body of the bogged, stray, vindicator, witch, creaking, villager
  and wandering trader between the arm cubes and leg cubes is no longer counted
  as a limb (x0.5). With Flywheel, the head and limbs already followed the
  cubes themselves.

## X469

- Without Flywheel, the head and limbs are counted for glyphids (all nine
  kinds), Mask Man (the head, and the skull when the mask has come off) and the
  Cyber Crab (the head is the shell) too. Before, without Flywheel their layout
  was thrown out: the head was counted by NTM's old height zone, and there were
  no limbs. The same for a glyphid or Mask Man made bigger or smaller by the
  `scale` attribute.
- A glyphid whose arm armour plate has been knocked off again takes half damage
  on limbs (with and without Flywheel). Before, once the arm's first plate was
  knocked off, the layout of its limbs was thrown out, and a shot at a leg or
  arm hit like a shot at the body.

## X467

- Without Flywheel, the head and limbs are counted for the wandering trader
  (the head together with the hat; before, a shot at its head counted as an arm
  hit, half damage), the piglin brute (limbs), the elder guardian (spikes and
  tail), the breeze (head), the creaking (head and limbs), and for NTM mobs the
  Golden, Nuclear, Tainted, Volatile and Phosgene Creepers, Quackos The Elder
  One, the Duck, the Parasitic Maggot (head and limbs, where they exist) and
  the Meltdown Elemental (head) too. Before, without Flywheel their layout was
  thrown out: the head was counted by NTM's old height zone, and there were no
  limbs.
- Without Flywheel, a goat's ears are also head and a zombified piglin's
  trousers are also legs, as with Flywheel.

## X464

- Without Flywheel, the head and limbs are counted for mobs with a layer over
  the model or with eyes too: the head for the drowned, stray, bogged, spider,
  cave spider, warden and enderman, the limbs for the same ones (except the
  cave spider), the villager, zombie villager and zombified piglin. Before,
  without Flywheel their layout was thrown out: the head was counted by NTM's
  old height zone, and there were no limbs.

## X463

- The head and limbs are counted with Flywheel off too (backend `flywheel:off`
  in CrankShaft's settings or with the command `/flywheel backend`, and also
  when CrankShaft itself gives up instancing on a video card): for the llama,
  carpeted llama, trader llama, hornless goat and vindicator. Before, without
  Flywheel their layout was thrown out: the head was counted by NTM's old
  height zone, and the vindicator had no limbs.
- Without Flywheel, the snow golem's pumpkin is also head, as with Flywheel.
  Without Flywheel, a goat's head is its snout and horns, and the ears are
  body.

## X460

- Arm editor: a slider that changes nothing with the current settings is still
  grey, but now instead of a number it says what it will start working with:
  "Shoulder to: with base or length 0", "Thickness limit: with base 0", "Slide
  to: with arm sliding" and so on. The full reason and the current value are in
  the tooltip.
- Sliders that used to change nothing although they were live are grey too:
  "Arm length" when the "Thickness base" of both arms is 0 (then the length is
  set by "Shoulder to"; with very thick arms the slider stays live); "Shoulder
  to" when the arm runs from the hand to the shoulder forward, not back;
  "Reload turn" and "Reload thickness" when this arm has no per-part path keys
  and no carry.

## X457

- The limbs (x0.5) of the villager, zombie villager and warden no longer
  disappear. Before, in about half the cases their layout was thrown out whole,
  depending on the villager's type (with a hat or without), its profession and
  which spots of the warden glow, and arms and legs counted as body. On the
  desert and swamp villager, whose clothes are shorter, the bottom of the robe
  counts as body, and arms and legs as limbs.

## X456

- The head and limbs are counted when a mob's model does not draw all its parts
  too: a goat without one or both horns, a snow golem without a pumpkin, a
  vindicator, evoker and illusioner when they attack, cast or aim a bow (arms
  not crossed). Before, such a layout was thrown out whole: the head was
  counted by NTM's old height zone, and pillagers lost their limbs (x0.5).
- A carpet on a plain llama's head is now head, as on a trader llama.
- Baby Minecraft and NTM mobs have their own head layout, by their own model
  (x1.75). Before, a baby's head was taken by height, and on babies of the
  donkey, mule, villager, wandering trader, drowned, zombie villager and
  zombified piglin a head hit counted as a limb hit (x0.5). A chick is one cube
  with a face, so it is all head, like a bee.

## X453

- The sliders "from hip ← →", "from hip ↓ ↑" and "from hip far" in the editor
  (O) again move pistols and revolvers at the hip, with `pistolHold` too. The
  value is taken as a share of this gun's default place: at the default the
  pistol stands where it stood, `hipX = 0` puts it in the middle of the screen
  by width, `hipY = 0` by height, a smaller `hipZ` brings it closer. The
  transition to the aim stays smooth, and in the aim itself the sliders do not
  move the pistol: the front sight stays on the crosshair.
- ⚠ Breaks compatibility: `hipX`, `hipY` and `hipZ`, which used to have no
  effect on pistols with `pistolHold`, now move them at the hip, both those
  written for a gun in `config/ntm-gunfeel-guns.properties` and the shared ones
  in `config/ntm-gunfeel.properties`, if they are not the default (0.6, 1.0,
  0.75). To get the measured place back, delete the gun's lines (or reset the
  sliders in the editor) and set the shared keys back to the default.

## X451

- Pistols and revolvers in the aim (`aimHold`, on) stand farther from the eye:
  on screen the pistol is now narrow, from the middle of the screen almost to
  the bottom edge, and does not block the view. The gun moves away only along
  the aiming line, so the front and rear sights stay on the crosshair, and
  bullets go where they went before. The place does not depend on where the
  camera looks. Long guns and guns with a scope stand in the aim as before.
  Turn it off with `aimHold = 0` in `config/ntm-gunfeel.properties`, for one
  gun with `hbm\:gun_x.aimHold = 0` in `config/ntm-gunfeel-guns.properties` or
  `/gunfeel set aimHold 0` with the gun in hand.

## X440

- Pistols and revolvers at the hip (`pistolHold`, on) are now held farther in
  front, a little right of the middle and lower, muzzle forward: on screen the
  pistol is smaller and does not block half the screen. The place is the same
  for all pistols, in shares of their length, so short and long ones stand the
  same. Going into the aim and out of it follows one smooth curve. The place at
  the hip no longer depends on where the camera looks: before, when you turned,
  the pistol slid down the screen a little and, unlike other guns, did not lag
  behind the turn.
- Walk sway and the sprint pose are on by default again, with new curves taken
  from measurements. When walking, the pistol is tilted left and sways in time
  with the steps, a long gun less; in the aim the gun barely sways. When
  sprinting, the barrel is raised and turned left. The cycle counts from the
  first step, and turns go around the middle of the gun. Turn them off for all:
  `walkSway = 0` and `sprintPose = 0` in `config/ntm-gunfeel.properties`; for
  one gun: `hbm\:gun_x.walkSway = 0` and `hbm\:gun_x.sprintPose = 0` in
  `config/ntm-gunfeel-guns.properties` or `/gunfeel set walkSway 0` and
  `/gunfeel set sprintPose 0` with the gun in hand. If the settings file was
  created by X432–X438, it already has `walkSway=0.0` and `sprintPose=0.0`, and
  the sway will stay off: delete those lines or set 1.
- In a sprint flight the gun keeps the sprint pose without swaying, and in a
  hover it stands still. The dip on a jump and on landing is fitted to the
  gun's length.

## X438

- Walk sway and the sprint pose (`walkSway`, `sprintPose`; off by default) no
  longer shake the gun in flight. The sway step now follows only the distance
  covered on the ground, so in creative flight and on an elytra the gun stays
  in place, and the sprint pose is still in a sprint flight. Walking, sprinting
  or standing is chosen once a tick, as in TaCZ; when walking crouched the gun
  does not sway.
- With `walkSway` the gun dips slightly on a jump and on landing and smoothly
  returns. The code for the jump, for choosing walking or sprinting and for its
  smoothing is taken from TaCZ (GPL-3.0), the licence and list of files are in
  `third-party/tacz.txt`. TaCZ assets are not part of the mod.

## X435

- A bullet no longer gets stuck at the joint of two neighbouring iron or copper
  bars. The game does not draw the ends of the bars where the bars meet, but a
  bullet used to hit them anyway: through that place you could hit the
  neighbouring bars, but not what is behind them. Now a bullet passes the joint
  where there is no drawn bar, just as X396 stopped putting holes there.

## X432

- The shot delay after a sprint is now on by default on all guns. Right after a
  sprint the gun does not fire until its time has passed
  (`hbm\:gun_x.sprintFireTime`: 0.08 s for pistols, 0.15 s for rifles, 0.19 s
  for heavy weapons). A shot pressed during that time goes off when the time is
  up, if the button is still held. While a shot is held or the gun is aimed,
  the sprint is cancelled. Turn it off on the server: `sprintFireDelay = false`
  in `ntm-gunfeel-server.properties`; for one gun:
  `hbm\:gun_x.sprintFireDelay = false`.
- The gun sway in time with walking and the sprint pose (X430) are off by
  default for now: the feature is still raw. Without them the gun sways with
  NTM's own bob and the vanilla arm bob, as before X430, with a left main hand
  too. Turn them on: `walkSway = 1` and `sprintPose = 1` in
  `config/ntm-gunfeel.properties`. If this file was created by X430 or X431, it
  already has `walkSway = 1.0` and `sprintPose = 1.0`, and the sway will stay
  on: to turn it off, set 0. Pistol holding (`pistolHold`) is still on.
- ⚠ Breaks compatibility: the client and the server each take the default from
  their own version of the mod. If the server is X432 or newer and the client
  is older, the delay is on the server, but the player's sprint is not
  cancelled: while they sprint, the gun does not fire. If it is the other way
  round, the sprint is cancelled, but there is no delay. Install one version,
  or have the server write `sprintFireDelay` explicitly.

## X431

- Pistols and revolvers are held as in TaCZ (on by default, except akimbo).
  From the hip the pistol stands almost on the aiming line: a little right and
  lower than in the aim, closer to the eye and looking the same way. Before, it
  stood far at the bottom right. It goes into the aim along a smooth curve and
  a little faster than before: 90 % of the way in 0.17 s instead of 0.3 s. Each
  pistol's aim point stays its own, by its front and rear sights. The Little
  Macintosh revolver with a scope is held the old way: it looks through the
  scope in the aim, and there is nothing to bring it to from the hip. With a
  left main hand (a Minecraft setting) the holding is still the old one for
  now.
- Turn it off for all: `pistolHold = 0` in `config/ntm-gunfeel.properties`; for
  one gun: `hbm\:gun_x.pistolHold = 0` in `config/ntm-gunfeel-guns.properties`
  or `/gunfeel set pistolHold 0` with the gun in hand. `hipX`, `hipY` and
  `hipZ` on pistols work only with `pistolHold = 0`, `hipZ = 1` on the laser
  pistol included.

## X430

- The sprint pose no longer takes the gun off the screen: the gun turns around
  its middle, the muzzle rises and goes left, as in TaCZ, and the shift is
  counted by the gun's length, not in pixels of the TaCZ model. Pistols and
  revolvers are held as in TaCZ: turned less and lowered less in a sprint,
  tilted more when walking. Walk sway also goes around the middle of the gun
  and does not move it about the screen.
- Gun sway in time with walking and the sprint pose are now on by default on
  all guns except akimbo. Turn them off for all: `walkSway = 0` and
  `sprintPose = 0` in `config/ntm-gunfeel.properties`; for one gun:
  `hbm\:gun_x.walkSway = 0` and `hbm\:gun_x.sprintPose = 0` in
  `config/ntm-gunfeel-guns.properties` or `/gunfeel set walkSway 0` and
  `/gunfeel set sprintPose 0` with the gun in hand. If the settings file was
  created by X400, it already has `walkSway=0.0` and `sprintPose=0.0`: those
  lines must be deleted or set to 1. With a left main hand (a Minecraft
  setting) the sway and the sprint pose do not apply for now, and the gun sways
  the old way, as in NTM.
- A gun just taken out does not sway until the mod has measured its model
  (usually one frame), and then smoothly enters the sway. Before, in the first
  frame it turned around the middle of the previous gun and could jerk.

## X416

- The "Gun arms" panel takes a third of the screen width, but not less than is
  needed to show all the labels in full. On a wide screen the buttons and
  sliders are wider, and the weapon on the right stays visible. A row of
  buttons that does not have enough width wraps to a second line.
- If on a low screen (for example 1280×720 with GUI scale "Auto") the page's
  settings do not fit, a line "Rows 1–8 of 20" with ▲ ▼ buttons appears at the
  bottom. You can also scroll with the mouse wheel. Before, the lower settings
  went off the edge of the screen.
- In the tooltip the pause key is called Space, as on the keyboard.

## X415

- The number field in "Gun arms" stops the animation and closes without changes
  if the time, animation, arm or page has changed. Before, with an animation
  running, the number could land in the next key.
- "Take at" of a carry does not accept a fractional number, for example 865.5,
  and explains that only whole milliseconds are allowed here. Before, it was
  silently rounded.
- A slider shows a number that is off its step as it is (−66.25°, 3.935 ms),
  not rounded.
- A key's time written relative to a moment is read by the game exactly as it
  was set. If this is impossible from some moment, that moment is not offered,
  and the time stays a number.

## X414

- In "Gun arms" you can type a number: right-click on a slider opens the field,
  Enter accepts, Esc closes. A comma and a point both work. Something that is
  not a number, or a number outside the slider's limits, is not accepted, and
  the field says why.
- The slider sets exactly the number that is written to the settings. Before,
  until the game restarted, it could differ in the last digit (0.35000002
  instead of 0.35). A number with five or more digits after the point (for
  example 0.42999998) is written as it is, not rounded to four digits.
- A path key on the "Approach" view now has the sliders "Approach ← →", "↓ ↑"
  and "along": the approach can be set in any direction, not only one of six
  sides. The sliders are visible when an approach is set.
- When an arm carries several parts in turn (Liberator's rounds), the "Part"
  row and the point sliders refer to the part that moves at the selected
  moment. Before, always to the first.
- A grip is written short (`0`, not `0.0`). If a grip is set by a point, the
  shares of the same arm that the player set earlier are removed: the point
  overrides them anyway.

## X413

- "Take arms from" in "Gun arms" on a weapon whose sawn-off or partial-parts
  view is held with its own grip (Mare's Leg) now gives such a view the grip of
  the weapon taken from too. Before, this view's grip became the standard one.
- "Load" does not accept a file in which a weapon's partial-parts view has
  anything set except the grip: the game does not read that.
- A hide of an arm shorter than a step (for example 5 ms) inserted with "Paste"
  stays the same length and is not stretched to the step.

## X412

- Two grips that repeated G3's grips are removed from G3 Zebra in the mod's
  settings: the Zebra's arms are taken from the G3 anyway, and it is held the
  same way.

## X411

- "Gun arms" has a new tab "File". "Take arms from": another weapon's arms
  (grip, tracks, all arm settings) are copied to this one; this can be undone.
  "Export": this weapon's arms are written to a file in the folder
  `config/ntm-gunfeel-export`, and its text is at once copied to the clipboard,
  to send in a message. "Load": this weapon's arms become what they are in the
  chosen file from that folder, newest files first. Another weapon's file or a
  file with an error is not loaded, and the tooltip says which line the error
  is in.
- On the "Key" tab a key has "Copy" and "Paste" (Ctrl+C, Ctrl+V): a copied key
  is pasted at another moment, to another arm, into another animation or to
  another weapon. Pasting over a key keeps its time.

## X410

- An arm hidden only for a reload from an empty magazine ("Hidden", "Keys: only
  with …" in "Gun arms") is now really hidden in that reload. Before, the game
  did not find such a hide, and the editor edited the general hide instead of
  it.
- "−" and "+" on the "Gun arms" sliders go all the way to the edge, even if it
  is less than a step away: a key whose neighbour is 3 ms away moves those 3
  ms. Before, the button was active but moved nothing.
- The tooltip "Keys: shared / only with …" now says that "shared" removes the
  animation variant's own keys and it plays the plain animation's keys.
## X409

- A new "Gun arms" editor: a "Gun arms" button in the weapon editor. It shows
  the hand movements of an animation and edits them on the "Key" tab. It shows
  only the animations the weapon has, and their lengths. There are buttons for
  play, pause, speed, a 10 ms step and a jump to the neighbouring key. For each
  hand there are tracks "Hidden", "By parts", "Carries", "Releases", "Part
  bypass" and "Correction" with keys. Above them a coloured bar shows what
  drives the hand at each moment, in the same order the game picks the
  movement. Grey marks where another track overrides a track, and under the
  tracks you can see when the weapon's parts move. For a weapon with shared
  hands (Heretic and Sexy, G3 A3 and G3, and others) it says who shares them.
  "Own arms" copies them to this weapon, "Shared arms" brings the shared ones
  back. There is undo and redo (Ctrl+Z, Ctrl+Y). Every button has a tooltip. A
  button that cannot be pressed now is grey, and the tooltip says why. The old
  hand movement editor stays for now (the "Arm movements (old)" button).
- In "Gun arms" you edit the grip and the hand settings while an animation
  plays. The "Grip" tab: where each hand holds the weapon (by a point of the
  model or in fractions), which part it moves with (pump, lever) and its own
  grip for a weapon variant without some parts (sawn-off). The "Arm" tab: the
  direction to the shoulder, rotation, thickness, the thickness base and how
  far the arm goes. The "Both arms" tab: the shared thickness, arm length,
  thickness limit, how far the arms turn with the weapon, wrist slide and the
  transition between animations. Every setting has a slider, "−" and "+"
  buttons and a "↺" that brings back the mod's value. A setting that affects
  nothing now is grey with the reason, for example "Thickness limit" when both
  hands have a thickness base set. Dragging a slider is undone with one "Undo".
  A grip outside the model cannot be converted to fractions and back, because
  the hand would move, and then the button is grey.
- The "Key" tab in "Gun arms" edits the key of the selected track at this
  moment. "+ Key" (Insert) adds a key that repeats the key before it, "− Key"
  (Delete) removes it. You can move the time of each key without going past its
  neighbours; a key written from a named moment stays tied to it. A "By parts"
  key: the part (or the grip), the first or last copy of it, a point on the
  part, the wrist touch and the wrist rotation, and on the "Approach" view the
  approach side, the shoulder shift and the shoulder behind the point.
  "Carries": how far the hand is on the part and where the part is (as in the
  animation, as at a given moment or at its own point). "Hidden": from which
  moment to which. "Part bypass": the angle. "Correction": the wrist shift and
  its three rotations. Entries that were not edited stay written as they were.
- The "Key" tab has the views "Key", "Approach", "Track" and "How it holds".
  "Track" edits the whole track. For "By parts" these are named moments: "+
  Moment" adds one, and the slider moves it together with all the keys written
  from it. A key can be written from a moment or as a number, and it stays in
  place either way. Here too are the visible length of the arm at the eye and
  the smooth transition to the grip. For "Carries": what the hand carries: "+
  Carry", "Carry nothing", a weapon part, an animation part (there can be
  several, the hand takes the one that moves), the moment the hand takes it,
  and a point on it. On a reload it can carry its own. "How it holds" sets the
  side the hand holds the part from, the rotation at a cartridge and an arm
  with no change of angle. For "Releases": the rotation of which part makes the
  hand let go of the weapon, and where it moves to. For "Part bypass": the
  shift of the arm outwards and down, separately on a reload. The "Releases"
  track can now be selected even when the hand releases nothing.
- If a weapon reloads from an empty magazine differently (SPAS-12), "Gun arms"
  has a separate "Reload from empty" animation, and the preview plays exactly
  that one. On the "Track" view, for the tracks "Hidden", "By parts" and
  "Carries", the line "Keys: shared / only with <part>" moves the keys into the
  animation variant where its own part moves, or makes them shared again. This
  is how the SPAS-12 keys `RELOAD/PumpGrip` are edited.
- The "Arm" tab got the arm's rotation and thickness at charging, and for the
  left hand the time until which it holds the magazine during inspect (an "On"
  or "Off" button).
- The tabs "Right", "Left" and "Arms" are gone from the weapon editor:
  everything that was on them is now in "Gun arms". "Recoil" and "Position"
  stay there.
- When a weapon with shared hands is given its own hands, editing any hand
  setting of this weapon is written to the weapon itself, not to the weapon the
  hands were shared with. This covers the grip too: own hands take the grip and
  the grips of weapon variants (sawn-off and others), and editing the grip no
  longer goes to the weapon the hands were shared with. An empty value on such
  a weapon (for example, a removed hand hiding) overrides the value of the
  shared weapon.
- The line "The arm is now driven by" no longer says "the arm follows the
  parts" if the path's part is not drawn in the model right now: the game then
  keeps the hand on the grip.

- The hand movement editor no longer spoils the hand's path when you edit.
  Before, any edit rewrote all the keys of the animation: a time from a named
  moment (`release+300` on Sexy) turned into the number `2710.0`, every key got
  the current wrist rotation, and a key without an approach side got the side
  `0,-1,0`, although the game takes `0,0,-1` for it. Now only the key you
  edited changes, and the others stay as they were written. The game and the
  editor read the gun hands with the same code, so the game shows exactly what
  the editor wrote. If an animation is written twice in a path, the editor
  opens the entry the game plays. Corrections at the same time that were not
  edited all stay. Very small and very large numbers are written without an
  exponent (`0.00001`, not `1.0E-5`). The hands on all guns did not change.
- Two keys for slim arms are removed from the minigun's built-in settings
  (`gun_minigun.slim.armRollLeft` and `gun_minigun-GunDual.slim.leftAt`). The
  mod has not read them since slim and normal arms got shared settings, so
  nothing changes in the game.
- The arm model (normal or slim) is now taken only from the player's skin. The
  `armModel` setting is removed: from the general settings, from the gun
  settings and from the editors (the buttons "Arms: …" and "Model: …"). The
  minigun no longer draws normal arms for a player with a slim skin. Slim arms
  still stand 1 pixel closer to the middle, and the settings of both models are
  shared.
- ⚠ Breaks compatibility: the `armModel` key in the player and gun settings is
  no longer read, and arms always follow the skin.

## X400

- A gun can sway in step with walking and go to the side while sprinting, as in
  TaCZ. The hands move with it, the sway is weaker when aiming, and aiming, a
  shot or an NTM animation quickly takes it out of the sprint pose. It is
  switched on per gun: `hbm\:gun_g3.walkSway = 1` and
  `hbm\:gun_g3.sprintPose = 1` in `config/ntm-gunfeel-guns.properties`, or with
  the command `/gunfeel set walkSway 1` with the gun in hand; the same keys
  without a gun name switch it on for all. Off by default. While the sway is
  on, NTM's own sway and the vanilla swing of the hand do not apply on this
  gun, and the camera sways as before.
- A server can forbid a shot right after sprinting, like `sprint_time` in TaCZ:
  `sprintFireDelay = true` in `ntm-gunfeel-server.properties` (or
  `hbm\:gun_x.sprintFireDelay = true` for one gun). While the player sprints,
  the delay grows up to the gun's time (`hbm\:gun_x.sprintFireTime`, 0.08 s for
  pistols, 0.15 s for rifles, 0.19 s for heavy weapons), and after the sprint
  it falls the same way. A shot pressed in that time is not lost: it fires when
  the delay ends, if the button is still held. With this delay the sprint is
  cancelled while a fire button is held or the gun is aimed. Off by default;
  with the delay off nothing changes, and the sprint is as in NTM.
- Worn items are part of the hitbox. Besides the drawn armour, which was
  already in the hitbox, the parts of the model under it become solid and
  thicker by the armour's thickness: helmet, chestplate and boots by 1 pixel on
  each side, leggings by 0.5. A head under a skull or a block (a pumpkin) grows
  to their drawn size, 9.5 and 10 pixels, and under a dragon head to 14.25; the
  skull and the dragon head stand on the base of the head, as they are drawn.
  The head zone grows with the head. If you change armour, the hitbox stops
  keeping the old one by the next frame. On by default;
  `modelHitboxesWorn = false` in `ntm-gunfeel-server.properties` leaves only
  the drawn armour.

## X398

- The bullet flies where the player looked in the frame of the click. The
  server knew the head's turn only from the last game tick, and NTM sends the
  press at once. So if you turned and fired in the same frame, the bullet went
  in the old direction: SPAS-12 after a 40° flick hit exactly 40° off. A burst
  lagged behind the camera by a tick, and at `/tick rate 1` by a second. Now
  before the press the client sends the turn of that frame, and while the
  trigger is held it sends the turn of every frame. The server takes it instead
  of the turn from the last tick. Switch it off with the key `clickAim=false`.
  The mod's network protocol did not change, the turn packet is an ordinary
  game one.

## X397

- With CrankShaft, a mob that has just appeared (an egg, `/summon`, a chunk
  loading) no longer flies in to its place from far away. Until the mob's first
  tick the game drew it between the world's origin and its real place: vanilla
  corrects this before drawing, but Vanillate from CrankShaft draws the mobs
  itself and skipped the correction. Now the mod makes it when Vanillate takes
  on drawing a new mob. Without CrankShaft/Flywheel nothing changes; when
  Vanillate is fixed on its side, there will be nothing to correct and the mod
  will touch nothing.

## X396

- Holes at the joint of fences no longer hang in the air. A bullet flying along
  a rail put a hole on the end of the rail at the joint of two fences, an end
  the game does not draw: it butts against the neighbour's rail. Such a hole
  hid inside the neighbour and hung in the air when the neighbouring fence was
  broken. Now there are no holes on undrawn faces. The same for stone walls and
  iron bars; glass panes did not have the bug.
- Bars and NTM's chainlink fence no longer get empty pieces of a hole with zero
  area, which drew nothing.
- For development: the joint check `runCheckClient -PfenceSeam`
  (FenceSeamCheck) builds pairs, corners and T-junctions from oak, spruce and
  chainlink fences, walls, panes and bars, and shoots along the rails and
  across the joint. Every point of every hole must lie on a drawn pixel, also
  after the neighbour was removed. The screenshots check that the holes did not
  darken the sky.

## X395

- The mod now writes its own lines to a separate file
  `logs/ntm-gunfeel-debug.log`, always, with no key. Only the mod's warnings
  and errors and one line at startup go to the game's log (the console and
  `latest.log`). The file keeps the last 8 MB and two old ones
  (`ntm-gunfeel-debug.1.log`, `.2.log`). A separate thread writes it, and
  frames do not pay for it. If you meet an error, attach this file to your
  report of it.
- For development: in dev runs (runClient, checks) all the mod's lines also go
  to the console, as before, and the stands read `latest.log`. The line rate
  limits and the `ModelHitLog.count` counters did not change.

## X393

- Cactus and bamboo break from bullets, like pumpkins, and drop as an item.
  Cactus is softer than a pumpkin and holds two bullets, and the bullet gets
  stuck in it. A bamboo stalk is cut by the very first bullet, which flies on,
  and the top of the stalk falls with it. Bullets fly through a bamboo shoot
  and sugar cane as before: there is nothing in them to stop a bullet.

## X390

- Aim on the right button. For guns with a scope (in NTM it is on the middle
  button) the right and middle buttons swapped places: the right now aims, and
  the gun's second action (switching the mode, Tau's charge and others) moved
  to the middle. Guns without a scope (SPAS-12, dual ones) keep the second
  action on the right button. Switch it off with the key
  `aimOnRightButton=false`, also for a single gun.
- The head of Mask Man is found in the second stage too, when NTM draws a skull
  in its place: a shot at the skull counts as a hit on the head.

## X381

- Machine windows by the owner's decision from the glass stand. The transparent
  places of 28 more machines and doors became empty (the bullet flies through),
  among them cargo door, assembly machine, arc furnace, flare stack, fracking
  tower, the Jeremy and Maxwell turrets and the Soyuz tower. For the torus,
  ICF, orbus and the Soyuz support, the empty part is only in some areas, and
  the rest is glass. On hephaestus window 34.1 is now glass and the rest is
  empty; the signs above the assembly factory's heads are now glass. Opaque
  pixels of the textures still stop the bullet.

## X380

- For development: on the glass stand, a right click on the highlight switches
  a machine between glass and empty; the colour changes at once, and the
  decision is written at once to `ntmgunfeel-machine-glass.txt`. A highlight
  with sub-numbers (12.1, 12.2) is switched one area at a time,
  Shift+right-click switches the whole texture, Z undoes the last one. The
  highlight is drawn only for machines in the field of view and is built from
  strips of pixels, so the stand no longer lags.
- The exception list understands a line with an area:
  `texture x0,y0,x1,y1 # reason` makes only the transparent pixels inside the
  rectangle empty (edges included), and the other transparent pixels of the
  texture stay glass.

## X379

- For development: the glass stand (`runGlassStand`). It puts in rows all NTM
  machines that have transparent pixels under the model's triangles, labels
  each with a number from the list and highlights those pixels right on the
  model: light blue where the bullet counts them as glass, red where the
  texture is in the exception list (empty). Groups of pixels of one texture get
  sub-numbers (12.1, 12.2). `-PglassStand=survey` walks through all machines
  and writes a list to `notes/glass-stand/`. It does not touch players or the
  server.

## X378

- Transparent pixels on NTM machine models are now glass, not air: a bullet no
  longer flies straight through the window of a chemical plant, but pierces the
  glass, loses a little damage, sounds and shatters like glass, and the hole
  stays on the window and is visible from both sides. The body behind the
  window stops the bullet as before. Grilles and other real gaps listed in
  `ntmgunfeel-machine-glass.txt` still let bullets through. The server key
  `bulletMachineGlass` is on by default; `false` brings back the old
  see-through windows.
- ⚠ Breaks compatibility: the bullet shapes for machines
  (`config/ntm-gunfeel-bullet-shapes.properties` and what the server sends to
  players) now carry glass faces. Earlier versions of the mod do not read such
  shapes and hit these machines by NTM's hitbox.

## X377

- For port checks: in the hand trace each hand now has three points in the
  camera's space (grip, wrist, elbow), the pose calculation branch and the
  contact point, and a hidden hand is written as a separate line. The mob check
  with the trace writes the hitbox parts and a 17×19 ray mask for each mob.
  Without `-Dntmgunfeel.trace` the game does not change.

## X375

- For port checks: the hand trace scenario (`-Dntmgunfeel.scenario=arms`
  together with `-Dntmgunfeel.trace`) plays a gun on the client frame by frame
  with a fixed step: draw, idle, a turn of the view, aiming, a shot, a burst, a
  reload and an inspect. Two runs give the same trace of hands, sway and
  recoil. Without these properties the game does not change.

## X374

- Recoil now raises the aim for good: after a shot it stays higher, a burst
  takes it up, and you have to move the mouse down. The part that stays comes
  with the camera's kick, and only the rest returns; the ±90° stop works as
  before.
- With automatic weapons the aim also drifts to the side over a burst: several
  shots to one side, then it may turn. The climb and the drift gain strength
  over the first shots of a burst, longest on machine guns.
- Gun settings: `cameraClimb` is the share of the vertical camera kick that
  stays (from 0 to 1); `cameraClimbDeg` is how many degrees stay upward per
  shot; `cameraDriftDeg` is how many degrees to the side; `cameraClimbRamp` is
  over how many shots of a burst they grow to full strength. `cameraClimbDeg`
  is needed by guns whose NTM recoil hits sometimes up and sometimes down: G3,
  AM180, Bolter, M2, miniguns, Sexy and Heretic. All of this is in the gun
  editor in the lines "camera: …"; zeros bring back the old behaviour.
- The climb is set for guns with recoil: for pistols, revolvers and shotguns it
  is small, and for submachine guns, rifles and machine guns it builds up over
  a burst. Energy weapons, flamethrowers and most grenade launchers have no
  climb.

## X373

- The random look of bullet marks (size, rotation, pattern) and the random
  deviations of the model's and camera's recoil are taken from one seed, and
  each shot has its own stream. The distributions are the same. With the same
  seed (`-Dntmgunfeel.seed=<number>`) marks and recoil repeat from run to run;
  without it the seed is new at every launch, as before.
- A block's wear from bullets on the server is forgotten after 400 ticks (20 s
  at 20 TPS), not after 20 s of real time: on pause and with slowed ticks the
  cracks no longer vanish before their time.
- For port checks: `-Dntmgunfeel.trace` writes a trace of the run (events of
  bullets, marks, recoil, hands and sway with exact numbers and game time), and
  `tools/trace_diff.py` compares two traces. Off by default and does not affect
  the game.

## X372

- Exact mob hitboxes load the game less, even without F3+B: the set of model
  parts is no longer walked every frame, and the pose checks hardly allocate
  memory. With 64 mobs the mod takes 0.17 ms of a frame instead of 0.43 ms.
- F3+B: the outlines of all mobs are drawn in one call. The new outline of an
  animated mob is finished in the background, and until it is ready the
  previous one is shown, so the outline of limbs may lag by a frame. With 64
  mobs the FPS with F3+B grew about one and a half times.
- Bullet holes cost less when there are thousands: a hole on a moving part of a
  machine does not look for its position again until the part has moved, and
  baked holes are not walked a second time per frame. With 13 thousand holes
  the frame became about 10% shorter.

## X371

- F3+B loads the game less with a large number of mobs. Model outlines are
  cached, not rebuilt every frame, and are drawn in one pass.
- The outlines of animated mobs are built faster and in parallel; the look of
  the outline did not change.
- The cracks of an iron golem no longer get separate hitbox lines and do not
  add parts to the hit shape.

## X369

- Added a left hand movement for the G3 inspect: it takes the magazine out,
  tosses it, catches it and puts it back in.
- The moment the magazine is let go can be set in the hand movement editor:
  from 350 to 1000 ms, 550 by default. The sounds and the length of the inspect
  follow it.

## X368

- Hits on moving mobs, including to the head and limbs, are counted by the pose
  the shooter saw. You no longer need to lead into empty space in front of a
  fast mob.
- The tracer ends where the server stopped the bullet in the target, and the
  marks past that point are removed.
- ⚠ Breaks compatibility: the network protocol is raised to 8. The client and
  the server must be updated together.

## X366

- Fixed bullets flying through the visible parts of copper and iron bars. For
  narrow blocks with a flat mask the client gets the exact faces and UV of the
  model; explicit path rules keep priority.
- The mod now includes the server shapes of all 288 states of iron bars and
  eight copper variants, including connections and water filling. Hits on the
  slats and flights through the gaps were checked against the real models.

## X365

- Fixed extra marks on neighbouring scaffold posts. A mark is carried across
  connected faces, unfolded around the shared edges; the distance travelled
  over the surface is counted in the mark's size. A separate parallel face no
  longer gets a repeated texture centre.
- The calculation is done when the mark is created. For dense models the search
  for neighbouring faces uses a spatial index; drawing finished marks stays in
  the cache.

## X364

- Fixed the clipping of holes on flat meshes and fences. The fallback path no
  longer picks the back face and does not merge the masks of opposite sides.
  For ordinary textures it uses an exact pixel grid, as in the main path of
  making marks.
- A mark on a cut-out texture is seen from the side of the matching face: the
  different UV of the two sides no longer lay a foreign pattern. Ordinary and
  explosive marks were checked on both sides of NTM's metal fence.

## X362

- Saving of block shapes is moved from the render thread to a background
  thread.
- The capture of machine geometry is spread across frames to reduce freezes.

## X361

- The direct damage of explosive ammo counts the head, body and limbs.
- Rockets and 40 mm grenades use the shared system of blast marks.
- The network protocol is raised to 6: the client and the server are updated
  together.

## X360

- Fixed the change of the side of the weapon's lag at a sharp turn of the
  camera.

## X359

- The weapon's lag when aiming and firing from the hip uses the calculation
  from Superb Warfare. The authorship and the GPL terms are kept in
  `third-party/`.

## X358

- Changing the hand's position, its rotation and its contact keeps the chosen
  preview time.

## X357

- Added a choice of the wrist's edge for contact with a weapon part.

## X356

- Added contact by the middle of the wrist's plane.

## X355

- The hand movement editor uses ready-made actions with position and rotation
  corrections.
