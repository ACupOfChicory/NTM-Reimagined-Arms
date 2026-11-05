# NTM Reimagined Arms for NTM: Decentralized Edition 1.21.1

The mod for Minecraft 1.21.1 on NeoForge 21.1, beside NTM: Decentralized Edition (modid `hbm`). The build stands
alone (ModDevGradle, Java 21) and reads the 26.2 sources at the repository root: the root build is left alone.

## Setup

`libs/` is not in git. It holds the DE jar the players have, and the jars DE carries inside it:

1. `libs/ntm-de-1.21.1-1.0.0-beta.14.jar`: DE from CurseForge (file 9434569). `deps.ntm` in `gradle.properties`
   names it.
2. The jars inside it (`META-INF/jarjar/`), for the DE classes that name them:

   ```
   cd libs && unzip -j ntm-de-1.21.1-1.0.0-beta.14.jar 'META-INF/jarjar/flywheel-neoforge-*.jar' \
       'META-INF/jarjar/ponder-neoforge-*.jar' 'META-INF/jarjar/sable-companion-*.jar'
   ```

Then, in this directory:

```
./gradlew build        # the jar: build/libs/reimagined-arms-X<B>.<P>-mc1.21.1-de.jar
./gradlew test
./gradlew runClient    # a dev client with DE, game directory run/
```

## How the sources come together

- **Shared files.** Every file of the root's `src/` is the port's too. It is copied into `build/generated/shared/`
  with the 26.x names of `rewrite.txt` turned into their 1.21.1 or DE names (`com.hbm.backport.*`). Three kinds of
  lines:
  - `<26.x class> -> <class here>` replaces the class everywhere, in its JVM form (`net/minecraft/...`) too.
  - `re: <regex> => <replacement>` applies to every file, line by line.
  - `re@<File.java>: ...` applies to that one file.
- **Own copies.** A file under `src/` here with the same path as one at the root takes its place. These are the files
  a rule cannot fix, and the stubs of the parts not ported yet. A merge of main does not update them: they are kept in
  step by hand (the notes, «Копии»).
- **`port/Mc.java`.** The calls 26.2 makes in one go that 1.21.1 makes otherwise. `rewrite.txt` sends the 26.2 call
  here.
- **`skip.txt`.** The root's files the port leaves out for good: the in-game editors.
- **`pending.txt`.** What is not ported yet:
  - `[main]` and `[test]` list the files left out of the build.
  - `[failing]` lists the tests that compile but pass only with their part. Gradle's test filter leaves them out.

  `tools/prune.py main|test` adds the files that do not compile. Porting a part takes its lines out by hand.
- **`compat/Ported.java`.** `Ported.DONE` lists the systems that are ported. At start every other system is off.

## Tests

The shared tests run with the repository root as their working directory, and read its `src/main/resources` and
`src/test/pinned`. Two tests read the port's own resources:

- `CompatTest` reads `build/resources/main`: the mixin configs and `neoforge.mods.toml` (`ntmTested`) as the jar
  has them.
- `NtmApiTest` reads `src/main/resources/ntmgunfeel-ntm-api.txt` here. This is the list of what the port calls in
  DE. Rewrite it with `PIN_UPDATE=1 ./gradlew test --tests acup.gunfeel.compat.NtmApiTest`.
