// port/continuum: the mod on NTM Continuum (Golem Core), one node per (Minecraft, loader) like Continuum's own.
// The shared src/ stays byte for byte main's (the 26.2 node, vcsVersion); this node builds Stonecutter's copy of it
// with the 26.1 renames (stonecutter.gradle.kts), minus what only fits NTM NEXT or 26.2 (continuum/excluded.txt),
// plus the Continuum adapter in continuum/src. Merges from main then only touch src/, never this node's own files.
plugins {
    id("net.neoforged.moddev")
}

val root: File = rootProject.projectDir
val port: File = root.resolve("continuum")
// Same numbering as build.gradle.kts (CLAUDE.md, "Versioning"): X<B>.<P>, B = main's commits up to where the port
// left it + 82, P = the port's own commits since.
val BUILD_OFFSET = 82
val mcVersion = stonecutter.current.version
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

val portBase = git("merge-base", "HEAD", "main") ?: git("merge-base", "HEAD", "origin/main")
val b = portBase?.let { count(it) }?.plus(BUILD_OFFSET)
val p = portBase?.let { count("$it..HEAD") }
val (modVersion, displayVersion, jarVersion) =
    if (b == null || p == null) Triple("0+mc$mcVersion-$loader", "X0-dev", "X0-dev-mc$mcVersion-$loader")
    else Triple("$b.$p+mc$mcVersion-$loader", "X$b.$p", "X$b.$p-mc$mcVersion-$loader")
version = modVersion
base.archivesName = "reimagined-arms"
tasks.jar {
    archiveVersion = jarVersion
    from(root.resolve("third-party")) { into("META-INF/licenses") }
}

// Files of the shared src/ this node leaves out, one Ant pattern per line under acup/gunfeel/ (# comments).
val excluded: List<String> = port.resolve("excluded.txt").readLines()
    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

// continuum/src goes straight to the tasks, past Stonecutter: it merges a source set's folders into one copy, where a
// stand-in would clash with the shared file of the same name that excluded.txt drops.
sourceSets.main {
    java {
        excluded.forEach { exclude("acup/gunfeel/$it") }
    }
    resources {
        // this node's own mods.toml, mixin lists and NTM API pin are in continuum/src/main/resources
        exclude("META-INF/neoforge.mods.toml", "ntmgunfeel.mixins.json", "ntmgunfeel-hbm.mixins.json", "ntmgunfeel-ntm-api.txt",
            // 26.1 depth is not reversed: the F3+B line shader compares it the other way round
            "assets/ntmgunfeel/shaders/core/model_hit_lines.fsh")
    }
}
tasks.compileJava { source(port.resolve("src/main/java")) }
// the tests of what excluded.txt leaves out, one Ant pattern per line under acup/gunfeel/
sourceSets.test {
    java {
        port.resolve("excluded-tests.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .forEach { exclude("acup/gunfeel/$it") }
    }
}
tasks.compileTestJava { source(port.resolve("src/test/java")) }

val resourceProps = mapOf("mod_version" to modVersion, "mod_display_version" to displayVersion,
    "ntm_range" to property("deps.ntm_range") as String, "golemcore_range" to property("deps.golemcore_range") as String)
tasks.processResources {
    val props = resourceProps
    inputs.properties(props)
    from(port.resolve("src/main/resources"))
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://cursemaven.com") { name = "CurseMaven" } }
        filter { includeGroup("curse.maven") }
    }
}

// dev-only mods: on the client run's classpath, never compiled against, never in the jar
val devMods: Configuration = configurations.create("devMods")
configurations.named("runtimeClasspath") { extendsFrom(devMods) }

dependencies {
    // NTM Continuum and Golem Core: compiled against, loaded in the dev runs, never in the jar
    compileOnly(property("deps.continuum") as String)
    compileOnly(property("deps.golemcore") as String)
    runtimeOnly(property("deps.continuum") as String)
    runtimeOnly(property("deps.golemcore") as String)
    if (providers.gradleProperty("gemrender.run").orNull != "false") devMods(property("deps.gemrender") as String)
    testImplementation(property("deps.continuum") as String)
    testImplementation(property("deps.golemcore") as String)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

neoForge {
    enable {
        version = property("deps.neoforge") as String
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
            gameDirectory = root.resolve("run-continuum")
            jvmArgument("-Xmx3000M")
            programArguments.addAll("--width", "1280", "--height", "800")
            // -Pcontinuum.join=localhost:25591 joins the dev server (runServer) on start
            providers.gradleProperty("continuum.join").orNull?.let { programArguments.addAll("--quickPlayMultiplayer", it) }
            // -Pcontinuum.world=<save> opens that singleplayer world; -Pcontinuum.gunSmoke adds Continuum's own gun check
            // there (GunSmokeClient: reload, fire, smoke and aim of five guns, with screenshots)
            providers.gradleProperty("continuum.world").orNull?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            if (providers.gradleProperty("continuum.gunSmoke").isPresent) systemProperty("hbm.gunSmokeTest", "true")
            // -Pcontinuum.portSmoke (with -Pcontinuum.world): the port's own gun feel check (PortSmoke), then quit
            if (providers.gradleProperty("continuum.portSmoke").isPresent) systemProperty("ntmgunfeel.portSmoke", "true")
            // a trace and its scenario, as main's runs (build.gradle.kts): -Ptrace, -Pscenario=arms, -PscenarioGuns, -Pseed
            for (p in listOf("trace", "traceScenario", "seed", "scenario", "scenarioGuns"))
                providers.gradleProperty(p).orNull?.let { systemProperty("ntmgunfeel.$p", it) }
            if (providers.gradleProperty("traceHex").isPresent) systemProperty("ntmgunfeel.traceHex", "true")
            // any -Pntmgunfeel.<name>=<value> reaches the client as the system property ntmgunfeel.<name> (a self-check of
            // a ported system can ask for its own switch without a line here)
            providers.gradlePropertiesPrefixedBy("ntmgunfeel.").get().forEach { (k, v) -> systemProperty(k, v) }
        }
        register("server") {
            server()
            gameDirectory = root.resolve("run-continuum-server")
            programArgument("--nogui")
            jvmArgument("-Xmx2000M")
        }
    }
}

sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines"
    options.compilerArgs.addAll(listOf("-proc:none", "-nowarn", "-Xmaxerrs", "2000"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = root
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<net.neoforged.nfrtgradle.NeoFormRuntimeTask>().configureEach {
    javaExecutable = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.executablePath.asFile.absolutePath }
}

// the dev runs accept the EULA in their own folders and skip the early window, as main's check runs do
tasks.matching { it.name == "runClient" || it.name == "runServer" }.configureEach {
    val dir = root.resolve(if (name == "runClient") "run-continuum" else "run-continuum-server")
    doFirst {
        dir.resolve("config").mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        dir.resolve("config/fml.toml").writeText("earlyWindowControl = false\n")
    }
}
