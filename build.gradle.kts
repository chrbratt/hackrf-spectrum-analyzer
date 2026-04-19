// Note: we deliberately avoid `org.gradle.internal.os.OperatingSystem` (an
// internal Gradle API that has shifted package between releases) and check
// the JVM-supplied `os.name` instead. Same answer, no internal-API warnings.
fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().startsWith("windows")

plugins {
    application
    java
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "se.voxo"
version = "3.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java {
            srcDirs("src/hackrf-sweep/src-java")
        }
        resources {
            // Three roots, all flattened onto the classpath:
            //   - src-java/** : CSS, PNG, ICO assets sitting next to their Java package
            //   - src/hackrf-sweep : presets.csv + freq/*.csv (formerly loaded via
            //     File I/O from -Dhackrf.resources.dir)
            //   - build/generated-resources : freq/index.txt written by generateFreqIndex
            srcDirs(
                "src/hackrf-sweep/src-java",
                "src/hackrf-sweep",
                layout.buildDirectory.dir("generated-resources")
            )
            include("**/*.csv", "**/*.css", "**/*.png", "**/*.ico", "**/*.txt")
            exclude("src-java/**", "lib/**")
        }
    }
    test {
        java {
            srcDirs("src/hackrf-sweep/src-java-test")
        }
    }
}

// Enumerate freq/*.csv at build time so the runtime code can discover them when
// running from a jar (where Files.list on a classpath URL is unreliable).
val generateFreqIndex by tasks.registering {
    val freqDir = file("src/hackrf-sweep/freq")
    val indexFile = layout.buildDirectory.file("generated-resources/freq/index.txt")
    inputs.dir(freqDir)
    outputs.file(indexFile)
    doLast {
        val out = indexFile.get().asFile
        out.parentFile.mkdirs()
        val names = freqDir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.map { it.name }?.sorted() ?: emptyList()
        out.writeText(names.joinToString("\n"))
    }
}

tasks.named("processResources") {
    dependsOn(generateFreqIndex)
}

javafx {
    version = "21.0.5"
    modules = listOf(
        "javafx.controls",
        "javafx.graphics",
        "javafx.swing",
        "javafx.fxml"
    )
}

dependencies {
    implementation("org.jfree:jfreechart:1.5.5")
    implementation("org.jfree:org.jfree.chart.fx:2.0.1")
    implementation("org.controlsfx:controlsfx:11.2.1")
    implementation("net.java.dev.jna:jna:5.14.0")

    // SLF4J on the compile classpath; logback supplies the runtime binding.
    // The application code uses LoggerFactory directly (engine, recorder,
    // device enumeration), so we need the API jar at compile time and not
    // just as a transitive of logback.
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The MSVC build (see src/hackrf-sweep/native/CMakeLists.txt + BUILD_NATIVE.md)
// stages all four runtime DLLs (hackrf-sweep, fftw3f, libusb-1.0, pthreadVC3)
// into build/native/dist. With HackRF Pro support the DLL ABI changed
// (hackrf_sweep_lib_list_devices / get_opened_info / start with serial
// parameter), so the legacy MinGW DLL is no longer ABI-compatible and the
// fallback was dropped - run `gradlew buildHackrfSweepDll` first if the
// folder is empty.
val msvcNativeDir = layout.buildDirectory.dir("native/dist").get().asFile
val nativeLibDir: String = msvcNativeDir.absolutePath
val nativeLibSourceDir: File = msvcNativeDir

application {
    // Main wraps FxApp so jpackage (which puts JavaFX on the classpath, not the
    // module path) can still launch the app. `./gradlew run` uses the openjfx
    // plugin which properly configures the module path, so either entry works.
    mainClass.set("jspectrumanalyzer.fx.Main")
}

// Bundle native DLLs into the installed distribution so the start script and
// the jpackage MSI can find them next to the launcher. presets.csv and the
// freq/*.csv files ride along inside the application jar (classpath resources).
distributions {
    main {
        contents {
            from(nativeLibSourceDir) {
                into("native")
                include("*.dll")
            }
        }
    }
}

// Tell the generated start scripts where the native libraries live (relative to
// APP_HOME which the Windows launcher sets).
tasks.named<CreateStartScripts>("startScripts") {
    defaultJvmOpts = listOf("-Djna.library.path=APP_HOME_PLACEHOLDER/native")
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%")
        )
        unixScript.writeText(
            unixScript.readText().replace("APP_HOME_PLACEHOLDER", "\$APP_HOME")
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Let the JNA load-test (HackRFSweepNativeBridgeLoadTest) find the
    // freshly built hackrf-sweep.dll; the test itself is skipped if the
    // path is missing or we're not on Windows.
    systemProperty("jna.library.path", nativeLibDir)
}

// The openjfx plugin wires JavaFX modules onto the default `run` task only,
// so `runFx` is just an alias for it.
tasks.named<JavaExec>("run") {
    // JNA picks hackrf-sweep.dll from jna.library.path; Windows then resolves its
    // sibling DLLs (libusb, libfftw3f, libwinpthread) from the same folder.
    systemProperty("jna.library.path", nativeLibDir)
}

tasks.register("runFx") {
    group = "application"
    description = "Run the new JavaFX UI (alias for `run`)."
    dependsOn("run")
}

// Stage everything jpackage needs (jars + native DLLs) under build/jpackage-input.
// CSV resources live on the classpath inside the application jar.
tasks.register<Copy>("stageJpackageInput") {
    group = "distribution"
    description = "Stage jars and DLLs under build/jpackage-input."
    into(layout.buildDirectory.dir("jpackage-input"))
    from(tasks.named<Jar>("jar"))
    from(configurations.runtimeClasspath)
    from(nativeLibSourceDir) {
        include("*.dll")
    }
}

fun jpackageCommand(type: String): List<String> {
    val inputDir = layout.buildDirectory.dir("jpackage-input").get().asFile
    val outputDir = layout.buildDirectory.dir("jpackage").get().asFile
    val iconFile = file("src/hackrf-sweep/lib/program.ico")
    val jarName = tasks.named<Jar>("jar").get().archiveFileName.get()
    return buildList {
        add("jpackage")
        addAll(listOf("--name", "HackRF Spectrum Analyzer"))
        addAll(listOf("--app-version", (project.version as String).removeSuffix("-SNAPSHOT")))
        addAll(listOf("--input", inputDir.absolutePath))
        addAll(listOf("--main-jar", jarName))
        addAll(listOf("--main-class", "jspectrumanalyzer.fx.Main"))
        addAll(listOf("--type", type))
        addAll(listOf("--dest", outputDir.absolutePath))
        if (type == "msi" || type == "exe") {
            add("--win-shortcut")
            add("--win-menu")
        }
        if (iconFile.exists()) {
            addAll(listOf("--icon", iconFile.absolutePath))
        }
        // jpackage copies --input into <APPDIR>. JNA finds hackrf-sweep.dll via
        // jna.library.path; Windows resolves the sibling DLLs from the same dir.
        addAll(listOf("--java-options", "-Djna.library.path=\$APPDIR"))
    }
}

// --------------------------------------------------------------------
// Native DLL build (MSVC, via vcpkg).
// --------------------------------------------------------------------
// Configure & build src/hackrf-sweep/native/CMakeLists.txt -> hackrf-sweep.dll.
//
// Requires (one-time setup, see BUILD_NATIVE.md):
//   - Visual Studio 2022 Build Tools with the C++ workload.
//   - vcpkg cloned to %VCPKG_ROOT% (defaults to C:\vcpkg) with these
//     packages installed for the x64-windows triplet:
//         pthreads libusb fftw3
//   - The HackRF release source extracted under <repo>/hackrf-2026.01.3/
//     (override with -PhackrfSourceDir=<path>).
//
// Run as:  ./gradlew buildHackrfSweepDll
val vcpkgRoot = providers.environmentVariable("VCPKG_ROOT")
    .orElse("C:/vcpkg").get()
// CMake parses the value of -D options as a CMake string, where '\' starts an
// escape sequence. Force forward slashes so Windows absolute paths survive.
val hackrfSourceDir: String = ((project.findProperty("hackrfSourceDir") as String?)
    ?: file("hackrf-2026.01.3/hackrf-2026.01.3").absolutePath).replace('\\', '/')
val nativeBuildDir = layout.buildDirectory.dir("native").get().asFile

val cmakeExe: String by lazy {
    // Prefer cmake on PATH; otherwise fall back to the copy bundled with
    // VS Build Tools so users without a standalone install still build.
    val onPath = ProcessBuilder("where", "cmake")
        .redirectErrorStream(true).start()
    onPath.waitFor()
    if (onPath.exitValue() == 0) "cmake"
    else "C:/Program Files (x86)/Microsoft Visual Studio/2022/" +
        "BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe"
}

val configureHackrfSweepDll by tasks.registering(Exec::class) {
    group = "native"
    description = "Run cmake configure for the hackrf-sweep MSVC build."
    onlyIf { isWindows() }
    doFirst {
        nativeBuildDir.mkdirs()
        val srcCheck = file("$hackrfSourceDir/host/libhackrf/src/hackrf.c")
        check(srcCheck.exists()) {
            "HackRF source not found at $srcCheck. Extract the official " +
            "hackrf-2026.01.3 release into <repo>/hackrf-2026.01.3 or pass " +
            "-PhackrfSourceDir=<path>."
        }
        val toolchain = file("$vcpkgRoot/scripts/buildsystems/vcpkg.cmake")
        check(toolchain.exists()) {
            "vcpkg toolchain not found at $toolchain. Set VCPKG_ROOT or " +
            "install vcpkg per BUILD_NATIVE.md."
        }
    }
    commandLine = listOf(
        cmakeExe,
        "-S", file("src/hackrf-sweep/native").absolutePath,
        "-B", nativeBuildDir.absolutePath,
        "-G", "Visual Studio 17 2022",
        "-A", "x64",
        "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
        "-DVCPKG_TARGET_TRIPLET=x64-windows",
        "-DHACKRF_SOURCE_DIR=$hackrfSourceDir",
    )
}

tasks.register<Exec>("buildHackrfSweepDll") {
    group = "native"
    description = "Build hackrf-sweep.dll (and its runtime DLLs) with MSVC."
    onlyIf { isWindows() }
    dependsOn(configureHackrfSweepDll)
    commandLine = listOf(
        cmakeExe,
        "--build", nativeBuildDir.absolutePath,
        "--config", "Release",
    )
    doLast {
        val out = nativeBuildDir.resolve("dist/hackrf-sweep.dll")
        check(out.exists()) { "Build did not produce $out" }
        logger.lifecycle("Built native DLL: $out")
    }
}

tasks.register<Exec>("jpackageWinApp") {
    group = "distribution"
    description = "Produce a self-contained Windows app-image (folder, no installer)."
    dependsOn("stageJpackageInput")
    onlyIf { isWindows() }
    doFirst { layout.buildDirectory.dir("jpackage").get().asFile.mkdirs() }
    // Pure GUI launch - no console window. If you ever need to see stdout/
    // stderr during development, append `--win-console` to the command line
    // below or run the app via `./gradlew run` instead.
    commandLine = jpackageCommand("app-image")
}

tasks.register<Exec>("jpackageWinMsi") {
    group = "distribution"
    description = "Produce a Windows MSI via jpackage (requires WiX Toolset on PATH)."
    dependsOn("stageJpackageInput")
    onlyIf { isWindows() }
    doFirst { layout.buildDirectory.dir("jpackage").get().asFile.mkdirs() }
    commandLine = jpackageCommand("msi")
}
