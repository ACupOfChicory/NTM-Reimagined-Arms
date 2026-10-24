# Changelog

## X290

- Bullet shapes made from block models are no longer a pixel too fat: a face lying on a pixel border (NTM steel
  scaffold poles at 14/16) went into the empty cell before it. Bullets stopped in air beside a scaffold pole and
  left no hole. The shapes in the jar are made again after the next world join.

## X289

- Arms are drawn whole, like TaCZ: no longer cut at a plane in front of the eye, so a hand near the screen is no
  longer a tiny stub. The editor's "докуда рука" slider is gone (it only cut the arm).

## X288

- The ammo lock key does nothing on what has no magazine (NTM's power armour melee controller): it said "Ammo
  locked: ?" there. X286 undone: our arms were never drawn on that controller.

## X285

- Laminate (block and pane): a hole shows from the other side, through the glass where it was made. Laminate has no
  clear pixels, so its near face hid the hole; holes on such glass are now also drawn under it.

## X284

- Glass holes are back to how they were in X279 and before: X280–X283 undone, and no second mark on the far side of
  stopped-in glass. The one change: NTM's laminate (block and pane) now counts as glass, so its holes follow the
  hitbox like reinforced glass and show from the other side.

## X283

- Glass (reinforced glass, laminate, panes): holes are drawn through the glass where they are, from any side, the edges
  too; no second mark, nothing moves. Holes on glass now lie under the glass in front of them (seen through it) and
  over the glass behind them.

## X282

- Glass (reinforced glass, laminate): from the other side a mark is seen through the glass where it was made, not on
  the near face of the glass.

## X281

- Laminate: a stopped bullet's mark shows from the other side again (X280 did it only for reinforced glass).

## X280

- A bullet stopped in translucent glass (reinforced glass, laminate) leaves one mark, not two: seen from the other
  side the same mark shows right behind where it was hit. Before, a second mark was made where the bullet would have
  come out, so the glass looked pierced.

## X279

- Bullets no longer stop in the air around NTM's solar boiler: the nearly invisible beams from its mirrors
  counted as solid. The boiler's cells are read again when you come near it.

## X278

- Gun editor: with an NTM gun in hand press O (Controls, "NTM Reimagined Arms") for sliders over the game, which
  goes on behind them. Tabs: right hand, left hand (where it holds the gun, the arm's direction, reach, roll,
  thickness), arms (both), recoil (camera and gun model), position (hip fire, tilt, smoke). Changes show at once and
  are saved for this gun only, in `config/ntm-gunfeel-guns.properties`, when you let go of a slider. The wheel moves
  a slider one step (Shift: a tenth), ↺ takes a setting back to the mod's, ● marks the gun's own settings,
  "Отменить всё" goes back to how it was when the editor opened. "Выстрел" and "Очередь" replay the gun's last real
  kick (camera and model; NTM's own recoil animation does not play).

## X277

- A bullet that hits a creature and then goes on into blocks behind it in the same tick (a shot down through a mob
  into the ground) no longer hits the creature with the damage left after those blocks: an armour-piercing .50 into
  a golem's head on grass did 6 instead of about 52.

## X276

- `/gunfeel testlog` writes each bullet hit on a creature: the bullet's damage when it got there, whether NTM
  counted it in the head, the creature's health before and after; each shot also notes how worn the gun is.
  `tools/ingame_report.py` lists these hits with what the bullet pierced on its way.

## X273

- Marks are black again: the colouring by the block (X271) is gone, and so is its setting `holeTint`.
- Metal, machines, wood and every block on no list get the soft soot hole instead of the old holes; the old holes
  stay in the mod as the style "old", for a decal rule to pick.

## X271

- Every mark style has 64 variants instead of 8 (one 8 x 8 texture per style); the first holes ("old") still have
  their own 8, repeated.
- Marks take the colour of the block they are on: a hole in copper is copper-brown, the darkest middle stays black.
  New setting `holeTint` (0 = black as before, 1 = full colour, default 0.5).

## X269

- Holes between two blocks a bullet goes straight through are back for every block (a grate against a scaffold,
  glass against glass); they are left out only between two steel panels (steel corner, steel wall).

## X268

- Glass keeps its holes between blocks: a bullet from one glass block into the next (or between glass and another
  block) leaves them on the face between, seen through the glass.

## X267

- A bullet going from one block straight into the next (two stacked steel blocks) no longer leaves holes on the
  face between them: one hole where it goes in, one where it comes out.

## X266

- Steel: armour-piercing rounds go through more (their threshold negation x (1 + 10 x armour piercing)), and no steel
  or metal block is stronger than 1.5. 2 pixels of steel wall: AP and DU .50 through, FMJ not; NTM's bunker door
  (3 pixels, was out of reach of every round): DU just through, the starmetal .50 easily; a block of steel still
  stops everything. Concrete and soft metal pierce as before.

## X265

- `/gunfeel clearholes` removes every hole, scratch and scorch mark.
- Undone: a machine hit's particles fly off the hitbox's face again (off the model's they stuck inside the machine).

## X264

- Every NTM multiblock is met by its model as drawn, not only the cargo door, the elevator and the cyclotron.
- Machines no longer let bullets through: a thin casing let them pass oddly. `bulletPierceMachines = true` in
  `ntm-gunfeel-server.properties` brings it back.
- NTM's chain-link fence and its post: bullets fly through the mesh's gaps and pierce its wire and posts.
- A bullet stopped in a laminate pane: its hole shows from the other side too.
- Muzzle smoke no longer pours without end from a gun in a new world till it fires (NTM keeps the last shot's time
  from the old world).

## X260

- Machines: a hit's sound and particles come with its hole, where the model is met, not where the bullet enters the
  hitbox; a bullet past a machine's model makes neither.
- The cyclotron is met by its model as drawn, not by its hitbox.
- Holes in the slit between a bottom and a top slab are no longer cut to triangles.
- `/gunfeel holedebug` lists each piece of a hole, shown or hidden and by what.

## X258

- Hit sounds fade with distance as they should: before, set nearer for a 16-block fade while they fade over 24, they
  played at a third of their volume or more up to 40 blocks and then stopped short.
- Hole limits: holes on ordinary blocks count by `holeMax` again (all of them went under `holeMaxMachines`).
- `/gunfeel quality` and the hole limits apply at once, not when the next hole is made.

## X254

- `/gunfeel testlog`: a test log of what bullets, holes, effects, settings and recoil do while you play
  (`logs/ntm-gunfeel-test-*.tsv`; an op switches the server's side on too). `tools/ingame_report.py` checks it
  against `review/INGAME-CHECKS.md`.
- ⚠ Ломает совместимость: сетевой протокол 3 (номер выстрела в ShotPayload, TestLogPayload) — клиент и сервер
  должны быть одной сборки.

## X252

- The version is counted from git: X<commits in main> (`252+mc26.2` for the loader); ports on `port/*` branches are
  X<B>.<P> (`252.3+mc1.7.10-forge`). The jar is now `reimagined-arms-X<build>-mc<Minecraft>.jar`. Before this:
  0.1.1 … 0.1.201, then the Pi work (materials, piercing, bullet rules, Gradle) without numbers.
