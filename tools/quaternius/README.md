# Free humanoid clips → Minecraft biped

Puts clips of free (CC0) humanoid animation packs on the six parts of a Minecraft `HumanoidModel`: head, body, both
arms, both legs. AI mobs play a few of them: `bot_clips.py` copies those into the mod.

| Pack | Author | License | Where | What we take |
|---|---|---|---|---|
| Universal Animation Library 1 and 2, `[Standard]` | Quaternius | CC0 (`animations/LICENSE-Quaternius-UAL.txt`) | https://quaternius.itch.io/universal-animation-library, https://quaternius.itch.io/universal-animation-library-2 | idle, walk, pistol, crouch, throw, hits, death, signals |
| KayKit Character Animations 1.1, Free | Kay Lousberg | CC0 (`animations/LICENSE-KayKit-Character-Animations.txt`) | https://kaylousberg.itch.io/kaykit-character-animations | rifle (aim, shoot, reload), crawl, crouch, sneak, lie down, throw, dodges |
| SWAT | Quaternius | CC0 (`animations/LICENSE-Quaternius-SWAT.txt`) | https://poly.pizza/m/Btfn3G5Xv4 | one-handed gun: hold, point, shoot, run and shoot |

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

## Choosing how a limb is carried over

A Minecraft arm or leg is one box where the clip has two bones. `retarget.MODES` are the ways to put it: along the
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

`retarget.py --choices anim-choices.json <animations dir> <check.json> TAG=<file.glb> ...` converts each clip in the
modes chosen and leaves out the ones not taken; without `--choices` the output is as before.

## Output

`animations/ual1.animation.json`, `ual2.animation.json`, `kaykit.animation.json`, `swat.animation.json`: Bedrock
animation JSON 1.8.0, linear keys.
Each clip listed in `SELECTED` (`retarget.py`) comes three times: `<clip>` (all six parts), `<clip>_upper`
(head, body, arms) and `<clip>_lower` (legs and a `root` bone). In the split halves the parts' positions leave out
the hips' shift and `root` carries it, so a player adds `root`'s position to every part.

Values are read the way PlayerAnimationLib reads them:

- `rotation` `[x, y, z]`, degrees: `ModelPart.xRot`, `yRot`, `zRot` from the default pose (all zero for a biped),
  applied as `Rz · Ry · Rx`;
- `position`: the pivot's shift from its default place, pixels, Y up (`ModelPart.y` takes `-y`).

`check.json`: every clip of both packs with how well it carries over (below).

## How a clip is carried over

- An arm points from shoulder to wrist, a leg from hip to ankle. The twist around the limb's length comes from the
  hand for an arm (it holds the gun) and from the thigh for a leg.
- The body points from the neck to the middle of the hips; its twist comes from the chest bone (UAL `spine_03`).
  The head takes the head bone's turn as it is.
- The parts are siblings in Minecraft, so each one gets its own full turn. The head, body and arms hang from the
  neck, 12 px above the hips along the body; the legs hang from the hips, turned with the pelvis.
- The hips move with UAL's pelvis, scaled by leg length (12 px per UAL leg). Their height comes from the ground:
  the lowest corner of the six boxes rises above the ground as far as UAL's lowest joint rises above its rest
  height.
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
- `arm_up`, `arm_fore`, `elbow` (and `leg_*`, `knee`): the part against the upper and lower bones, and the bend
  between them. Minecraft limbs do not bend, so this is what is lost.
- `hand_px`: how much shorter shoulder→wrist is than a straight arm, in pixels of a 10 px arm. The hand ends up this
  far beyond where UAL has it. Only the arm's length: the clavicle's shift is not counted. It is measured against
  the rest pose's arm: SWAT rests with bent elbows (26° and 46°), so there `hand_px` reads low and `elbow` is the
  honest number.
- `hip_px`, `ankle_px`: the hips' height and the ankle's place against UAL's, in pixels, taken from the solved
  pose before the keys are written (dropping keys moves it by at most 0.05 px).

A clip's upper half is graded by `hand_px` (p95 ≤ 1 px good, ≤ 2.5 px fair), its lower half by the larger of
`hip_px` and `ankle_px` (≤ 1.5 px, ≤ 3 px).
