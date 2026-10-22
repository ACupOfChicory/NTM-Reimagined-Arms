// The mod for one Minecraft version (versions/<version>/), built by Stonecutter from the shared src/.
// The jar came out the same as the old build.sh's (./gradlew jarDiff): keep the javac flags, add nothing to it.
plugins {
    id("net.neoforged.moddev")
}

val root: File = rootProject.projectDir
val modsToml = root.resolve("src/main/resources/META-INF/neoforge.mods.toml")
version = Regex("""(?m)^version *= *"(.*)"""").find(modsToml.readText())!!.groupValues[1]
base.archivesName = "ntm-gunfeel"

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
tasks.matching { it.name == "runDumpBlocks" }.configureEach {
    doFirst {
        val dir = root.resolve("run-dump")
        // the dump stops the server before any world: what a stopped run left of one would only break the next
        dir.resolve("world").deleteRecursively()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
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
