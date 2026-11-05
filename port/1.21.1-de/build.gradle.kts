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
}

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
        }
        register("server") {
            server()
            gameDirectory.set(projectDir.resolve("run-server"))
            programArgument("--nogui")
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
            filter { line: String -> mine.fold(line) { acc, r -> r.regex.replace(acc, r.to) } }
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
