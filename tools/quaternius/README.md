# Free humanoid clips → Minecraft biped

Puts clips of free (CC0) humanoid animation packs on the AI mobs' model of ten parts (`BotModel` in the mod): head,
body, and each arm and leg in two, upper arm and forearm, thigh and shin, as vanilla's biped cut at the elbow and the
knee. AI mobs play some of the clips: `bot_clips.py` copies those into the mod, the crouching ones with their legs
fitted under the head (`fit.py`).

| Pack | Author | License | Where | What we take |
|---|---|---|---|---|
| Universal Animation Library 1 and 2, `[Standard]` | Quaternius | CC0 (`animations/LICENSE-Quaternius-UAL.txt`) | https://quaternius.itch.io/universal-animation-library, https://quaternius.itch.io/universal-animation-library-2 | idle, walk, pistol, crouch, throw, hits, death, signals |
| KayKit Character Animations 1.1, Free | Kay Lousberg | CC0 (`animations/LICENSE-KayKit-Character-Animations.txt`) | https://kaylousberg.itch.io/kaykit-character-animations | rifle (aim, shoot, reload), crawl, crouch, sneak, lie down, throw, dodges |
| SWAT | Quaternius | CC0 (`animations/LICENSE-Quaternius-SWAT.txt`) | https://poly.pizza/m/Btfn3G5Xv4 | one-handed gun: hold, point, shoot, run and shoot; hits |

## Run

Unpack each download into its own empty folder (glTF/glb files: UAL `Unreal-Godot/UAL?_Standard.glb`, KayKit
`Animations/gltf/Rig_Medium/*.glb`, SWAT the poly.pizza glb), then name each file by its pack (`PACKS` in
`retarget.py`):

```
python3 -I tools/quaternius/retarget.py tools/quaternius/animations tools/quaternius/check.json \
  UAL1=<UAL1_Standard.glb> UAL2=<UAL2_Standard.glb> \
  KK_RANGED=<Rig_Medium_CombatRanged.glb> KK_GENERAL=<Rig_Medium_General.glb> \
  KK_MOVE_ADV=<Rig_Medium_MovementAdvanced.glb> KK_MOVE_BASIC=<Rig_Medium_MovementBasic.glb> \
  KK_SIM=<Rig_Medium_Simulation.glb> SWAT=<swat.glb>
UAL1_GLB=<UAL1_Standard.glb> KAYKIT_GLB=<Rig_Medium_General.glb> SWAT_GLB=<swat.glb> \
  python3 -I tools/quaternius/test_retarget.py
```

Python 3 and numpy only; the glb is read directly, no Blender. Each rig's bones are named in `RIGS`.

## The clips in the mod

```
python3 -I tools/quaternius/bot_clips.py     # writes src/main/resources/assets/ntmgunfeel/animations/bot.animation.json
python3 -I tools/quaternius/test_fit.py      # needs no pack
```

`bot_clips.py` names each clip the mod plays (`BOT`: the clip or its half as it is; `SPLICE`: clips cut and joined;
`FIT`: below) and why. A clip goes in where it is graded good or fair in the half used, or is played over the whole
body for an action's few ticks (the roll, the slide, the fall dead).

An AI mob's head may not leave the eye the server looks and shoots from (3 cm), and a clip lowers and sways the whole
body as its rig does. So a crouching clip is not taken as it is: `fit.py` moves it, frame by frame, till its neck is
where vanilla's crouch has it (the body's pivot 3.2 px down), and bends each leg again at the knee so its ankle is
back where the clip had it over the ground. The hips go with the head, the feet stay on the ground, no box goes into
it. `fit.fit` takes besides: `plant` (a leg that does not reach the ground is lowered onto it), `body` (the trunk's
turn in place of the clip's), `feet` (where the ankles stand, in place of the clip's) and `square` (each leg turned
from the rest pose, without the clip's twist of a thigh about its own length, which a box shows as a leg edge on).
The mod's two: `crouch_walk` (KayKit `Crouching`, its feet moved 0.4 px at most) and `kneel` (down on the right knee
as UAL `Fixing_Kneeling` kneels, the trunk upright for the gun).

## The picker page (six parts)

Kept from the model of six parts and no longer developed: there a Minecraft arm or leg was one box where the clip has
two bones. `retarget.MODES` are the ways to put it: along the
whole limb (`end`: shoulder to wrist, hip to ankle — the default), the upper bone (`upper`), the lower one (`lower`)
or halfway between the upper bone and the whole limb (`mix`). A picker page shows every clip of `SELECTED` in each
mode beside the rig's own skeleton:

```
python3 -I tools/quaternius/anim_picker.py <page dir> UAL1=<UAL1_Standard.glb> ...   # the same TAG=file pairs
```

It writes `<page dir>/index.html` and `<page dir>/data/*.js`; the page opens from disk with no server and no network
(plain WebGL, no libraries). Its choice, per clip the arms' mode, the legs' mode and whether to take the clip, is
saved as `anim-choices.json`:

```json
{"version": 1, "clips": {"UAL1/Idle_Loop": {"arms": "end", "legs": "upper", "take": true,
  "adjust": {"right_arm": {"rot": [-20, 0, 5], "pos": [0, 1, 0]}}}}}
```

`adjust` is the owner's correction where no mode looks right: one for the whole clip, on top of the modes. A part
named is turned by `rot` (degrees, Rz · Ry · Rx as ModelPart's xRot, yRot, zRot) around the model's axes at its pivot,
after its pose, and its pivot moved by `pos` (pixels, as Bedrock's position: X the model's left, Y up, Z back). On a
part in its default pose that is just adding to its angles. It comes after the clip is put on the ground, so a
leg moved down goes into the ground. Limits: ±180° and ±12 px. The page shows it in its "chosen" window as it is set.
`check.json`'s grades of a corrected clip measure it with the correction against the rig, as they would any other
difference.

`retarget.py --choices anim-choices.json <animations dir> <check.json> TAG=<file.glb> ...` leaves out the clips not
taken and applies `adjust`; the modes are not used any more (each bone has its part now), and it says so when a file
gives them.

## Output

`animations/ual1.animation.json`, `ual2.animation.json`, `kaykit.animation.json`, `swat.animation.json`: Bedrock
animation JSON 1.8.0, linear keys.
Each clip listed in `SELECTED` (`retarget.py`) comes three times: `<clip>` (all ten parts), `<clip>_upper`
(head, body, arms and forearms) and `<clip>_lower` (thighs, shins and a `root` bone). The forearm and the shin are
`right_forearm`, `left_forearm`, `right_shin`, `left_shin`: their turn is their own, from their upper part (the
model has them as its children), and they have no position. In the split halves the parts' positions leave out
the hips' shift and `root` carries it, so a player adds `root`'s position to every part.

Values are read the way PlayerAnimationLib reads them:

- `rotation` `[x, y, z]`, degrees: `ModelPart.xRot`, `yRot`, `zRot` from the default pose (all zero for a biped),
  applied as `Rz · Ry · Rx`;
- `position`: the pivot's shift from its default place, pixels, Y up (`ModelPart.y` takes `-y`).

`check.json`: every clip of both packs with how well it carries over (below).

## How a clip is carried over

- Bone by bone: the upper arm takes the upper arm bone (shoulder to elbow), the forearm the lower one (elbow to
  wrist), the thigh and the shin the same. The twist of an arm comes from the hand (it holds the gun), of a leg from
  the thigh.
- The body points from the neck to the middle of the hips; its twist comes from the chest bone (UAL `spine_03`).
  The head takes the head bone's turn as it is.
- The head, body and upper parts are siblings in Minecraft, so each one gets its own full turn; a forearm or a shin
  turns from its upper part. The head, body and arms hang from the neck, 12 px above the hips along the body; the
  legs hang from the hips, turned with the pelvis. The elbow is 4 px below the arm's pivot and 1 px in from it (on
  the arm's middle line, 6 px below its top), the knee 6 px below the hip.
- The hips move with UAL's pelvis, scaled by leg length (12 px per UAL leg), their height too; they are lifted only
  where a box would go into the ground.
- Where straight interpolation of the Euler angles between two 30 fps frames strays more than 2° from the pose
  halfway (a fast swing), frames are added in between, halving the step up to 4 times. Then keys are dropped where
  linear interpolation stays within 0.5° and 0.05 px of every frame.
- A loop's seam is checked: `loop_seam_deg` in `check.json` is how far its last frame is from its first. KayKit's
  `Ranged_*_Aiming` raise the gun from the idle pose and then hold it, so they are written as played once.
  `loop_seam_px` is how far the hips' shift (`root`) moves between the two.
- Clips that end in a pose to keep (the aims, deaths, `Lie_Down`) are written with
  `"loop": "hold_on_last_frame"`: a player keeps their last frame until it plays something else.
- Clips that scale a bone to nothing (KayKit's `Spawn_*`) are skipped: a bone's turn is not defined there.

## The check

For every frame the parts are rebuilt from the written keys and compared with the UAL bones:

- `rebuild`: the part's direction against shoulder→wrist (hip→ankle), the target itself; `rebuild_turn`: the whole
  turn, twist included, against the solved one; `mid`: the same halfway between two frames, where a player
  interpolates. Together: what the Euler angles and dropped keys cost.
- `arm_up`, `arm_fore` (and `leg_up`, `leg_low`): each part against its bone; `elbow`, `knee`: the bend there.
- `hand_px`, `foot_px`: how far the end of the forearm (shin) is from where the rig's wrist (ankle) would be on a
  model of its proportions, pixels.
- `hip_px`, `ankle_px`: the hips' height and the ankle's place against UAL's, in pixels, taken from the solved
  pose before the keys are written (dropping keys moves it by at most 0.05 px).

A clip's upper half is graded by `hand_px` (p95 ≤ 1 px good, ≤ 2.5 px fair), its lower half by the larger of
`hip_px` and `ankle_px` (≤ 1.5 px, ≤ 3 px).
