# Known bugs

Every bug found, fixed or not. Open ones that change what the player sees wait for a decision: the Gradle move must
leave the game exactly as it is.

## Open

### Curve: a bad curve text can borrow another curve's fallback
`src/acup/gunfeel/Curve.java:23`. `Curve.of(src, builtin)` caches by the text alone, and a text it can't parse is
cached together with the fallback it got the first time. If two curve settings hold the same bad text (say
`kickPitch = 0:0` and `cameraPitch = 0:0`), the second one gets the first one's built-in curve: the camera then kicks
along the gun model's pitch curve (peak 12.3 instead of 1). Only with a broken config. Fix: cache only parsed curves.
Found 2026-10-22 when PinnedTest and CurveTest shared the text "0:0".

### CameraRecoil: the camera drifts when the pitch is at its ±90° stop
`src/acup/gunfeel/CameraRecoil.java:66`. `turn()` clamps the pitch to ±90°, but `frame()` stores the whole recoil
as applied (`appliedPitch = pitch`). Looking straight up and firing, the kick up is cut off, yet its return down is
applied in full: the view ends lower than where the player was aiming, by up to the shot's recoil each time. Fix:
count only what `turn()` really applied.

### NTM: the ammo's own spread counts twice (NTM's code, not ours)
`com.hbm.items.weapon.sedna.factory.Lego.calcSpread` adds `config.spread × the gun's ammo multiplier`, and
`EntityBulletBaseMK4`'s constructor adds `config.spread` again before aiming the bullet. Shotgun shells and other
ammo with their own spread scatter wider than their numbers say, and the mod's spread settings scale only the first
part. Left as NTM has it: changing it would change NTM's balance. Found 2026-10-23 while tuning the spread settings.

## Fixed

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
