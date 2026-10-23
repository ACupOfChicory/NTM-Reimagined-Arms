# Known bugs

Every bug found, fixed or not. Open ones that change what the player sees wait for a decision: the Gradle move must
leave the game exactly as it is.

## Open

### Piercing: a second post or board in the same block costs nothing
`src/main/java/acup/gunfeel/BulletServer.java:115` (`paid`). A bullet pays once per block: after the first post of a
scaffold (wooden or steel) or the first board of a wooden ceiling, the next one in the same block is free. The
`paid` set keeps a bullet that ends a tick inside a block from paying twice; per-part payment needs it per cell run.
Small: a 2-pixel board of wood takes about 3 damage. Found with the wooden trench blocks, 2026-10-23.

## Fixed

### Wooden trench blocks: bullets hit solid slabs where boards with gaps are drawn
`src/main/java/acup/gunfeel/WoodBoxes.java`. NTM's wood barrier, wooden roof, ceiling and scaffold are drawn as boards
and posts with gaps that join their neighbours, but bullets met their hitboxes: the ceiling and roof as solid slabs,
the barrier as a solid panel (its ceiling, drawn under a block above, not at all), the scaffold as an almost full
block. Now bullets meet the boards as drawn, with NTM's own neighbour rules. Found when the user asked about them,
2026-10-23.

### Piercing: a bullet through a model's cells left at the far side of its hitbox
`src/main/java/acup/gunfeel/PassableClip.java` (exit). A pierced block with model cells (NTM's steel scaffold, the
cargo door) let the bullet out where its collision box ended, so a 2-pixel post cost as much as the whole box and the
exit hole was drawn on the far side. Now it leaves where the drawn pixels end. Found with the wooden trench blocks,
2026-10-23.

### Piercing: a door counts as a whole block of planks; vanilla concrete half as strong as NTM's
`src/main/java/acup/gunfeel/BulletRules.java:44`, `src/main/java/acup/gunfeel/Materials.java` (strength). A break
rule's pierce= took its loss once per block whatever the bullet's path through it: a wooden door or trapdoor (3/16 of
a block) took as much as a block of planks. Now per block of the path. And vanilla concrete has the blast resistance of
wool (1.8), so its strength against concrete's usual 84 came out at the floor, 0.5: half NTM's concrete. Concrete is
now never below 1. Found while drawing up the piercing table with the user, 2026-10-23.

### Piercing: a block of concrete or steel goes through to rifles; sandbags stop nothing
`src/main/java/acup/gunfeel/Materials.java:30`. The thresholds were set for thin walls: a 7.62 DU or .50 AP round went
through a block of concrete, the saboted .50 through a block of steel, and a sandbag wall (half a block of "soft",
with wool and hay) stopped only buckshot. Retuned with the user (concrete 170, stone 100, metal 200, steel 400, solid
wood 25) and packed ground split off soft as soil (120): a sandbag wall stops every rifle round, a block of concrete
or metal everything. Planks keep their rule, so buckshot still goes through wooden walls.

### Bullet rules are not sent from the server to the players
`src/main/java/acup/gunfeel/BulletRules.java:29`. The server and every client read their own
`config/ntm-gunfeel-bullets.txt`. The server decides hits, breaking and where a bullet stops (break, path, material);
each client draws tracers and holes by its own copy. A server with changed rules (say `path somemod:railing fence`)
and players without them: the player sees the bullet stop on the railing, the server lets it through. Found
2026-10-23 adding the path rule; it holds for every rule since rules exist. Fixed 2026-10-23, not yet tried in game: the server sends its rules, its
`ntm-gunfeel-server.properties` and its extra bullet shapes to each player on joining and on every change
(ServerSettings, ServerSettingsPayload). On another game's server the client takes the server's break, material,
path, ricochet, entity and kill lines and keeps its own hit, decal, hole, whizz and fly lines (ServerSettingsTest).

### Server settings read by two threads at once in single player
`src/main/java/acup/gunfeel/ReaimTimes.java:40`. In single player the client and the server thread share the class.
`number()` and `seconds()` were not synchronized and clear and fill plain HashMaps, so a client read (bullet speed on
the client's copy of the bullet) during the server's (spread on a shot) could see a map half rebuilt. Found
2026-10-23 splitting the server and client settings. Fixed 2026-10-23: the methods are synchronized.

### Bullet shapes: a dedicated server without the shapes its players' games made
`src/main/java/acup/gunfeel/PassableClip.java:209`. A client makes the cells of blocks with a `path ... model` rule
and of NTM's big doors from what it draws (MeshShapes) and uses them at once. A dedicated server has no models, only
the jar's shapes and its config file: the player saw a bullet pass through a gap that the server's hitbox stopped.
Also the shape map was a HashMap changed in place while bullet threads read it. Found 2026-10-23. Fixed 2026-10-23:
on another game's server the client goes by the jar's shapes and the server's extra ones; the maps are replaced whole,
never changed in place.

### Fences: a bullet through the gap between the rails hits the fence
`src/main/java/acup/gunfeel/PassableClip.java:43`. Bullets meet blocks by their collision box (`Block.COLLIDER`),
except the blocks in `generate` of `ntmgunfeel-bullet-shapes.properties` (doors, trapdoors, chains, lanterns, NTM's
scaffold), which go by their model's pixels. A fence's collision box is a solid wall from the ground to 1.5 blocks,
so a bullet between the rails (or above the top rail) hits it: the fence takes damage (wood break rule) and the
bullet loses damage as through a plank. Same for fence gates and walls (and other mods' blocks shaped so). Found
2026-10-23 from the user's report. Fixed 2026-10-23, not yet tried in game: every
FenceBlock, FenceGateBlock and WallBlock (other mods' too, not one with a mask) takes its family's cells from the
vanilla model (tools/fence_shapes.py, PassableClip.familyKey), so the gaps are a пролёт (FenceShapesTest).

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
