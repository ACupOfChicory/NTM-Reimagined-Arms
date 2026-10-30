# NTM Reimagined Arms

An addon for HBM's Nuclear Tech Mod (NTM-NEXT) on NeoForge 26.2. It adds
first-person arms, recoil, weapon sway, detailed model hitboxes and
material-based bullet effects through mixins.

## Features

- Two-handed weapon grips and configurable arm animations.
- Camera recoil, weapon recoil and turn sway.
- An ammo HUD and radial ammo selection with damage information.
- Hold-to-aim controls and spread affected by movement, stance and sustained
  fire.
- Model-based mob hitboxes, body-zone damage and debug outlines through F3+B.
- Bullet penetration, ricochets, impact sounds and decals matched to block
  materials and visible geometry.
- Quality presets for impact effects, including low, medium, high and realism.

## Installation

Install the same addon jar on the client and server, together with the required
NTM-NEXT version and its dependencies. The mod is required on both sides.
Client and server builds must use the same network protocol.

Supported NTM versions are listed in
`src/main/resources/META-INF/neoforge.mods.toml`. With other versions,
compatibility checks disable systems whose NTM interfaces no longer match.
`/gunfeel status` lists loaded versions and disabled systems.

## Settings

Open the weapon editor with O while holding a gun. Client settings are stored
in `config/ntm-gunfeel.properties`; per-weapon settings are stored in
`config/ntm-gunfeel-guns.properties`.

`/gunfeel quality low|medium|high|realism` changes impact-effect quality. High
is the default. Individual limits changed manually in the configuration file
override preset values.

The server controls bullet speed, spread and other gameplay settings through
`config/ntm-gunfeel-server.properties`. Material and bullet behavior rules are
in `config/ntm-gunfeel-bullets.txt`; its header documents the available rules.
Gameplay settings are synchronized to connected clients, while visual
preferences remain local.

## Building

Use JDK 25. Put the NTM dependency at the path specified by `deps.ntm` in
`stonecutter.properties.toml`, then run `./gradlew build -PnoAssets`. Gradle
resolves Minecraft and NeoForge dependencies. The output jar is written to
`versions/26.2/build/libs/`.

Build numbers are calculated from Git history in `build.gradle.kts`. On main,
the number is the commit count plus `BUILD_OFFSET`; port branches use their
base version and port commit count. Version numbers are not maintained
manually.

The build runs JUnit tests, resource checks, compatibility checks and mixin
validation. `src/test/` contains the test sources and fixtures.
`./gradlew runClient` starts a development client with the configured
dependencies.

## License

Copyright (C) 2026 ACupOfChicory

Unless a file or section says otherwise, original code written for this project
is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version
(GPL-3.0-or-later). See [LICENSE](LICENSE).

Model hitbox code derives in part from Countered's Accurate Hitboxes (MIT). The
author, source link and license are in
[third-party/accurate-hitboxes.txt](third-party/accurate-hitboxes.txt), also
included in the jar under `META-INF/licenses/accurate-hitboxes.txt`.

Weapon turn sway in `WeaponSway.java` is adapted from Superb Warfare by
Mercurows and contributors under GPL-3.0-only. That file remains GPL-3.0-only;
the or-later grant above applies to this project's original code, not that
adapted code. The combined mod, which includes this GPL-3.0-only component, is
distributed under GPL-3.0. Source revision, changes and the license text are
included in [third-party/superb-warfare.txt](third-party/superb-warfare.txt)
and packaged under `META-INF/licenses/`.

Sounds under `src/main/resources/assets/ntmgunfeel/sounds/` come from
freesound.org and keep their own licenses (CC0 1.0 or CC BY 3.0/4.0); authors
and links are in
[CREDITS.txt](src/main/resources/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
