# NTM Reimagined Arms

## Versioning

The version is never changed by hand. The build counts it from git (`build.gradle.kts`, top); `neoforge.mods.toml`
holds only `${mod_version}` and `${mod_display_version}`, which the build fills in.

- `BUILD_OFFSET` = 82 is added to every count and stays as it is: main at 0.1.201 (`917c862`, 119 commits) is X201,
  the commit that brought this scheme in is X252. Numbers in between may be skipped.
- **main** (and any branch that is not `port/*`): N = `git rev-list --count HEAD` + offset.
  - for the loader: `<N>+mc<Minecraft version>`, e.g. `252+mc26.2`;
  - for players (the mod list, the jar, the CHANGELOG): `X<N>`, e.g. "NTM Reimagined Arms X252",
    `reimagined-arms-X252-mc26.2.jar`.
- **port/\*** branches: B = main's commits up to where the port left main, P = the port's own commits since:
  - B = `git rev-list --count $(git merge-base HEAD main)` + offset (`origin/main` when there is no local `main`);
  - P = `git rev-list --count $(git merge-base HEAD main)..HEAD`;
  - for the loader: `<B>.<P>+mc<Minecraft version>-<loader>`, e.g. `252.3+mc1.7.10-forge`;
  - for players: `X<B>.<P>`, jar `reimagined-arms-X252.3-mc1.7.10-forge.jar`.
  - The loader is `mod.loader` in `stonecutter.properties.toml`: each port sets its own.
- Without git: `0+mc<version>` (a port: `0+mc<version>-<loader>`), shown as `X0-dev`.

`CHANGELOG.md`: every release gets the heading `## X<build>` (`## X<B>.<P>` for a port), the build of the commit that
releases it. A change that breaks compatibility (configs, the network, the players' settings) gets its own line:
`⚠ Ломает совместимость: …`.
