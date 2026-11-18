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
- The M2 and the AM-180 overheat (the minigun's turning barrels do not): about
  180 rounds of the M2 in one burst, or three magazines of the AM-180 one
  after another, and the barrel is at its hottest. It cools in about a
  minute, four times as fast in water or rain. A hot barrel smokes at the
  muzzle and hisses, its heat shows as a bar under the gun's condition, the
  shot spreads up to four times the gun's own and the barrel wears up to five
  times as fast. The AM-180 jams in the burst; the M2 fires from a closed bolt,
  so a round left in its hot chamber can go off on its own a moment after the
  trigger is let go. AI soldiers fire shorter bursts as their barrel warms and
  wait for it to cool near the top (server key `gunOverheat`).

### Ammo and HUD

- An ammo counter in the bottom-right corner instead of NTM's.
- A hit marker round the crosshair when your shot hurts someone: four short
  white strokes on the body, red ones on the head, with a quiet tick or ding,
  and a bell on a kill. Any NTM gun's bullets, beams and blasts, the
  electroshock shell's too.
- When you take damage, a red arc round the crosshair shows where it came from
  and stays pointing there as you turn.
- Tap Y to lock the gun's ammo type: it keeps that type, also once empty. Hold
  Y for an ammo wheel: point at a type with the mouse and left-click to reload
  with it; letting go of Y without a click changes nothing. Under the type you
  point at, the wheel shows its damage from the gun in hand, on a head hit and
  against armour, next to the loaded one.
- A 12 Gauge Electroshock Shell for NTM's 12 gauge shotguns (all but the
  Shredder), made in NTM's ammo press, at the tier of NTM's 10 gauge and 12
  gauge magnum shells: three tantalum capacitors, a gold coil, smokeless powder
  and an advanced buckshot casing give six. One round, about 2 damage. A
  hit shocks for 2 seconds, which then wear off smoothly over 3 more: the HUD
  (other mods' too, not the chat or menus) glitches and shakes, the view
  shakes and drifts on its own, and whoever was hit, an AI soldier or any mob
  too, walks 80 % slower (a player's view narrows by a fifth) and
  spreads their shots wide. A shocked player also sees discharges jag in from
  the screen's edges and a light bluish vignette, as strong as the glitch.
  Then 3 seconds of immunity: a shell that hits during the shock or the
  immunity does not shock again but does 90 % of a slug from the same gun
  (with the mod's balance; about 2 under `balance = ntm`). The shooter sees a
  spark at the crosshair.
- A 12 Gauge Rubber Slug for the same shotguns (ammo press: latex, smokeless
  powder and a shotshell casing give six, rubber twelve). A hit does 6 damage
  before armour and never takes anyone below 4 health. It pushes the one hit
  back about 3 blocks, slows a player by 60 % for 1.5 seconds and mixes up
  their movement for 4 (forward and back, left and right, jump and sneak swap,
  whatever the key bindings; a boat or a horse steers the other way too). An
  AI soldier is slowed and its aim swings off for 4 seconds. An undead mob,
  not a boss, runs from the shooter for 4 seconds, then cannot be frightened
  for 10.
- 5.56mm and 7.62mm Duplex Rounds wherever NTM's soft point round of the
  calibre goes (ammo press: four lead ingots, four smokeless powder, two small
  weapon steel casings give 16 and 12). Each shot fires two bullets at once,
  the second a quarter of a block behind the first and about 3 milliradians
  off its line; each bullet goes through things as a soft point and does 70 %
  of its damage.
- A .50 BMG Nuclear Round for the AMAT rifles only (ammo press: two
  americium-242 nuggets, a schrabidium nugget, two TATB charges and a steel
  large casing give one). It does no damage of its own and goes off where it
  hits as 80 % of NTM's Fat Mine, breaking no blocks; point blank as well. In
  its reach it lays the ground waste as a nuke does (grass and leaves dead,
  plants and mushrooms gone, uranium ore to schrabidium or scorched ore) but
  burns no wood. One shot wears the rifle by a fixed share of its durability:
  an AMAT from new is worn out in 3 shots, a Subtlety in 6, a Penance in 10.
  Wear spreads this round's shots only a quarter as much as NTM's other
  rounds, so the last shots before the rifle is worn out still go about where
  they are aimed. A worn-out rifle no longer fires the nuclear round (it
  clicks as if empty), and R does not swap them for another round while they
  are in it: take another type from the ammo wheel (Y). Its other rounds it
  fires as NTM has it, spread wide.
- These rounds go into NTM's ammo bags, the plain and the infinite one, as
  NTM's own do, and guns reload them from the bag.

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
  and take the gun's recoil. Shots carry by the gun, for them and for you
  alike: a rifle is heard 80 blocks off, a pistol 48, a silenced gun only
  close by (server key `gunshotRange`).
- Bots of one side fight as a squad: when you vanish behind cover one goes
  round the side while the others fire to keep your head down, and a bot
  leaves cover only while another covers it. Lose them behind a long wall
  and they split its ends between them: one watches where you went, the
  others the corners you could come round. They throw grenades, run from
  grenades thrown at them, including a player's, and do not turn their heads
  to the sounds of animals.
- A bot that has lost you behind cover and has none of its own does not
  stand in the open: it goes down on a knee, unless that would hide the place
  it watches. A bot that knows of no one looks slowly round, up to 60 degrees
  either side of the way it faces and never so far that this way leaves its
  view, so one creeping up from the side can be seen; it turns its head to
  anyone it glimpses.
- Fire suppresses them as it does you: bullets passing close, hitting their
  cover or bursting near pin a bot down, and a squad kept under fire loses
  heart, holds back and falls back (server key `aiSuppression`).
- A bot with a machine gun is its squad's base of fire: it stays in place and
  keeps your cover under long bursts while a rifleman goes round the side
  (server keys `aiRoles`, `aiBaseMagazine`). A role can be given by word
  whatever the gun: `role base` or `role rifle` after the gun in
  `/armsai spawn` and in `/armsai kit`, `role auto` to go by the gun again.
- A squad's supplier carries magazines, grenades and smoke grenades for the
  others: when one of its squad is down to its last magazine or out of
  grenades, it runs over and hands a portion on, if it is not pinned down
  itself and the other is not out in the open in sight of the enemy. Its
  stock runs out; when a supplier falls, one of its side picks the stock up
  and carries on. A squad with no spare magazine left and no supplier to
  bring one loses heart and falls back. `/armsai spawn fbi 6` makes each of
  the six a supplier by chance (about one in six, any number in a squad);
  `role supplier` makes one by word (server keys `aiSupply`,
  `aiSupplyShare`, `aiSupplyOut`, `aiSupplyMagazines`, `aiSupplyGrenades`,
  `aiSupplySmokes`).
- A bot with a scoped gun is its squad's marksman: with you 24 blocks off or
  farther it climbs to a perch behind its squad, high and with a long view of
  you, mostly 40 to 90 blocks off, fires single shots from there a second
  apart and moves on after two or three, as soon as it is shot at, or when it
  has had no shot for five seconds. It never goes round the side, and leaves
  looking for you to its squad's riflemen while one of them knows where you
  are. `role marksman` makes one by word (server key `aiMarksman`).
- A bot with a grenade launcher (40 mm) is its squad's grenadier: it lobs its
  shells over your low cover and onto the place you just ducked out of sight
  at, the barrel raised as far as the shell drops, never within 17 blocks of
  itself or its own. `role grenadier` makes one by word (server key
  `aiGrenadier`).
- A squad may have a medic: it runs to one of its own (or to you, if you are
  on its team) who has lost more than 40% of their health, when the way is out
  of the enemy's sight, kneels by them three seconds, not firing, and gives
  half their health back; it treats itself too. While its squad sees the enemy
  it only fights and treats no one; the hurt take cover as they fight and stay
  in it. `/armsai spawn` with a number makes medics by chance; `role medic`
  makes one by word (server keys `aiMedic`, `aiMedicKits`, `aiMedicShare`,
  `aiMedicCover`).
- A team of bots that knows of no one comes for you: its commander sends each
  of its squads, two at most to one group, to roughly where you, or another
  team it fights, are (an area of radius 16 to 32 blocks, wider the longer
  none of them has seen you), and the squad looks through it with its own
  eyes. A minute there with no one found, and the squad goes for another
  group. Where the team loses soldiers, one squad more may go: the reserve
  comes up. The squads go there by the commander's own map of the land:
  behind hills and through woods where the enemy would see them in the open,
  round deep water and cliffs. Each squad has a task: one strong enough
  advances; two about as strong as the enemy, one pins it from a lookout
  while the other goes round its side; one too weak holds a higher place
  that sees it; an area not seen lately is scouted; a squad that lost more
  than half its men or ran out of rounds falls back on its own; with no enemy
  known they scout round their side. On the way they march in file while
  far off, bound from cover to cover half by half once near or in sight,
  creep crouched when close, and storm the last stretch of an advance. A
  squad pinning an enemy and one going round it work together: the pinning
  one keeps the enemy's places under fire once the other is round and goes
  in, and does not chase a lost enemy far from its place. Squads report
  contact, losses and being pinned down; a pinned squad holds and fires back
  while another goes round the enemy, and breaks contact under smoke if it
  keeps losing men.
  Soldiers far from any player are moved by their commander too, till a
  player comes near (server keys `aiCommander`, `aiCommanderHelp`,
  `aiCommanderStaff`, `aiCommanderTasks`, `aiCommanderMoves`,
  `aiCommanderPairs`, `aiCommanderReports`).
- They open wooden doors, fence gates and NTM's plain doors on their way; an
  iron door still keeps them out (server key `aiDoors`).
- They cross water over a bridge or through a ford when there is one, and swim
  only when the way round is about eight times longer. Under fire they do not
  go into water too deep to stand in; swimming they neither fire nor throw,
  take no cover there and make for the bank. Lava and NTM's acids, mud and
  other liquids they never walk into.
- Leaves hide you only so far: through a block or two of them a bot sees you
  but takes longer to notice you, through about eight it hardly does, and
  further not at all. Grass hides little. Lying down or crouching you are
  noticed later, running sooner. Bullets go through leaves as before, so a bot
  that sees you through a bush can hit you there.
- They go into a room together: beside the doorway first, out of the line
  through it, then in at once, each to its own side (server key `aiEntry`).
  One of them steps out before the doorway first and throws a grenade in,
  then steps back behind the wall; they go in after it bursts. It is thrown
  only where the burst reaches none of their own (server keys
  `aiEntryGrenade`, `aiEntryGrenadeFilling`). Before going in they look in
  from round the doorway where there is room to, stepping out wide and
  closing in, guns on the edge of what comes into view by the doorway's wall
  (server key `aiEntrySlice`). Going in after their grenade they look for
  whoever it left alive: one in the room is noticed sooner, and no slower at
  the edge of sight, though never in under a quarter of a second (server key
  `aiEntryPrimed`).
- They point at a target they call out, wave their own on, put a hand to a
  door they open and hold the gun close beside a doorway (server key
  `aiGestures`).
- They search a building room after room, two to a room, and do not go back
  into a room already checked (server key `aiClearing`). Into the next room
  inside they go as into the first: from beside the doorway, together (with
  `aiEntry` on).
- They go into a trench one or two wide as into a building: each straight
  stretch, with its fire step, is a room, each turn a doorway. They stack up
  beside a turn, one steps into it and throws a grenade down the next
  stretch, then they go in and search stretch after stretch, instead of
  walking along the edge above it (server key `aiTrenches`).
- A river between a squad and its foe is dealt with by the field manual. On
  its own the squad does not swim it: it keeps to its bank, two riflemen go
  along the bank looking for a bridge, a ford or a narrow place to swim, and
  it reports the foe and what it found to its staff. A bridge or a ford is
  walked as any way. Ordered over by its staff, it crosses by the drill: the
  machine gunner, the marksman and a rifleman stay on the near bank and keep
  the foe under fire, a pair goes over first and holds the far bank, one of
  them a little further on to watch, the rest follow by pairs, the near bank's
  cover last at the same place. Smoke goes onto the far bank when the
  crossing is watched: the squad is under fire, its own fire has given it
  away, or it knows of a foe at the far bank. One shot at while swimming from
  across, not halfway over yet, swims back and goes again; past halfway it
  goes on (server key `aiCrossing`).
- A bunker is taken by its firing slits. Any open hole at head height in a
  wall with wall over it is a slit, its field of fire a wedge widening as the
  slit is wide for its depth. Their ways keep out of the wedges; one in a
  wedge with nothing to hide behind steps aside out of it. The machine gunner
  fires into a slit it cannot see the foe behind. Through a slit in a wall
  two blocks thick or more (a bunker, not a house's open window) the one of
  the squad with a grenade nearest it goes to the wall beside it, out of its
  fire, steps out before it and throws a grenade in, then back to the wall,
  and its own do not run from that grenade (server key `aiBunker`). `/armsai bunker
  [squad]` builds a bunker with a slit 20 blocks ahead of you, one inside at
  it, and a squad with a grenade each beside you behind a low wall, to watch.
- They open trapdoors and climb ladders up and down, NTM's steel hatch
  included. A foe lost under the ground down a shaft is gone after by the
  drill: the squad gathers a few blocks off the hatch, one drops a grenade
  down if it gets to the foot of the shaft before its fuse is out (a shaft as
  deep as NTM's bunker's is too deep for one) and none of its own is in its
  reach, then they go down one after another; the first at the foot holds the
  way out of the shaft till the next is down, and one stays up by the hatch
  till the others are down, then goes last (server key `aiHatches`). A foe
  lost up a shaft is gone after up its ladder, the search going on from its
  way out at the top. A ladder is never taken for cover, and one left on a
  ladder with nothing to do climbs on to its end and steps off.
- A soldier who sees a foe fall forgets it and tells its squad, so none of
  them waits for it or goes after it where it fell (server key
  `aiDownTold`).
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
- NTM's small nuclear blasts (the Fat Mine, the Fat Man's shells, the
  artillery mini nuke, the nuclear creeper and the rest) rise as the
  mushroom cloud of NTM's bombs, sized to the blast, instead of their own
  flash and wave. They keep their own sound; the bombs' screen flash, sound
  and shake are left out (server key `miniNukeCloud`).
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
  applies to every gun. With a gun in hand, `/gunfeel set <key> <value>`
  and `/gunfeel unset <key>` change its keys; `/gunfeel set <key>` with no
  value shows the key's value, where it comes from and its defaults.
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
| `hitMarker` | `true` | A mark round the crosshair and a quiet sound when your shot hurts someone (red on the head, a bell on a kill) |
| `damageIndicator` | `true` | An arc round the crosshair towards where damage you take came from |
| `ammoMenu` | `steel` | Look of the ammo wheel: `steel`, `petals`, `bubbles`, `ntm`, `glass`, `arc` |
| `quality` | `high` | Impact effects: `low`, `medium`, `high`, `realism` |
| `hitVolume` | `2` | Loudness of hit sounds, 0 to 4 |
| `hipX`, `hipY`, `hipZ` | `0.6`, `1`, `0.75` | Gun place at the hip; on pistols a share of their own place |
| `tracers` | `true` | Tracers |
| `bulletHoles` | `true` | Bullet holes and marks |
| `waterSplash` | `true` | Splashes, rings, foam and bubbles on water |
| `gunWeightTooltip` | `true` | A gun's tooltip names its weight class and how much it slows |
| `suppressionDarken`, `suppressionMuffle` | `1` | How much the screen darkens and the other sounds go down under fire, 0 to 1 (the server sets the most) |
| `shockGlitch` | `1` | How much the HUD glitches and shakes, and how bright the discharges and the vignette at the screen's edges are, when a shock shell hits you, 0 to 1 (the server sets the most and the least) |
| `casingMax` | `96` | Most of NTM's spent casings drawn at once, the nearest on screen (`-1` = no limit, `0` = none); casings off screen or over 48 blocks away are never drawn |
| `casingInstances` | `true` | NTM's spent casings drawn by the graphics card, all of a kind in one draw (`false` = NTM's way); off by itself under an Iris shader pack |
| `soundNoWait` | `true` | A new sound no longer makes the frame wait while the sound thread is busy (many shots, Sound Physics); it starts in the sound thread's turn as before (`false` = the game's way) |
| `botRunGait` | `vanilla` | AI soldiers' legs on the run: `vanilla`, `tempo` (fewer steps, nearer the run's own pace; the feet slide more), `stride` (longer steps; the feet slide less) |
| `botMoves` | `clips` | AI soldiers' moves from recordings of people: `clips` (a slow turn on the spot steps as people step), `old` (the feet's own steps) |
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
| `shockSeconds`, `shockImmunity` | `2`, `3` | How long a shock lasts at full (it then wears off over 3 more seconds), and the seconds after it when another shell does not shock (it does 90 % of a slug instead, with the balance) |
| `shockSpread` | `0.12` | The spread added to a shocked one's shots (NTM's units: buckshot's is 0.035; about 7 degrees) |
| `shockGlitch`, `shockGlitchMin` | `1`, `0.5` | The most the HUD glitches, and the least share of it a player may turn their own down to |
| `shockCamera`, `shockShake` | `45`, `0.6` | How fast the view drifts on its own at most (degrees a second), and how much it shakes (degrees) |
| `gunshotRange` | `1` | How far shots are heard, players and AI bots alike: silenced 16 blocks, energy weapons 24, pistols 48, shotguns and launchers 64, rifles 80, machine guns 96, anti-materiel rifles 128; the part over 16 scaled (`0`: 16 for all, as in NTM) |
| `gunOverheat` | `1` | The M2's and the AM-180's barrels heat up: times the heat each shot adds (`0`: they never heat) |
| `miniNukeCloud` | `true` | NTM's small nuclear blasts drawn as the mushroom cloud of NTM's bombs, sized to the blast (`false`: NTM's own flash and wave) |
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
- `/gunfeel set|unset <key>`: the gun-in-hand settings; `set <key>` with no
  value shows it.
- `/gunfeel quality <preset>`: impact-effect quality.
- `/gunfeel hitvolume <0..4>`: loudness of hit sounds.
- `/gunfeel botrungait <vanilla|tempo|stride>`: AI soldiers' legs on the run.
- `/gunfeel botmoves <clips|old>`: AI soldiers' moves from recordings of people, or the old steps.
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

Original textures and other assets made for this project by ACupOfChicory are
licensed under GPL-3.0-or-later as well.

Every file is by ACupOfChicory under GPL-3.0-or-later except those named in
[REUSE.toml](REUSE.toml) ([REUSE](https://reuse.software/) 3.x), which gives each
one's author and license; the license texts are in [LICENSES/](LICENSES).

Files made from HBM's Nuclear Tech Mod (HbmMods (The Bobcat) and contributors;
the NTM NEXT port by movblock), such as the new gun mechanisms' textures, NTM's
recipes, the machines' bullet shapes and NTM's numbers the tests check, stay
under its LGPL-3.0-only; see [third-party/ntm.txt](third-party/ntm.txt).

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

AI mobs' turns on the spot, starts, stops, grenade throw and their legs walking,
running and sprinting ahead, and walking aside and back with the gun up, are
made from recordings of people: 100STYLE by Ian Mason (CC BY 4.0) and the CMU
Graphics Lab Motion Capture Database (mocap.cs.cmu.edu; created with funding
from NSF EIA-0196217). Their ways down into a crouch and up out of it are made
from the Open Motion Project by ACCAD/The Ohio State University (CC BY 3.0).
Takes used and the changes made are in
[third-party/bot-moves.txt](third-party/bot-moves.txt).

The jar bundles XZ for Java 1.10 (0BSD), which unpacks the machine shapes
shipped in the mod; its notice is in [third-party/xz.txt](third-party/xz.txt)
and packaged under `META-INF/licenses/`.

The 1.7.10 port also bundles stub classes from JvmDowngrader
(LGPL-2.1-or-later) and a shaded copy of JOML (MIT). Their notices are in
`port/1.7.10/third-party/` on that branch and packaged in its jar.

Bullet sounds under `src/main/resources/assets/ntmgunfeel/sounds/bullet/` come
from freesound.org and keep their own licenses (CC0 1.0 or CC BY 3.0/4.0);
authors and links are in
[CREDITS.txt](src/main/resources/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
AI mobs' call-outs under `sounds/ai/voice/` were spoken by the Kokoro-82M
text-to-speech model (Apache-2.0); see
[third-party/ai-voices.txt](third-party/ai-voices.txt).
