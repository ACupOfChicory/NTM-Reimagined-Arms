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
            systemProperty("ntmgunfeel.weaponSwaySelfTest",providers.gradleProperty("weaponSwaySelfTest").isPresent.toString())
            jvmArgument("-Xmx2500M")
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("clientcheck/result.txt").get().asFile.path)
            programArguments.addAll("--width", "854", "--height", "480")
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
            systemProperty("ntmgunfeel.contourShots",providers.gradleProperty("mobContourShots").isPresent.toString())
            systemProperty("ntmgunfeel.checkHeads",providers.gradleProperty("checkHeads").isPresent.toString())
            systemProperty("ntmgunfeel.checkLimbs",providers.gradleProperty("checkLimbs").isPresent.toString())
            systemProperty("ntmgunfeel.checkExplosives",providers.gradleProperty("checkExplosives").isPresent.toString())
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
            systemProperty("ntmgunfeel.benchFile",root.resolve("notes/mob-bench/$label.tsv").path)
            systemProperty("MC_DEBUG_ENABLED","true")
            systemProperty("MC_DEBUG_PREFER_WAYLAND","true")
            jvmArgument("-XX:StartFlightRecording=filename=${root.resolve("notes/mob-bench/$label.jfr").path},settings=profile,dumponexit=true")
            programArguments.addAll("--width","1280","--height","800")
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
val checkRuns = mapOf("runDumpBlocks" to "run-dump", "runCheckSettings" to "run-check", "runCheckBullets" to "run-bullets")
tasks.matching { it.name in checkRuns }.configureEach {
    val dir = root.resolve(checkRuns.getValue(name))
    doFirst {
        // the dump stops the server before any world: what a stopped run left of one would only break the next
        dir.resolve("world").deleteRecursively()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        // the smallest world that still has one: flat, two chunks around, nothing generated on it
        if (name == "runCheckBullets") dir.resolve("server.properties").writeText(
            "level-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n" +
            "spawn-protection=0\nonline-mode=false\nspawn-monsters=false\nspawn-animals=false\nmax-players=1\n")
    }
}

// Настоящий клиент использует только копию тестового мира и отдельную папку запуска.
tasks.matching { it.name in setOf("runCheckClient","runCheckMobs","runReviewMobs","runBenchMobs","runBenchHoles") }.configureEach {
    val review = name == "runReviewMobs"
    val bench = name == "runBenchMobs"
    val holes = name == "runBenchHoles"
    val mobs = name == "runCheckMobs" || review
    val selfTest = providers.gradleProperty("mobReviewSelfTest").isPresent || providers.gradleProperty("headLabelSelfTest").isPresent
    // a trace scenario on every gun (-Pscenario, -PscenarioGuns=all) plays for about half an hour
    val scenario = providers.gradleProperty("scenario").isPresent
    timeout.set(if(review && !selfTest) Duration.ofDays(1) else if((mobs && !selfTest) || scenario) Duration.ofHours(if(scenario) 2 else 1) else Duration.ofMinutes(if(holes) 30 else 8))
    val result = if(holes) "holebench/result.txt" else if(bench) "mobbench/result.txt" else if(review) "mobreview/result.txt" else if (mobs) "mobcheck/result.txt" else "clientcheck/result.txt"
    doFirst {
        if(holes || review) {
            val runName=if(review) "reviewMobs" else "benchHoles"
            val vmArgs="@${layout.buildDirectory.file("moddev/${runName}RunVmArgs.txt").get().asFile.absolutePath}"
            val active=ProcessHandle.allProcesses().use { processes ->
                processes.filter { it.info().arguments().orElse(emptyArray()).contains(vmArgs) }.map { it.pid() }.toList()
            }
            check(active.isEmpty()) { "Прежний тестовый клиент ещё работает: PID $active. Второе окно не запускается." }
        }
        if(review && providers.gradleProperty("mobReviewSelfTest").isPresent) root.resolve("notes/mob-review/self-test").deleteRecursively()
        if(review && providers.gradleProperty("headLabelSelfTest").isPresent) root.resolve("notes/head-labels/self-test").deleteRecursively()
        val dir = root.resolve(if(holes) { if(providers.gradleProperty("holeBenchLoaded").isPresent) "run-hole-bench-loaded" else "run-hole-bench" } else if(bench) "run-mob-bench" else if(review) "run-mob-review" else if (mobs) "run-mob-check" else "run-client-check")
        if(holes) root.resolve("notes/hole-bench").mkdirs()
        if(bench) root.resolve("notes/mob-bench").mkdirs()
        val source = root.resolve("run-bullets/world")
        require(source.resolve("level.dat").exists()) { "Сначала выполните runCheckBullets: нужен изолированный тестовый мир" }
        val world = dir.resolve("saves/gunfeel-smoke")
        if((review || holes) && world.resolve("session.lock").exists()) {
            FileChannel.open(world.resolve("session.lock").toPath(),StandardOpenOption.WRITE).use { channel ->
                val lease=channel.tryLock()
                check(lease!=null) { "Тестовый мир уже открыт. Закройте прежний клиент перед новым запуском." }
                lease.release()
            }
        }
        if(!review || !world.resolve("level.dat").exists()) {
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
        dir.resolve("options.txt").writeText("pauseOnLostFocus:false\nenableVsync:false\nguiScale:2\nrenderDistance:${if(review || bench) 8 else 3}\nsimulationDistance:5\nmaxFps:60\n")
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
