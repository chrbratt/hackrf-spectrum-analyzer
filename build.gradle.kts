import org.gradle.internal.os.OperatingSystem

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
            // capture/* depends on Xuggler (no longer on the classpath). Rewriting the
            // recording pipeline is out of scope; the sources are kept for reference
            // and excluded from compilation until a JavaFX-native replacement lands.
            exclude("jspectrumanalyzer/capture/**")
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
            exclude("src-java/**", "lib/**", "capture/**")
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

    // slf4j binding needed by a few core utils. The legacy Swing code (compiled by Ant)
    // pulls in its own MigLayout + Xuggler jars from src/hackrf-sweep/lib.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val nativeLibDir = file("src/hackrf-sweep/lib/win32-x86-64").absolutePath

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
            from("src/hackrf-sweep/lib/win32-x86-64") {
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
    from("src/hackrf-sweep/lib/win32-x86-64") {
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

tasks.register<Exec>("jpackageWinApp") {
    group = "distribution"
    description = "Produce a self-contained Windows app-image (folder, no installer)."
    dependsOn("stageJpackageInput")
    onlyIf { OperatingSystem.current().isWindows }
    doFirst { layout.buildDirectory.dir("jpackage").get().asFile.mkdirs() }
    // --win-console keeps a console window attached so stdout/stderr (and any
    // startup errors) are visible. Drop the flag for a "clean" release build.
    commandLine = jpackageCommand("app-image") + listOf("--win-console")
}

tasks.register<Exec>("jpackageWinMsi") {
    group = "distribution"
    description = "Produce a Windows MSI via jpackage (requires WiX Toolset on PATH)."
    dependsOn("stageJpackageInput")
    onlyIf { OperatingSystem.current().isWindows }
    doFirst { layout.buildDirectory.dir("jpackage").get().asFile.mkdirs() }
    commandLine = jpackageCommand("msi")
}
