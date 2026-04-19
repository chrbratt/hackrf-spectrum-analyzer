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

/**
 * Try to kill any running instance of the packaged executable so its file
 * handles release before we delete the app-image directory. Only fires
 * when the user opted in with `-PkillRunning` (default off so we never
 * surprise-terminate something the user wanted alive). Returns true if
 * a process was actually killed.
 */
fun killRunningExe(exeName: String, logger: org.gradle.api.logging.Logger): Boolean {
    val tasklist = ProcessBuilder("tasklist", "/FI", "IMAGENAME eq $exeName",
                                  "/FO", "CSV", "/NH")
        .redirectErrorStream(true).start()
    tasklist.waitFor()
    val output = tasklist.inputStream.bufferedReader().readText()
    if (!output.contains(exeName, ignoreCase = true)) return false

    logger.lifecycle("Found running $exeName, terminating before rebuild (-PkillRunning)...")
    val taskkill = ProcessBuilder("taskkill", "/F", "/IM", exeName)
        .redirectErrorStream(true).start()
    taskkill.waitFor()
    // Give Windows a moment to release file handles before the deleteRecursively.
    Thread.sleep(500)
    return true
}

/**
 * Recursively clear the read-only attribute that jpackage stamps onto
 * everything in the produced app-image (it does this so end users do not
 * accidentally modify a deployed install). Java's File.delete on Windows
 * silently fails on read-only files, which is the most common reason the
 * old "close the app" error message was misleading: the app was not
 * running, the files were just read-only.
 */
fun clearReadOnlyRecursive(file: File) {
    if (file.isDirectory) {
        file.listFiles()?.forEach { clearReadOnlyRecursive(it) }
    }
    if (file.exists() && !file.canWrite()) {
        file.setWritable(true)
    }
}

/**
 * Delete the directory with a small backoff loop so transient locks
 * (antivirus scanning a freshly written file, Windows Search indexer,
 * Explorer holding a thumbnail) don't immediately fail the build.
 *
 * Sequence: clear read-only attributes -> Java deleteRecursively (3
 * attempts with growing backoff) -> shell out to `rmdir /S /Q` as last
 * resort. The shell fallback handles the few corner cases where Java's
 * File.delete refuses for opaque reasons (long paths, ACL quirks).
 * Returns true if the directory is gone after all attempts.
 */
fun deleteAppImageWithRetries(appDir: File, logger: org.gradle.api.logging.Logger): Boolean {
    if (!appDir.exists()) return true
    val attempts = 3
    for (i in 1..attempts) {
        clearReadOnlyRecursive(appDir)
        if (appDir.deleteRecursively() && !appDir.exists()) return true
        if (i < attempts) {
            logger.lifecycle("Delete attempt $i for $appDir failed, retrying in ${500L * i}ms...")
            Thread.sleep(500L * i)
        }
    }
    if (appDir.exists()) {
        logger.lifecycle("Java delete failed; falling back to 'rmdir /S /Q' for $appDir")
        val rmdir = ProcessBuilder("cmd", "/c", "rmdir", "/S", "/Q", appDir.absolutePath)
            .redirectErrorStream(true).start()
        rmdir.waitFor()
    }
    return !appDir.exists()
}

tasks.register<Exec>("jpackageWinApp") {
    group = "distribution"
    description = "Produce a self-contained Windows app-image (folder, no installer). " +
                  "Pass -PkillRunning to auto-terminate any running copy first."
    dependsOn("stageJpackageInput")
    onlyIf { isWindows() }
    doFirst {
        // jpackage refuses to overwrite an existing app-image directory and
        // exits non-zero ("Application destination directory ... already
        // exists"). Wipe just our own output folder so re-running the task
        // is idempotent without nuking other build artifacts.
        val outDir = layout.buildDirectory.dir("jpackage").get().asFile
        val appDir = outDir.resolve("HackRF Spectrum Analyzer")
        if (appDir.exists()) {
            logger.lifecycle("Removing previous app-image at $appDir")
            if (project.hasProperty("killRunning")) {
                killRunningExe("HackRF Spectrum Analyzer.exe", logger)
            }
            check(deleteAppImageWithRetries(appDir, logger)) {
                "Could not delete $appDir after 3 attempts.\n" +
                "  Most likely cause: the previously installed app is still running.\n" +
                "  Fix one of these ways:\n" +
                "    1. Close the app window manually, then re-run.\n" +
                "    2. Run:\n" +
                "         taskkill /F /IM \"HackRF Spectrum Analyzer.exe\"\n" +
                "       and re-run.\n" +
                "    3. Re-run with auto-kill:\n" +
                "         .\\gradlew.bat jpackageWinApp -PkillRunning"
            }
        }
        outDir.mkdirs()
    }
    // Pure GUI launch - no console window. If you ever need to see stdout/
    // stderr during development, append `--win-console` to the command line
    // below or run the app via `./gradlew run` instead.
    commandLine = jpackageCommand("app-image")
}

/**
 * Locate the WiX Toolset bin/ directory. Returns null if WiX cannot be
 * found anywhere obvious. Order of search:
 *   1. The current process PATH (fast path; works when WiX was on PATH
 *      *before* this gradle process started).
 *   2. The standard WiX 3.x install location from `winget install
 *      WiXToolset.WiXToolset` or the offline wix3*.exe installer:
 *        C:\Program Files (x86)\WiX Toolset v3.x\bin\light.exe
 *      and the same path under "Program Files" for the rare 64-bit msi.
 *
 * jpackage on JDK 21 invokes WiX 3.x (light.exe / candle.exe), so we
 * key the search on light.exe rather than the WiX 4 wix.exe.
 */
fun findWixBinDir(): File? {
    val whereProc = ProcessBuilder("where", "light")
        .redirectErrorStream(true).start()
    whereProc.waitFor()
    if (whereProc.exitValue() == 0) {
        val first = whereProc.inputStream.bufferedReader().readLine()
        if (!first.isNullOrBlank()) {
            val parent = File(first.trim()).parentFile
            if (parent != null && parent.exists()) return parent
        }
    }
    val roots = listOf(
        File("C:/Program Files (x86)"),
        File("C:/Program Files"),
    )
    for (root in roots) {
        val dirs = root.listFiles { f ->
            f.isDirectory && f.name.startsWith("WiX Toolset v3.")
        } ?: continue
        for (dir in dirs) {
            val light = File(dir, "bin/light.exe")
            if (light.exists()) return light.parentFile
        }
    }
    return null
}

tasks.register<Exec>("jpackageWinMsi") {
    group = "distribution"
    description = "Produce a Windows MSI via jpackage (auto-detects WiX 3.x)."
    dependsOn("stageJpackageInput")
    onlyIf { isWindows() }
    doFirst {
        // Resolve WiX up-front so we can surface a friendly error if it's
        // missing, and so we can inject its bin/ into PATH for jpackage
        // (which itself only checks PATH when looking for light.exe).
        // This makes the build work right after `winget install
        // WiXToolset.WiXToolset` in the same shell session - the user no
        // longer has to log out / restart their terminal to pick up the
        // installer's PATH entry.
        val wixBin = findWixBinDir()
        check(wixBin != null) {
            "WiX Toolset is required for MSI packaging but was not found.\n" +
            "  Install with one of:\n" +
            "    winget install WiXToolset.WiXToolset\n" +
            "    choco install wixtoolset\n" +
            "  or download WiX 3.x from https://wixtoolset.org\n" +
            "  Searched: PATH, plus standard install locations under\n" +
            "    C:\\Program Files (x86)\\WiX Toolset v3.*\\bin\\\n" +
            "    C:\\Program Files\\WiX Toolset v3.*\\bin\\\n" +
            "  Then re-run: ./gradlew jpackageWinMsi\n" +
            "  (For a no-installer build that has no WiX dependency, use ./gradlew jpackageWinApp instead.)"
        }
        logger.lifecycle("Using WiX from: $wixBin")
        // Prepend WiX to the PATH the jpackage subprocess will inherit.
        // The Gradle Exec task takes a fresh snapshot of `environment`
        // before launching, so this only affects the jpackage call.
        val currentPath = System.getenv("PATH") ?: ""
        environment("PATH", "${wixBin.absolutePath};$currentPath")

        // Same idempotency cleanup as the app-image task: delete any previous
        // MSI so re-running doesn't leave stale artifacts around. The MSI
        // file itself is rarely locked (Windows Installer reads then closes
        // it), but a single retry handles transient AV-scan locks.
        val outDir = layout.buildDirectory.dir("jpackage").get().asFile
        val version = (project.version as String).removeSuffix("-SNAPSHOT")
        val msi = outDir.resolve("HackRF Spectrum Analyzer-$version.msi")
        if (msi.exists()) {
            logger.lifecycle("Removing previous MSI at $msi")
            if (!msi.delete()) {
                Thread.sleep(500)
                check(msi.delete()) {
                    "Could not delete $msi - is the file open in another process?"
                }
            }
        }
        outDir.mkdirs()
    }
    commandLine = jpackageCommand("msi")
}
