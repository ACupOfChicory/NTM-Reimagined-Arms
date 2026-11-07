import java.time.Duration
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// The mod for one Minecraft version (versions/<version>/), built by Stonecutter from the shared src/.
// The jar came out the same as the old build.sh's (./gradlew jarDiff): keep the javac flags, add nothing to it.
plugins {
    id("net.neoforged.moddev")
}

val root: File = rootProject.projectDir
// The version is counted from git, never set by hand. BUILD_OFFSET puts the count on the old
// numbers: main at 0.1.201 (917c862) had 119 commits, so it is X201; numbers in between may be skipped.
val BUILD_OFFSET = 82
val mcVersion = project.name // versions/<Minecraft version>/
val loader = property("mod.loader") as String
fun git(vararg args: String): String? = runCatching {
    val out = providers.exec {
        commandLine("git", *args)
        workingDir = root
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim() else null
}.getOrNull()
fun count(range: String): Int? = git("rev-list", "--count", range)?.toIntOrNull()

val branch = git("rev-parse", "--abbrev-ref", "HEAD")
// port/*: X<B>.<P>, B = main's commits up to where the port left it, P = the port's own commits since
val portBase = if (branch != null && branch.startsWith("port/"))
    (git("merge-base", "HEAD", "main") ?: git("merge-base", "HEAD", "origin/main")) else null
// (version for the loader, name for players, jar suffix)
val (modVersion, displayVersion, jarVersion) = if (portBase != null) {
    val b = count(portBase)?.plus(BUILD_OFFSET)
    val p = count("$portBase..HEAD")
    // for the loader: <B>.<P>+mc<Minecraft version>-<loader>
    if (b == null || p == null) Triple("0+mc$mcVersion-$loader", "X0-dev", "X0-dev-mc$mcVersion-$loader")
    else Triple("$b.$p+mc$mcVersion-$loader", "X$b.$p", "X$b.$p-mc$mcVersion-$loader")
} else {
    // main (and any other branch): <N>+mc<Minecraft version>
    val n = count("HEAD")?.plus(BUILD_OFFSET)
    if (n == null) Triple("0+mc$mcVersion", "X0-dev", "X0-dev-mc$mcVersion")
    else Triple("$n+mc$mcVersion", "X$n", "X$n-mc$mcVersion")
}
version = modVersion
tasks.jar {
    archiveVersion = jarVersion
    from(root.resolve("third-party")) { into("META-INF/licenses") }
}
base.archivesName = "reimagined-arms"

tasks.processResources {
    val props = mapOf("mod_version" to version.toString(), "mod_display_version" to displayVersion)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

repositories {
    mavenCentral()
}

val ntm = files(root.resolve(property("deps.ntm") as String))

// tools/: MixinCheck and JarDiff, run by the tasks below
val tools: SourceSet = sourceSets.create("tools") {
    java.setSrcDirs(listOf(root.resolve("tools")))
    java.include("*.java")
}

dependencies {
    compileOnly(ntm)
    // XZ for Java (0BSD, third-party/xz.txt): the machines' bullet shapes in the jar (ShapePack), its jar inside the mod's
    jarJar(implementation("org.tukaani:xz:1.10")!!)
    compileOnly(files(root.resolve("libs/mods/crankshaft-neoforge-1.6.0+mc26.2.jar")))
    testImplementation(files(root.resolve("libs/mods/crankshaft-neoforge-1.6.0+mc26.2.jar")))
    // NTM loads as a mod in the dev runs (runDumpBlocks); the mod jar never contains it
    runtimeOnly(ntm)
    testImplementation(ntm)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // MixinCheck shares its checks with the game's (acup.gunfeel.compat.MixinProbe)
    "toolsImplementation"(sourceSets.main.get().output)
    "toolsImplementation"("org.ow2.asm:asm-tree:9.10.1")
    "toolsImplementation"("org.ow2.asm:asm-util:9.10.1")
}

neoForge {
    enable {
        version = property("deps.neoforge") as String
        // no decompiling and recompiling Minecraft: the mod only compiles against it
        setDisableRecompilation(true)
    }

    mods {
        register(property("mod.id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            client()
            gameDirectory = root.resolve("run")
        }
        register("checkClient") {
            client()
            gameDirectory = root.resolve("run-client-check")
            systemProperty("ntmgunfeel.armEditorSelfTest",providers.gradleProperty("armEditorSelfTest").isPresent.toString())
            // -ParmScreenSelfTest: the arm editor screen on five guns: every button, the layout, "what moves the hand" per frame
            systemProperty("ntmgunfeel.armScreenSelfTest",providers.gradleProperty("armScreenSelfTest").isPresent.toString())
            systemProperty("ntmgunfeel.weaponSwaySelfTest",providers.gradleProperty("weaponSwaySelfTest").isPresent.toString())
            // -PmachineGlass: windows of real NTM machines are glass (MachineGlassCheck) instead of the smoke check
            systemProperty("ntmgunfeel.machineGlassCheck",providers.gradleProperty("machineGlass").isPresent.toString())
            // -PfenceSeam: holes where two fences, walls, panes or bars meet lie on their pixels (FenceSeamCheck)
            systemProperty("ntmgunfeel.fenceSeamCheck",providers.gradleProperty("fenceSeam").isPresent.toString())
            // -PfenceAir: bullets through the empty part of fences, walls, panes and bars, by what they draw (FenceAirStand)
            systemProperty("ntmgunfeel.fenceAir",providers.gradleProperty("fenceAir").isPresent.toString())
            // -PwaterFx: the water effects counted per kind per shot on four pools, the surface audit, frames (WaterFxStand)
            systemProperty("ntmgunfeel.waterFx",providers.gradleProperty("waterFx").isPresent.toString())
            // -PglassBack[=fabulous]: glass seen through, and holes on its far side seen through it (GlassBackCheck)
            systemProperty("ntmgunfeel.glassBack",providers.gradleProperty("glassBack").map { it.ifBlank { "fancy" } }.orElse("").get())
            // -PspectatorStand: may a spectator use an NTM gun (SpectatorStand)
            systemProperty("ntmgunfeel.spectatorStand",providers.gradleProperty("spectatorStand").isPresent.toString())
            // -PhudShots: real frames of the corner HUD and the ammo menu into notes/gl54/hud-real (HudShots); the window is
            // 1920 x 1080; -PhudShotsGl54=<quads file>: that model's silhouette made with the mod's code
            systemProperty("ntmgunfeel.hudShots",providers.gradleProperty("hudShots").isPresent.toString())
            systemProperty("ntmgunfeel.hudShotsDir",providers.gradleProperty("hudShotsDir").orElse(root.resolve("notes/hud-shots").path).get())
            providers.gradleProperty("hudShotsGl54").orNull?.let { systemProperty("ntmgunfeel.hudShotsGl54",it) }
            // -Pgl54Stand=<dir>: the GL-54 in the game in numbers and frames (Gl54Stand): poses at five view turns, the bolt,
            // the case, the grenade (damage by distance, glass, the dud, leaves)
            systemProperty("ntmgunfeel.gl54Stand",providers.gradleProperty("gl54Stand").isPresent.toString())
            providers.gradleProperty("gl54Stand").orNull?.let { systemProperty("ntmgunfeel.gl54StandDir",it) }
            // -Pgl54StandMag (with -Pgl54Stand=<dir>): only the magazines' plan: reloads with their ticks, the pour, a change of
            // kind, a mixed magazine, the HUD's frames (with the block and without), the wheel and the turned camera
            systemProperty("ntmgunfeel.gl54StandMag",providers.gradleProperty("gl54StandMag").isPresent.toString())
            // -PproneStand: lying down on the prone key, its spread, recoil and hits (ProneStand)
            systemProperty("ntmgunfeel.proneStand",providers.gradleProperty("proneStand").isPresent.toString())
            // -Pgl54ReflexStand=<dir> (with -PstandSize=1920x1080): the GL-54's reflex sight, range finder, fuse and laser in numbers
            // and frames (Gl54ReflexStand); -Pgl54ReflexSolve solves the aim pose from the probe, -Pgl54ReflexRelief=<units> sets the eye relief it aims for
            systemProperty("ntmgunfeel.gl54ReflexStand",providers.gradleProperty("gl54ReflexStand").isPresent.toString())
            providers.gradleProperty("gl54ReflexStand").orNull?.let { systemProperty("ntmgunfeel.gl54ReflexStandDir",it) }
            systemProperty("ntmgunfeel.gl54ReflexSolve",providers.gradleProperty("gl54ReflexSolve").isPresent.toString())
            providers.gradleProperty("gl54ReflexRelief").orNull?.let { systemProperty("ntmgunfeel.gl54ReflexRelief",it) }
            // -PshapesStand=<file>: every NTM machine taken in turn, the jar's table of their shapes written there (ShapesStand)
            systemProperty("ntmgunfeel.shapesStand",providers.gradleProperty("shapesStand").orElse("").get())
            // -PshapesFallback: a machine in the jar's table of shapes is met from there, one left out of it is taken in
            // the game and comes out as the table has it (ShapesFallbackCheck)
            systemProperty("ntmgunfeel.shapesFallback",providers.gradleProperty("shapesFallback").isPresent.toString())
            if(providers.gradleProperty("shapesFallback").isPresent) systemProperty("ntmgunfeel.shapesSkip","hbm:machine_chemical_factory")
            // -PshapesOnly=a,b: only the machines whose names hold one of these (the report alone is what is wanted)
            systemProperty("ntmgunfeel.shapesOnly",providers.gradleProperty("shapesOnly").orElse("").get())
            // -PshapesResume: a stand cut short goes on from <file>.parts, the machines kept there not taken again
            systemProperty("ntmgunfeel.shapesResume",providers.gradleProperty("shapesResume").isPresent.toString())
            // -PsmokeStand=<dir>: smoke clouds measured, frame times and screenshots there (SmokeCloudStand);
            // -PstandSize=1920x1080 the window, -PsmokeBackend=vulkan the renderer
            systemProperty("ntmgunfeel.smokeStand",providers.gradleProperty("smokeStand").orElse("").get())
            // -PsmokePoses=sky,wall: only the poses whose names start with one of these
            systemProperty("ntmgunfeel.smokePoses",providers.gradleProperty("smokePoses").orElse("").get())
            // the shapes stand keeps every machine's shapes it takes (with their faces, over a gigabyte)
            jvmArgument(if(providers.gradleProperty("shapesStand").isPresent) "-Xmx6G" else if(providers.gradleProperty("smokeStand").isPresent || providers.gradleProperty("gl54Stand").isPresent || providers.gradleProperty("gl54ReflexStand").isPresent || providers.gradleProperty("waterFx").isPresent || providers.gradleProperty("hudShots").isPresent) "-Xmx3G" else "-Xmx2500M")
            // -PgcLog=<file>: the collector's pauses with their times, to set against a long frame
            providers.gradleProperty("gcLog").orNull?.let { jvmArgument("-Xlog:gc*:file=$it:uptime,time") }
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("clientcheck/result.txt").get().asFile.path)
            val size = providers.gradleProperty("standSize").orElse(if(providers.gradleProperty("hudShots").isPresent || providers.gradleProperty("gl54StandMag").isPresent) "1920x1080" else "854x480").get().split("x")
            programArguments.addAll("--width", size[0], "--height", size[1])
            providers.gradleProperty("smokeBackend").orNull?.let { programArguments.addAll("--graphicsBackend", it) }
        }
        register("checkMobs") {
            client()
            gameDirectory = root.resolve("run-mob-check")
            jvmArgument("-Xmx2500M")
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("mobcheck/result.txt").get().asFile.path)
            systemProperty("ntmgunfeel.checkMobs", "true")
            systemProperty("MC_DEBUG_ENABLED", "true")
            systemProperty("MC_DEBUG_PREFER_WAYLAND", "true")
            providers.gradleProperty("mobCases").orNull?.let { systemProperty("ntmgunfeel.mobCases", it) }
            providers.gradleProperty("mobHealth").orNull?.let { systemProperty("ntmgunfeel.mobHealth", it) }
            systemProperty("ntmgunfeel.contourShots",providers.gradleProperty("mobContourShots").isPresent.toString())
            systemProperty("ntmgunfeel.checkHeads",providers.gradleProperty("checkHeads").isPresent.toString())
            systemProperty("ntmgunfeel.checkLimbs",providers.gradleProperty("checkLimbs").isPresent.toString())
            // -PcheckHeadBindings: строки привязки головы и конечностей; -PdumpParts: части каждой позы в notes/head-labels/research
            systemProperty("ntmgunfeel.checkHeadBindings",providers.gradleProperty("checkHeadBindings").isPresent.toString())
            systemProperty("ntmgunfeel.checkTurn",providers.gradleProperty("checkTurn").isPresent.toString())
            systemProperty("ntmgunfeel.dumpParts",providers.gradleProperty("dumpParts").isPresent.toString())
            systemProperty("ntmgunfeel.checkExplosives",providers.gradleProperty("checkExplosives").isPresent.toString())
            // -PcheckNpcShots: пули турелей и NPC NTM по мобу и блокам против той же пули у игрока (NpcShotSmoke)
            systemProperty("ntmgunfeel.checkNpcShots",providers.gradleProperty("checkNpcShots").isPresent.toString())
            systemProperty("ntmgunfeel.checkAvatarLayers",providers.gradleProperty("checkAvatarLayers").isPresent.toString())
            if(providers.gradleProperty("selectionSelfTest").isPresent) {
                systemProperty("ntmgunfeel.mobReview","true")
                systemProperty("ntmgunfeel.limbLabels","true")
                systemProperty("ntmgunfeel.selectionSelfTest","true")
                systemProperty("ntmgunfeel.checkGlyphidCreases",providers.gradleProperty("checkGlyphidCreases").isPresent.toString())
                systemProperty("ntmgunfeel.headLabelDir",root.resolve("notes/selection-self-test").path)
                systemProperty("ntmgunfeel.mobReviewDir",root.resolve("notes/selection-self-test/review").path)
            }
            programArguments.addAll("--width", if(providers.gradleProperty("mobContourShots").isPresent) "1708" else "854",
                    "--height", if(providers.gradleProperty("mobContourShots").isPresent) "960" else "480")
        }
        register("benchHoles") {
            client()
            val loaded=providers.gradleProperty("holeBenchLoaded").isPresent
            gameDirectory=root.resolve(if(loaded) "run-hole-bench-loaded" else "run-hole-bench")
            jvmArgument(if(loaded) "-Xmx6000M" else "-Xmx3000M")
            val label=providers.gradleProperty("holeBenchLabel").orElse("before").get()
            require(label.matches(Regex("[a-zA-Z0-9_-]+")))
            systemProperty("ntmgunfeel.checkClient",layout.buildDirectory.file("holebench/result.txt").get().asFile.path)
            systemProperty("ntmgunfeel.benchHoles","true")
            systemProperty("ntmgunfeel.holeBenchLegacy",providers.gradleProperty("holeBenchLegacy").isPresent.toString())
            systemProperty("ntmgunfeel.holeBenchFile",root.resolve("notes/hole-bench/$label.tsv").path)
            providers.gradleProperty("holeBenchSurfaces").orNull?.let { systemProperty("ntmgunfeel.holeBenchSurfaces",it) }
            providers.gradleProperty("holeBenchRepeats").orNull?.let { systemProperty("ntmgunfeel.holeBenchRepeats",it) }
            providers.gradleProperty("holeBenchShells").orNull?.let { systemProperty("ntmgunfeel.holeBenchShells",it) }
            providers.gradleProperty("holeBenchModes").orNull?.let { systemProperty("ntmgunfeel.holeBenchModes",it) }
            systemProperty("ntmgunfeel.holeBenchCheckMotion",providers.gradleProperty("holeBenchCheckMotion").isPresent.toString())
            systemProperty("ntmgunfeel.holeBenchLoaded",loaded.toString())
            systemProperty("ntmgunfeel.holeBenchRealism",providers.gradleProperty("holeBenchRealism").isPresent.toString())
            providers.gradleProperty("holeBenchOrigin").orNull?.let { systemProperty("ntmgunfeel.holeBenchOrigin",it) }
            systemProperty("MC_DEBUG_ENABLED","true")
            systemProperty("MC_DEBUG_PREFER_WAYLAND","true")
            jvmArgument("-XX:StartFlightRecording=filename=${root.resolve("notes/hole-bench/$label.jfr").path},settings=profile,dumponexit=true")
            programArguments.addAll("--width",if(loaded) "1920" else "1280","--height",if(loaded) "1080" else "800")
        }
        register("benchMobs") {
            client()
            gameDirectory = root.resolve("run-mob-bench")
            jvmArgument("-Xmx3000M")
            val label=providers.gradleProperty("mobBenchLabel").orElse("before").get()
            require(label.matches(Regex("[a-zA-Z0-9_-]+")))
            systemProperty("ntmgunfeel.checkClient",layout.buildDirectory.file("mobbench/result.txt").get().asFile.path)
            systemProperty("ntmgunfeel.benchMobs","true")
            systemProperty("ntmgunfeel.benchLegacy",providers.gradleProperty("mobBenchLegacy").isPresent.toString())
            providers.gradleProperty("mobBenchScenes").orNull?.let { systemProperty("ntmgunfeel.benchScenes",it) }
            // -PmobBenchLimit=<ms>[,<walking ms>]: fail a scene whose F3+B adds more than that over vanilla's F3+B;
            // -PcontourAge: count the contour points older than the last frame's model or -PcontourLag=<ms> (1/60 s)
            providers.gradleProperty("mobBenchLimit").orNull?.let { systemProperty("ntmgunfeel.benchLimit",it) }
            systemProperty("ntmgunfeel.contourAge",providers.gradleProperty("contourAge").isPresent.toString())
            providers.gradleProperty("contourLag").orNull?.let { systemProperty("ntmgunfeel.contourLag",it) }
            providers.gradleProperty("mobBenchFps").orNull?.let { systemProperty("ntmgunfeel.benchFps",it) }
            systemProperty("ntmgunfeel.benchFile",root.resolve("notes/mob-bench/$label.tsv").path)
            systemProperty("MC_DEBUG_ENABLED","true")
            systemProperty("MC_DEBUG_PREFER_WAYLAND","true")
            jvmArgument("-XX:StartFlightRecording=filename=${root.resolve("notes/mob-bench/$label.jfr").path},settings=profile,dumponexit=true")
            programArguments.addAll("--width","1280","--height","800")
        }
        // the glass stand (GlassStand): NTM machines with clear pixels in rows, for the owner to say glass or empty;
        // -PglassStand=survey finds them and writes notes/glass-stand/list.tsv
        register("glassStand") {
            client()
            gameDirectory = root.resolve("run-glass-stand")
            jvmArgument("-Xmx3000M")
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("glassstand/result.txt").get().asFile.path)
            systemProperty("ntmgunfeel.glassStand", providers.gradleProperty("glassStand").orNull ?: "stand")
            systemProperty("ntmgunfeel.glassStandDir", root.resolve("notes/glass-stand").path)
            systemProperty("ntmgunfeel.glassStandRules", root.resolve("src/main/resources/ntmgunfeel-machine-glass.txt").path)
            systemProperty("ntmgunfeel.glassStandCheck", providers.gradleProperty("glassStandCheck").isPresent.toString())
            programArguments.addAll("--width","1600","--height","900")
        }
        register("reviewMobs") {
            client()
            gameDirectory = root.resolve("run-mob-review")
            jvmArgument("-Xmx3000M")
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("mobreview/result.txt").get().asFile.path)
            systemProperty("ntmgunfeel.checkMobs", "true")
            systemProperty("ntmgunfeel.mobReview", "true")
            val selfTest=providers.gradleProperty("mobReviewSelfTest").isPresent
            systemProperty("ntmgunfeel.headLabels",providers.gradleProperty("headLabels").isPresent.toString())
            systemProperty("ntmgunfeel.limbLabels",providers.gradleProperty("limbLabels").isPresent.toString())
            systemProperty("ntmgunfeel.headLabelSelfTest",providers.gradleProperty("headLabelSelfTest").isPresent.toString())
            systemProperty("ntmgunfeel.headCutSelfTest",providers.gradleProperty("headCutSelfTest").isPresent.toString())
            systemProperty("ntmgunfeel.headLabelDir",root.resolve(if(providers.gradleProperty("headLabelSelfTest").isPresent) "notes/head-labels/self-test" else if(providers.gradleProperty("limbLabels").isPresent) "notes/limb-labels" else "notes/head-labels").path)
            systemProperty("ntmgunfeel.mobReviewSelfTest", selfTest.toString())
            val sweep=providers.gradleProperty("mobRenderSweep").isPresent
            systemProperty("ntmgunfeel.mobRenderSweep",sweep.toString())
            systemProperty("ntmgunfeel.checkExtraBoxes",providers.gradleProperty("mobCheckExtraBoxes").isPresent.toString())
            systemProperty("ntmgunfeel.checkHeads",providers.gradleProperty("checkHeads").isPresent.toString())
            systemProperty("ntmgunfeel.checkHeadBindings",providers.gradleProperty("checkHeadBindings").isPresent.toString())
            systemProperty("ntmgunfeel.checkTurn",providers.gradleProperty("checkTurn").isPresent.toString())
            systemProperty("ntmgunfeel.mobBabiesOnly",providers.gradleProperty("mobBabiesOnly").isPresent.toString())
            providers.gradleProperty("mobNamespaces").orNull?.let { systemProperty("ntmgunfeel.mobNamespaces",it) }
            providers.gradleProperty("mobCases").orNull?.let { systemProperty("ntmgunfeel.mobCases",it) }
            providers.gradleProperty("mobStart").orNull?.let { systemProperty("ntmgunfeel.mobStart",it) }
            systemProperty("ntmgunfeel.mobReviewDir", root.resolve(if(sweep) "notes/external-mob-review/sweep" else if(selfTest) "notes/mob-review/self-test" else if(providers.gradleProperty("mobBabiesOnly").isPresent) "notes/mob-review/babies" else "notes/mob-review").path)
            systemProperty("MC_DEBUG_ENABLED", "true")
            systemProperty("MC_DEBUG_PREFER_WAYLAND", "true")
            programArguments.addAll("--width", "1280", "--height", "800")
        }
        // a dedicated server that writes what the game knows about every block (BlockDump) and stops before any
        // world: the facts the material tests run on. No client, no assets, no world generation.
        register("dumpBlocks") {
            server()
            gameDirectory = root.resolve("run-dump")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.dumpBlocks", layout.buildDirectory.file("blockdump/blocks.tsv").get().asFile.path)
        }
        // a dedicated server that checks the server's settings for the players (SettingsCheck): it plays the server and a
        // player on another game's server, writes versions/26.2/build/settingscheck/result.txt and stops before any world
        register("checkSettings") {
            server()
            gameDirectory = root.resolve("run-check")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkSettings", layout.buildDirectory.file("settingscheck/result.txt").get().asFile.path)
        }
        // bullets on real blocks in a small flat world (BulletCheck)
        register("checkBullets") {
            server()
            gameDirectory = root.resolve("run-bullets")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkBullets", layout.buildDirectory.file("bulletcheck/result.txt").get().asFile.path)
        }
        // the living spread in power armour (SpreadCheck): ./gradlew runCheckSpread -PnoAssets, the result is a tsv
        register("checkSpread") {
            server()
            gameDirectory = root.resolve("run-spread")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkSpread", layout.buildDirectory.file("spreadcheck/result.tsv").get().asFile.path)
        }
        // bullets in water: a basin in a flat world (WaterCheck), the result is a tsv
        register("checkWater") {
            server()
            gameDirectory = root.resolve("run-water")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkWater", layout.buildDirectory.file("watercheck/result.tsv").get().asFile.path)
        }
        // the explosives in water (ExplosiveCheck): ./gradlew runCheckBoom -PnoAssets
        register("checkBoom") {
            server()
            gameDirectory = root.resolve("run-boom")
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkBoom", layout.buildDirectory.file("boomcheck/result.tsv").get().asFile.path)
        }
        // -Ptrace=<file> (relative to the run's folder) or -Ptrace=true: the run writes a trace (Trace.java) to compare
        // with tools/trace_diff.py; -PtraceScenario=<name>, -PtraceHex, -Pseed=<n> go with it
        configureEach {
            providers.gradleProperty("trace").orNull?.let { systemProperty("ntmgunfeel.trace", it) }
            providers.gradleProperty("traceScenario").orNull?.let { systemProperty("ntmgunfeel.traceScenario", it) }
            if (providers.gradleProperty("traceHex").isPresent) systemProperty("ntmgunfeel.traceHex", "true")
            providers.gradleProperty("seed").orNull?.let { systemProperty("ntmgunfeel.seed", it) }
            // -Pscenario=arms (runCheckClient, with -Ptrace): TraceScenario plays the guns instead of the smoke check;
            // -PscenarioGuns=<id,...> or all
            providers.gradleProperty("scenario").orNull?.let { systemProperty("ntmgunfeel.scenario", it) }
            providers.gradleProperty("scenarioGuns").orNull?.let { systemProperty("ntmgunfeel.scenarioGuns", it) }
            providers.gradleProperty("scenarioArmor").orNull?.let { systemProperty("ntmgunfeel.scenarioArmor", it) }
        }
    }
}

// the tests see what the mod compiles against, the Minecraft client included
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

tasks.withType<JavaCompile>().configureEach {
    // javac's -g changes the code itself (constant locals are kept): only source and lines, as javac does by default
    options.release = 25
    options.encoding = "UTF-8"
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines"
    options.compilerArgs.addAll(listOf("-proc:none", "-nowarn"))
}

tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("headDataset").orNull?.let {
        systemProperty("ntmgunfeel.headDataset",it)
        inputs.file(file(it).resolve("index.json"))
        inputs.dir(file(it).resolve("samples"))
        outputs.file(file(it).resolve("research/oracle.json"))
    }
    // the tests read src/main/resources and src/test/pinned from the repository root
    workingDir = root
    inputs.property("pinUpdate", providers.environmentVariable("PIN_UPDATE").orElse(""))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The mixins against the classes they change (tools/MixinCheck.java): Mixin itself only reports a miss by crashing.
val unpackedJar = tasks.register<Sync>("unpackJar") {
    from(zipTree(tasks.jar.flatMap { it.archiveFile }))
    into(layout.buildDirectory.dir("mixincheck"))
}
val mixinCheck = tasks.register<JavaExec>("mixinCheck") {
    group = "verification"
    description = "Checks every mixin against the Minecraft, NeoForge and NTM classes it changes."
    dependsOn(unpackedJar)
    classpath = tools.runtimeClasspath
    mainClass = "MixinCheck"
    val game = sourceSets.main.get().compileClasspath
    val classes = unpackedJar.map { it.destinationDir.path }
    argumentProviders.add(CommandLineArgumentProvider {
        // Необязательная проверка адаптера к установленному Crankshaft и вложенному Vanillate.
        val crankshaft = root.resolve("libs/mods/crankshaft-neoforge-1.6.0+mc26.2.jar")
        val extra=providers.gradleProperty("compatCheckMods").orNull?.let { file(it).listFiles()?.toList() }.orEmpty()
        listOf(classes.get(), (game.files + listOf(crankshaft).filter { it.exists() } + extra)
            .filter { it.name.endsWith(".jar") }.joinToString(":"))
    })
}
tasks.check { dependsOn(mixinCheck) }

// the dump server needs the EULA accepted in its own folder
val checkRuns = mapOf("runDumpBlocks" to "run-dump", "runCheckSettings" to "run-check", "runCheckBullets" to "run-bullets", "runCheckWater" to "run-water", "runCheckBoom" to "run-boom", "runCheckSpread" to "run-spread")
// Every stand server opens its port before it stops or makes its world, each on one of its own: 25565 is where the real
// servers are (one running there would leave the stand without a port, the stand would not start). -PserverPort=<n>
// sets another, 25565 included.
val checkPorts = mapOf("runCheckBullets" to 25640, "runCheckWater" to 25641, "runCheckBoom" to 25642, "runCheckSettings" to 25643, "runDumpBlocks" to 25644, "runCheckSpread" to 25645)
tasks.matching { it.name in checkRuns }.configureEach {
    val dir = root.resolve(checkRuns.getValue(name))
    val port = providers.gradleProperty("serverPort").orNull?.takeIf { it.isNotBlank() } ?: checkPorts.getValue(name).toString()
    doFirst {
        // the dump stops the server before any world: what a stopped run left of one would only break the next
        dir.resolve("world").deleteRecursively()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        val properties = dir.resolve("server.properties")
        // the smallest world that still has one: flat, two chunks around, nothing generated on it
        if (name == "runCheckBullets" || name == "runCheckWater" || name == "runCheckBoom" || name == "runCheckSpread") properties.writeText(
            "level-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n" +
            "spawn-protection=0\nonline-mode=false\nspawn-monsters=false\nspawn-animals=false\nmax-players=1\n" +
            "server-port=$port\n" + (if (name == "runCheckSpread") "pause-when-empty-seconds=0\n" else ""))
        // the dump and the settings check make no world: only the port is set, the rest of the file left as the server wrote it
        else properties.writeText((if (properties.exists()) properties.readLines().filter { !it.startsWith("server-port=") } else emptyList())
            .joinToString("") { it + "\n" } + "server-port=$port\n")
    }
}

// Настоящий клиент использует только копию тестового мира и отдельную папку запуска.
tasks.matching { it.name in setOf("runCheckClient","runCheckMobs","runReviewMobs","runBenchMobs","runBenchHoles","runGlassStand") }.configureEach {
    val stand = name == "runGlassStand"
    val survey = stand && (providers.gradleProperty("glassStand").orNull == "survey" || providers.gradleProperty("glassStandCheck").isPresent)
    val review = name == "runReviewMobs" || (stand && !survey)
    val bench = name == "runBenchMobs"
    val holes = name == "runBenchHoles"
    val smoke = name == "runCheckClient" && providers.gradleProperty("smokeStand").isPresent
    val mobs = name == "runCheckMobs" || review
    val selfTest = providers.gradleProperty("mobReviewSelfTest").isPresent || providers.gradleProperty("headLabelSelfTest").isPresent
    // a trace scenario on every gun (-Pscenario, -PscenarioGuns=all) plays for about half an hour; the shapes stand
    // (-PshapesStand) takes every machine in turn
    val scenario = providers.gradleProperty("scenario").isPresent || providers.gradleProperty("shapesStand").isPresent || providers.gradleProperty("fenceSeam").isPresent || providers.gradleProperty("fenceAir").isPresent
    timeout.set(if(survey) Duration.ofMinutes(40) else if(review && !selfTest) Duration.ofDays(1) else if((mobs && !selfTest) || scenario) Duration.ofHours(if(scenario) 2 else 1) else Duration.ofMinutes(if(holes || bench || smoke) 30 else 8))
    val result = if(stand) "glassstand/result.txt" else if(holes) "holebench/result.txt" else if(bench) "mobbench/result.txt" else if(review) "mobreview/result.txt" else if (mobs) "mobcheck/result.txt" else "clientcheck/result.txt"
    doFirst {
        if(holes || review || stand) {
            val runName=if(stand) "glassStand" else if(review) "reviewMobs" else "benchHoles"
            val vmArgs="@${layout.buildDirectory.file("moddev/${runName}RunVmArgs.txt").get().asFile.absolutePath}"
            val active=ProcessHandle.allProcesses().use { processes ->
                processes.filter { it.info().arguments().orElse(emptyArray()).contains(vmArgs) }.map { it.pid() }.toList()
            }
            check(active.isEmpty()) { "Прежний тестовый клиент ещё работает: PID $active. Второе окно не запускается." }
        }
        if(review && providers.gradleProperty("mobReviewSelfTest").isPresent) root.resolve("notes/mob-review/self-test").deleteRecursively()
        if(review && providers.gradleProperty("headLabelSelfTest").isPresent) root.resolve("notes/head-labels/self-test").deleteRecursively()
        val dir = root.resolve(if(stand) "run-glass-stand" else if(holes) { if(providers.gradleProperty("holeBenchLoaded").isPresent) "run-hole-bench-loaded" else "run-hole-bench" } else if(bench) "run-mob-bench" else if(review) "run-mob-review" else if (mobs) "run-mob-check" else "run-client-check")
        if(holes) root.resolve("notes/hole-bench").mkdirs()
        // in the stand's nested kwin (notes/ports/vkwin.sh) MC_DEBUG_PREFER_WAYLAND has GLFW take its Wayland, and the
        // first mouse grab (wp_pointer_warp_v1) breaks the connection: the client closes in the first phase. The
        // stand's Xwayland works.
        if((holes || bench) && System.getenv("XDG_RUNTIME_DIR")?.contains("/vkwin-home/")==true) (this as JavaExec).environment.remove("WAYLAND_DISPLAY")
        if(bench) root.resolve("notes/mob-bench").mkdirs()
        val source = root.resolve("run-bullets/world")
        require(source.resolve("level.dat").exists()) { "Сначала выполните runCheckBullets: нужен изолированный тестовый мир" }
        val world = dir.resolve("saves/gunfeel-smoke")
        if((review || holes || stand) && world.resolve("session.lock").exists()) {
            FileChannel.open(world.resolve("session.lock").toPath(),StandardOpenOption.WRITE).use { channel ->
                val lease=channel.tryLock()
                check(lease!=null) { "Тестовый мир уже открыт. Закройте прежний клиент перед новым запуском." }
                lease.release()
            }
        }
        if(!review || stand || !world.resolve("level.dat").exists()) {
            world.deleteRecursively()
            if(holes && providers.gradleProperty("holeBenchLoaded").isPresent) {
                val saved=file(providers.gradleProperty("holeBenchWorld").get()).canonicalFile
                world.parentFile.mkdirs()
                check(ProcessBuilder("cp","--reflink=always","-a",saved.path,world.path).inheritIO().start().waitFor()==0)
            } else source.copyRecursively(world, overwrite = true)
            world.resolve("session.lock").delete()
        }
        dir.resolve("mods").mkdirs()
        dir.resolve("config").mkdirs()
        dir.resolve("config/fml.toml").writeText("earlyWindowControl = false\n")
        root.resolve("libs/mods/crankshaft-neoforge-1.6.0+mc26.2.jar")
            .copyTo(dir.resolve("mods/crankshaft.jar"), overwrite = true)
        dir.resolve("config/crankshaft-client.toml").writeText(
            "backend = \"${if(providers.gradleProperty("mobCpuGeometry").isPresent) "flywheel:off" else "DEFAULT"}\"\n")
        dir.resolve("options.txt").writeText("pauseOnLostFocus:false\nenableVsync:false\nguiScale:2\nrenderDistance:${if(stand) 10 else if(review || bench || smoke) 8 else 3}\nsimulationDistance:5\ninactivityFpsLimit:minimized\nmaxFps:${providers.gradleProperty("standMaxFps").orNull ?: if(stand && providers.gradleProperty("glassStandCheck").isPresent) 260 else 60}\n${if(stand || smoke) "tutorialStep:none\n" else ""}")
        if(holes && providers.gradleProperty("holeBenchLoaded").isPresent) {
            val original=file(providers.gradleProperty("holeBenchInstance").get()).canonicalFile
            original.resolve("config").copyRecursively(dir.resolve("config"),overwrite=true)
            dir.resolve("config/fml.toml").writeText("earlyWindowControl = false\n")
            original.resolve("mods").listFiles()!!.filter { it.name.endsWith(".jar") && !it.name.startsWith("ntm-next-")
                    && !it.name.startsWith("reimagined-arms-") && !it.name.startsWith("crankshaft-") }
                .forEach { it.copyTo(dir.resolve("mods/${it.name}"),overwrite=true) }
            original.resolve("options.txt").copyTo(dir.resolve("options.txt"),overwrite=true)
            dir.resolve("options.txt").appendText("\npauseOnLostFocus:false\nenableVsync:false\nrenderDistance:12\nmaxFps:260\n")
            val voxy=dir.resolve("config/voxy-config.json")
            voxy.writeText(voxy.readText().replace(Regex("\"section_render_distance\"\\s*:\\s*[0-9.]+"),"\"section_render_distance\": 16.0"))
        }
        layout.buildDirectory.file(result).get().asFile.delete()
    }
    doLast {
        val report = layout.buildDirectory.file(result).get().asFile
        check(review && !selfTest || report.exists() && report.readLines().lastOrNull() == "PASS клиентская проверка") {
            "Клиентская проверка не прошла: ${report.path}"
        }
    }
}

// NeoForm tools ask for Java 21 by default; the Java 25 the build runs on does as well (no second JDK on the Pi)
tasks.withType<net.neoforged.nfrtgradle.NeoFormRuntimeTask>().configureEach {
    javaExecutable = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.executablePath.asFile.absolutePath }
}

// -PnoAssets (the Pi): skip the game assets, hundreds of MB that only a client needs; the runs get an empty folder.
// runDumpBlocks is a dedicated server, so ./gradlew runDumpBlocks -PnoAssets is all it takes.
if (providers.gradleProperty("installedAssets").isPresent) {
    tasks.named<net.neoforged.nfrtgradle.DownloadAssets>("downloadAssets") {
        val props = assetPropertiesFile
        val installed = providers.gradleProperty("installedAssets")
        actions.clear()
        doLast {
            val dir = File(installed.get())
            require(dir.resolve("indexes/32.json").exists()) { "Нет индекса ресурсов Minecraft 26.2 (32.json)" }
            props.get().asFile.writeText("asset_index=32\nassets_root=" + dir.absolutePath.replace("\\", "/") + "\n")
        }
    }
} else if (providers.gradleProperty("noAssets").isPresent) {
    tasks.named<net.neoforged.nfrtgradle.DownloadAssets>("downloadAssets") {
        val props = assetPropertiesFile
        val empty = layout.buildDirectory.dir("no-assets")
        actions.clear()
        doLast {
            val dir = empty.get().asFile
            dir.resolve("indexes").mkdirs()
            dir.resolve("indexes/none.json").writeText("{\"objects\":{}}")
            props.get().asFile.writeText("asset_index=none\nassets_root=" + dir.absolutePath.replace("\\", "/") + "\n")
        }
    }
}

// ./gradlew jarDiff -Pbaseline=<jar>: does this build run the same as that one (tools/JarDiff.java)
tasks.register<JavaExec>("jarDiff") {
    group = "verification"
    description = "Compares the built jar with -Pbaseline=<jar>: class bytecode without debug info, other files byte for byte."
    dependsOn(tasks.jar)
    classpath = tools.runtimeClasspath
    mainClass = "JarDiff"
    val baseline = providers.gradleProperty("baseline")
    val jar = tasks.jar.flatMap { it.archiveFile }
    argumentProviders.add(CommandLineArgumentProvider { listOf(baseline.get(), jar.get().asFile.path) })
}
