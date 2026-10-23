# Changelog

## X258

- Hit sounds fade with distance as they should: before, set nearer for a 16-block fade while they fade over 24, they
  played at a third of their volume or more up to 40 blocks and then stopped short.
- Hole limits: holes on ordinary blocks count by `holeMax` again (all of them went under `holeMaxMachines`).
- `/gunfeel quality` and the hole limits apply at once, not when the next hole is made.

## X254

- `/gunfeel testlog`: a test log of what bullets, holes, effects, settings and recoil do while you play
  (`logs/ntm-gunfeel-test-*.tsv`; an op switches the server's side on too). `tools/ingame_report.py` checks it
  against `review/INGAME-CHECKS.md`.
- ⚠ Ломает совместимость: сетевой протокол 3 (номер выстрела в ShotPayload, TestLogPayload) — клиент и сервер
  должны быть одной сборки.

## X252

- The version is counted from git: X<commits in main> (`252+mc26.2` for the loader); ports on `port/*` branches are
  X<B>.<P> (`252.3+mc1.7.10-forge`). The jar is now `reimagined-arms-X<build>-mc<Minecraft>.jar`. Before this:
  0.1.1 … 0.1.201, then the Pi work (materials, piercing, bullet rules, Gradle) without numbers.
