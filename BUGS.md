# Known bugs

Every bug found, fixed or not. Open ones that change what the player sees wait for a decision: the Gradle move must
leave the game exactly as it is.

## Open

### Fences: a bullet through the gap between the rails hits the fence
`src/main/java/acup/gunfeel/PassableClip.java:43`. Bullets meet blocks by their collision box (`Block.COLLIDER`),
except the blocks in `generate` of `ntmgunfeel-bullet-shapes.properties` (doors, trapdoors, chains, lanterns, NTM's
scaffold), which go by their model's pixels. A fence's collision box is a solid wall from the ground to 1.5 blocks,
so a bullet between the rails (or above the top rail) hits it: the fence takes damage (wood break rule) and the
bullet loses damage as through a plank. Same for fence gates and walls (and other mods' blocks shaped so). Found
2026-10-23 from the user's report.

## Fixed

### Holes: a second glass test that disagrees with the material
`src/main/java/acup/gunfeel/Tracers.java:1196`. `glassLike()` (glass tags, glass sound, "glass" in the id) decides
whether a hole draws as glass (late pass, both sides, no box-face snap), apart from the block's material. On the
NTM 1.0.3 dump it calls 5 non-glass blocks glass: `hbm:frozen_dirt`, `hbm:frozen_grass` (glass sound, SOFT),
`hbm:block_fiberglass` (id, SOFT), `minecraft:nether_portal`, `minecraft:end_portal_frame` (UNBREAKABLE). Every
GLASS block is glassLike, so only those 5 are off. Found 2026-10-23 auditing what uses the classification. Fixed
2026-10-23: `glassLike()` asks the material (`Material.glass()`), GlassTest.

### Break rules pick glass and wood by id and tag, not by material
`src/main/resources/ntmgunfeel-bullets.txt:9-19`. Glass wears by `~minecraft:.*glass.*` and a list of NTM ids, wood by
vanilla tags. Another mod's glass or wood is classified right (it sounds and hits like glass) but never cracks or
breaks. On the dump the regex catches exactly the blocks tagged `c:glass_blocks`/`c:glass_panes`, so those
tags would do the same here and also take other mods' glass. Wood's vanilla tags already reach other mods' planks
if those mods tag them; `$wood` would be too wide (logs, beds, chests, crates, NTM's plastics). Found 2026-10-23. Fixed 2026-10-23 for glass: the line takes the tags, wood stays on
its tags (GlassTest).

### Curve: a bad curve text can borrow another curve's fallback
`src/main/java/acup/gunfeel/Curve.java:23`. `Curve.of(src, builtin)` caches by the text alone, and a text it can't parse is
cached together with the fallback it got the first time. If two curve settings hold the same bad text (say
`kickPitch = 0:0` and `cameraPitch = 0:0`), the second one gets the first one's built-in curve: the camera then kicks
along the gun model's pitch curve (peak 12.3 instead of 1). Only with a broken config. Fix: cache only parsed curves.
Found 2026-10-22 when PinnedTest and CurveTest shared the text "0:0". Fixed 2026-10-23: a bad text is cached
as "bad", and each setting gets its own built-in curve (CurveTest).

### CameraRecoil: the camera drifts when the pitch is at its ±90° stop
`src/main/java/acup/gunfeel/CameraRecoil.java:66`. `turn()` clamps the pitch to ±90°, but `frame()` stores the whole recoil
as applied (`appliedPitch = pitch`). Looking straight up and firing, the kick up is cut off, yet its return down is
applied in full: the view ends lower than where the player was aiming, by up to the shot's recoil each time. Fixed
2026-10-23: only what `turn()` really applied is counted (RecoilTest).

### Compat: the tested NTM counted as untested
`src/main/java/acup/gunfeel/compat/Compat.java`. NTM's jar version is `1.0.3+1b8c6c2d`; Maven's `VersionRange` keeps
the `+1b8c6c2d` and does not count it as `1.0.3` in `[1.0.3]`, so the "NTM not tested" check would have run, and the
warning shown, with the very NTM the mod is built for. Found 2026-10-23 by CompatTest before it ever ran in game;
fixed by leaving out the build after `+` when comparing.

### Multiblock cells: piercing, marks and ricochet by the cell, the hit sound by its machine
`src/main/java/acup/gunfeel/PassableClip.java`, `Tracers.java`, `mixin/MixinBulletServer.java`: NTM builds its
multiblocks from shared `multiblock_cell_*` blocks. The hit sound looked up the cell's machine, but piercing, bullet
marks and the ricochet check used the cell itself, a steel casing: the brick-built machines the author made concrete
(foundry, crucible, blast furnace, chimneys, cooling towers...) sounded like concrete yet were pierced, marked and
bounced off like steel. Found 2026-10-23; fixed with `Cells.ruleState`, used everywhere a bullet rule is looked up.

### Materials: vanilla barrel, pumpkins and chorus plant counted as NTM machines
`src/main/java/acup/gunfeel/Materials.java`: the class step looks for machine words in the block's class names, and
vanilla's `BarrelBlock` ("Barrel"), `PumpkinBlock`/`CarvedPumpkinBlock` ("Pump") and `PipeBlock` (chorus plant,
"Pipe") matched, so these five took a hollow steel casing (dt 150) and the steel hit sound. Found 2026-10-23 when the
hit sounds moved to materials; fixed: the class step only for mods' blocks.

### Decals: NTM tiles get shattered-glass bullet holes
`src/main/resources/ntmgunfeel-bullets.txt` (`decal @glass,...`): the line picks blocks by the glass sound, and NTM
gives it to `vinyl_tile_large/small` (plastic floor) and `tile_lab*` (ceramic tiles), so a shot leaves a glass
shatter mark on them. In the game now; changing it changes what the player sees, so it waits for a decision.
Found 2026-10-22 with the glass materials bug (see Fixed).
Fixed 2026-10-23: bullet marks now come from the block's material (as its piercing), the tiles are wood and stone.

### Materials: glass is one material, armoured glass and glass-sounding tiles count as plain glass
`src/main/java/acup/gunfeel/Materials.java`: GLASS has no strength scale (its blast is 0), so the bulletproof
`reinforced_laminate` (blast 180) and its pane stop a bullet no better than a window (blast 0.3). The glass sound
also makes glass of `vinyl_tile_large/small` (plastic floor, hardness 10) and `tile_lab*` (ceramic tiles). Not in the
game yet (the classifier is not called). Found 2026-10-22 when the user asked about strong and weak glass; fixed with two armoured glass materials
(`reinforced_glass`, `laminate`, thickness counted as its square root) and rule lines for the tiles.

### Materials: an infinite hardness was not unbreakable
`src/main/java/acup/gunfeel/Materials.java`: only a hardness below 0 (bedrock's -1) counted as unbreakable, but NTM
marks its silo hatch and statue with an infinite hardness, so they came out as stone. Not in the game yet (the
classifier was not called). Found in the material review, 2026-10-22; fixed with the test for both blocks.

### ConfigTest reported five read settings as ignored
`test/acup/gunfeel/ConfigTest.java`: the test's "changed value" was x * 0.5 + 0.01, which is NaN again for NaN and
0.02 again for 0.02 (tracerWidth). The mod was right. Fixed in 338318b.
