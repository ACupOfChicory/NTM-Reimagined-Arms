# Quaternius UAL → Minecraft biped

Puts clips of Quaternius' Universal Animation Library 1 and 2 (CC0,
`animations/LICENSE-Quaternius-UAL.txt`) on the six parts of a Minecraft
`HumanoidModel`: head, body, both arms, both legs. Nothing in the mod plays
them yet.

## Run

Get the free `[Standard]` zips from https://quaternius.itch.io/universal-animation-library and
https://quaternius.itch.io/universal-animation-library-2, unpack each into its own empty folder, then:

```
python3 -I tools/quaternius/retarget.py \
  <UAL1>/Unreal-Godot/UAL1_Standard.glb <UAL2>/Unreal-Godot/UAL2_Standard.glb \
  tools/quaternius/animations tools/quaternius/check.json
UAL1_GLB=<UAL1>/Unreal-Godot/UAL1_Standard.glb python3 -I tools/quaternius/test_retarget.py
```

Python 3 and numpy only; the glb is read directly, no Blender.

## Output

`animations/ual1.animation.json`, `ual2.animation.json`: Bedrock animation JSON 1.8.0, linear keys.
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
- The body points from the neck to the middle of the hips; its twist comes from `spine_03`. The head takes the
  `Head` bone's turn as it is.
- The parts are siblings in Minecraft, so each one gets its own full turn. The head, body and arms hang from the
  neck, 12 px above the hips along the body; the legs hang from the hips, turned with the pelvis.
- The hips move with UAL's pelvis, scaled by leg length (12 px per UAL leg). Their height comes from the ground:
  the lowest corner of the six boxes rises above the ground as far as UAL's lowest joint rises above its rest
  height.
- Keys are dropped where linear interpolation stays within 0.5° and 0.05 px of every 30 fps frame.

## The check

For every frame the parts are rebuilt from the written keys and compared with the UAL bones:

- `rebuild`: the part's direction against shoulder→wrist (hip→ankle), the target itself. At most 0.85° over all
  86 clips: what the Euler angles and dropped keys cost.
- `arm_up`, `arm_fore`, `elbow` (and `leg_*`, `knee`): the part against the upper and lower bones, and the bend
  between them. Minecraft limbs do not bend, so this is what is lost.
- `hand_px`: how much shorter shoulder→wrist is than a straight arm, in pixels of a 10 px arm. The hand ends up this
  far beyond where UAL has it.
- `hip_px`, `ankle_px`: the hips' height and the ankle's place against UAL's, in pixels.

A clip's upper half is graded by `hand_px` (p95 ≤ 1 px good, ≤ 2.5 px fair), its lower half by the larger of
`hip_px` and `ankle_px` (≤ 1.5 px, ≤ 3 px).
