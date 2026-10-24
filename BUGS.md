# Known bugs

Every bug found, fixed or not. Open ones that change what the player sees wait for a decision: the Gradle move must
leave the game exactly as it is.

## Open

### Reload: about four times slower with the inventory open (unconfirmed)
Seen by the user, 2026-10-23: a reload started and the inventory opened, the reload seemed to slow down about four
times. Not found in the code: NTM counts the reload on the server by ticks (`ItemGunBaseNT.inventoryTick`), which an
open inventory does not touch, and its animations run by the clock (`HbmAnimations`, `GameTime.now`); the mod changes
neither. Still to tell: does the ammo come in later, or does only the animation look slower, and is it the same with
the mod off.

### Ricochet: after a bounce the server's bullet leaves no trace, the tracer flies on (unconfirmed)
`src/main/java/acup/gunfeel/Tracers.java:589` (client ricochet), NTM `BulletConfig.LAMBDA_STANDARD_RICOCHET`. Four
Maresleg pellets off NTM steel (tcalloy, titanium deco): the client's tracer bounced and hit concrete 6–7 blocks
further, the server logged the bounce and then nothing more for those bullets. Either the server's bullet dies there
(the tracer then draws a bounce that did not happen) or the test log misses its hits after a bounce. Needs a look at
NTM's ricochet path and a shot at a steel face at a slant. Found with `/gunfeel testlog`, 2026-10-23.

### Piercing: a second post or board in the same block costs nothing
`src/main/java/acup/gunfeel/BulletServer.java:115` (`paid`). A bullet pays once per block: after the first post of a
scaffold (wooden or steel) or the first board of a wooden ceiling, the next one in the same block is free. The
`paid` set keeps a bullet that ends a tick inside a block from paying twice; per-part payment needs it per cell run.
Small: a 2-pixel board of wood takes about 3 damage. Found with the wooden trench blocks, 2026-10-23.

## Fixed

### Machines: bullets stopped in the air around the solar boiler (X279)
`src/main/java/acup/gunfeel/RenderedMesh.java` (`Capture.tri`). NTM's solar boiler draws a beam from every mirror
into it (`RenderSolarBoiler`, additive, alpha 1 %), almost invisible. The cells of a machine are read from what its
renderer draws, so the beams became solid cells: a bullet stopped about a pixel off the tank, in the gap between a
corner post and the tank, and over the top. Now a triangle drawn clear at every corner (alpha below the cutout's 26)
is left out: no cells, no holes. Found with `/gunfeel testlog` (12 stops 0.7–1.2 px off the drawn mesh), 2026-10-24.

### Piercing: a creature got the damage left after the blocks behind it (X277)
`src/main/java/acup/gunfeel/BulletServer.java` (`clip`), `PierceSteps.java`. NTM asks for the blocks of a bullet's
whole tick path (16 blocks) before it looks for creatures on it, and `clip` paid for every block it pierced at once.
A .50 DU shot down through an iron golem's head into the grass behind it: the two grass blocks took 41 -> 4.9, then
the head shot did 6 instead of 52. Now the damage goes back to before the first block beyond the creature, and the
blocks are paid after the hit. Found with `/gunfeel testlog` (the hit lines of X276), 2026-10-24; the bullet check
`golem over a floor` repeats it.

### Piercing: four holes from one bullet on the edge of two stacked blocks
`src/main/java/acup/gunfeel/Tracers.java` (`against`). A bullet along the edge of two stacked steel corners went into
the upper one and 5 cm later straight on into the lower one: each block pierced got a hole where the bullet went in
and where it came out, the two on the face between them (not drawn) spilling onto the faces around. Fixed in X267:
no hole, sound or particles on a face pressed against another block's solid part; in X268 not when glass is on
either side. In X269 the rule holds only between two steel panels (`hbm:steel_corner`, `hbm:steel_wall`): for every
other pair it hid holes that show (a copper grate against a steel scaffold). Found by the user, 2026-10-23.

### Muzzle smoke without end, thick, from a gun in a new world (NTM)
`src/main/java/acup/gunfeel/mixin/MixinItemGunBaseNT.java` (`staleShot`). NTM keeps a gun's last shot on the item,
not the stack (`ItemGunBaseNT.lastShot`), in the level's game time. In a world whose time is behind the last one's it
lies in the future: `Lego.handleStandardSmoke` then smokes till the gun fires, and its alpha grows far past 1 (the
mod's clamp only caught alpha below 0). Fixed in X263: a last shot later than now is forgotten. Found by the user,
2026-10-23.

### Laminate pane: a hole not seen from the other side
`src/main/java/acup/gunfeel/Tracers.java` (`seenThrough`). NTM's laminate pane is drawn translucent, and translucent
faces write depth: its far face hid the hole on its near face, though the hole is drawn from both sides. Fixed in X262:
a bullet stopped in translucent glass leaves the same mark on its far face too. Found by the user, 2026-10-23.
That fix was wrong: it made a second mark, a new one (its own size and look), where the bullet's line would come out,
so a stopped 9 mm left an entry and an "exit" in reinforced glass, apart when shot at a slant. Fixed again in X280:
one mark, drawn on the far face, right behind where it was hit, when the camera is beyond that face. Found by the user,
2026-10-24.
X280 did it only for marks laid on the hitbox (reinforced glass): the laminate's are laid on its model (the hole rule
`~.*glass.*` does not take `reinforced_laminate_pane`), so on the laminate no mark showed from behind at all. Fixed in
X281 for marks on a model too. Found by the user, 2026-10-24.
X280 and X281 were wrong too: they moved the mark onto the far face (B) when seen from there, while it lies on the
face that was hit (A) and must be seen through the glass. Fixed in X282: the mark stays on A, on the same screen
pixels, and seen from B each vertex is pulled along the line of sight to just before B (as marks on bars are), so
B no longer hides it. Found by the user, 2026-10-24.
X282 made it worse: on an edge (the pane's side face) the glass behind a mark is the whole pane, and marks there and
the ones seen from the edge jumped over the screen. All of X280–X282 undone in X283, and so is the second mark of
X262. Holes on glass are now drawn before the translucent terrain and write depth (`HoleRender`, the
`MixinSubmitNodeCollection` / `afterWeather` late drawing gone): the glass in front of a hole lies over it, so it is
seen through the glass where it is, from any side; the glass behind it does not cover it. Found by the user,
2026-10-24.

### Chain-link fence: bullets never fly through its mesh
`src/main/resources/ntmgunfeel-bullets.txt` (`path hbm:fence_metal...`). The fence went by a pixel mask laid on its
hitbox faces (its hitbox is a quarter-block slab, its mesh a plane in the middle), and the post block had no mask at
all. Fixed in X262: both go by their model (`path ... model`), and pierce (`break ... pierce=1.5 keep=0.8`). Found by
the user, 2026-10-23.

### Machines: hit sounds and particles where the bullet enters the hitbox, not where it meets the model
`src/main/java/acup/gunfeel/Tracers.java` (`hit`). A bullet through a machine's hitbox where nothing is drawn (a
grazing shot past the cyclotron) played its sound and particles on going in and coming out, with no hole. Fixed in
X260: on a multiblock's cell or a block a renderer draws, the effects wait for the hole and go where it is made; no
hole, none. Found by the user, 2026-10-23.

### Cyclotron: a bullet stops in mid-air inside its hitbox
`src/main/resources/ntmgunfeel-bullet-shapes.properties` (`structures`). Only the cargo door and elevator had cells as
drawn; other machines are met by NTM's hitbox, so a bullet past the cyclotron's model stopped inside an empty cell and
vanished. Fixed in X260 for the cyclotron (listed under `structures`, the client takes its cells from what it draws);
other machines are still met by their hitbox. Found by the user, 2026-10-23.

### Holes on a bottom slab under a top slab: cut to triangles
`src/main/java/acup/gunfeel/Tracers.java` (`hidden`). A piece of a hole was hidden when a face lay over it along the
hole's normal: in the half-block slit between a bottom and a top slab the top one hid the hole's pieces on the bottom
one, a whole triangle of its face at a time. Fixed in X260: hidden only when a face also lies between it and the
shooter. Found by the user with `/gunfeel holedebug`, 2026-10-23.

### Hit sounds hardly fade with distance: placed nearer for a 16-block fade, but they fade over 24
`src/main/java/acup/gunfeel/BulletFx.java:190` (`play`). A hit sound heard over `range` (40) blocks is set nearer the
listener at `16 / range` of its distance, as if Minecraft faded it out within 16 blocks. Our hit sounds have
`attenuation_distance` 24 (some 32) in `sounds.json`, so the sound set 16 blocks away still plays at a third of its
volume (a half for 32), and it goes from 1.6 at the shooter (hitVolume 2) to about 0.5 at 40 blocks, then stops
short. From `/gunfeel sounddebug`: 36.8 blocks away, set at 14.7, heard at 1.6 × (1 − 14.7/24) ≈ 0.62, not 0.11.
The scale should be the sound's own attenuation distance over `range`. Found by the user, 2026-10-23. Fixed in X258: the sound is set nearer by its own attenuation distance over `range`, so it fades to nothing at 40.

### Hole limits: every drawn hole counts against holeMaxMachines, ordinary blocks too
`src/main/java/acup/gunfeel/Tracers.java:1251` (`new Hole(…, true)` in `drawnHole`). `drawnHole` makes the holes on
every block now (the chunk's mesh), not only on machines and doors, and marks them all `machine`. So `holeMax` (blocks)
counts nothing and every hole shares `holeMaxMachines` (150 on `high` instead of 500). The holes of one hit share a
birth time and the limit counts hits, so it still holds. A hole is a machine one when its piece is on a model
(`RenderedMesh.models`) rather than on the chunk's mesh. Found with `/gunfeel testlog`, 2026-10-23. Fixed in X258: a hole counts as a machine one when its piece has no block (a renderer drew it).

### Quality presets: new hole limits apply only when the next hole is made
`src/main/java/acup/gunfeel/Tracers.java:1268` (`trimHoles`). `/gunfeel quality low` lowers `holeMax` and
`holeMaxMachines`, but the extra holes stay until a new hole calls `trimHoles`. Not critical (user). Found in game,
2026-10-23. Fixed in X258: the holes are trimmed on the next frame after the limits change.

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
