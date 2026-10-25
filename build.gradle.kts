// The mod for one Minecraft version (versions/<version>/), built by Stonecutter from the shared src/.
// The jar came out the same as the old build.sh's (./gradlew jarDiff): keep the javac flags, add nothing to it.
plugins {
    id("net.neoforged.moddev")
}

val root: File = rootProject.projectDir
// The version is counted from git, never set by hand (CLAUDE.md, "Versioning"). BUILD_OFFSET puts the count on the old
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
        listOf(classes.get(), game.files.filter { it.name.endsWith(".jar") }.joinToString(":"))
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

// NeoForm tools ask for Java 21 by default; the Java 25 the build runs on does as well (no second JDK on the Pi)
tasks.withType<net.neoforged.nfrtgradle.NeoFormRuntimeTask>().configureEach {
    javaExecutable = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.executablePath.asFile.absolutePath }
}

// -PnoAssets (the Pi): skip the game assets, hundreds of MB that only a client needs; the runs get an empty folder.
// runDumpBlocks is a dedicated server, so ./gradlew runDumpBlocks -PnoAssets is all it takes.
if (providers.gradleProperty("noAssets").isPresent) {
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
