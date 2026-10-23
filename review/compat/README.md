# Untested NTM on a server

2026-10-23, Orange Pi, `./gradlew runDumpBlocks -PnoAssets` with three NTM jars made by `tools/spoof_ntm.py`
from `hbm-neoforge-1.0.3.jar`. The `.log` files are the mod's lines from each server log.

| NTM jar | what the mod did | check time |
|---|---|---|
| 1.0.3+1b8c6c2d (tested) | nothing: no check, no warning | — |
| 1.0.4 (same classes) | checked, 0 of 13 systems off, all mixins applied | 654 ms |
| 1.0.4, `Lego.calcSpread` renamed | MixinLegoSpread skipped, only "spread" off | 613 ms |

All three servers started and stopped normally. Not checked here (needs a client): the title screen warning, the
chat message on joining, `/gunfeel status`.
