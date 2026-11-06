import java.io.ByteArrayOutputStream
import java.time.Duration

// NTM Reimagined Arms for Minecraft 1.21.1 and NTM: Decentralized Edition, on NeoForge 21.1 (ModDevGradle).
// The 26.2 build at the repository root is left alone: this one reads the root's src/ (README.md here).
// - Every file of the root's src/ is the port's too, copied into build/ with the 26.x names of rewrite.txt
//   turned into their 1.21.1 or DE ones, unless the port has its own copy at the same path in src/ here, or
//   skip.txt leaves it out (not ported, or not yet).
// - A merge of main brings the shared files along by itself; a port's copy is kept in step by hand
//   (notes/ports/de-1.21.1.md, "Копии").
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

// the repository checkout: port/1.21.1-de -> ../..
val repo: File = projectDir.resolve("../..").canonicalFile

// ---- version: counted from git like the root build's (root CLAUDE.md, "port/*" branches), never set by hand ----
val BUILD_OFFSET = 82
val mcVersion = "1.21.1"
val loader = "de"
fun git(vararg args: String): String? = runCatching {
    val out = providers.exec {
        commandLine("git", *args)
        workingDir = repo
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim() else null
}.getOrNull()
fun count(range: String): Int? = git("rev-list", "--count", range)?.toIntOrNull()

val portBase = git("merge-base", "HEAD", "main") ?: git("merge-base", "HEAD", "origin/main")
val b = portBase?.let { count(it) }?.plus(BUILD_OFFSET)
val p = portBase?.let { count("$it..HEAD") }
// for the loader <B>.<P>+mc1.21.1-de; for players X<B>.<P>; the jar reimagined-arms-X<B>.<P>-mc1.21.1-de.jar
val modVersion = if (b == null || p == null) "0+mc$mcVersion-$loader" else "$b.$p+mc$mcVersion-$loader"
val displayVersion = if (b == null || p == null) "X0-dev" else "X$b.$p"
val jarVersion = if (b == null || p == null) "X0-dev-mc$mcVersion-$loader" else "X$b.$p-mc$mcVersion-$loader"
version = modVersion
group = "acup.gunfeel"
base.archivesName.set("reimagined-arms")
tasks.jar {
    archiveVersion.set(jarVersion)
    from(repo.resolve("third-party")) { into("META-INF/licenses") }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines"
    options.compilerArgs.addAll(listOf("-proc:none", "-nowarn", "-Xmaxerrs", "10000"))
}

repositories {
    mavenCentral()
}

val ntm = files(projectDir.resolve(property("deps.ntm") as String))
// what DE carries inside its jar (META-INF/jarjar): on the compile classpath for the classes of DE that name them
val ntmInside = fileTree(projectDir.resolve("libs")) {
    include("flywheel-neoforge-*.jar", "ponder-neoforge-*.jar", "sable-companion-*.jar")
}

dependencies {
    compileOnly(ntm)
    compileOnly(ntmInside)
    // DE loads as a mod in the dev runs, its Flywheel from inside it; the mod jar never contains it
    runtimeOnly(ntm)
    testImplementation(ntm)
    testImplementation(ntmInside)
    // XZ for Java (0BSD, third-party/xz.txt): the machines' bullet shapes in the jar (ShapePack), its jar inside the mod's
    jarJar(implementation("org.tukaani:xz:1.10")!!)
    // JSpecify's @Nullable: 26.2 brings it with Minecraft, 1.21.1 does not
    compileOnly("org.jspecify:jspecify:1.0.0")
    testCompileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Minecraft's own assets (block models: FenceSeamTest): 26.2's game jar has them, 1.21.1 keeps them apart
    testRuntimeOnly(files(layout.buildDirectory.file("moddev/artifacts/neoforge-${property("deps.neoforge")}-client-extra-aka-minecraft-resources.jar"))
        .builtBy("createMinecraftArtifacts"))
}

// the classpath of the deServer and deClient runs: Minecraft and DE, not this mod (FML would find the mod's
// neoforge.mods.toml in the main source set's resources and load it without its classes)
val deOnly: SourceSet = sourceSets.create("deOnly")
dependencies { "deOnlyRuntimeOnly"(ntm) }

neoForge {
    enable {
        version = property("deps.neoforge") as String
        // no decompiling and recompiling Minecraft: the mod only compiles against it
        setDisableRecompilation(true)
    }
    mods {
        register("ntmgunfeel") {
            sourceSet(sourceSets.main.get())
        }
    }
    runs {
        register("client") {
            client()
            gameDirectory.set(projectDir.resolve("run"))
            jvmArgument("-Xmx4G")
            // -Pjoin=host:port, as deClient's: de-repro.sh mod
            providers.gradleProperty("join").orNull?.let { programArguments.addAll("--quickPlayMultiplayer", it) }
        }
        register("server") {
            server()
            gameDirectory.set(projectDir.resolve("run-server"))
            programArgument("--nogui")
        }
        // the test world of checkClient: a dedicated server makes a flat one in run-world/world and is stopped
        // (notes/ports/de-1.21.1/makeworld.sh); 26.2's run-bullets world is too new for 1.21.1
        register("makeWorld") {
            server()
            gameDirectory.set(projectDir.resolve("run-world"))
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
        }
        // the root's checkBullets: bullets on real blocks in a small flat world (BulletCheck), its own folder and port
        register("checkBullets") {
            server()
            gameDirectory.set(projectDir.resolve("run-bullets"))
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            systemProperty("ntmgunfeel.checkBullets", layout.buildDirectory.file("bulletcheck/result.txt").get().asFile.path)
        }
        // the root's checkClient: the smoke check, or with -Ptrace -Pscenario=arms the trace scenario
        register("checkClient") {
            client()
            gameDirectory.set(projectDir.resolve("run-client-check"))
            jvmArgument("-Xmx4G")
            systemProperty("ntmgunfeel.checkClient", layout.buildDirectory.file("clientcheck/result.txt").get().asFile.path)
            programArguments.addAll("--width", "854", "--height", "480")
            // -PshapesStand=<file>, -PshapesOnly=a,b, -PshapesResume, -PshapesFallback: as at the root (ShapesStand,
            // ShapesFallbackCheck); the stand keeps to the -Xmx4G of every stand client here
            systemProperty("ntmgunfeel.shapesStand", providers.gradleProperty("shapesStand").orElse("").get())
            systemProperty("ntmgunfeel.shapesOnly", providers.gradleProperty("shapesOnly").orElse("").get())
            systemProperty("ntmgunfeel.shapesResume", providers.gradleProperty("shapesResume").isPresent.toString())
            systemProperty("ntmgunfeel.shapesFallback", providers.gradleProperty("shapesFallback").isPresent.toString())
            if (providers.gradleProperty("shapesFallback").isPresent) systemProperty("ntmgunfeel.shapesSkip", "hbm:machine_chemical_factory")
            // -PcheckMobs: the root's checkMobs in the smoke world (MobSmoke, without its review), with -PmobCases=a,b
            // and -PmobHealth as there; -PcheckHeads, -PcheckLimbs, -PcheckExplosives and -PcheckHeadBindings run the
            // root's damage checks on each mob (HeadSmoke, ExplosiveSmoke); -PcheckTurn, as the root's, a pose kept from
            // before a turn on the server against the pose the player sees after it
            if (providers.gradleProperty("checkMobs").isPresent) {
                systemProperty("ntmgunfeel.checkMobs", "true")
                for (check in listOf("checkHeads", "checkLimbs", "checkExplosives", "checkHeadBindings", "checkTurn"))
                    systemProperty("ntmgunfeel.$check", providers.gradleProperty(check).isPresent.toString())
                providers.gradleProperty("mobCases").orNull?.let { systemProperty("ntmgunfeel.mobCases", it) }
                providers.gradleProperty("mobHealth").orNull?.let { systemProperty("ntmgunfeel.mobHealth", it) }
                // -PdumpProfiles=<dir>: with -PcheckHeadBindings each model's parts as the label profiles hold them (HeadSmoke)
                providers.gradleProperty("dumpProfiles").orNull?.let { systemProperty("ntmgunfeel.dumpProfiles", it) }
                // -PcheckOutlineDrift: after the move each mob walks, turns and stands, its F3+B outline and the
                // server's zone set against the model (OutlineDrift); -PoutlineLegacy draws F3+B as before, for the
                // numbers it had
                systemProperty("ntmgunfeel.checkOutlineDrift", providers.gradleProperty("checkOutlineDrift").isPresent.toString())
                systemProperty("ntmgunfeel.outlineLegacy", providers.gradleProperty("outlineLegacy").isPresent.toString())
            }
            // -PcheckHud: the HUD part's stand (HudCheck) instead of the smoke check
            systemProperty("ntmgunfeel.checkHud", providers.gradleProperty("checkHud").isPresent.toString())
            // -PmachineGlass: windows of real NTM machines are glass (MachineGlassCheck) instead of the smoke check
            systemProperty("ntmgunfeel.machineGlassCheck", providers.gradleProperty("machineGlass").isPresent.toString())
            // -PfenceSeam: holes where two fences, walls, panes or bars meet lie on their pixels (FenceSeamCheck)
            systemProperty("ntmgunfeel.fenceSeamCheck", providers.gradleProperty("fenceSeam").isPresent.toString())
            // -PglassBack[=fabulous]: glass seen through, and holes on its far side seen through it (GlassBackCheck)
            systemProperty("ntmgunfeel.glassBack", providers.gradleProperty("glassBack").map { it.ifBlank { "fancy" } }.orElse("").get())
            // -PspectatorStand: may a spectator use an NTM gun (the root's SpectatorStand)
            systemProperty("ntmgunfeel.spectatorStand", providers.gradleProperty("spectatorStand").isPresent.toString())
            // -PholeBench=<label>: the root's benchHoles in the smoke world (HoleBenchmark), its table in
            // build/holebench/<label>.tsv; -PholeBenchCheckMotion, -PholeBenchSurfaces, -PholeBenchModes,
            // -PholeBenchRepeats, -PholeBenchShells, -PholeBenchLegacy as there (no loaded world, no JFR)
            providers.gradleProperty("holeBench").orNull?.let { label ->
                require(label.matches(Regex("[a-zA-Z0-9_-]+")))
                systemProperty("ntmgunfeel.benchHoles", "true")
                systemProperty("ntmgunfeel.holeBenchFile", layout.buildDirectory.file("holebench/$label.tsv").get().asFile.path)
                systemProperty("ntmgunfeel.holeBenchLegacy", providers.gradleProperty("holeBenchLegacy").isPresent.toString())
                systemProperty("ntmgunfeel.holeBenchCheckMotion", providers.gradleProperty("holeBenchCheckMotion").isPresent.toString())
                for (k in listOf("Surfaces", "Repeats", "Shells", "Modes", "Origin"))
                    providers.gradleProperty("holeBench$k").orNull?.let { systemProperty("ntmgunfeel.holeBench$k", it) }
            }
        }
        // DE alone, without this mod: for what DE does by itself (notes/ports/de-1.21.1/de-repro.sh runs the two at once).
        // -Pjoin=host:port: the client goes straight onto that server
        register("deServer") {
            server()
            gameDirectory.set(projectDir.resolve("run-de-server"))
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
            sourceSet = deOnly
            loadedMods.empty()
        }
        // the same server with this mod (de-repro.sh mod)
        register("deModServer") {
            server()
            gameDirectory.set(projectDir.resolve("run-de-mod-server"))
            programArgument("--nogui")
            jvmArgument("-Xmx1200M")
        }
        register("deClient") {
            client()
            gameDirectory.set(projectDir.resolve("run-de-client"))
            jvmArgument("-Xmx4G")
            // the whole stack of an exception thrown many times (else HotSpot gives it as "null" with no frames)
            jvmArgument("-XX:-OmitStackTraceInFastThrow")
            sourceSet = deOnly
            loadedMods.empty()
            providers.gradleProperty("join").orNull?.let { programArguments.addAll("--quickPlayMultiplayer", it) }
        }
        // -Ptrace=<file> (relative to the run's folder) or -Ptrace=true, -Pscenario, -PscenarioGuns, -Pseed: as at the root
        configureEach {
            providers.gradleProperty("trace").orNull?.let { systemProperty("ntmgunfeel.trace", it) }
            providers.gradleProperty("scenario").orNull?.let { systemProperty("ntmgunfeel.scenario", it) }
            providers.gradleProperty("scenarioGuns").orNull?.let { systemProperty("ntmgunfeel.scenarioGuns", it) }
            providers.gradleProperty("seed").orNull?.let { systemProperty("ntmgunfeel.seed", it) }
            if (providers.gradleProperty("traceHex").isPresent) systemProperty("ntmgunfeel.traceHex", "true")
        }
    }
}

neoForge.addModdingDependenciesTo(deOnly)

tasks.matching { it.name == "runMakeWorld" || it.name == "runCheckBullets" }.configureEach {
    val bullets = name == "runCheckBullets"
    doFirst {
        val dir = projectDir.resolve(if (bullets) "run-bullets" else "run-world")
        dir.resolve("world").deleteRecursively()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        // as the root's run-bullets: flat, two chunks around, nothing generated on it; a port of its own, not 25565
        dir.resolve("server.properties").writeText(
            "level-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n" +
            "spawn-protection=0\nonline-mode=false\nspawn-monsters=false\nspawn-animals=false\nmax-players=1\n" +
            "server-port=${if (bullets) 25611 else 25610}\n")
    }
}
tasks.matching { it.name == "runCheckClient" }.configureEach {
    val scenario = listOf("scenario", "shapesStand", "fenceSeam", "holeBench", "checkMobs").any { providers.gradleProperty(it).isPresent }
    timeout.set(Duration.ofMinutes(if (scenario) 120 else 8))
    val result = layout.buildDirectory.file("clientcheck/result.txt")
    val cpuGeometry = providers.gradleProperty("mobCpuGeometry").isPresent
    doFirst {
        val source = projectDir.resolve("run-world/world")
        require(source.resolve("level.dat").exists()) { "Сначала сделайте мир: notes/ports/de-1.21.1/makeworld.sh" }
        val dir = projectDir.resolve("run-client-check")
        val world = dir.resolve("saves/gunfeel-smoke")
        world.deleteRecursively()
        source.copyRecursively(world, overwrite = true)
        world.resolve("session.lock").delete()
        dir.resolve("config").mkdirs()
        dir.resolve("config/fml.toml").writeText("earlyWindowControl = false\n")
        // -PmobCpuGeometry: as the root's, the mobs without Flywheel: its backend "flywheel:off" (NTM's mobs then draw
        // through their renderers, cube by cube, as the vanilla ones always do on DE); without it Flywheel decides
        val flywheel = dir.resolve("config/flywheel-client.toml")
        val backend = "backend = \"${if (cpuGeometry) "flywheel:off" else "DEFAULT"}\""
        val lines = if (flywheel.exists()) flywheel.readLines() else emptyList()
        flywheel.writeText((if (lines.any { it.startsWith("backend =") }) lines.map { if (it.startsWith("backend =")) backend else it }
            else listOf(backend) + lines).joinToString("\n", postfix = "\n"))
        dir.resolve("options.txt").writeText("pauseOnLostFocus:false\nenableVsync:false\nguiScale:2\nrenderDistance:3\n" +
            "simulationDistance:5\ninactivityFpsLimit:minimized\nmaxFps:60\nonboardAccessibility:false\n" +
            // -Pfabulous: the "Fabulous!" graphics (shader transparency, the holes on glass after the translucent terrain)
            (if (providers.gradleProperty("fabulous").isPresent) "graphicsMode:2\n" else "") +
            // -PfastGraphics: the "Fast" graphics
            (if (providers.gradleProperty("fastGraphics").isPresent) "graphicsMode:0\n" else ""))
        result.get().asFile.delete()
        layout.buildDirectory.dir("holebench").get().asFile.mkdirs()
    }
    doLast {
        val report = result.get().asFile
        check(report.exists() && report.readLines().lastOrNull() == "PASS клиентская проверка") {
            "Клиентская проверка не прошла: ${report.path}"
        }
    }
}

// FML 4 does not put a plain library on the dev runs' classpath by itself (the configuration comes with neoForge {})
dependencies {
    "additionalRuntimeClasspath"("org.tukaani:xz:1.10")
}

// ---- the version for the code: acup.gunfeel.BuildInfo (VERSION, DISPLAY) ----
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo/java")
    val v = modVersion
    val d = displayVersion
    inputs.property("version", v)
    inputs.property("display", d)
    outputs.dir(out)
    doLast {
        val f = out.get().file("acup/gunfeel/BuildInfo.java").asFile
        f.parentFile.mkdirs()
        f.writeText("package acup.gunfeel;\n\n// generated by build.gradle.kts\npublic final class BuildInfo {\n" +
                "    public static final String VERSION = \"$v\";\n    public static final String DISPLAY = \"$d\";\n\n" +
                "    private BuildInfo() {\n    }\n}\n")
    }
}

// ---- the shared files: the root's src/, less the port's own copies and skip.txt ----
// skip.txt: sections [main], [test], [resources] of Ant patterns relative to src/main/java, src/test/java and
// src/main/resources; '#' starts a comment.
// pending.txt: the same for what is not ported yet (tools/prune.py).
fun listed(file: String, section: String): List<String> {
    val f = projectDir.resolve(file)
    if (!f.exists()) return emptyList()
    val lines = f.readLines().map { it.substringBefore('#').trim() }
    val start = lines.indexOf("[$section]")
    if (start < 0) return emptyList()
    return lines.drop(start + 1).takeWhile { !it.startsWith("[") }.filter { it.isNotEmpty() }
}
fun skipped(section: String) = listed("skip.txt", section) + listed("pending.txt", section)
// the port's own copies: the paths under its src/<dir>
fun own(dir: String): List<String> {
    val base = projectDir.resolve("src/$dir")
    if (!base.isDirectory) return emptyList()
    return base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.toList()
}
// rewrite.txt: "<26.x class> -> <1.21.1 or DE class>", one per line. The full name is replaced everywhere; when the
// simple names differ, the simple name is replaced as a whole word too (Identifier -> ResourceLocation).
// Lines "re: <Java regex> => <replacement>" are applied after the names, line by line, in their order: for the
// members 1.21.1 names otherwise (mc.gui.screen() -> mc.screen). "re@<File.java>: ..." is the same for that file only.
// '#' starts a comment only before "re".
class Rule(val file: String?, val regex: Regex, val to: String)
// a.b.C.D -> a/b/C$D: the package is what comes before the first capitalised name, a nested class follows a '$'
fun internal(name: String): String {
    val parts = name.split('.')
    val first = parts.indexOfFirst { it.first().isUpperCase() }.let { if (it < 0) parts.size - 1 else it }
    return (parts.take(first) + parts.drop(first).joinToString("$")).joinToString("/")
}
val rewriteLines = projectDir.resolve("rewrite.txt").readLines()
val reLine = Regex("""^re(?:@(\S+))?:\s*(.*)$""")
val rewrites: List<Rule> = rewriteLines
    .filter { !reLine.matches(it.trim()) }
    .map { it.substringBefore('#').trim() }.filter { it.contains("->") }
    .flatMap { line ->
        val (from, to) = line.split("->").map { it.trim() }
        val fromSimple = from.substringAfterLast('.')
        val toSimple = to.substringAfterLast('.')
        val full = Rule(null, Regex("(?<![\\w.])" + Regex.escape(from) + "(?!\\w)"), to)
        // the JVM's form too, as mixin targets and descriptors write it (net/minecraft/X, Lnet/minecraft/X;)
        val jvm = Rule(null, Regex(Regex.escape(internal(from)) + "(?![\\w/])"), internal(to))
        if (fromSimple == toSimple) listOf(full, jvm)
        else listOf(full, jvm, Rule(null, Regex("(?<![\\w.])" + Regex.escape(fromSimple) + "(?!\\w)"), toSimple))
    }
    .plus(rewriteLines.mapNotNull { reLine.matchEntire(it.trim()) }.map { m ->
        val (from, to) = m.groupValues[2].split(" =>", limit = 2).map { it.trim() }
        Rule(m.groupValues[1].ifEmpty { null }, Regex(from), to)
    })
fun sharedSources(dir: String, section: String, out: String, rewrite: Boolean) = tasks.register<Sync>(out) {
    val ownFiles = own(dir)
    val skip = skipped(section)
    val rules = rewrites
    inputs.file(projectDir.resolve("rewrite.txt"))
    listOf("skip.txt", "pending.txt").map { projectDir.resolve(it) }.filter { it.exists() }.forEach { inputs.file(it) }
    from(repo.resolve("src/$dir")) {
        exclude(ownFiles)
        exclude(skip)
        if (rewrite) eachFile {
            val mine = rules.filter { it.file == null || it.file == name }
            val file = name
            filter { line: String ->
                mine.fold(line) { acc, r ->
                    try {
                        r.regex.replace(acc, r.to)
                    } catch (e: Exception) {
                        throw GradleException("rewrite.txt: ${r.regex.pattern} => ${r.to} on $file: ${e.message}", e)
                    }
                }
            }
        }
    }
    // a rule that broke on a file leaves it empty: an empty shared file whose root file is not is an error
    doLast {
        val base = repo.resolve("src/$dir")
        destinationDir.walkTopDown().filter { it.isFile && it.length() == 0L }.forEach {
            val src = base.resolve(it.relativeTo(destinationDir).path)
            if (src.length() > 0L) throw GradleException("rewrite.txt left ${it.relativeTo(destinationDir)} empty")
        }
    }
    into(layout.buildDirectory.dir("generated/shared/$section"))
}
val sharedMain = sharedSources("main/java", "main", "sharedMainSources", true)
val sharedTest = sharedSources("test/java", "test", "sharedTestSources", true)
val sharedResources = sharedSources("main/resources", "resources", "sharedResources", false)
val ntmTested = property("deps.ntmTested") as String
tasks.processResources {
    val props = mapOf("mod_version" to modVersion, "mod_display_version" to displayVersion, "ntm_tested" to ntmTested)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}
sourceSets.main {
    java.srcDir(sharedMain)
    java.srcDir(buildInfo)
    resources.srcDir(sharedResources)
}
// the tests see what the mod compiles against, the Minecraft client included (as at the root)
sourceSets.test {
    java.srcDir(sharedTest)
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

// tools/ of the root: MixinCheck, run by mixinCheck below
val tools: SourceSet = sourceSets.create("tools") {
    java.setSrcDirs(listOf(repo.resolve("tools")))
    java.include("MixinCheck.java")
}
dependencies {
    // MixinCheck shares its checks with the game's (acup.gunfeel.compat.MixinProbe)
    "toolsImplementation"(sourceSets.main.get().output)
    "toolsImplementation"("org.ow2.asm:asm-tree:9.10.1")
    "toolsImplementation"("org.ow2.asm:asm-util:9.10.1")
}
// The mixins against the Minecraft 1.21.1, NeoForge and DE classes they change, without starting the game
val unpackedJar = tasks.register<Sync>("unpackJar") {
    from(zipTree(tasks.jar.flatMap { it.archiveFile }))
    into(layout.buildDirectory.dir("mixincheck"))
}
val mixinCheck = tasks.register<JavaExec>("mixinCheck") {
    group = "verification"
    description = "Checks every mixin against the Minecraft, NeoForge and DE classes it changes."
    dependsOn(unpackedJar)
    classpath = tools.runtimeClasspath
    mainClass = "MixinCheck"
    val game = sourceSets.main.get().compileClasspath
    val classes = unpackedJar.map { it.destinationDir.path }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(classes.get(), game.files.filter { it.name.endsWith(".jar") }.joinToString(":"))
    })
    // A mixin the port's configs do not list yet (its part is not ported) is not applied: what MixinCheck finds in
    // it is shown, and fails the check only for a listed one.
    val out = ByteArrayOutputStream()
    standardOutput = out
    isIgnoreExitValue = true
    val configs = listOf("ntmgunfeel.mixins.json", "ntmgunfeel-hbm.mixins.json", "ntmgunfeel-de.mixins.json").map { projectDir.resolve("src/main/resources/$it") }
    inputs.files(configs)
    doLast {
        val listed = configs.flatMap { f ->
            val o = groovy.json.JsonSlurper().parse(f) as Map<*, *>
            listOf("mixins", "client", "server").flatMap { (o[it] as List<*>?).orEmpty().map { m -> m.toString() } }
        }.toSet()
        val text = out.toString()
        // MIXINCHECK_ALL=1: the findings of the mixins not listed yet too (for the part being ported)
        if (System.getenv("MIXINCHECK_ALL") != null) text.lines().filter { Regex("""^(ERROR|WARN) """).containsMatchIn(it) }.forEach(::println)
        val failing = mutableListOf<String>()
        var pending = 0
        for (line in text.lines()) {
            val m = Regex("""^(ERROR|WARN) +(\w+)""").find(line) ?: continue
            if (m.groupValues[2] in listed) {
                println(line)
                if (m.groupValues[1] == "ERROR") failing += line
            } else pending++
        }
        println(text.lines().lastOrNull { it.contains("injectors checked") } ?: "")
        println("${listed.size} mixins listed; $pending findings in mixins not listed yet (their parts are not ported)")
        if (failing.isNotEmpty()) throw GradleException("${failing.size} mixin errors in listed mixins")
    }
}
tasks.check { dependsOn(mixinCheck) }

tasks.test {
    useJUnitPlatform()
    // pending.txt [failing]: the tests of a part not ported yet
    val failing = listed("pending.txt", "failing")
    inputs.property("failing", failing)
    filter { failing.forEach { excludeTestsMatching(it) } }
    // the shared tests read src/main/resources and src/test/pinned from the repository root, as at the root
    workingDir = repo
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
