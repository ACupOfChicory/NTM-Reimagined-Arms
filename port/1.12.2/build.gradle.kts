// NTM Reimagined Arms for Minecraft 1.12.2 and HBM's Nuclear Tech Mod: Community Edition, on RetroFuturaGradle with
// MixinBooter. One jar for the client and the server, Java 8 bytecode: it loads on Forge 14.23.5 (Java 8) and on
// Cleanroom (Java 25). The 26.2 build at the repository root is left alone: this one only reads the root's src/
// (shared.txt), and port/1.7.10 is the base the 1.12.2 code was copied from (notes/ports/port-1.12.2-copied.md).
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.5"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
    id("com.gradleup.shadow") version "9.6.1"
}

// the repository checkout: port/1.12.2 -> ../..
val repo: File = projectDir.resolve("../..").canonicalFile

// ---- version: counted from git like the root build's (root CLAUDE.md, "port/*" branches), never set by hand ----
val BUILD_OFFSET = 82
val mcVersion = "1.12.2"
val loader = "ce"
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
// for the loader <B>.<P>+mc1.12.2-ce; for players X<B>.<P>; the jar reimagined-arms-X<B>.<P>-mc1.12.2-ce.jar
val modVersion = if (b == null || p == null) "0+mc$mcVersion-$loader" else "$b.$p+mc$mcVersion-$loader"
val displayVersion = if (b == null || p == null) "X0-dev" else "X$b.$p"
val jarVersion = if (b == null || p == null) "X0-dev-mc$mcVersion-$loader" else "X$b.$p-mc$mcVersion-$loader"
version = modVersion
group = "acup.gunfeel"
base.archivesName.set("reimagined-arms")
tasks.withType<AbstractArchiveTask>().configureEach { archiveVersion.set(jarVersion) }

// ---- Java: the sources are Java 25 (the 26.2 files compile as they are), the jar is Java 8 bytecode ----
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
    // RFG's own Minecraft sources stay on its Java 8 compiler
    if (name !in setOf("compileMcLauncherJava", "compilePatchedMcJava", "compileInjectedTagsJava")) {
        sourceCompatibility = "25"
        targetCompatibility = "25"
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }
}

minecraft {
    mcVersion.set("1.12.2")
    username.set("Developer")
    // NTM CE ships an access transformer (hbm_at.cfg): applied to the dev Minecraft as on the players' game
    useDependencyAccessTransformers.set(true)
    extraRunJvmArguments.addAll("-Dmixin.debug.countInjections=true", "-Dmixin.checks.interfaces=true",
        "-Dfml.coreMods.load=acup.gunfeel.core.ArmsCore", "-Xmx4G")
}

repositories {
    maven {
        name = "Cleanroom Maven"
        url = uri("https://maven.cleanroommc.com")
    }
    exclusiveContent {
        forRepository { maven { name = "Curse Maven"; url = uri("https://www.cursemaven.com") } }
        filter { includeGroup("curse.maven") }
    }
    mavenCentral()
}

// shadowImplementation: shaded into the jar and relocated into acup.gunfeel.shadow (JOML, XZ), as in 1.7.10
val shadowImplementation: Configuration = configurations.create("shadowImplementation")
listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach {
    configurations.named(it) { extendsFrom(shadowImplementation) }
}
// devOnly: on the compile classpath and in the dev runs, never in the jar
val devOnly: Configuration = configurations.create("devOnly")
configurations.compileOnly { extendsFrom(devOnly) }
configurations.runtimeOnly { extendsFrom(devOnly) }
configurations.testCompileOnly { extendsFrom(devOnly) }
configurations.testRuntimeOnly { extendsFrom(devOnly) }

val mixinRefmap = "mixins.ntmgunfeel.refmap.json"
dependencies {
    // MixinBooter (Cleanroom has it built in): the mixin API, the annotation processor writing the refmap
    annotationProcessor("org.ow2.asm:asm-debug-all:5.2")
    annotationProcessor("com.google.guava:guava:30.0-jre")
    annotationProcessor("com.google.code.gson:gson:2.8.9")
    val mixinSpec = modUtils.enableMixins("zone.rong:mixinbooter:10.7", mixinRefmap) as String
    devOnly(mixinSpec) { isTransitive = false }
    annotationProcessor(mixinSpec) { isTransitive = false }

    // NTM CE 2.6.1.0 (release jar, deobfuscated by RFG for the dev runs); the mod jar never contains it.
    // Copy NTM-CE-1.12.2-2.6.1.0.jar (Modrinth ntm-ce) into libs/ (libs/ is not in git).
    devOnly(rfg.deobf(project.files("libs/NTM-CE-1.12.2-2.6.1.0.jar")))
    // what NTM CE needs in the dev runs
    runtimeOnly("com.cleanroommc:configanytime:3.0")
    // CTM (CTM-MC1.12.2-1.0.2.31): NTM CE needs it on the client (required-client:ctm)
    runtimeOnly(rfg.deobf("curse.maven:ctm-267602:2915363"))

    // Java 9+ classes the old Minecraft libraries look for on a modern JDK (as NTM CE's build)
    "patchedMinecraft"("me.eigenraven.java8unsupported:java-8-unsupported-shim:1.0.0")
    "patchedMinecraft"("net.minecraft:launchwrapper:1.12") { isTransitive = false }
    testCompileOnly("me.eigenraven.java8unsupported:java-8-unsupported-shim:1.0.0")

    // JOML, the vector maths of 26.2's arms: shaded in and relocated (acup.gunfeel.shadow.org.joml); MIT,
    // third-party/joml.txt. Not transitive: its Gradle metadata pulls in the Kotlin standard library.
    shadowImplementation("org.joml:joml:1.10.8") { isTransitive = false }
    // XZ for Java (public domain, Java 8 classes): the jar's shape tables packed (ShapePack); third-party/xz.txt
    shadowImplementation("org.tukaani:xz:1.10") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the version for the code: acup.gunfeel.BuildInfo (VERSION, DISPLAY)
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

// ---- the 26.2 files this port compiles as they are (shared.txt), copied into build/ for each build ----
// A file listed there must compile here unchanged; one that needs changes is copied into src/ instead.
fun shared(section: String): List<String> {
    val lines = projectDir.resolve("shared.txt").readLines().map { it.substringBefore('#').trim() }
    val start = lines.indexOf("[$section]")
    if (start < 0) return emptyList()
    return lines.drop(start + 1).takeWhile { !it.startsWith("[") }.filter { it.isNotEmpty() }
}
fun sharedSources(section: String, dir: String, out: String) = tasks.register<Sync>(out) {
    val files = shared(section)
    from(repo.resolve(dir)) { include(files.ifEmpty { listOf("-none-") }) }
    into(layout.buildDirectory.dir("generated/shared/$section"))
}
// 1.12.2 has no immutable net.minecraft.world.phys.Vec3: acup.gunfeel.phys.Vec3 computes the same numbers. Only that
// name is rewritten, so these files stay in step with 26.2 by themselves.
fun physSources(section: String, dir: String, out: String) = tasks.register<Sync>(out) {
    val files = shared(section)
    from(repo.resolve(dir)) { include(files.ifEmpty { listOf("-none-") }) }
    filter { line: String -> line.replace("net.minecraft.world.phys.Vec3", "acup.gunfeel.phys.Vec3") }
    into(layout.buildDirectory.dir("generated/shared/$section"))
}
val sharedMain = sharedSources("main", "src/main/java", "sharedMainSources")
val sharedTest = sharedSources("test", "src/test/java", "sharedTestSources")
val sharedResources = sharedSources("resources", "src/main/resources", "sharedResources")
val sharedMainPhys = physSources("main-phys", "src/main/java", "sharedMainPhysSources")
val sharedTestPhys = physSources("test-phys", "src/test/java", "sharedTestPhysSources")
sourceSets.main {
    java.srcDir(sharedMain)
    java.srcDir(sharedMainPhys)
    java.srcDir(buildInfo)
    resources.srcDir(sharedResources)
}
sourceSets.test {
    java.srcDir(sharedTest)
    java.srcDir(sharedTestPhys)
    // the tests see Minecraft as the mod does
    compileClasspath += sourceSets["patchedMc"].output + sourceSets["mcLauncher"].output
    runtimeClasspath += sourceSets["patchedMc"].output + sourceSets["mcLauncher"].output
}

tasks.processResources {
    val v = modVersion
    val d = displayVersion
    inputs.property("version", v)
    inputs.property("display", d)
    filesMatching("mcmod.info") { filter { it.replace("@VERSION@", v).replace("@DISPLAY@", d) } }
}

// ---- the jar: classes -> shadowJar (JOML, XZ relocated) -> downgradeJar (Java 8) -> shadeDowngradedApi (the
// Java 9+ library stubs it needs) -> reobfJar (SRG names, the jar players install) ----
val manifestAttributes = mapOf(
    "FMLCorePlugin" to "acup.gunfeel.core.ArmsCore",
    "FMLCorePluginContainsFMLMod" to "true",
    "ForceLoadAsMod" to "true",
)
tasks.jar {
    archiveClassifier.set("dev-plain")
    manifest { attributes(manifestAttributes) }
}
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("dev-shadow")
    configurations.set(listOf(shadowImplementation))
    manifest { attributes(manifestAttributes) }
    relocate("org.joml", "acup.gunfeel.shadow.org.joml")
    relocate("org.tukaani.xz", "acup.gunfeel.shadow.org.tukaani.xz")
    minimize()
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/maven/**")
    // the licences, as in the 26.2 jar (WeaponSway is GPL-3.0-only); xz.txt: 26.2 nests XZ as a jar, the port
    // shades it and its own third-party/xz.txt says so
    from(repo.resolve("LICENSE"))
    from(repo.resolve("third-party")) { into("META-INF/licenses"); exclude("xz.txt") }
    from(projectDir.resolve("third-party")) { into("META-INF/licenses") }
}
jvmdg {
    downgradeTo.set(JavaVersion.VERSION_1_8)
    // one Java 8 jar: no META-INF/versions
    multiReleaseOriginal.set(false)
    multiReleaseVersions.set(emptySet())
}
val shadowJarTask = tasks.named<Jar>("shadowJar")
tasks.named<xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar>("downgradeJar") {
    inputFile.set(shadowJarTask.flatMap { it.archiveFile })
    archiveClassifier.set("dev-downgraded")
}
val shadeTask = tasks.named<xyz.wagyourtail.jvmdg.gradle.task.ShadeJar>("shadeDowngradedApi") {
    archiveClassifier.set("dev")
}
tasks.named<ReobfuscatedJar>("reobfJar") {
    inputJar.set(shadeTask.flatMap { it.archiveFile })
    dependsOn(shadeTask)
}
tasks.assemble { dependsOn("reobfJar") }

// ---- JvmDowngrader's stubs in the jar (LGPL-2.1-or-later): the notice, the licence text and their sources ----
// shadeDowngradedApi puts the java-api classes the mod calls (Math.clamp, Map.of...) into the jar. The LGPL wants
// their notice, the licence and their source with each copy: third-party/ goes into META-INF/licenses with the jar,
// and reobfJar then gets the sources of exactly the shaded classes in META-INF/licenses/jvmdowngrader-src. The build
// fails when one has no source.
val jvmdgVersion = providers.provider { jvmdg.version }
val jvmdgSources = configurations.create("jvmdgApiSources") {
    isCanBeConsumed = false
    isTransitive = false
    withDependencies {
        add(project.dependencies.create("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:${jvmdgVersion.get()}:sources"))
    }
}
tasks.named<ReobfuscatedJar>("reobfJar") {
    val sources: FileCollection = jvmdgSources
    val notice = projectDir.resolve("third-party/jvmdowngrader.txt")
    val v = jvmdgVersion
    val jar = archiveFile
    inputs.files(sources)
    inputs.file(notice)
    inputs.property("jvmdgVersion", v)
    doLast {
        if (!notice.readText().contains("Версия: ${v.get()} "))
            throw GradleException("third-party/jvmdowngrader.txt names another JvmDowngrader version than ${v.get()}")
        val stub = Regex("^(?:.*/)?(xyz/wagyourtail/jvmdg/[^$]+?)(?:\\$.*)?\\.class$")
        val path = jar.get().asFile.toPath()
        val classes = ZipFile(path.toFile()).use { z ->
            z.entries().asSequence().mapNotNull { stub.find(it.name)?.groupValues?.get(1) }.map { "$it.java" }.toSortedSet()
        }
        ZipFile(sources.singleFile).use { src ->
            FileSystems.newFileSystem(path, null as ClassLoader?).use { fs ->
                for (c in classes) {
                    val e = src.getEntry(c) ?: throw GradleException("no source for the shaded JvmDowngrader class $c")
                    val to = fs.getPath("META-INF/licenses/jvmdowngrader-src/$c")
                    Files.createDirectories(to.parent)
                    src.getInputStream(e).use { i -> Files.copy(i, to, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
    }
}

// ---- the dev runs: Java 8, the downgraded jar in place of the classes (they are Java 25 bytecode) ----
listOf("runClient", "runServer").forEach { name ->
    tasks.named<RunMinecraftTask>(name) {
        classpath = classpath.minus(files(sourceSets.main.get().output.classesDirs))
            .minus(files(sourceSets.main.get().output.resourcesDir))
            .minus(files(tasks.jar)) + files(shadeTask)
        dependsOn(shadeTask)
        workingDir = projectDir.resolve(if (name == "runClient") "run/client" else "run/server")
    }
}

// A dev scenario in the client (ShootScenario): -Pscenario=shoot on runClient, with its trace (Trace) in
// run/client/logs/ntm-gunfeel-trace-<scenario>.tsv for tools/trace_diff.py.
val scenario = providers.gradleProperty("scenario")
val scenarioGuns = providers.gradleProperty("scenarioGuns")
val scenarioMachines = providers.gradleProperty("machines")
val scenarioMobs = providers.gradleProperty("mobs")
val lookDoor = providers.gradleProperty("lookDoor")
val testSkin = providers.gradleProperty("testSkin")
val tested = providers.gradleProperty("tested")
val forceOff = providers.gradleProperty("forceOff")
tasks.withType<RunMinecraftTask>().configureEach {
    if (scenario.isPresent) {
        val s = scenario.get()
        systemProperty("ntmgunfeel.scenario", s)
        systemProperty("ntmgunfeel.trace", "logs/ntm-gunfeel-trace-$s.tsv")
        systemProperty("ntmgunfeel.traceScenario", s)
    }
    if (scenarioGuns.isPresent) systemProperty("ntmgunfeel.scenarioGuns", scenarioGuns.get())
    if (scenarioMachines.isPresent) systemProperty("ntmgunfeel.machines", scenarioMachines.get())
    if (scenarioMobs.isPresent) systemProperty("ntmgunfeel.mobs", scenarioMobs.get())
    if (lookDoor.isPresent) systemProperty("ntmgunfeel.lookDoor", lookDoor.get())
    if (testSkin.isPresent) systemProperty("ntmgunfeel.testSkin", testSkin.get())
    // the NTM check as with an untested NTM (NtmCheck): -Ptested=[1,2]; -PforceOff=tracers,hud
    if (tested.isPresent) systemProperty("ntmgunfeel.tested", tested.get())
    if (forceOff.isPresent) systemProperty("ntmgunfeel.forceOff", forceOff.get())
}
// Bullets on real blocks on the dedicated server (BulletCheck): -PcheckBullets on runServer
if (providers.gradleProperty("checkBullets").isPresent) tasks.named<RunMinecraftTask>("runServer") {
    systemProperty("ntmgunfeel.checkBullets", "logs/bullet-check.txt")
    systemProperty("ntmgunfeel.trace", "logs/ntm-gunfeel-trace-bullets.tsv")
    systemProperty("ntmgunfeel.traceScenario", "bullets")
}

// ---- the tests: on Java 8 with the downgraded classes, as the players' game runs them (test), and on Java 25 with
// the classes as compiled (test25). Paths to the 26.2 pins (src/test/pinned at the root) come in as a property. ----
val downgradeTestClasses = tasks.register<xyz.wagyourtail.jvmdg.gradle.task.files.DowngradeFiles>("downgradeTestClasses") {
    inputCollection = sourceSets.main.get().output.classesDirs + sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().compileClasspath
    dependsOn(tasks.classes, tasks.testClasses)
}
fun Test.common() {
    useJUnitPlatform()
    systemProperty("ntmgunfeel.repo", repo.path)
    systemProperty("ntmgunfeel.config", layout.buildDirectory.dir("test-config").get().asFile.path)
}
tasks.test {
    common()
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    dependsOn(downgradeTestClasses)
    val t = sourceSets.test.get()
    val m = sourceSets.main.get()
    testClassesDirs = files(downgradeTestClasses.map { it.outputCollection }).filter { f ->
        // build/tmp/downgradeTestClasses/test for build/classes/java/test
        t.output.classesDirs.any { f.name == it.name }
    }
    classpath = files(downgradeTestClasses.map { it.outputCollection }) +
        files(m.output.resourcesDir, t.output.resourcesDir) +
        (t.runtimeClasspath - m.output - t.output) +
        files(providers.provider { jvmdg.getDowngradedApi(JavaVersion.VERSION_1_8) })
}
// the Java 25 tests read the mod's own classes (Java 25 bytecode) with ASM: a newer one than Forge's goes first
val asm25 = configurations.create("asm25")
dependencies { "asm25"("org.ow2.asm:asm-tree:9.9") }
val test25 = tasks.register<Test>("test25") {
    description = "The tests on Java 25"
    group = "verification"
    common()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = asm25 + sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(test25) }
