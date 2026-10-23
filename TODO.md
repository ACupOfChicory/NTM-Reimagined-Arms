# Later

Ideas agreed on but put off.

## Local weakening of blocks

Shots that land near one spot of a block make the next bullet there pierce easier: the block's dt and dr drop
around recent hits, so a burst into one place breaks through where single shots would stop.

- Keep per block a few recent hit points (block-local, quantised, e.g. to a 4×4 grid per face) with the damage
  taken, forgotten after ~20 s like the crack wear in BulletServer's WEAR map.
- `PassableClip.pierced` scales the material's dt (and a bit of dr) down by the damage near the entry point.
- Cost: a lookup per pierce in a small map and a few floats per hit block; only blocks hit lately take memory.
- The author asked for this later (2026-10-23), after the material-based piercing and glass breaking.

## Bullets in water

Slow and stop bullets in water.
