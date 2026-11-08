# Free humanoid clips → Minecraft biped

Puts clips of free (CC0) humanoid animation packs on the six parts of a Minecraft `HumanoidModel`: head, body, both
arms, both legs. Nothing in the mod plays them yet.

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
UAL1_GLB=<UAL1_Standard.glb> python3 -I tools/quaternius/test_retarget.py
```

Python 3 and numpy only; the glb is read directly, no Blender. Each rig's bones are named in `RIGS`.

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

## The check

For every frame the parts are rebuilt from the written keys and compared with the UAL bones:

- `rebuild`: the part's direction against shoulder→wrist (hip→ankle), the target itself; `rebuild_turn`: the whole
  turn, twist included, against the solved one; `mid`: the same halfway between two frames, where a player
  interpolates. Together: what the Euler angles and dropped keys cost.
- `arm_up`, `arm_fore`, `elbow` (and `leg_*`, `knee`): the part against the upper and lower bones, and the bend
  between them. Minecraft limbs do not bend, so this is what is lost.
- `hand_px`: how much shorter shoulder→wrist is than a straight arm, in pixels of a 10 px arm. The hand ends up this
  far beyond where UAL has it. Only the arm's length: the clavicle's shift is not counted.
- `hip_px`, `ankle_px`: the hips' height and the ankle's place against UAL's, in pixels, taken from the solved
  pose before the keys are written (dropping keys moves it by at most 0.05 px).

A clip's upper half is graded by `hand_px` (p95 ≤ 1 px good, ≤ 2.5 px fair), its lower half by the larger of
`hip_px` and `ankle_px` (≤ 1.5 px, ≤ 3 px).
