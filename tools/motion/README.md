# How the bots move, against how people move

`feel.py` puts an AI mob's body as the game draws it and recordings of people into the same few numbers: how fast
and how smoothly the head, the chest and the pelvis turn and in which order, the jerk of each of the ten boxes of the
bot's model, how far a standing foot slides, how long a start and a stop take, how far the head moves over the
pelvis. Both sides are measured at 60 frames a second in the bot's size (its leg, 12 px, is 0.75 m); a recording is
first put on the bot's ten boxes (each box from its bone, as `tools/quaternius` does it).

`mocap.py` reads the recordings (BVH, CMU's ASF/AMC) into one skeleton with forward kinematics. It comes from the
owner's own animation tooling (the enarche project, `tools/motion/mocap.py`), taken with the owner's consent; the
code is as it was there, its comments put into English.

## Run

The bot's side comes from the bot stand's frames (`BotFeelStand`, `-PbotFeelStand`): a turn on the spot of 90 and
180 degrees at the aim's speed, a turn walking and running, a start and a stop, a crouch on the walk, the gun raised,
a strafe turned back, a hit. Each drawn frame goes to `notes/ai/bot-stand/<label>-feel.tsv`.

```
./gradlew runBenchBots -PbotFeelStand -PbotStandLabel=<label>      # in the stand's nested kwin
python3 -I tools/motion/feel.py bot notes/ai/bot-stand/<label>-feel.tsv
python3 -I tools/motion/feel.py people <motion-data dir>
python3 -I tools/motion/feel.py table notes/ai/bot-stand/<label>-feel.tsv <motion-data dir> [out.md]
```

Python 3 and numpy only. `<motion-data dir>` holds the downloaded sets as they unpack:
`100style/100STYLE/<Style>/<Style>_<Move>.bvh` with `Frame_Cuts.csv`, `cmu/all_asfamc/subjects/<n>/<n>_<k>.amc`
with `<n>.asf`. The recordings are not in this repository and do not go into it.

| Set | Where | License |
|---|---|---|
| 100STYLE (Ian Mason) | https://www.ianxmason.com/100style/, https://zenodo.org/records/8127870 | CC BY 4.0 |
| CMU Graphics Lab Motion Capture Database | http://mocap.cs.cmu.edu/ | free for all uses; the data itself may not be resold, even converted |

The recordings measured are listed in `people_sets` (`feel.py`): turns on the spot (100STYLE `Neutral_TR1`,
`Swat_TR1`, CMU 69_13 to 15), turns walking (CMU 16_17 to 20, 69_20 and 21) and running (CMU 16_41 to 44), starts and
stops (100STYLE `StartStop_FW`, `_FR`, CMU 16_33, 16_34 and sharp stops), walks, strafes and crouched walks of
100STYLE.

## What the numbers are

- **turn**: the peak speed (deg/s) and acceleration (deg/s²) of each yaw, smoothed by a Gaussian of sigma 2 frames;
  a turn is any run of 60 degrees and more of the three together.
- **lag**: when each of the head, the chest, the pelvis has done half of the turn; the bot's also against the yaw its
  client received from the server.
- **jerk**: the change of a box's angular speed from one frame to the next, deg/frame² at 60 fps; the bot's raw, a
  recording's after the Gaussian of sigma 1.5 frames a clip made from it would be smoothed with.
- **slide**: how far the lowest point of a standing foot moves over the floor, mm a frame and summed over each time it
  stands. The bot's foot stands where its sole is within 1 cm of the floor; a recorded foot where it is within 3 cm
  and slower than 0.25 m/s (its toe passes within a centimetre or two in its swing).
- **start, stop**: from under 0.1 m/s to 90 % of the most it goes at in the 1.5 s after, and back; the feet lifted.
- **head over the pelvis**: the most the head moves from its middle place over the pelvis, in the body's frame, cm.
  The bot's head may not leave the server's eye by more than 3 cm.
