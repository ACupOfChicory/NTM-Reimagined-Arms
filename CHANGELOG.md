# Changelog

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
