// The mod's common code: what any Minecraft version runs as it is (simulations, ballistics, balance, arm curves,
// config parsing, maths). Its sources are compiled into the mod of each version (versions/<v>/: a second source
// directory of main), so the mod's jar holds them as it always did. This project compiles them once more, alone,
// without Minecraft, NeoForge, NTM or LWJGL on the class path: that build failing is the guard. A class that needs
// the game stays in src/main/java (the version's adapter) and reaches core through an interface core declares.
plugins {
    java
}

val root: File = rootProject.projectDir

// the JDK the mod is built with (the versions get it from ModDevGradle)
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
}

// The only libraries core may use, the versions Minecraft 26.2 ships (or, for XZ, the mod nests): plain Java
// libraries the older versions' builds can shade. Not transitive: the class path is exactly this list.
val coreLibraries = listOf(
    "org.joml:joml:1.10.8",
    "org.slf4j:slf4j-api:2.0.17",
    "com.google.code.gson:gson:2.14.0",
    "it.unimi.dsi:fastutil:8.5.18",
    "org.tukaani:xz:1.10",
    "org.jspecify:jspecify:1.0.0",
)
val coreGroups = coreLibraries.map { it.substringBefore(':') }.toSet()
val testGroups = setOf("org.junit", "org.junit.jupiter", "org.junit.platform", "org.opentest4j", "org.apiguardian")

dependencies {
    for (lib in coreLibraries) implementation(lib) { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The guard on the build itself: no other library, no jar or project, may come onto core's class path. Checked on
// what the script declares, every build (a class path is only resolved when there is something to compile).
afterEvaluate {
    for (conf in configurations) for (d in conf.dependencies) {
        val ok = d is ExternalModuleDependency && (d.group in coreGroups || (conf.name.startsWith("test") && d.group in testGroups))
        if (!ok) throw GradleException("core may use only ${coreLibraries.joinToString()} " +
                "(configuration ${conf.name} has ${d.group}:${d.name}): what needs the game stays in src/main/java")
    }
}

sourceSets {
    main { java.setSrcDirs(listOf("src/main/java")); resources.setSrcDirs(emptyList<File>()) }
    // the tests read the mod's data files as the mod does, from the class path
    test { java.setSrcDirs(listOf("src/test/java")); resources.setSrcDirs(listOf(root.resolve("src/main/resources"))) }
}

// The lowest Java a port compiles core with as it is: 1.21.1 (DE) builds at release 21; 1.12.2 and 1.7.10 build at 25
// and downgrade the jar (JvmDowngrader). So core's sources must compile at 21 too: no Java 22+ language or API.
val PORT_RELEASE = 21

// the same flags as the mod's (versions' build.gradle.kts); compileForPorts (below) at the ports' release
tasks.withType<JavaCompile>().configureEach {
    options.release = if (name == "compileForPorts") PORT_RELEASE else 25
    options.encoding = "UTF-8"
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines"
    options.compilerArgs.addAll(listOf("-proc:none", "-nowarn"))
}

// nothing uses a jar of core alone: the versions compile its sources
tasks.jar { enabled = false }

// core at PORT_RELEASE once more, for that check alone (the classes are thrown away), with every test run
val compileForPorts = tasks.register<JavaCompile>("compileForPorts") {
    group = "verification"
    description = "Compiles core at release $PORT_RELEASE, the lowest a port compiles it with."
    source = sourceSets.main.get().java
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory = layout.buildDirectory.dir("classes/forPorts")
    javaCompiler = javaToolchains.compilerFor(java.toolchain)
}

// test: every test but the slow ones (@Tag("slow")); slowTest: the slow ones only. As in the versions' build.
val slowTest = tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs core's tests too slow for every build (@Tag(\"slow\"))."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    shouldRunAfter(tasks.test)
}
tasks.test {
    useJUnitPlatform { excludeTags("slow") }
    dependsOn(compileForPorts)
}
tasks.withType<Test>().configureEach {
    // the tests read src/main/resources, src/test/pinned and core/ from the repository root; CoreBoundaryTest reads
    // core's sources, comments and strings too
    workingDir = root
    inputs.dir(root.resolve("core/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("pinUpdate", providers.environmentVariable("PIN_UPDATE").orElse(""))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
