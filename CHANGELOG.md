# Changelog

## Unreleased

- AI soldiers go into a room together. The ones that go after a target lost
  in a building no longer walk in through the doorway one after another:
  they come up beside it, behind the wall and out of the line through it,
  wait a moment for the second, and go in at once, each to its own side.
  From there they search as before. A doorway one or two blocks wide counts;
  a wider opening is entered as before. New server setting `aiEntry` (true
  by default).
- AI soldiers tell a cave from a building. A place under a roof is read by
  its shell: mostly ground as the world made it, it is no building, and the
  squad does not stack up at a cave's narrows; blocks someone put there make
  it one. A bunker broken into a cave ends at the breach. A cellar dug out of
  the rock counts when something is kept in it (a chest, a bed, a machine),
  up to its door. New server settings `aiRoomsShell` (true by default) and
  the block lists `aiRoomsNatural`, `aiRoomsDwelling`, `aiRoomsWild` (ids,
  `#tags`, `~patterns`).

## X977

- The Shredder's damage falls off with range as the laser guns' does: full
  to 64 blocks, then down to half at 160 (`balance.final.range.lineOf`). It
  fires beams of plasma, and the drops the beam leaves where it strikes do
  its damage; they took the shotgun's line (0.4 at 64 blocks), the slug's or
  the explosive shell's. Its damage up close is as it was.

## X973

- AI soldiers open doors: a shut wooden door, fence gate or NTM door of the
  plain kind (metal, office, bunker, red) on a bot's way no longer stops it.
  Its path goes through, it opens the door as it comes up and leaves it
  open. Before, a shut door was a wall to it: shut the door behind you and
  the squad stayed outside. Iron doors, trapdoors and NTM's sliding, blast
  and vault doors still keep them out. New server setting `aiDoors` (true by
  default).

## X967

- Fable's third review of the balance. A shotgun aimed throws its shot
  tighter: NTM's spread past 0.015 counts 0.3 times (`spreadAimKnee`,
  `spreadAimOver`; buckshot 0.035 -> 0.021, flechettes 0.025 -> 0.018, magnum
  and slugs as they were). The SPAS-12 has no aim in NTM and gets the same
  from the hip (`spreadHipKnee`, `spreadHipOver`). Akimbo guns have no aim and
  no choke.
- Shot keeps damage farther, down to 0.4 at 64 blocks (was 48); a slug keeps
  full damage to 32 blocks and falls to 0.3 at 128; an explosive shell's
  direct hit has its own line, full to 24 blocks and 0.25 at 80
  (`balance.final.range`, `shell`).
- The guns of two hands are counted from the hip in the shares of hits: the
  twin miniguns 0.56, the twin Star F 0.80 (they were 0.87 and 1.00).

## X956

- The STG-77 no longer shakes while it is raised to the scope. Its model
  zooms in as it comes up, and that zoom was worked out with the previous
  frame's timing: across each game tick it jumped a tick ahead, then back.
  At a steady high frame rate this was hardly seen; when the frame rate
  varied (a fight, smoke, many particles) the model jerked back and forth.

## X953

- A bot or another mob in armour of the balance's table takes a gun's hit as
  a player in the same set does. Its own vanilla armour (an FBI agent's 20
  and `balance.fbiArmor`) took the hit a second time: an FBI agent in
  Security lived through two dozen STG-77 rounds, the head's among them,
  where its table kills it with 9 in the head (`balance.final.mobArmor`;
  `own`: as before). A bare mob still takes NTM's damage.
- A shot on a helmet is a shot in the head for every helmet: an NTM suit's
  own helmet (T-51, HEV, AJR, NCR and the rest) stands further off the head,
  and its edge counted as the body. F3+B shows a bot's helmet green, as the
  head (`modelHitboxesHelmet`; false: as before).

## X951

- AI soldiers can be given armour and rounds by command. `/armsai spawn
  fbi|dummy [count] [gun]` takes, after the gun and in any order, `armor
  <set>` (a full set by the balance table's name or NTM's: `steel`, `t51`,
  `hev`, `sec`, `bj`, `dns` and the rest; `none` for no armour), `armor
  <slot>=<item> …` for single pieces (`head`, `chest`, `legs`, `feet`; an
  item's id, a set's name or `none`) and `ammo <round>` (one of the rounds the
  gun takes, such as `r556_ap`); `team <name>` goes there too. The bot loads
  that round and reloads with it; before, a bot always had its gun's first
  round and no armour but what `/item` put on it. Powered pieces come charged.
  The words complete as they are typed, the rounds by the gun named.
- `/armsai kit <targets> [armor …] [ammo …]` changes the armour and the round
  of bots that already stand. A set there is not, or a round the gun does not
  take, is refused with the names that would do.

## X949

- AI soldiers change low cover as they do high cover: after two or three
  bursts over one low wall a bot now and then moves to other cover, after
  four always. Before, a bot behind low cover left it only when hit.

## X942

- Fable's second review of the balance (`balance.final`, each rule its own
  key, its old behaviour a value of it). The share of hits of every pair is
  now one measure from the stand (`balance.final.hits`): the shooter aimed and
  standing, the target at the role's working distance, an automatic in a held
  burst; one share for a gun and its ammo's spread. The miniguns hit 0.87 of
  a held burst at 12 blocks, all of it at 4 and a little over half at 20.
- Shotguns kill their equal in two clicks (`balance.final.roles` shotgun 0.35,
  fading to nothing two tiers up, `balance.final.roles.fade`); the Shredder
  counts as a shotgun (`balance.final.roleOf`). Shot loses damage past 16
  blocks, slugs past 24, beams only past 64 (`balance.final.range`); an
  explosive shell's direct hit takes its role's line
  (`balance.final.range.shells`).
- Beams no longer take longer than bullets (`balance.final.energy` 1.0); no
  set is weaker against beams (the -1 of steel and the like is gone);
  Trenchmaster is tier 6, its blocked third of hits counted on top. The
  magazine's rule never cuts a fight below 0.65 of its time; a tier of few
  pairs takes no raw-DPS character (`balance.final.character.few`); an empty
  slot of a mixed set counts as nothing.
- The Tesla Cannon's IR beam does a click of its pair on anyone the click's
  arc did not strike (`balance.final.tesla.ir`).
- The acid's corrosion takes the set's tier down (`balance.final.acid`): HEV
  at full corrosion as T-51, BJ two thirds of a tier, FAU and up untouched,
  never below bare.
  The mod's gas on FAU, DNS or euphemium drains the pool HP for HP, like a
  shot (`balance.final.pools.gas`). The GL-54's range finder does not measure
  through thick smoke (`gl54.rangeSmoke`).

## X925

- AI soldiers are suppressed as players are: each bot has the players' own
  scale (0 to 100), raised by a foe's bullets passing its eyes, by those
  stopping near it (in its cover too) and by blasts near, with the server's
  suppression forces and ranges. From 50 up a bot is pinned down: it keeps
  behind its cover, fires blind over a low one, lies down in the open; kept
  under fire it loses confidence and its squad holds back or falls back.
  Pinned behind one low cover for 5 seconds, it leaves for other cover.
  Before, only three shots within 2 blocks in a second pinned a bot, for 3
  seconds whatever came after. New server setting `aiSuppression` (true by
  default; false: as before).
- Roles in a squad of AI soldiers, by the gun: a bot with a machine gun (a
  belt, or an automatic with 40 rounds or more) among riflemen is the squad's
  base of fire. It no longer goes round the side or after a lost target; while
  another of its squad is on the move it fires long bursts at your cover with
  short gaps, so your suppression scale stays up until the flanker is there.
  New server settings `aiRoles` (true by default) and `aiBaseMagazine` (40).

## X922

- Fable's review of the balance (`balance.final`, each rule its own key, its
  old behaviour a value of it). The ladder below the equal fight now goes by
  equal hours: a group a tier or more below with as many hours in all as the
  player above fights them even (a tier: about 2 players, two: 3, three: 5);
  above, x0.77 a tier down to 1.25 s at +4 (`balance.final.ttk`). Pistols are
  timed as rifles (1.0, was 1.2), a shotgun's slug or explosive shell too (role
  `slug`, `balance.final.slug.pellets`). The pools swap: DNS 45, FAU 40
  (`balance.final.pools`). The usual fight of equals: 3.0 s (was 3.5).
- The magazine's rule (`balance.final.mag`): a kill that fell on the last
  rounds of a magazine or the first after a reload comes a little earlier, so
  one miss does not double the fight. G3 on HEV: 26 rounds of 30.
- Blasts on the same ladder (`balance.final.blast*`): a launcher's or a hand
  grenade's blast on a set takes a share of the health and pool by the tier
  gap (direct hit x1.5, 3 blocks x0.75); a launcher lifts its round at most
  two tiers. A tier 1 HE grenade no longer kills BJ in 5 hits but in 35; the
  GL-54's fragment grenades count by their cone; a mob's grenade keeps NTM's
  numbers. `false`: formula C, as X912.
- Damage by range (`balance.final.range`): SMGs and pistols stronger close up
  and weaker past 24 blocks, rifles and machine guns full to 64, sniper rifles
  to 160, each down to a floor; blasts and the Tesla Cannon's arc unchanged.
- The fight log (`balance.final.log`): a line for every kill of a player by a
  player in `logs/ntmgunfeel-fights.log` (gun, ammo, sets, distance, time from
  the first hit, shots, hits, head hits, reloads), at most 4 MB.

## X914

- The GL-54's recipe moves up to technetium's tier: a rangefinder, a military
  grade circuit board and a vacuum tube on top; a heavy barrel and a light
  receiver of resistant alloy (technetium or cadmium alloy, 10 ingots in all)
  and a technetium steel gun mechanism in the middle; a plastic grip and a
  polymer stock at the bottom. With `gunMechanismRecipes = false` the
  mechanism is NTM's weapon steel one, as in NTM's recipes of the tier.

## X912

- New gun balance, one formula for every gun (server key `balance.final`,
  on; `false`: X908 as it was). A bullet or a beam no longer goes through
  NTM's damage, the armour's threshold and resistance and the old armour
  formula: a click whose projectiles all hit does 20/N HP, N worked out so
  that the gun kills the set in the time its tier difference gives
  (`balance.final.ttk`), shaded by the gun's role and raw damage and counting
  its spread, pause and reloads. The head (×1.75), limbs, the damage cap and
  the DNS, FAU and euphemium pools come after, as before; a pool counts in
  the time only on a player whose set has one (`balance.immune*.mode = pool`).
- A fight of equals now takes about 3.5 s on average (body, at the gun's
  range), with each kind of gun its own: SMGs from the hip about 5.8 s,
  machine guns 4.0, pistols 3.5, rifles 3.2, shotguns close up and sniper
  rifles 2.5 (`balance.final.roles`). All hits on the head at point blank
  take about half that (1.65 s on average). No gun kills with one shot on the
  head any more, sniper rifles included (`balance.final.head.guns`, empty).
- Armour a tier above the gun takes twice as long (7 s), two tiers 18 s.
  Security and the steam suit count as tier 2.5,
  DNS 9 (never killed quicker than FAU). A mixed or incomplete set counts by
  its pieces (helmet 3, chestplate 8, leggings 6, boots 3; no pool).
- Tau and the Old Classic go by the same formula (Tau at tier 8); the
  explosive shotgun shells' hit on the creature they strike counts a tier up,
  their blast on anyone else as before. Blasts, grenades, rockets, shields,
  Med-X, melee and fire are unchanged; mobs' and turrets' shots of no gun take
  NTM's own damage.

## X908

- Tau no longer goes through tier 6 and 7 armour as if it were not there. Its
  click did more than the damage cap lets through in a window, so it killed
  any set below DNS in 0.8 s (tier 7 in 1.2 s), the same as no armour. Against
  sets below tier 8 its hit now counts as ×1.15 (tier 7: ×1.15 +18 threshold
  negation) instead of ×2 +30: BJ and the other tier 6 sets 2.0–3.0 s, CMB,
  RPA and Schrabidium 3.0 s, HEV 1.0 s. DNS (5.6 s), FAU (4.8 s), euphemium
  (18.2 s) and no armour (0.8 s) are as before. The gun table's new
  `under=T:mult:neg:pen` does it; new server key `balanceGunUnder` (`true`;
  `false`: as before).
## X906

- The 12 Gauge Electroshock Shell's recipe now sits with NTM's last rounds in a
  weapon steel casing, the 10 gauge shells and the 12 gauge magnum, phosphorus
  and explosive shells (tier 4): NTM's ammo press makes six from a capacitor
  (niobium), a copper coil, smokeless powder and an advanced buckshot casing.
  The old recipe asked for NTM's Old Capacitor block, which nothing makes. The
  shell's tier in the gun table follows the recipe: 4 (9 with the tier 9 shotgun).

## X904

- The Lever Action Rifle and Lincoln's Repeater now take DNS, FAU and
  euphemium like other guns of their tier: their reload from empty and a shot
  (10.5 s) ran past the 10 s a hit holds the window open, so every reload shut
  the window, gave the full pool back and cost a minute of invulnerability.
  A gun whose shot and reload take longer than `balance.immune.hold` now holds
  the window, and the pool's refill pause, that long and a second more after
  its hit. AP on DNS: 1050 s → 200 (Lever Action) and 1120 s → 215 (Lincoln),
  the other rounds 270–500 s, where they did not kill in two hours before;
  euphemium 650–1460 s, never before. Every other gun is as it was. New server
  key `balance.immune.reloadHold` (`true`; `false`: as before).
- Heavy Revolver's AP round is no longer slower than the cheaper revolvers'
  against armour of tier 6 and up (BJ 9.3 s → 7.2, CMB 15.8 → 10, DNS 140 →
  101, euphemium 432 → 308); against no armour and armour of tier 4 as before.
- Aberrator holds 9 rounds (5 before) and Eyes of the Tempest 8 a gun (5), so
  the tier 9 secret guns take DNS and FAU in 3.9 s (5.8) and euphemium in
  13 s (17.4), not slower than the tier 8 guns. Their click was already at the
  damage cap, so a bigger multiplier could not do it; against no armour they
  take 1.3 s, as before.
- Tesla Cannon's and Lasrifle's tier in the gun table follows their recipes
  (tier 7 with `balanceRecipes`, for every round, not only the overcharged
  capacitor): Tesla's plain and IR capacitors no longer get the half floor of
  tier 5 guns against DNS and FAU (DNS 91 s → 61 and 62 → 41). Lacunae is
  tier 9 in the table, as its pedestal recipe; nothing changes in play.

## X900

- New ammo: the 12 Gauge Electroshock Shell, for every NTM 12 gauge shotgun but
  the Shredder (in the ammo wheel and the HUD like the rest). NTM's ammo press
  makes six from a copper capacitor, four fine copper wires and an advanced
  buckshot casing. One round of about 2 damage. A hit shocks for 2 seconds:
  the whole HUD, other mods' too, glitches hard and shakes (not the chat or the
  menus), the view shakes a little and drifts on its own, and the shots of
  whoever was hit spread wide (the server decides, for players and AI soldiers
  alike). After a shock, 3 seconds of immunity. The shot is heard as a zap, and
  the shooter sees a spark at the crosshair. Server keys `shock`,
  `shockSeconds`, `shockImmunity`, `shockSpread`, `shockGlitch`,
  `shockGlitchMin`, `shockCamera`, `shockShake`; client key `shockGlitch`.

## X896

- One player in HEV now beats three in steel armour: guns of tier 2 and below
  had the same per-click floor against HEV as against steel, so three cheap guns
  at once outran the damage cap. Against armour of tier 4 and up those guns'
  floor is halved (HEV wins 97 % of the pairs of tier 1–2 guns, 66 % before;
  median health left 10.7, was 3). Guns of tier 5 and below against DNS and FAU
  likewise: three tier 4 guns take DNS in 21 s at the quickest (11 before).
  New server key `balanceArmorTierFloor` (`2:4:0.5 5:8:0.5`; `off`: as before).
- The cheap round no longer beats the dear one on armour of tier 4 and up:
  AMAT's SP and JHP (BJ 5 s → 7.5 and 6.2) while its AP is quicker (HEV
  2.5 s → 1.2), SPAS-12's and the Auto Shotgun's black powder slugs, the Uzi
  akimbo's FMJ, JHP and SP (HEV 1.5 s → 2.9); the explosive shells of both
  shotguns take HEV in 2 s (3). Against no armour and armour of tier 3 and below
  they are as they were; AMAT's one-shot head stays.
- The .357 AP round of Light Revolver and Atlas and the Maresleg akimbo's
  phosphorus shells no longer take HEV in a second (2.2, 2.8 and 2 s).
- Little Macintosh's AP round came down to its tier's median on heavy armour
  (BJ 3.5 s → 7.2, RPA 7.2 → 9.3); HEV as before.
- Swapping DNS for FAU no longer hands out a fresh pool: the pool and its
  window carry over to the new suit. New server key `balance.immune.shared`
  (`true`; `false`: a pool per suit, as before).
- Super stimpaks no longer stack: a second one while the first heals keeps the
  bigger heal, not the sum. New server key `balance.healStack`
  (`false`; `true`: as before).
- The T-51 chestplate, leggings and boots take a basic circuit, as the helmet
  does (the chestplate one titanium armour plate less, for room), so three T-51 parts with a steel helmet are no longer tier 1 work for
  85–95 % of the full suit's protection (with `balanceRecipes`).
- The balance stand covers the GL-54's fragment grenades, Quadro's rockets and
  the Charge Thrower's mortars, and fights of one against three.

## X891

- A player hit by an NTM gun's bullet, pellet or beam is no longer thrown back
  or stopped short, and their view no longer tilts with the hit, so a hit does
  not throw off the aim. The damage, the hurt sound and the red of the hit
  stay; blasts, melee hits, mobs, arrows and falls push and tilt as before.
  Server keys `gunHitKnockback` and `gunHitTilt` (both `false`) give them back.

## X885

- The 20 mm grenades' coloured nose is shaded anew: even bands along the round, in the same six shades.

## X884

- Atlas beats the Break-Action Revolver on the .357 AP round: it took tier 6 and
  up armour in half again the time of the tier 2 gun (CMB 20 s against 14, DNS
  116 against 74). Atlas's AP now matches it on bismuth and beats it elsewhere
  (CMB 12 s, DNS 62), and the Break-Action Revolver's AP round loses a little
  on armour of tier 6 and up (DNS 74 → 85 s); against tier 4 and below it is as
  it was.
- The pedestal guns are no weaker than the median plain gun of their tier: Little
  Macintosh's AP round (bismuth 14 s → 3.5, DNS 125 → 42), Broken's explosive
  shells (CMB 11 s → 5, DNS 65 → 46), Day And Night's AP round (DNS 80 s → 63,
  never ahead of Atlas) and Hangman's AP round (CMB 6.3 s → 3.5, DNS 32 → 26).
- Aberrator's rounds pierce more: it takes FAU in 5.8 s like DNS (7.1 before)
  and euphemium in 17 s (19), as Eyes of the Tempest.
- The Lever Action Rifle's JHP and Lincoln's Repeater's FMJ rounds: a slight
  change of their damage keeps them from the pool's worst timing on DNS (3300 s
  and 3500 s → 1100 and 1500, like their gun's other rounds).
- Trenchmaster holds a weapon's blast like HEV, as NTM gives it HEV's explosion
  resistance: it took MK108's, Panzerschreck's and the rockets' direct hits in
  one hit fewer than HEV. It now takes blasts by tier 4's rules (weak blasts
  too: a gunpowder grenade 4 hits, not 9). New server key
  `balanceBlastTrenchTier` (`6`: as before).

## X881

- Suppression under fire (`suppression`, server, on): someone else's bullet
  passing within 3.5 blocks of the head, a bullet stopping within 8 blocks (in
  a wall, a creature, water) and a blast within 14 blocks (NTM's grenades,
  rounds, rockets and bombs, TNT, creepers, not a wind charge's gust; a big
  blast farther, up to 42) fill a scale of 0 to 100. It climbs in a few ticks,
  a burst of shots counts up to 3.3 times more, and it falls from full to none
  in 5 s. Under it the screen darkens, more at the edges: at a full scale 40 %
  at the edges and 22 % in the middle, never more. The other sounds go down, the world's to
  40 %, shots, blasts and bullets only to 79 %, so the fight is still heard.
  The player's own bullets and blasts, lasers and beams do not count; nor does a
  grenade before it goes off. Each bullet is counted once, on the player's own
  game, from the same pass the whizz sound plays on.
- A jolt of the view at each of those events, as in Squad
  (`suppressionFlinch`, server, off): 0.11 to 0.66 degrees to a random side,
  and the view stays there; not while riding.
- Server keys: the forces (`suppressionPass`, `suppressionImpact`,
  `suppressionBlast`), the ranges (`suppressionPassRange`,
  `suppressionImpactRange`, `suppressionBlastRange`), the fall
  (`suppressionRecovery`), the most darkening and muffling the players get
  (`suppressionDarken`, `suppressionMuffle`), the jolt's size
  (`suppressionFlinchDeg`). Client keys: `suppressionDarken` and
  `suppressionMuffle`, 0 to 1, take less for oneself.

## X877

- Tier 4 armour (HEV, Security, the steam suit, AJR) is never weaker than T-51b:
  HEV, Security and the steam suit let small rounds through more than T-51b in
  about one cell of six (a .22 SMG's JHP: 33 clicks on HEV, 39 on T-51b). They
  now take the formula at T-51b's tier or above, HEV's rating 46 → 60, AJR's
  38 → 55; tier 4 needs a third more clicks than T-51b at the median. The best
  tier 3 gun takes them in 4 s, not 3. The FBI agent wears Security: the Heavy
  Revolver's express rounds take it in about 16 clicks (13 to 15 before).
- Starmetal is a combat set of tier 6 with bismuth's numbers: it held like
  T-51b (a Target Pistol's FMJ: 5 clicks, bismuth 35). `balanceS.starmetal` `0`
  makes it NTM's joke set again, as X875.
- The paired guns fire two barrels a click and now do about 1.85 times the
  single gun's damage a click (Uzis and Uzi, Dual Miniguns and Minigun, the
  Lever Action Shotguns and the Lever Action Shotgun were within 3 to 16 %, the
  Target Pistols already twice), short of the next tier's best gun: the Dual
  Miniguns' AP and DU rounds stay as they were, at the edge of their tier, and
  the Lever Action Shotguns gain 1.4 to 1.7 times on most shells (their black
  powder shells as before) and their explosive shells catch up with the single
  gun's.
- A later gun on the same round beats the earlier one by about a third:
  Protège over the Heavy Revolver, the Comically Long Pistol over the Uzi, South
  Star over the Minigun against DNS, FAU and euphemium (its AP and DU only by a
  tenth to a fifth: more would beat tier 7 against CMB).
- The 7.62 mm and .50 BMG high-explosive rounds count as armour-piercing ones
  against armour of tier 4 and up (the carbine's, Ma Deuce's, Minigun's and
  South Star's): they took two to three times their gun's AP time on heavy
  armour, South Star's 8.1 s on CMB against 3.4. Subtlety's soft-point and
  Penance's hollow-point rounds now follow their sister's (Subtlety SP on DNS
  37 s → 22, Penance 20).
- Weak blasts on armour up to tier 4: the floor of `balanceBlastLowFloor` lifts
  only the share of the blast that passes, its flesh stays the blast's own, so
  no set of tier 4 holds a weak blast worse than T-51b (Congo Lake's splash at
  3 blocks: 5 hits on tier 4, 6 on T-51b; now 7 and 7). `balanceBlastLowFloor`
  0.83 → 0.9 keeps a gunpowder grenade at 4 hits on tier 4. New server key
  `balanceBlastFloorShare` (false, with `balanceBlastLowFloor` 0.83: as X875).
- New server key `balance.immune.refillPause` (5 s, as before): how long a
  worn-down DNS, FAU or euphemium pool waits after the last hit before it comes
  back.

## X875

- Armour-piercing and uranium rounds, slugs, explosive and flechette shells beat
  the same gun's soft rounds against armour of tier 4 and up: against heavy
  armour the formula let every round through by the same least share of its
  flesh damage, so the softer round, with more of it, won (the Ma Deuce's AP
  needed 14.8 s on CMB, its JHP 6.5; a 12 gauge slug three times the
  buckshot's). The gun table's pairs now give that least share a floor
  (`floor=`, `from=`): a piercing round about the gun's best time of before, its
  soft rounds two thirds as fast from tier 6 (the Ma Deuce: AP 6.6 s on CMB,
  JHP 9.9). Unarmoured and up to tier 3 nothing changes, and each tier's best
  gun stays where it was. New server key `balanceArmorFloor` (false: as X873).
- The double Lever Action Shotguns no longer beat the miniguns of tier 5 and the
  Shredder against armour of tier 6 and up (BJ 3 s → 5, CMB 5 → 11); up to
  tier 4 as before. The Shredder reaches its tier's middle (BJ 4 s → 2.5, CMB
  6 → 4). The Pepperbox's stones do a fifth against tier 6 and up (BJ 110 s →
  560): a starting gun is no answer to an 80-hour set.
- DNS, FAU and euphemium: a shot, beam or blast in the window keeps it open for
  10 s more (melee does not), so a gun too weak to kill in the 15 s no longer pays a whole minute of
  invulnerability and a new pool for it while it keeps hitting. No gun took
  between 60 and 120 s on DNS, and one gun could take DNS in 45 s and FAU in
  134; now the times run on without that gap and a gun's FAU and DNS times are
  within 20 % of each other. Weak guns get there sooner: a tier 2 gun takes DNS
  in about 90 s (was 206). New server key `balance.immune.hold` (0: as X873).
- Weak blasts on tier 4 armour: a gunpowder or DEMO grenade and a 40 mm shell's
  splash took 9 to 15 hits on HEV, AJR, Security and the steam suit (3 with no
  armour); now 3 to 4, the HE grenade's 3 as it was. A blast's far edge (under
  7 raw damage) gets less of this, so armour still takes some off it. New server key
  `balanceBlastLowFloor` (0: as X873).
- Panzerschreck's rocket hits RPA, CMB and Schrabidium as a 40 mm shell of its
  damage (12 hits, was 18): the weaker power on tier 7 is for the big rockets
  only. New server key `balanceBlastRocketHighFrom` (0: as X873).

## X873

- An explosive bullet's blast (HE rounds of rifles, the AMAT's HE, explosive
  buckshot) is taken by the armour formula and the damage cap at any damage.
  NTM deals such a blast with the bullet's whole damage, so a heavy gun's HE
  round came past the limit meant for mines and charges and killed any armour
  from full health with one shot; now the AMAT Penance's HE round takes 9.7
  off RPA, the MAS-36's 7.3. New server key `balanceBulletBlastCap` (false:
  as X865).
- The Tesla Cannon's click is capped like any other: its blast (electric, the
  beam's damage again around the target) went past the damage cap, so one click
  took 20 HP through RPA and BJ. Now a click takes at most 60 % of the health,
  blast and beam together. New server key `damageCapAnyBlast` (false: as X865).
- The IR capacitors of the laser guns and Lacunae fire beams the armour takes
  as beams (energy), under the damage cap. They are NTM's fire class, so they
  went past the balance's armour, the cap and DNS's pool: a laser pistol IR
  shot killed an unarmoured player at once and Lacunae IR took 10.4 off BJ
  past its rating. New server key `balanceIrBeams` (false: as X865).
- Armour holds longer against guns of lower tiers, so the hours put into a set
  pay: the ratings and their tiers (the curve's steepness) are now diesel 13/3,
  T-51 23/4, the steam suit 66/3, HEV 46/3, AJR 38/4, Security 56/3,
  Trenchmaster 72/4, bismuth 96/5, BJ and NCRPA 120/5, CMB and Schrabidium
  159/6, RPA 158/6, FAU 168/7. A tier 2 gun now needs about 6 s on AJR (was
  2.9), 12 s on BJ (5.1) and 16 s on RPA (8.6). The server keys
  `balanceS.<set>` and the new `balanceTau.<set>` hold them (X865's numbers: as
  X865).
- DNS, FAU and euphemium are no longer all but invulnerable to the guns of
  tiers 5 to 8: their pools are 40, 45 and 150 (were 500, 450 and 2000), the
  owner's choice. The best gun of tier 7 now takes DNS in about 10 s (was 82),
  of tier 8 in 6 s; guns of tiers 2 and 3 still need minutes. Keys
  `balance.immune.dns.pool`, `balance.immune.fau.pool`, `balance.immune.pool`:
  500, 450 and 2000 as X865, 120, 100 and 400 a middle way.
- The guns' table is evened out by tier: the lever rifles and revolvers of
  tiers 2 to 4 (Henry, Lincoln, the light and heavy revolvers, the Mare's Leg,
  the double barrel) hit harder, as do the guns of tier 6; Lacunae (x0.3), the
  Sexy (x0.55) and the AM180 (x0.8-0.9) less, the Penance's black round more.
  Each tier now kills DNS faster than the one below it.
- The Tesla Cannon's electric hit counts 1.5 times on a powered chestplate, not
  NTM's 5 times: one click took 12 of 20 HP through BJ and RPA. New server key
  `balanceElectricPowered` (5: as X865).
- Weapons' blasts are a little stronger on the high sets (`balanceBlastPower`
  3, was 2.7) and weaker on the sets of tier 4 and below
  (`balanceBlastPowerLow` 1.7: a hand grenade needs 3-4 hits on AJR, not 2),
  and a rocket's direct hit on RPA, CMB and Schrabidium takes
  `balanceBlastPowerRocketHigh` 2.2: the Missile Launcher needs 6 rockets on
  RPA (was 3), 13 on FAU and 15 on DNS. All three keys 2.7: as X865.

## X867

- Melee is NTM's again on armour: the balance's armour formula and its flesh
  squeeze take only NTM's shots (bullets, beams and their blasts) and weapons'
  blasts. A sword, the Crucible, a chainsaw, a mob's blow, vanilla's arrows,
  fire, lava, electricity, gas and the rest take NTM's numbers on every set, as
  before the balance; the Crucible no longer does one heart to a player in
  Security. DNS's and FAU's pool no longer takes melee either (euphemium's
  still does, drained as before). The combat sets' vanilla armour, which the
  balance takes off for the formula's hits, is back for these hits, so they
  meet the same armour as in NTM; the GL-54's acid eats it too.
- The flamethrowers (the Flamethrower, Topaz, Daybreaker) hit and set on fire
  as in NTM. NTM's FBI agent is immune to fire by NTM's own design, so flames
  do nothing to it there too; Daybreaker's blasts still hurt it.

## X865

- DNS and FAU get euphemium's invulnerability with a pool, with their own
  numbers: while the pool holds, bullets, beams and blasts on the full
  charged set are called off, each taking from the pool what it would have
  done to the set (DNS's rating 186, FAU's 148). The emptied pool opens 15 s
  of hits by the set's rating, then the pool fills back over 60 s, the set
  invulnerable meanwhile. Pools: DNS 500, FAU 450. A Tau now takes about
  50 s to kill DNS (26 s FAU), Lacunae 12 s (7 s), an AMAT SM round 4.6 min
  (2.6 min). Other damage is NTM's as before; the tomb's radon gas takes the
  pool off. New server keys `balance.immune.dns.mode` / `.pool` / `.window`
  / `.recover` and `balance.immune.fau.*` (`mode`: `pool`; `formula`: by the
  rating only, as in X858; DNS also `ntm`). `balance.immune.dns` is now
  `balance.immune.dns.mode` (an old `ntm` still counts).
- Pepperbox, 13th-century tech, is a gun against no armour and steel only:
  stone x1.59 (was x2.65), flint x1.0 (x1.68), iron x0.9 (x1.5), stone shot
  x2.5 (x10.3). Against steel 5 clicks (was 3), the diesel suit 7 (3), T-51b
  11 (5), HEV 13 (7); the stone shot against the diesel suit 8 clicks (was 2).
- A pellet of a round of several (buckshot, Pepperbox's stone shot) never
  takes a sniper's head cap, whatever its gun waits between shots: the stone
  shot no longer kills with one click from full health.
- Grenades, 40 mm shells and rockets hit armour harder: the combat sets take
  a weapon's blast as 2.7 times its damage, never more than it does with no
  armour; the damage cap is as before. A Panzerschreck rocket on target now
  takes 3 hits on BJ (was 11), 5 on RPA (20), 14 on FAU (32), 23 on DNS (47);
  the Missile Launcher's 4 on FAU (21) and 6 on DNS (33); a HE grenade at a
  block 10 on FAU (28). Nothing takes longer than before; no armour is as
  before. Explosive bullets' blasts are not touched. New server key
  `balanceBlastPower` (`2.7`; `1`: as before).
## X859

- Five new gun mechanisms, the parts NTM's guns and their B-side upgrades
  are built with: steel, high-speed steel, desh, ferrouranium and technetium
  steel (NTM had gunmetal, weapon steel and saturnite). They look like NTM's,
  in the colours of the material's other gun parts, are cast in the
  crucible's mechanism mold, melt back down, and are in JEI and NTM's parts
  tab.
- A mechanism stands for a gun's tier, as in NTM, now on eight steps:
  steel, gunmetal, high-speed steel (crucible tier), desh, ferrouranium,
  weapon steel (desh tier), technetium steel, saturnite. A gun takes the step
  of its tier and material: steel for the flare gun, Grease Gun, light
  revolver, Maresleg and Charge Thrower; high-speed steel for AM180, Congo
  Lake, the flamethrower and Liberator; desh for the carbine, heavy revolver,
  Panzerschreck, SPAS-12 and UZI (all were gunmetal); ferrouranium for AMAT,
  the auto-shotgun, M2 and Quadro; technetium steel for Lag, the minigun, the
  missile launcher and Tesla Cannon (all were weapon steel; Tesla Cannon with
  the balance's recipe takes saturnite).
- A B-side takes the step above its base gun's, at its own tier: Atlas,
  Henry Lincoln, the Maresleg pair and Topaz desh, the UZI pair ferrouranium
  (were weapon steel). Gunmetal and weapon steel mechanisms no longer fit
  these recipes. New server key `gunMechanismRecipes` (`true`; off: NTM's
  recipes).

## X858

- The damage cap no longer holds NTM's blasts of more than 60 raw damage (a
  mine, C4, TNT), as it already did not hold nuclear ones
  (`balanceExplRawLimit`, 60, now counts for the cap too). Grenades, 40 mm
  shells and rockets stay capped: a click takes at most 60 % of a player's
  health, 80 % on the head.
- New server keys: `damageCapBlasts` (`true`; off: no NTM blast is capped) and
  `balanceArmorBlasts` (`true`; off: blasts go through NTM's threshold and
  resistance, bullets and beams still through the balance's formula).
- VATS and thermal vision show players as before: VATS none, thermal vision
  none but FAU's, DNS's and AMAT Penance's sight within 16 blocks and in
  sight. The decision is now in one place shared by the client and the
  server's checks.

## X857

- DNS is no longer invulnerable: it takes bullets, beams and blasts by its
  armour rating (186), like the rest of the sets (was: every hit but a blast
  called off, blasts down to a thousandth). A Tau now takes 10 clicks on it,
  an AMAT SM round 7, Lacunae 9 (were 0 for the AMAT and Lacunae; NTM's beams
  got through it already). New server key `balance.immune.dns` (`formula`;
  `ntm`: as before).
- The balance's gun table gives every ammo of a gun in it a pair of its
  own (256 pairs, were 74): the double barrel's buckshot and shrapnel x2.37,
  slugs x0.96 (all took DU's x0.76), Autoshotgun's buckshot x0.96-1.34 (took
  the explosive round's x0.175), Heretic's buckshot x0.48 and slugs x0.16
  (were all x0.2). An ammo still with no pair takes only a pair of its own
  family (soft, piercing, blast; buckshot and slugs apart), else hits as
  before the balance; never another family's multiplier.
- On a server, players' tooltips and guns' receivers (pause, magazine, auto
  fire) go by the server's gun table (`config/ntm-gunfeel-balance-guns.tsv`
  or the mod's), not by a file of their own.
- Power armour takes up recoil by its tier, as it does spread: T-51b 0.8,
  HEV, AJR, AJRO, Envsuit and Trenchmaster 0.7, BJ and NCRPA 0.6, RPA 0.5,
  FAU and DNS 0.4 (was 0.5 for every set); Security, the steam and diesel
  suits none. It is the server's (`powerArmorRecoil.<set>`,
  `powerArmorRecoil` for a chestplate with no line); the players' own
  `powerArmorRecoil` setting is gone. The old one-number `powerArmorSpread`
  and `powerArmorRecoil` in a server's file now count only for a power
  armour chestplate with no line of its own: the tiers count on servers
  with an old file too.
- The shield is at most 16 (the mod and three flasks; was 30). The shield
  takes a hit's raw damage before the armour, and what it takes counts to
  the damage cap first, so with Med-X and a super stimpak a fight takes about
  twice as long at most (was up to 2.7 times).
- NTM's FBI agents have 10 vanilla armour points of their own again, as they
  had with Security's before the combat sets lost theirs: a .44 Heavy
  Revolver takes 10-11 clicks on one again, not 5. New server key
  `balance.fbiArmor` (0: none).

## X847

- A shot on the armour an FBI agent or a test dummy wears now hits the part
  under that piece: a helmet is the head, a chestplate the body, a sleeve, a
  legging or a boot the limb. Their helmet stands 1 to 1.5 pixels out of the
  8-pixel head, and a shot on it counted as a body shot, without the head
  multiplier; a shot on a sleeve, a legging or a boot counted as a body shot
  instead of a limb. The server and the shooter's client count it the same.
  A glass block on the head is still no part of the hitbox: shots go through
  it to the head.
- Other mobs and players: under a helmet the head zone now takes in the
  helmet's outer layer as well (1.5 pixels out of the head, was 1), and the
  head and limb zones under armour reach a tenth of a pixel past it, so a
  shot on the very face of a piece is not lost to rounding.

## X846

- An AI mob that falls dead now lies on the ground. Its fall used to end with
  the knees folded under it and the trunk short of flat, so its back and
  hips stayed up to half a block above the ground. Now, as the fall ends, it
  settles flat on its back: legs straight, arms by its sides a little out,
  the head face up and turned a little aside. On the way down it stays on
  the ground too.
- AI mobs move differently in a fight and out of one. Running, they sprint
  leaned into it with the gun held across the chest, not jogging with it
  held out. Walking in a fight they lean a little forward with the gun
  ready. Calm, they stroll with the gun lowered in front of them. They
  switch over half a second as a fight starts or ends.
- An AI mob behind low cover now fires from a crouch where its line of fire
  from there is clear (over cover lower than its gun, through a gap), a
  smaller mark. Where the cover would stop its shots it still rises to fire
  and keeps low between bursts. It never fires into its own cover.

## X843

- What a player carries on top of the armour makes a fight about twice as
  long at most, not ten times. The shield mod (australium III) gives 10
  shield (was 25), a shield flask 2 (was 5), and the shield is at most 30 in
  all (was up to 125); no more flasks can be drunk once they alone are at
  30. The shield comes back 5 s after the last hit (was 3 s) and slower:
  10 in the next 5 s (was 25). Med-X gives Resistance I for 60 s (was III
  for 4 minutes) and needs two plastic plates and two quartz (was four
  quartz). The super stimpak heals its 25 HP over 12 s instead of at once.
  An armour insert takes at most 10 % off a bullet (SAPI, ESAPI, XSAPI, DU
  and ghiorsium took 15–25 %); their damage and blast shares are as
  before. The shield mod and an insert share the chestplate's socket, so a
  kit has one of them. New server keys `balance.shield` (`.mod`, `.flask`,
  `.cap`, `.delay`, `.rate`), `balance.medx`, `balance.superStimpak`,
  `balance.inserts` (`balance.inserts.bullets` 0.9); off: as before.
- NTM's combat armour sets of the balance's table have no vanilla armour
  points and toughness any more: vanilla's armour came on top of the
  armour formula (a player in steel or HEV had 20 points against a
  zombie's claws and bullets alike). FBI agents in Security armour keep
  their own 20. The joke sets (starmetal, the dineutronium ingot set) and
  the rest keep theirs. New server key `balance.vanilla0`; a worn set
  changes when a piece is put on again.
- Euphemium is still invulnerable, but bullets, beams and blasts wear down
  a pool of 2000: each takes from it what it would do to a DNS-class set.
  The emptied pool leaves the wearer open to every hit for 15 s, by the
  set's armour rating, without its Regeneration and Resistance 128 ("Euphemium
  overwhelmed" over the hotbar); then the pool fills back over 60 s, the set
  invulnerable meanwhile. A worn-down pool comes back as fast after 5 s
  without a hit. The tomb's radon gas takes the invulnerability off while
  the wearer is in it, its radiation too. New server keys
  `balance.immune.mode` (`pool`; `ntm`: as before), `balance.immune.pool`,
  `balance.immune.window` and `balance.immune.recover` (seconds).
- AMAT Penance's thermal sight shows players as FAU and DNS thermal vision
  does: only within 16 blocks and only those in its line of sight (was:
  every player through walls within 64 blocks). Mobs as before. The same
  server keys `abilities.esp.players` (`off`: no players; `all`: as before)
  and `abilities.esp.playerRange`.

## X840

- Fixed: the client could crash while loading ("Components not bound yet") with the guns' balance table on; the table now waits until the game's items are ready.

## X838

- Armour sets' abilities fit their tier. Speed and Jump Boost: up to tier 6
  at most Speed II and Jump Boost I, from tier 7 Speed III and Jump Boost
  II. The diesel suit goes from Speed III and Jump Boost III to II and I,
  Trenchmaster's Jump Boost from II to I, DNS's from III to II. Strength is
  at most II on every set: NCRPA and RPA had IV, CMB V, DNS X, Trenchmaster
  and schrabidium III. Bismuth is a tier 6 set now, not a joke: Speed III,
  Jump Boost II, Regeneration I, night vision and two dashes (were VII, VII,
  II and three). VATS and the sets' thermal vision no longer show players:
  mobs, bosses, items and experience as before. Only FAU and DNS (tier 8)
  see players with thermal vision, within 16 blocks and only those in their
  line of sight. A gun's thermal sight is as it was. Haste, fire
  resistance, step height, hard landing and immunity are untouched. New
  server keys `abilities.mobilityCap`, `abilities.strengthCap` (2; 0:
  NTM's), `abilities.bismuth`, `abilities.esp.players` (`tier8`; `off`: no
  players; `all`: NTM's) and `abilities.esp.playerRange` (16).

## X834

- AI mobs call out what they do: "Contact!" on a new enemy, "Frag out!" and
  "Smoke out!" on a throw, "Grenade!" on one coming, "Reloading!",
  "Falling back!", "Flanking!", "Moving!" on a run to new cover, "I'm hit!",
  and "Man down!" from the nearest of a fallen mob's side. Each line has two
  takes, over a radio, in one of three men's voices; a mob keeps its own
  voice, a little higher or lower than the next. Not too often: a mob speaks
  at most once in 4 seconds, no one within 8 blocks of one who spoke in the
  last 1.5 seconds, and the same line not again within 16 blocks for 10 seconds;
  "Frag out!", "Grenade!" and "Man down!" are said over the first two, and
  again near after 3 seconds; and a squad says two lines a second at most. Heard up to 32 blocks, as
  the game's voice sounds (the Voice/Speech slider); AI mobs do not hear
  them. New server key `aiVoices` (on); new client settings `aiVoice` (on)
  and `aiVoiceVolume` (1). The lines were made with the Kokoro-82M speech
  model (Apache 2.0, see `third-party/ai-voices.txt`).
- An AI mob down behind its cover after a near grenade throw no longer gets
  up and runs, when shot or with an enemy in sight within 6 blocks, once its
  grenade is less than 2 seconds from going off: it could not get out of the
  blast by then, and took some of it.
- An AI mob throws a near grenade only from where its cover keeps all of it
  off the blast as NTM works it out: from the burst and from the points a
  block round it the blast reaches out from, and with its head and feet
  either way it may lie. Lying at a wall's end it took some of its own blast.

## X831

- Power armour holds the gun steadier the later its tier: what moving,
  jumping and a held-down burst add to the spread counts T-51b x0.8; HEV,
  AJR, AJRO and Envsuit x0.7; BJ and NCRPA x0.6; RPA x0.5; FAU and DNS x0.4
  (all were x0.5). Trenchmaster now counts too, x0.7. Security, the steam
  suit and the diesel suit give no bonus, as before. New server keys
  `powerArmorSpread.<set>`; `powerArmorSpread` (0.5) is for a power armour
  chestplate with no line of its own, and a server that sets it in its file
  keeps one number for every set it gives no line, as before. Recoil in
  power armour is the players' own setting and stays as it was.
- New recipes for later tiers. The diesel suit needs oil: its helmet takes
  an integrated circuit board instead of the analog one, its chestplate two
  of them and two cast high-speed steel plates (one red wool less), its
  leggings two cast high-speed steel plates more; the boots are as they
  were. Tesla Cannon: a quantum processing unit instead of the military
  grade circuit board and two welded combine steel plates instead of two of
  the three copper coils. Lasrifle: a quantum processing unit instead of the
  versatile circuit board and schraranium crystals instead of redstone
  crystals. Tau: a quantum processing unit instead of the versatile circuit
  board, two 4000K coils instead of the copper tori and two dineutronium
  compound plates instead of the copper pipe. The recipe book and JEI show
  only the new recipe. Coilgun and Liberator are as they were. New server
  key `balanceRecipes` (on; off: NTM's recipes, after `/reload` or a
  restart).

## X830

- NTM's combat armour sets take bullets, beams and blasts by one formula
  instead of NTM's threshold and resistance. A set has a rating; a hit
  whose damage (with its ammo's threshold negation and armour piercing) is
  well under the rating loses most of itself, one well over it gets nearly
  all through. No hit is ever stopped to 0, so every gun can hurt every set
  in the table, and none gets all through. One hit's damage on a set is also
  squeezed past 8 towards 14. Beams and lasers meet three quarters of the
  rating, blasts the full one (sets that had no blast armour, as steel,
  titanium, cobalt and schrabidium, now have it). The ratings, from the
  balance model: steel, cobalt, Advanced Alloy, titanium and Taurun 8,
  diesel suit 13, T-51b 18, steam suit and HEV 26, AJR and AJRO 25,
  Security 22, Trenchmaster and bismuth 48, BJ 63, NCRPA 54, CMB and
  schrabidium 91, RPA 90, FAU 148, DNS 186, euphemium 280. So an AMAT .50 SM
  round now kills FAU in 4 body shots (was 12, with the damage cap), the
  laser pistol takes 8 shots to kill BJ (was 2), MAS-36 DU kills RPA in 5
  (as before, from 4.4 to 4.9 a hit). A piece alone protects its share of
  the body (helmet 3, chestplate 8, leggings 6, boots 3 of 20) with its
  set's numbers. The sets protect whoever wears them, FBI agents too. Fire,
  radiation, gas, nuclear blasts and falling are as before, and so are
  euphemium's and DNS's invulnerability, Trenchmaster's blocks and the
  sets' abilities. Starmetal and the dineutronium ingot set keep NTM's
  numbers, but a hit keeps at least 18 % of itself. The GL-54's acid
  corrodes the rating as it corroded NTM's numbers.
  New server keys `balanceArmor` (on), `balanceS.<set>` (the ratings),
  `balanceEnShare` (0.75), `balanceFleshKnee` (8), `balanceFleshMax` (14),
  `balanceExplRawLimit` (60), `balanceRoflFloor` (0.18).
- Guns' damage goes with the armour: each gun and ammo pair has a
  multiplier from the balance model, fitted so that a gun kills armour of its
  own tier in about the same time at its range (shotguns 7 blocks, pistols
  and SMGs 12, rifles and machine guns 20, snipers 30). Guns that miss a lot
  at their range hit harder (Maresleg buckshot x4.2, Greasegun x2.65),
  strong ones less (the auto shotgun's explosive shells x0.175, carbine HE
  x0.54). An ammo with no pair of its own takes the gun's nearest pair of
  the same kind (piercing, blast or soft). The table is one line a pair, and
  a server can put its own `config/ntm-gunfeel-balance-guns.tsv` in its
  place. The pedestal guns get at most x2, +50 threshold negation and +0.5
  piercing; the Tau x2 and +30. Some guns change: Heretic is a
  semi-automatic heavy shotgun (one shot in 12 ticks, 24 rounds, x0.2);
  AM180 fires one in 3 ticks with 60 rounds at x3.5; the AMAT Penance fires
  one in 15 ticks, the Subtlety and MAS-36 one in 17 (were 25); Morning
  Glory's base damage is 40 (was 20). Liberator and Coilgun are as they
  were. A gun's tooltip shows the new damage. The multipliers count for
  everyone's shots, NTM's mobs too, so FBI agents with a Greasegun or an
  AM180 hit harder. New server key `balanceGuns` (on).

## X827

- A damage cap against players: one click of a gun takes at most 60 % of a
  player's max health (80 % on the head), and one shooter takes at most 80 %
  in any 14 ticks. A click is everything one shooter fires in one tick: a
  shotgun's pellets, SPAS-12's double shot, both barrels of the double
  barrel, Liberator's four rounds, both akimbo guns; pellets that land a tick
  later still count with their click. So SPAS-12's double slug, the double
  barrel's DU and explosive shells no longer kill an HEV suit with one click,
  and a minigun takes no less than 0.7 s to kill. Guns that wait 25 ticks or
  more between shots (AMAT, MAS-36, Penance, Subtlety) still kill with one
  head shot; on the body they take two. Health, absorption and NTM's shield
  count together, and the shield's share over the cap is given back.
  Whatever a shooter fires is capped (bullets, beams, grenades, arrows, and
  NTM's blasts); nuclear blasts, digamma, fire, falling, radiation, gas,
  melee and vanilla blasts (a creeper's) are not. The shooter can be a player,
  an AI bot or any mob. Each shooter has their own cap on each target, so two
  shooters still kill twice as fast. A head shot right after other hits from
  the same shooter is still held by the 14-tick share. Mobs and AI bots as
  targets are capped only with `damageCapMobs` on.
  New server keys `damageCap` (on), `damageCapClick` (0.6), `damageCapHead`
  (0.8), `damageCapWindow` (0.8), `damageCapWindowTicks` (14),
  `damageCapSniperDelay` (25), `damageCapSniperHead` (1.0), `damageCapMobs`
  (off).

## X825

- An AI mob walking backwards or sideways, as when it falls back firing
  ahead, gets up a step one block high. Before, its jump pushed it the way it
  faced, off the step, and it jumped on the spot without end. Should it still
  jump three times without getting anywhere, it turns to its path for a
  moment, holds its fire and looks for another way.
- In the AI check a mob aiming ahead walks back, aside, and back and aside up
  a ledge a block high: it is up at its first jump, within 3 seconds.

## X823

- AI soldiers throw frags more often. Besides a target that went behind
  cover 15 to 26 blocks off, they throw at one that sits behind low cover in
  sight (only its head and chest show and it stays put), and at a lost one
  for as long as they wait for it (it was 7 seconds at most). They throw from
  8 blocks off as well, a softer lob, but only from where blocks would hide
  all of the thrower from the burst once it is down: right after the throw it
  lies down behind its cover (crouches where lying down is off) and stays
  there till the grenade has gone off, without running from it. It is up at
  once if it is shot or an enemy shows within 6 blocks. Its own side is kept
  out of the blast's reach as before.
- AI soldiers throw smoke less often: one smoke grenade per squad every 35
  seconds (it was every 15).
- In the AI check a foe 10 blocks off behind a wall gets a grenade within a
  block of it each time; the thrower lies down behind its wall and is never
  hit. In the battles and duels no AI mob was hit by its own grenade.

## X822

- A player's bullet hits what that player's game saw it hit. Your game tests
  your own bullet against the models it draws and tells the server which mob
  or player it hit, where and on which part; the server takes that hit, with
  the same damage, armour, part multiplier and piercing as before, and no
  longer tests the bullet against its own, older view of the models. Running
  and jumping mobs in a crowd are no longer missed where you see a hit.
  The server checks only for mistakes, not for cheats: the target is alive
  and in reach, the bullet is yours and still flies, one hit per target per
  bullet (each buckshot pellet is its own bullet). A bullet near mobs waits
  for the report up to your ping and 3 ticks; AI bots' bullets and late
  reports go by the server's view, as before. New server key `clientHits`
  (on by default); off, every bullet goes by the server's view.
  Sixty FBI agents in a crowd, 3000 carbine rounds at each kind of movement
  (standing, running across or at the shooter, jumping, leaning out,
  crawling): every hit the shooter's game saw is the server's, on the same
  part, and the server hit nothing else. The bullets past the crowd went
  through the same wool and stopped in the same wall on both sides, all
  but a few in a hundred that grazed the floor: the shooter's tracer
  bounced them on, the server's bullet stopped in the floor.

## X820

- AI mobs' legs: the foot a bot stands on stays on the ground, its shin
  upright, walking, running, sideways and crouched; a stop ends the step
  instead of freezing the legs mid-stride, and a quick dash aside steps aside
  from its start rather than playing the walk ahead. A crouched walk no longer
  swings the thighs up level.

## X819
- Mob AI: a shaken bot falling back first throws a smoke grenade half the way
  to the enemy, one of its squad at a time: the GL-54 smoke grenade's cloud,
  which AI mobs do not see through either. New server setting `aiSmokes`
  (smoke grenades a bot carries, 1 by default, 0 to 4; 0: none); needs smoke
  on (`smoke`).

## X817

- Shots hit a mob in a crowd again where the shooter sees it. The server
  moves each mob's model on from the shooter's last view of it, and took a
  mob standing on the ground for falling (the game's leftover gravity), so
  between a crowd's views, which come every few ticks, its model sank up to
  0.4 block and shots at the head and arms went over it. Now a mob goes on
  by its last step: none standing, the whole step walking (the stored motion
  is after the ground's friction), its rise or fall in the air, its mount's
  way riding.
  Sixty FBI agents in a crowd, a player's shots at the head and arms
  (bodies and legs were hit before too):
  - standing, leaning out, crawling: 99–100% hits (63–94% before), with or
    without armour;
  - running at the shooter: 90–94% (69% before); jumping: 57–69% (46–54%);
    running across: 58% at the head (39%). A mob running across or jumping
    is still missed some: the model the server finds is about half a block
    from where the shooter sees it, as before.

## X816

- A blast by the body's parts costs the server about what NTM's own does:
  which parts count, their marks and shares are worked out once for each pose
  a creature takes (a client sends a new one every dozen ticks or so in a
  crowd), not for each blast, and a creature NTM's blast sees whole needs no
  rays from its parts. One MK108 shell a tick into 100 FBI agents stepping
  about: 15.3 ms a shell (NTM's 14.8, 20.2 before); an HE grenade into 50:
  6.4 ms (NTM's 6.2, 10.1 before). The damage in the open and behind a wall
  is as before, within 0.15.

- A blast by the body's parts takes about NTM's damage again from a creature
  in the open: each part's multiplier is divided by the creature's mean one
  (its parts' shares times their multipliers). An HE grenade 1–3 blocks away
  does 2–6% less than NTM's to a standing or crouching FBI agent or test dummy
  (20% less before), about NTM's to a lying one; dynamite next to the head of
  a lying one does 16% more. Behind a one-block wall a standing agent takes
  39% less, a test dummy 22% less. Server key `blastPartsEven` (on); off, it
  is as in X807.

## X813

- Mob AI: a bot running from a grenade no longer runs into the reach of
  another grenade it knows of, its own side's above all: running from one at
  its feet, it could end up at the edge of its own grenade's blast.

## X812

- Mob AI: a badly shaken bot (confidence at its lowest, after losses) falls
  back: to cover at least six blocks farther from the enemy, a moment in each
  before the next, until it calms down. A squad falls back in waves: a bot
  goes while another of its squad is in cover ready to fire and fewer than
  half are on the move, or at once if it is hit where it waits; the ones in
  cover lean out early to fire for it.

## X811

- Mob AI: each bot has a confidence that falls when it is hit, pinned down or
  sees one of its own side go down near it, and rises when it lands hits or
  sees an enemy go down; it drifts back to normal within a minute once the
  fight goes quiet, and a squad shares it a little. A confident bot takes
  cover nearer the enemy and goes after a lost target sooner; a shaken one
  takes cover farther back and does not hunt or flank until it calms.

## X810

- Mob AI: when a target goes out of sight, each bot decides once, at even
  odds, whether to wait where it went or where it may come out again: the
  first place it would step back into the bot's sight, the way it was going.
  A bot watching for the target to come out keeps its gun there and does not
  join in the bursts at the place it went; the rest do as before. Bots that go
  searching try the side the target was heading first.

## X809

- Mob AI: a bot running to cover picks a way that keeps out of the lines of
  fire of its own side's bots that are shooting, where it can, instead of
  running down or along them (a friend holds fire while it is in the way).

## X808

- Mob AI: bots now slide into low cover and roll under fire in real fights
  too, not only on the test stand: a bot's run was taken for standing still,
  so neither ever began. A roll now follows a shot that passed close within
  the last second (before: a fifth of a second). In six test battles of 5 on 5:
  6 slides and 3 rolls, no more hits on their own side.
- Mob AI: a bot left with nobody near stops leaning round its corner and
  lowers its gun, instead of staying leaned and aiming until someone comes
  back; shots no longer hit its leaned-out head in that time.

## X807

- NTM's blasts hit each part of the body on its own. Grenades, 40 mm shells
  (MK108, Congo Lake), rockets, GL-54 HE, mines, charges, missiles,
  artillery, C4 and dynamite: each part of a creature takes the blast by its
  own distance to it and by what it sees of it (a wall shields what it
  covers), with a share of the damage by its size. The head (×1.75) and the
  limbs (×0.5) scale their shares after the armour, as with bullets.
  - In the open an HE grenade 1–3 blocks away now takes about 20% less from a
    standing or crouching creature and 10–16% less from a lying one (10%
    with the head towards it, 16% with the feet); dynamite next to a head
    lying towards it hits as hard as before.
  - Standing behind a one-block wall an HE grenade takes about half (8.2
    instead of 16.0 from an FBI agent): only the head and shoulders show.
  - It costs the server about 0.09 ms per creature in range per blast (50
    FBI agents: 10.5 ms a grenade instead of 5.9; one MK108 shell a tick into
    100 agents: 20.4 ms a shell instead of 15.0).
  - For the creatures whose model has a marked head or limbs (FBI agents,
    test dummies, players, most mobs), while `modelHitboxes` is on and the
    server has the creature's pose from a client nearby; the others, nuclear
    blasts and fire, plasma and electric blasts are as before. A creature
    with a marked head and no marked limbs (a pig, a chicken) takes no more
    than before.
  - Server keys `blastParts` (on) and `blastPartRays` (rays from each part,
    1 to 9, 3).

## X805

- Mob AI: a bot crawls on its belly when it moves lying down: each leg in turn
  drawn up along the ground, knee out to the side, the gun swinging a little
  with each pull. Its arms no longer jerk as it gets up from a crawl on the
  move.
- Mob AI: on a run to cover, a bot may slide the last blocks into low cover,
  and a bot running in the open may roll forward when a shot passes close.
  Either lasts 0.8 s, crouched, without firing, at most once in 5 seconds.

## X804

- Mob AI: a bot leaning out of cover where no player's game has drawn it
  lately is hit by its model's own head, arms and legs, so a shot to its
  head or a limb does what it does to a bot standing upright.

## X803

- Mob AI: a bot's grenade no longer hurts the bot or its own side. It throws
  only where it knows how the grenade will fly: no flat throw that skids along
  the ground, none that would catch a block's edge on the way. Its own side
  keeps out of the blast's reach and no longer takes a low wall for cover
  from it.

## X801

- Mob AI: FBI agents and dummies move their legs in step with how fast and
  which way they go: a walk, a jog or a sprint going forward, a walk going
  back and a side step going sideways, blended as they turn. They reload with
  a rifle's two-handed reload, fall dead in a fall of their own rather than
  tipping onto their side, and are thrown back by a hit that sends them
  flying (a blast, a heavy round).

## X798

- Mob AI: bots throw NTM's frag grenades (HE, three seconds' fuze). When you
  go behind cover, a bot in cover 15 to 26 blocks away lobs one over it to
  burst within 3 blocks of where you went out of sight, working out the arc,
  the bounces and the roll as NTM flies it. It never throws one that would
  burst within 17 blocks (the blast reaches 15) of itself or of its own side
  where they stand, go or look out, and one of a squad throws at a time.
  Each bot carries a few: new server setting `aiGrenades` (2 by default, 0
  to 8), kept on the bot.
- Mob AI: a bot that sees a grenade coming or hears one land nearby runs from
  where it is going to burst: 12 blocks off or behind a block, and back to the
  fight once it has gone off. Anyone's, yours too: NTM's hand grenades and
  dynamite, 40 mm grenades (GL-54, Congo Lake, MK108) and rockets. A bot
  behind a block from it already stays and fights on.
- Mob AI: blind fire leaves from the gun held up over the bot's head, and the
  bot holds it back when a block is in the way from there.
- Mob AI: bots no longer take the sounds of animals and other creatures they
  do not fight (a cow's call, a chicken's steps, a zombie's groan) for you.
  Near a farm they turned to each one, and with no one to fight their heads
  spun all the time.

- Heavy and super-heavy guns slow the player who carries them a little:
  6% for a heavy one (the drill, the flamethrowers, the rocket launchers,
  the anti-materiel rifle, the auto-shotgun...), 12% for a super-heavy one
  (the minigun, the tesla cannon, the Fat Man, the Ma Deuce...), walking and
  running alike. The heavier hand counts; two miniguns weigh as one. Not in
  creative or spectator, and the AI's soldiers are not slowed.
  `gunWeightSlow`, `gunWeightHeavySpeed`, `gunWeightSuperHeavySpeed` (server).
- A gun's tooltip names its kind (pistol, SMG, rifle, minigun...), its weight
  class and how much it slows. `gunWeightTooltip` (client, on). The kinds and
  classes are in `ntmgunfeel-gun-weight.txt` in the mod jar.

## X793

- GL-54 mustard gas no longer leaves oily stains on the floor. The ground it
  poisons stays as it was.

## X790

- FBI agents and test dummies bend at the elbows and the knees: each arm and
  leg is two parts, and the animations carry over bone by bone, so far more of
  them keep their look (67 of 81 clips fit, 26 before). A straight limb looks
  as before, the skin is the same, and the gun sits in the hand at the end of
  the forearm.
- Their hitboxes follow the ten parts: head, body, upper arms, forearms,
  thighs and shins, each where it is drawn.
- The head and limb multipliers (head ×1.75, limb ×0.5) now come after the
  armour: NTM's damage threshold and resistance and vanilla's armour take
  their share of the whole bullet first, then the part hit scales what gets
  through. This goes for NTM's bullets and beams, its old bullets and the
  Maxwell and Tauon turrets. Before, the threshold was taken off a limb's half
  of a bullet and the armour let more of a headshot through: an FBI agent in
  SECURITY armour took a carbine 20 shots to the body, 10 to the head and 80
  to an arm or a leg; now 20, 12 and 40. Explosions no longer use the head and
  limb multipliers: a blast hits no part of the body.
- A glass block worn on the head no longer makes an agent's head hitbox
  bigger.
- FBI agents and test dummies show the armour they wear (SECURITY, PAA),
  with sleeves and leggings that bend with the limbs. The armour adds nothing
  to their hitboxes.

## X787

- Smoke: clouds that overlap are drawn as one smoke. Before, each cloud was
  drawn on its own and laid over the others in an order that could be wrong
  inside the overlap and flicker as the camera moved; the swirls are now the
  same in every cloud.
- Smoke: a smoke grenade that bursts in a smoke cloud adds its smoke to that
  cloud instead of making a second one inside it. The cloud grows outward at
  its usual density and starts to thin later. Gas grenades still add up:
  twice the gas in the same air is twice as strong.

## X786

- Smoke: a blast punches a hole in a smoke cloud, and the hole closes again
  from its edge in 2 to 3 seconds; the smoke is pushed aside, not gone.
  Bullets leave smoke as it is. A GL-54 high-explosive grenade opens a hole
  of 4.5 blocks (one and a half times the reach it hurts in, at most 12).
  `smokeHole` (server, on).

## X781

- GL-54 gas: every gas poisons the ground now, not the mustard only. Fired at
  one place, a magazine of mustard turns the ground into the poisoned biome,
  two of chlorine or acid, three of phosgene (two magazines of mustard
  before); gases fired at one place add up, and a grenade now and then still
  never does it.

## X777

- Mob AI: the pose layer tells where an AI mob firing blind over cover has its
  gun: in its hand held up over its head, 1.81 blocks up when it crouches (its
  eye is at 1.27), with its lean. The blind shot can leave from there, so a mob
  whose gun shows over a wall can fire over it.

## X776

- Mob AI: a bot pinned down behind low cover by shots close past it no longer
  stands up into the fire: it stays down and fires blind over the cover at
  where it last saw you, the gun held up, its shots spread three times wider
  than its aimed shots from there. It does not fire blind into a wall, nor at
  you closer than 6 blocks.
- Mob AI: no more than two bots of a side come after you at once, also when
  the hunt spreads their squad out (a third could join before).

## X775

- Mob AI: bots of one side near each other fight as a squad. When they lose
  sight of you behind cover, one goes round the side to a spot that sees where
  you went, while the others fire bursts at that spot to keep your head down.
  A bot leaves its cover for better cover or to hunt you only while another
  covers it, half of them move at a time at most, and no more than two come
  after you at once; the rest watch where you went. A bot alone fights as
  before.

## X774

- Mob AI, new: bots can learn from your fights. `/armsai record on|off`
  (operators) records how you fight: where you stand and look, what you
  carry, the enemies you have seen, the cover the bots' own search finds round
  you. The files go to `logs/ntm-gunfeel-fights/`, gzipped, about 5 to 10 MB
  an hour of fighting, each at most 256 MB, and no new one is started once the
  folder holds 2 GB. Off by default; it records only the player who turns it
  on. `/armsai course` (in creative) lays a 64 by 64 ground to fight on in
  front of you: a field with low and high cover, low ground with pits, the
  walls of four houses with doors and windows, and an open strip along one
  side; `/armsai course clear` takes it away again (grass is left, not what
  stood there before).
- Mob AI: a model trained on those recordings (`tools/imitation/`) decides
  for the bots when to look out of cover, when to get back in, when to move
  to other cover and which, and when to go after someone they lost; their
  own code still finds the paths, aims and shoots. Where the model has seen
  nothing like the fight at hand, the bots decide as before. It reads
  `config/ntm-gunfeel-imitation.json` and is used only with the server key
  `aiImitation=true` (off by default); `/armsai imitation info` tells what
  it decided, `/armsai imitation reload` reads the file again.

## X758

- Mob AI: to look or fire round a corner an AI mob takes a short step to it
  and leans out, so you see its head and a shoulder, not all of it; its shots
  leave from round the corner where its gun is, and its leaned head can be
  hit with no player near. Where a lean shows it nothing it steps out as
  before, and with model hitboxes off it never leans.
- Mob AI: an AI mob's shot no longer starts inside the wall it stands at.

## X756

- Mob AI: AI mobs move their bodies. They breathe as they stand, flinch when hit
  (in the chest or the head), crouch and stand up smoothly, lean out of cover to
  the side, lie down with the gun held out along the ground, aim with both hands
  along their look, and hold the gun up over their head to fire blind over cover.
  They lower the gun and reach for the magazine to reload, kick up with a shot,
  throw overarm, and wave their free hand, point or raise it to halt to give a
  signal. The hitbox takes the pose: a mob leaning out past a corner or crouched
  behind a wall is hit where it is drawn, and its shot leaves from where its gun
  is. The idle, flinch, throw and wave clips come from Quaternius' Universal
  Animation Library and Kay Lousberg's KayKit Character Animations (both CC0);
  the rest is posed in code.

## X742

- Mob AI: an AI mob running to cover along a low wall in your sight runs low
  past it, a crouching player's size and a little slower, so only its head
  shows over the wall; in the open it runs upright, as fast as before.
- Mob AI: behind low cover an AI mob keeps low between bursts and through a
  short reload, a crouching player's size, and rises a moment before it fires
  again; it never fires crouched. Over a wall one high you see its head, not
  its chest. Clients see its stance, lean, arms and what it does (reloading),
  ready for poses drawn on it.

## X738

- GL-54 gas grenades: a bullet that flies through a gas cloud thins the gas
  along its path a little; a burst of fire down one line clears a channel
  through it. A blast blows away the gas within twice its reach, all but a
  little in the middle: a high-explosive grenade fired into a gas cloud takes
  more than half of it. Smoke is not stirred. `gasStir` (server, on).
- GL-54 mustard gas leaves dark oily stains on the floor it lies on; they stay
  5 minutes after the gas is gone.
- GL-54 mustard gas soaks the ground. Where it lies for long (two magazines of
  it fired at one place, not a grenade now and then), the ground becomes a
  poisoned biome for good, in patches of 4 by 4 blocks under the gas: the
  plants on it die, nothing grows there (saplings, crops, grass spreading,
  bone meal) and its grass and leaves turn a sick yellow-brown. `gasPoison`
  (server, on).

## X735

- Mob AI: AI mobs aim at what they see of you: your head over a wall, your
  legs under a beam, the middle of what shows over low cover; with all of you
  in sight, your chest. Before, they aimed at your chest whatever hid it, and
  shot the wall in front of you.
- Mob AI: an AI mob with one of its own in its line of fire steps to the side
  that clears it, two steps if one does not, and at once if it is answering
  fire with no cover to run to; before, it could step back and forth in the
  line and never fire.
- Mob AI: an AI mob shot in its cover from where it does not look moves to
  other cover if there is any (once in a while, not at every hit of a burst),
  and takes the one it lost sight of for the shooter: it looks out and searches
  where the shot came from. Before, a rough guess of where from could leave it
  in the cover it was shot in, watching the old place. A blast that no one set
  off does not move where it thinks they are.
- Mob AI: in a big fight an AI mob looks over at most twelve foes it has not
  noticed yet at a time, in turn, besides those it knows of, and sees all of
  only the one it deals with: a hundred AI mobs fighting cost the server half
  the time they did (3 ms a tick, from 5.6).

## X713

- Mob AI, new: mobs that fight as a player does, with NTM's guns. `/armsai
  spawn fbi|dummy [count] [gun]` (operators) brings NTM's FBI agents or test
  dummies in round you, each with a full NTM gun and four magazines besides;
  `team <name>` at the end puts them in that team, made if there is none. Any
  mob tagged `armsai` (`/tag`) is taken over the same way. `/armsai clear`
  takes them all away, and `/armsai ammo infinite|<rounds>` sets what they
  carry. They work their guns with the same keys a player does and reload only
  from what they carry: one that has shot it all stays empty. Their guns fire
  and reload at the guns' own pace. FBI agents NTM brings in itself are as
  before.
- Mob AI: what an AI mob notices. It sees only what is in front of it, within
  its sight, and not through walls, leaves or thick smoke (glass, bars and
  fences hide nothing; sandbags and other blocks that stop bullets hide what
  is behind them), and it takes a moment to notice someone, longer far off or
  at the edge of its view. It hears shots, blasts and steps nearby, and knows
  where a hit came from, but only roughly; a shot that flies close past it is
  taken as one at it. The same noise from the same place, heard again and
  again, soon means nothing to it. It neither sees nor hears a player in
  creative or spectator mode, nor hears their shots land (`/armsai spawn`
  given in those modes says so).
- Mob AI: how an AI mob fights. One that meets you near, or sees you looking at
  it, first answers with a burst where it stands. Then it gets into cover from
  you (a spot your eyes do not reach, at the distance its gun is best at,
  closer with a shotgun, run to by a way that keeps out of your sight where it
  can; with you near it walks there firing), and steps out to one of its
  cover's sides for a burst and back in, from another place each time where it
  can; over low cover it fires standing. It never fires into its own cover. It
  reloads in cover. It changes cover as a matter of course: when you see into
  it, often when shot at, now and then after looking out from one place a few
  times, and when a better place turns up. It keeps a few blocks from the cover
  of its own side and does not take their places to shoot from. A shot it only
  heard sends it into cover it can look out of; it looks out the way the shot
  came from once and, seeing no one, creeps there to see. In a fight it picks
  cover from everyone it knows of, not only the one it fights, and a place to
  shoot from only where it sees you and a bullet gets through. It walks as you
  do: walking, it keeps its gun on you and steps sideways or back; running, it
  faces where it runs and holds fire. Out of sight after it has looked for you,
  it goes after you within a few seconds, to where you were last seen, then
  searches round that place.
- Mob AI: AI mobs lie down on the terms a player does (`prone`), only to fire
  lying or for a long reload, and stay down three seconds at least (two if they
  must run). In the open with you over 30 blocks off they fire lying; pinned
  down by shots flying past (three within a second) with no cover, they lie;
  behind low cover they fire standing. Never closer than 10 blocks. Lying, they
  are a crawling player's size, crawl at a third of the speed and get the
  crawling spread (`spreadCrawl`). They keep still for each burst and crawl
  between bursts, as a shot while crawling is not let go (`proneCrawlNoFire`).
- Mob AI: AI mobs aim as a person does, and never miss on purpose. They act on
  where they saw you a moment ago, lead you by the way they saw you move,
  correct their aim every few ticks, each correction a little off, and pull the
  trigger only while the spot they expect you at is on the barrel. A steady
  strafe they learn after a few swings; an uneven one they do not. Their spread
  is a player's. A shot kicks an AI mob's view as it kicks your camera: NTM's
  recoil for the gun, scaled as your camera's (`cameraScale`), with the gun's
  climb and drift that stay, less while it aims (`adsRecoil`) or lies; what is
  left it pulls back a moment after it notices.
- Mob AI: AI mobs fight in the game's teams (`/team`). One in a team fights the
  players and the AI mobs of the other teams, and players with no team; one
  with no team fights every player. Neither shoots through its own, nor takes a
  hit from its own as a threat, nor goes after the sound of its own shots.
  NTM's FBI agents take shots from FBI agents of another team. An AI mob holds
  fire while another AI mob it does not fight is in its line of fire, and moves
  when that lasts.
- Mob AI: new server settings. `aiRadius` (128 blocks, 32 to 256): further from
  every player AI mobs stand still, and go on when a player comes near.
  `aiSkill` (`recruit`, `regular`, `veteran`; `regular` by default): how late
  they see, how often and how well they correct their aim, how soon they notice
  you and whether they read a strafe. `aiRecoil` (1 by default, 0 for none):
  how strong their recoil is. `aiThreads` (2, 1 to 8): the background threads
  on which the server works out where its mobs can stand and what covers them,
  kept up to date as blocks are built, broken or blown up, so the server's own
  thread is not held up.
- Mob AI: `/armsai debug [radius]` (operators) shows the AI's cells around you
  (green for high cover that stops bullets, blue for high cover that only
  hides, yellow for low cover, grey for none; red and white bars mark the
  covered sides) and over each AI mob what it does, whom it knows of and how
  (sees, is noticing, remembers, hears), what it decided and why, its health
  and rounds, where it looks, its path and its cover. While anyone watches, or
  after `/armsai log on`, every AI mob's decisions go to
  `logs/ntm-gunfeel-debug.log`. `/armsai info` tells how many AI mobs there are
  and what their work costs. Whoever an AI dummy kills dies by an "AI dummy".

## X684

- The grenade wheel's armour line shows what a GL-54 fragment grenade does
  against armour: the armour-piercing airburst gets through it, the
  airburst and the incendiary do nothing. HE, smoke and the gases still
  show a dash.

## X683

- The GL-54's gas clouds are a pale haze now, not a bright, solid wall of
  colour: chlorine faintly yellow-green, mustard gas faintly brown, acid
  greenish grey, phosgene almost clear. Through two blocks of gas you see
  what is behind it; through five it is hazy. Only the looks changed: what
  a gas does, and how much it hides you from mobs, are as before.

## X682

- The GL-54, the mod's first gun of its own: a 20 mm grenade launcher with a
  reflex sight and a rangefinder. It is crafted from a light workers' alloy
  barrel, receiver and grip, a gun metal mechanism, a polymer stock, a vacuum
  tube and NTM's rangefinder. `gl54` (server, on); off, the gun stays in the
  world but does nothing, and a grenade already in flight falls as a dud.
- Its ammunition is a 20 mm magazine of 8 grenades, which can be mixed in any
  order:
  - Two empty magazines are crafted from a steel bolt, a gun metal plate and a
    steel shell.
  - A magazine is filled in NTM's ammo press with no recipe chosen: the empty
    magazine goes in the bottom right slot and the grenades in the others. The
    top left grenade fires first.
  - The press's list also offers ready layouts, one per kind and one mixed. A
    layout you lay out by hand is remembered by that press, up to 6; Shift+click
    one to forget it.
  - Magazines that are alike stack to 4.
- A magazine in hand:
  - Right click opens it as a row of 8 cells. The leftmost cell is the mouth:
    grenades go in and come out there, the last in comes out first.
    Shift+click moves one grenade.
  - Shift+right click unloads one magazine into the inventory and leaves an
    empty one.
  - Operators can give magazines with `/gl54mag he,he,airburst`.
- Nine grenades:
  - high-explosive, airburst, armour-piercing airburst, incendiary and smoke
    are made in the ammo press;
  - chlorine, phosgene, mustard gas and acid are made in the assembly machine,
    from the fluid and plastic.
  - The grenade wheel chooses which magazine the next reload takes.
- Airburst, armour-piercing airburst and incendiary grenades burst into
  fragments at the fuse's range: a cone along the flight and a ring across
  it, which reaches behind low cover from a burst above it. The airburst
  throws 40 + 120 fragments, the armour-piercing one 20 + 60 that pierce
  armour as NTM's 5.56 AP does, the incendiary 20 + 60 that set mobs and
  the ground on fire for a few seconds. Fragments leave small holes where
  they hit blocks (at most 24 a burst).
- A grenade leaves the barrel at 94 m/s and falls as it would on Earth, so it
  carries far but has to be aimed above a target.
- The reflex sight:
  - The red dot sits in the middle of the sight's window and glows as a real
    one does: the same by day, at night and in a cave. So do the ring, the
    arrow and the range digits.
  - The key shown in the gun's tooltip, used while aiming (the middle button
    with the mod's right-button aim), measures the range to what the dot is
    on. The reading may be off by up to half a block. Measuring into the sky
    clears it.
  - The fuse bursts the grenade at the measured range: one block past it if
    the target is a block, at it if it is a mob.
  - While aiming, the mouse wheel moves the burst up to 3 blocks nearer or
    farther.
  - A ring stays on the measured point in the world and shows where the barrel
    has to point for the grenade to reach it.
- The smoke grenade lets out a smoke cloud. Each gas grenade lets out a
  coloured cloud of its gas, which drifts and thins as the smoke does:
  - chlorine: hurts within seconds and makes you cough. A cough shakes the view
    and lets go of the aim. `gasCoughBlocksAim` (server, on); off, the cough
    only shakes the view;
  - phosgene: almost clear, and does nothing for a while before it starts to
    hurt;
  - mustard gas: also burns the skin, so only a full suit keeps it out;
  - acid: eats the protection of the armour worn in it.
  - A gas mask keeps the breathed gases out and its filter wears down while
    you breathe them. A HUD of its own shows the doses taken.
  - One grenade does not kill, but two or three in a row will.

## X611

- Volumetric smoke seen from outside no longer lies over glass and water.
  Smoke behind a pane of glass (plain or stained) or behind water was laid
  over it, as if it were in front. Now smoke behind glass or water shows
  through it, dimmed and tinted by it, and smoke in front of a pane covers
  it, to its very edge. With improved transparency, when CrankShaft is not
  installed or its backend is off, the game laid the pane over all the
  smoke, the smoke in front of it too; there, too, each now lies where it
  is.

## X607

- A ring on the water left by a shot no longer opens a hole in it as it fades.
  The ring was drawn before the water and kept the water under it from being
  drawn, so as it faded the bottom showed through it. Rings are now drawn
  after the water, with the game's translucent particles, and fade into it.
  Seen from under the surface a ring now lies over the water instead of
  behind it.

## X605

- `proneCrawlNoFire` (server, on): no shot while crawling. A shooter lying
  down (on the prone key, crawling under a trapdoor, a mob laid down) that
  moves along the ground faster than `proneCrawlSpeed` (0.25 blocks a second;
  crawling is about 1.3) does not fire: a click does nothing, not even a dry
  fire, and spends no round; a held burst stops, and a new click fires again
  once the shooter lies still. Lying still, or only turning the view, the gun
  fires as before. Aiming and reloading while crawling are not touched. A Tau
  charge let go while crawling fizzles. A gun can have its own:
  `hbm\:gun_xxx.proneCrawlNoFire = false`. Off: guns fire while crawling, as
  before.
- The drill and the charge thrower work while crawling: they are tools, and
  the mod gives them `proneCrawlNoFire = false` of their own.

## X600

- The hitbox follows worn armour as it is drawn. On a player, a mannequin or
  any mob drawn without Flywheel the armour was taken for the body and grown
  once more: the hitbox stood about a pixel off the armour all round. Under
  armour drawn by Flywheel the parts grew by a pixel as whole cubes, so the
  hitbox stood a pixel off a bare forearm or a helmet's open face, 0.6 pixels
  off leggings and 0.1 off boots. Now the drawn armour is the hitbox, where
  its texture is not clear, and the parts under it keep their size. So is a
  skull or a dragon head drawn by Flywheel; without Flywheel, and for a block
  (a pumpkin), the head still grows to their size. A shot on a helmet is still
  a shot in the head. An enchanted or trimmed piece no longer adds whole cubes
  over the armour (and over a helmet a cube 1.5 pixels off the head): its
  glint and trim are drawn on the armour and are not taken as more of it.

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
