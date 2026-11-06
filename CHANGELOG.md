# Changelog

## X555.177

- As in 26.2 X547, on NTM CE: the hunter chopper's and the cyber crab's
  bullets hit where they meet a creature's model, 1.75 times the damage in the
  head and half in an arm or a leg. The chopper's bullets follow the mod's
  block rules: they break glass and fly on through it, and leave a hole with
  sparks and sound where they stop in a block. The cyber crab's still fly
  through blocks, as in NTM. The server setting `npcShots` (on by default)
  turns both off. NTM CE's turrets, the taint crab and its mobs with guns fire
  the same bullets as a player's guns, so they already followed these rules.
- The Jeremy turret's 240 mm shells still leave no holes on 1.12.2: NTM CE
  draws them as grenades, with no tracer to make a hole from. X547's holes from
  those shells do not apply here.
- X544 and X548 were already on 1.12.2: X544's explosive rounds in water came
  with X543.174, and `armArmor` has let the player go on leaving a world since
  X543.173. Of X555, 1.12.2 already had the tracer that ends where water stops
  or skips it right at a tick's start, the stop at a creature before a piece
  of the tracer that water or a ricochet began, and the late stop on the piece
  begun by then. Not yet: when the late stop comes after the tracer's first
  piece in water is gone, its later pieces still fly on; and a tracer that
  ricochets off a block in water bounces at the speed and damage it had when
  that tick began.

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

## X543.176

- As in 26.2 X543: rounds seen in water are sized to the casings NTM CE drops
  for them, not twice their real size: a bullet is as big next to its
  calibre's casing as the real bullet next to its case (a 9 mm one is 0.05 of a
  block long, a 7.62 mm one 0.05, a .50 BMG one 0.08). Rounds with no casing
  (.357 Magnum, stones) stay twice their real size.
- `waterBodyScale` (1 by default, 0.25 to 4) now works on 1.12.2: the size of
  the rounds seen in water, on top of the above.

## X543.175

- As in 26.2 X498 and X517: bullets meeting water splash the way it moves: a
  crown and spray on still water, a fan downstream on a slope, a burst through
  a waterfall, a low spray on a thin sheet. Rings lie on the surface, drift
  with the current and are lit like particles; foam and bubbles rise and pop
  at the surface, and a skipping round splashes at each bounce. Nothing sinks
  under the surface or hangs over air, and a ring goes away when its water
  does. Off with `waterSplash = false`.
- Under water a shot has no muzzle flash. It throws a gas bubble at the muzzle
  that swells, shrinks, bounces and breaks into rising bubbles, its size set by
  the calibre. The round itself is visible in the water with a fading light
  trail, and hollow and soft points open into a mushroom as they slow. Rounds
  seen in water are twice their real size: 1.12.2 does not read NTM CE's
  casings for their size, so `waterBodyScale` does nothing yet.
- A round the water stopped sinks, tumbles and lies on the bottom for a while
  (on your screen only), and falls when the block under it is broken. Keys:
  `waterBodyMax = 400` (0 = none), `waterBodyLife = 30` (seconds).
- A blast at water shows it: on the surface a column of water with spray, rings
  and foam; under it a burst of bubbles that pushes sunk rounds aside.
- On 1.12.2 the splashes come from the tracer of a shot, so grenades and
  rockets, which have none, make no splash of their own; at the surface their
  fuze makes them a blast, and that shows. 1.12.2 has no bubble-pop particle:
  a bubble at the surface swells and fades instead.

## X543.174

- Explosive NTM rounds, grenades and rockets meet water by their fuze. Hitting
  it steeper than 10° they go off on the surface; shallower they skip and go
  off at the next thing they touch. Fired under water they fly, slow down like
  any bullet and go off on a block or a mob. One that water stopped sinks at
  most a block a second and goes off where it lands, if NTM's life of the round
  lasts that long; when that ends first, the round is gone without a blast.
- In water an explosive round goes off at the first touch of another bullet or
  a sinking explosive; pellets of one shot leave each other alone. In the air
  bullets pass each other as in NTM. Explosive pellets fired straight down
  while standing in water, which water stops inside the shooter, do not hit
  the shooter from inside.
- A blast with its centre in water reaches 1.5 times as far for mobs in the
  water, and a mob above it takes half. Blocks break as before.
- Under water phosphorus, incendiary rounds and flares do not burn, and fire
  grenades and rockets only explode. The Bolter's rounds stay plain bullets
  there, as NTM CE has them everywhere.
- Keys in `ntm-gunfeel-server.properties`: `waterFuze`, `waterFuzeSkipAngle`,
  `waterBlastRadius`.

## X543.173

- As in 26.2 X510: the arms holding a gun now wear what the player wears on
  the arms seen from outside: vanilla armour with its dye and enchantment
  glint, NTM's armour (its 3D suits included), the skin's sleeve layer, and
  what other mods draw on the player's arms. It follows the arm's pose,
  thickness and cut, on pistols and rifles, at the hip and aiming. NTM's suits
  hide the sleeves, as they do in third person. Key `armArmor` in
  `ntm-gunfeel.properties` (on by default). 1.12.2 has no armour trims.

## X543.171

- On a client, a tracer also stops where the server's bullet stopped when the
  stop came before the client began the piece water or a ricochet goes on in.

## X543.170

- NTM bullets slow down in water, and their damage and piercing fall with the
  square of the speed; a hole in a block behind water comes out smaller. A
  pistol bullet goes through about 2.4 blocks of water, a rifle bullet 1.2, an
  armour-piercing one 1.7, a shotgun slug 2.7, buckshot 0.6, and 10 gauge DU,
  ten uranium mini-bullets, 1.3. Hollow and soft points open in water and stop
  sooner: a 9 mm JHP after 1.3 blocks, an SP after 1.8. A falling stream barely
  slows a bullet; lava and a flamethrower's fire stop where they meet. The
  tracer slows, stops and skips the same way.
- At a shallow angle (under 6°) a bullet skips off water, at most twice a
  flight, losing a third of its speed. A shot fired with the eyes in water
  sounds duller and lower.
- Keys in `ntm-gunfeel-server.properties`: `waterBallistics`,
  `waterStopFraction`, `waterRangeScale`, `waterSkipAngle`, `waterMuffle`.

## X543.169

- The first-person sleeves on the arms holding a gun now follow Skin
  Customization, as in 26.2 and in vanilla's third person: a sleeve switched
  off there is not drawn. Before both sleeves were always drawn.

## X543.167

- A bullet hole on iron bars and other blocks with holes on both sides, on the
  top or bottom of such a block, now lies on the side the camera's eyes are on,
  not the feet: on the top of bars at waist height it no longer hides under the
  bars' own pixels.

## X543.166

- A bullet hole on see-through blocks drawn with the water and stained glass
  (NTM's reinforced laminate and its pane, ice, such glass from other mods),
  seen from their side, no longer has its half-clear edge darkened twice: it
  was drawn both under the glass and over it, now only over it, as on 26.2.
  Which side the camera is on now counts from the eyes, not the feet, so a hole
  on the top of such a block in the floor is drawn over the glass too. Seen
  through the glass from behind, and on all other blocks, holes look as before.
- Players with a slim skin (the Alex model) now hold the gun with slim arms,
  3 pixels wide, as in 26.2 and in vanilla's third person. They were always
  drawn 4 pixels wide: the port looked for slim arms in Et Futurum, a 1.7.10
  mod; it now reads the skin's own model type.

## X543.163

- As in 26.2 X510: in NTM power armour all recoil is halved: the camera kick,
  the gun model's kick the arms follow and NTM's own recoil animation. Power
  armour is the chestplate of a battery-powered suit: T-51, AJR, AJRO, RPA,
  NCRPA, BJ (with or without the jetpack), Envsuit, HEV, FAU, DNS; not the steam
  and diesel suits. The chestplate is enough, charged or not. Key
  `powerArmorRecoil` (0.5; 1 = off). On a server that sets `cameraScaleMin`,
  the camera kick stays at least half.

## X543.162

- As in 26.2 X515: the walking bob and the sprint pose now work on long guns
  too: the Panzerschreck, the Stinger, the Tesla Cannon, the Folly and the Zebra
  Rifle used to stay still.
- The sections X483–X543 below are 26.2's. On 1.12.2 their settings are already
  in the config files (water, smoke, armour on the arms, power armour recoil)
  but do nothing yet: these parts come to 1.12.2 one by one in the next builds.

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

## X479.161

- A client and a server now need the very same build of the mod to connect.

## X479.158

- As in 26.2 X479: a player in spectator mode no longer fires NTM guns or hurts
  mobs: the server does not take gun keys from them (fire, aim, reload) or an
  ammo choice, and puts their gun away as if it had been taken out of the hand:
  a reload, burst or power armour strike started before the mode change does
  not go through, and held keys are released. Back in a normal mode, the player
  takes the gun out again. The client does not send their key presses, and they
  have no gun HUD: no NTM ammo counter (or the mod's corner panel), no NTM
  crosshair, no ammo wheel. On 1.12.2 the same goes for two old-style guns from
  NTM CE, the vortex gun and the super shotgun with a hook: their mouse buttons
  do nothing for a spectator. This is a bug in NTM CE itself: it lets a
  spectator fire without the mod too. To get NTM's behaviour back:
  `spectatorNoGuns = false` in `ntm-gunfeel-server.properties`.

## X477.156

- As in 26.2 X477: with `pistolHold`, a pistol's right and left arms are the
  same size on screen both at the hip and in aim: an arm's thickness now takes
  into account not only how deep the hand is but also which side of the arm
  faces the eye. Before, on 1.12.2 the right arm came out 9–12 % larger than
  the left in aim. Turn it off as before: `armPistolScale = false`.

## X472.154

- On 1.12.2, the same as 26.2 X438–X440: walk sway and the sprint pose are on
  by default again (`walkSway = 1`, `sprintPose = 1`), with new curves taken
  from measurements. The gun turns about its middle, and the offsets are
  counted by its length, so in the sprint pose the gun stays on screen.
  Walking, sprinting or standing still is chosen once a tick, as in TaCZ: in
  creative flight and while sneaking the gun does not sway, and flying while
  sprinting it holds the sprint pose without swaying. On a jump and a landing
  the gun dips slightly and comes back smoothly. The code for the jump, for
  choosing between walking and sprinting and for its smoothing is taken from
  TaCZ (GPL-3.0); the licence and the list of files are in
  `third-party/tacz.txt`; TaCZ's assets are not in the mod. If the settings
  file was created by X432.137–X432.139, it already has `walkSway=0.0` and
  `sprintPose=0.0`, and the sway stays off: delete those lines from
  `config/ntm-gunfeel.properties` or set them to 1. With the left hand as the
  main hand the gun sways the old way, as in NTM, just as in 26.2.
- Pistols and revolvers at the hip (`pistolHold`, on) are held further forward,
  a little right of the middle and lower, the barrel pointing ahead almost
  along the line of aim: on screen the pistol is smaller and does not cover
  half the screen. The place is one for all pistols, in fractions of their
  length. Going into aim and out of it follows one smooth curve.
- Pistols and revolvers in aim (`aimHold`, on) sit further from the eye: the
  gun moves back only along the line of aim, so the front and rear sights stay
  on the crosshair, and bullets go where they went before. Long guns and guns
  with scopes sit in aim as before. Turn it off: `aimHold = 0` in
  `config/ntm-gunfeel.properties`; for one gun, `hbm\:gun_x.aimHold = 0` in
  `config/ntm-gunfeel-guns.properties` or `/gunfeel set aimHold 0` with the gun
  in hand.
- `hipX`, `hipY` and `hipZ` (in `config/ntm-gunfeel.properties`; for one gun,
  in `config/ntm-gunfeel-guns.properties` or `/gunfeel set hipX …` with the gun
  in hand) now move pistols and revolvers with `pistolHold` too. The value is a
  fraction of that gun's default place: at 1 the pistol stays where it was,
  `hipX = 0` puts it in the middle of the screen horizontally, `hipY = 0`
  vertically, a smaller `hipZ` brings it closer. The move into aim stays
  smooth, and in aim itself these keys do not move the pistol: the front sight
  stays on the crosshair.
- ⚠ Breaks compatibility: `hipX`, `hipY` and `hipZ`, which did nothing for
  pistols with `pistolHold` before, now move them at the hip — both the ones
  saved for a gun in `config/ntm-gunfeel-guns.properties` and the shared ones
  in `config/ntm-gunfeel.properties` if they are not at the defaults (0.6, 1.0,
  0.75). To get the measured place back, delete the gun's lines (or
  `/gunfeel unset hipX` with the gun in hand) and set the shared keys back to
  their defaults.
- As in 26.2 X472: with `pistolHold`, both of a pistol's arms are the same size
  on screen, at the hip and in aim: the further the hand is from the eye, the
  thicker the arm. Before, on 1.12.2 the left arm came out one and a half times
  larger than the right. Turn it off: `armPistolScale = false` in
  `config/ntm-gunfeel.properties`; for one gun,
  `/gunfeel set armPistolScale false` with the gun in hand. The new keys
  `armAimScaleRight` and `armAimScaleLeft` set each arm's thickness in aim, and
  between the hip and aim it changes smoothly; 0 (the default) means the same
  as `armScaleRight` and `armScaleLeft`. There is no arm editor on 1.12.2, so
  there are no sliders for this either.
- Minecraft mobs' heads and limbs now go by the model's own cubes. The legs of
  a horse, donkey, mule, skeleton horse and zombie horse count as limbs (×0.5)
  whole, the lower part included; before, the lower leg counted as the body.
  The same goes, whole, for the arms and legs of a villager and a zombie
  villager and for an elder guardian's tail. A snow golem's pumpkin is a head
  too. A shot next to a llama's head, missing its model, no longer counts as a
  headshot (×1.75). For Mask Man, once the mask has come off, the skull counts
  as the head.

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

## X432.139

- On 1.12.2 Forge the game with StellarCore 1.6.x no longer crashes at the end
  of model loading when StellarCore's parallel loading is on
  (`ParallelModelLoader`, on by default). StellarCore puts its class into
  Forge's package `net.minecraftforge.client.model`, Forge is signed, and Java
  did not let such a class in. Now at startup the mod lifts this check for that
  package only. It does so only on Java 8, with a signed Forge and when the
  package holds another mod's class. On Cleanroom and without StellarCore
  nothing changes. Turn it off: `forgeSignerFix = false` in
  `config/ntm-gunfeel.properties` (add the line yourself; it works from the
  next start). What was done at startup is written in one line to
  `logs/ntm-gunfeel-debug.log` (on Cleanroom the mod touches nothing and writes
  nothing).

## X432.137

- On 1.12.2, the same as 26.2 X432: the shot delay after a sprint is on by
  default (`sprintFireDelay = false` in `ntm-gunfeel-server.properties` turns
  it off), and walk sway and the sprint pose are off for now (`walkSway = 1`,
  `sprintPose = 1` turn them on). Without them the gun sways with NTM's own
  sway and the vanilla arm bobbing, as before X431.136, the left main hand
  included. If `config/ntm-gunfeel.properties` was created by X431.136, it
  already has `walkSway = 1.0` and `sprintPose = 1.0`: to turn them off, set
  them to 0. The pistol hold stays on.

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

## X431.136

- On 1.12.2 walking and sprinting are now as in 26.2 X430: the sway and the
  sprint pose go by the gun's length, pistols and revolvers have a pose of
  their own, and both turn the gun about its middle. On by default
  (`walkSway = 0`, `sprintPose = 0` turn them off). With the left hand as the
  main hand NTM's sway stays, as in 26.2.
- Fixed: on 1.12.2 the barrel's tilt, the roll and the sideways shift while
  walking and sprinting were mirrored compared with 26.2.
- Pistols and revolvers on 1.12.2 are held as in 26.2 X431: at the hip almost
  on the line of aim, rising to aim along a smooth curve. On by default;
  `pistolHold = 0` brings back NTM's hold, and then `hipX`, `hipY`, `hipZ` work
  again.
- ⚠ Breaks compatibility: on 1.12.2 the `armModel` key is no longer read
  either; the arms go by the skin, as in 26.2.

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

## X400.133

- On 1.12.2 worn items are part of the hitbox, as in 26.2. Drawn armour
  (vanilla and NTM armour with its own models, for example T-51b) is now in the
  hitbox itself, and the model parts under it become solid and thicker by the
  armour's thickness. The head under a skull grows to 9.5 pixels, under a
  dragon head to 14.25, from the base of the head. After a change of armour the
  hitbox stops holding the old one by the next frame. The key
  `modelHitboxesWorn` is now on by default; `false` leaves only the drawn
  armour.
- On 1.12.2 there are now gun sway while walking, a sprint pose and a shot
  delay after a sprint, as in 26.2 (X400). The keys are the same: `walkSway`,
  `sprintPose` in the client settings, `sprintFireDelay`, `sprintFireTime` in
  `ntm-gunfeel-server.properties`. All off by default. While `walkSway` is on,
  NTM's own sway is not applied to the gun. The gun's tilt when falling and
  jumping stays as in NTM CE. Works on Forge and on Cleanroom.
- For development: the scenario `walksprint` (WalkSprintScenario): the G3
  walks, aims, sprints and fires. The sway trace is written to `logs`, frames,
  key presses and bullets to `aimprobe.tsv` in the game folder.

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

## X396.126

- Worn items can make the hitbox bigger, on 1.12.2 the same way as in 26.2:
  `modelHitboxesWorn = true` in `ntm-gunfeel-server.properties`. Model parts
  under armour become thicker by the armour's thickness as it is drawn: the
  helmet, chestplate and boots by 1 pixel on each side, the leggings by 0.5.
  The head under a skull or a block (a pumpkin) grows to their drawn size: 9.5
  and 10 pixels. The head zone grows with it. Off by default: the hitbox goes
  by the bare model, as before.
- On 1.12.2 a bullet flies where the player was looking at the moment of the
  click, as in 26.2 (X397). The server only knew the head's turn from the last
  game tick, while NTM sends the key press at once. So a SPAS-12 after a 40°
  flick with a click missed by exactly 40°, and an M2 burst lagged behind the
  camera by about 50 ms. Now the client sends this frame's turn before the key
  press, and while the trigger is held, it sends the turn every frame. A flick
  with a click: 0°, a burst: about 9 ms. Turned off with the key
  `clickAim=false`. The mod's network protocol did not change; the turn packet
  is the game's usual one.
- For development: the scenario `-Pscenario=aimlag` (AimLagScenario) makes four
  SPAS-12 flicks with a click and an M2 burst while turning at 90°/s. It writes
  how far each bullet lagged behind the camera to `run/client/aimlag.txt`.

## X396.125

- On 1.12.2 bullet holes on fences, walls and iron bars land only on the posts
  and bars themselves, as in 26.2 (X396). Before, a hole could land in the gap
  between bars, above a wall's arm or in the air past the joint of two fences:
  the game treated such a block as the whole cube of its frame.
- For development: the scenario `-Pscenario=fences` (FencesScenario) fires at
  pairs and corners of fences, walls, panes and bars and checks that every
  point of every hole lies on the block's shape.

## X396.124

- On 1.12.2 the mod writes its own lines, as in 26.2 (X395), to a separate
  file, `logs/ntm-gunfeel-debug.log`: the last 8 MB and two old ones. The game
  log only gets the mod's warnings and errors and one line at startup. The file
  is written by a separate thread; frames do not wait for it.

## X392.122

- On NTM CE the `smokeWidth` setting (the width of the smoke at the muzzle)
  works, as in 26.2: 2 draws the smoke twice as wide, 1 as NTM does. Before, on
  CE it changed nothing.

## X392.121

- On NTM CE the `hideOffhand` setting works, as in 26.2. It is on by default,
  and the item in the second hand is not drawn while a gun is in hand (on CE
  NTM itself does this too). If it is turned off, the item in the second hand
  is visible again. Before, on CE it was always hidden, whatever the setting
  said.
- For development: the scenario `hands` (`runClient -Pscenario=hands`) checks
  the second hand and the width of the muzzle smoke by numbers and screenshots.

## X392.120

- For development: the sound stand clears the bullet rules left by BulletCheck
  (otherwise bullets flew through stone), gives the minigun a belt of rounds,
  puts the shooter on the ground (no spread from jumping) and writes a bullet
  log on the server and on the client. The stand's client closes by itself when
  the server has gone, and marks in the log every check of a passing bullet's
  whistle. The stand fires the overhead pass from the left: on the right a post
  stood on the line, and the bullets did not reach the listener.

## X392.119

- On NTM CE a bullet's ricochet off stone, dirt and other non-steel blocks is
  heard by everyone nearby, as on 26.2: it is NTM's own quiet ricochet. Before,
  NTM CE played it in a way that made it sound for no one on a server.

## X392.118

- On NTM CE a bullet's ricochet off steel sounds as on 26.2: within 16 blocks,
  fading as if silence were 32 blocks away. Before, it faded out over 16, and a
  ricochet 12 blocks away sounded two and a half times quieter than on 26.2.

## X392.117

- On NTM CE a loud hit (a sound louder than 1, for example `hitVolume 2`)
  sounds like one hit. The mod plays such a sound as several copies, and on CE
  each copy took its own recording, so one hit sounded like two. Now all the
  copies play one recording, as on 26.2.

## X392.116

- On NTM CE the sound of a bullet hitting a block comes from the side and
  height where the bullet hit. Before, the mod counted the direction from the
  player's feet rather than the ears, and the sound went down, by up to 25° on
  hits on a wall nearby.

## X392.115

- On NTM CE the whistle of a bullet passing by and the crack right at the head
  fade with distance as in the 26.2 version: the whistle over 8 blocks, the
  crack over 12. Before, both faded over 16, and a bullet passing two or three
  blocks away whistled noticeably louder than on 26.2.

## X392.114

- On NTM CE hitting a mob with a weapon held by a FakePlayer (that is how other
  mods' machines shoot) can no longer crash the server. The hit sound went to
  the shooter, and a FakePlayer has no connection.
- For development: the sound stand. `runServer -PsoundStand` builds a platform
  with walls and posts, and a shooter (FakePlayer) fires next to the player,
  past them, over their head and into the wall near them.
  `runClient -PsoundRecord=<port>` joins that server and writes every sound to
  `logs/sounds.tsv`: volume, distance, side and how many ticks it played.

## X392.113

- On NTM CE bullets meet the sliding blast doors (`sliding_blast_door_legacy`
  and `sliding_blast_door_2` with two keypads) by their model, not by the
  block's box. At a closed door a bullet meets the door leaf; at an open one it
  flies through the passage and meets only the frame. Holes on the leaves move
  with them when the door opens.
- For development: the look scenario places another door with
  `-PlookDoor=<block>`; BulletCheck checks the CE sliding door in four
  rotations.

## X392.112

- On Cleanroom 0.7 the game with the mod no longer crashes while loading.
  Cleanroom turns the camera with its own call, and the ammo menu's mixin did
  not find it. While the ammo menu is open, the mouse moves its pointer on both
  Cleanroom and Forge.

## X392.111

- On NTM CE bullets meet the satellite dish on a pole, the radio box and the
  steel roof by their model, not by the block's box: they fly past the model
  and leave a hole where they hit.

## X392.110

- On NTM CE bullet holes also stay on the parts of machines that CE draws
  without models, for example on the molten metal in a crucible. CE draws the
  pumpjack's cables and the BAT9000 battery's gauge the same way.
- For development: the look and mobs scenarios on 1.12.2 aim from the player's
  eyes, not the feet, and the machine stand in look hits the machines again;
  look also captures a swing of the arm with a gun (`look-swing`).

## X392.109

- On NTM CE locking ammo (the ammo menu, the lock) names the round in the
  message, not "Air", when the gun's first magazine is empty.
- For development: the server ammo self-check (`ammocheck`) on 1.12.2 runs to
  the end (22 checks) and stops the server by itself.

## X392.108

- On NTM CE a shot to the head and limbs finds them by the mob's model: the jar
  has all of 26.2's markup (218 heads, 109 sets of limbs), and mobs without
  markup of their own take it from a similar model. The head is found on the
  llama, guardians, wither, shulker, Elder One, and on Mask Man and NTM's
  creepers, which CE renamed.

## X392.107

- On NTM CE bullets meet blocks and machines by their models from CE itself,
  not from NTM 1.7.10: the chemical plant's windows and walls, the chain-link
  fence, the bunker door's grate, a single iron bar (on 1.12.2 it is a post in
  the middle) are pierced and flown through where they are seen in the game.
- A dedicated server on 1.12.2 no longer crashes at startup (the mod called a
  client-side sound method).
- The mod holds 100–150 MB less in memory: CE's models are parsed only when
  their faces are needed.

## X392.106

- On NTM CE aiming is on the right button, for as long as it is held. The
  middle button got the gun's second action, as in the 1.7.10 version.

## X392.105

- On NTM CE first-person arms, gun recoil and the camera climb when firing
  work: the arm pose and the kick for each shot match the 1.7.10 version.
- The corner ammo HUD no longer turns itself off at startup: CE also passes the
  hand to the HUD drawing, and the mixin did not fit that.

## X392.104

- The first build for NTM CE 2.6.1.0 on Minecraft 1.12.2: one jar for Forge
  14.23.5 (Java 8) and Cleanroom 0.7 (Java 25), mixins through MixinBooter. The
  mod loads on the server, and the compatibility check with NTM at startup
  passes for all systems. The guns' behaviour is being ported piece by piece in
  the next builds.

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
