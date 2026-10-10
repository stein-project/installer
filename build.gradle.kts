// The Stein Loader installer build.
//
//   ./gradlew build            the installer (build/installer/SteinLoader-Installer.jar), the Java 25 of each system
//                              (build/installer/runtime-<system>.zip) and, on Windows, the app for people without Java
//                              (build/installer/SteinLoader-Installer-windows.zip)
//   ./gradlew build -Pstein.targets=windows     only some systems (a quick local build)
//   ./gradlew build -Pstein.dist=false          with the plain Bridge and Loader (local tests only; the default and every
//                                               release ship the obfuscated ones, ../bridge and ../loader ./gradlew dist)
//   ./gradlew spotlessApply    formats the Java code (google-java-format, AOSP style)
//
// The installer jar carries the payload: the Stein Bridge and the Stein Loader (built by ../bridge and ../loader),
// LWJGL 3 with the natives of every system, the Patcher with its rules. Nothing from Mojang: the game jar is patched
// on the player's machine, from their own installation. The Java 25 of each system goes in a zip of its own, downloaded
// while installing and checked against the sha256 written into the installer.
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    java
    alias(libs.plugins.spotless)
}

val installJava = file("src/main/java/dev/xavier/stein/installer/Install.java")
fun constant(source: File, name: String) =
    Regex("""String $name = "([^"]+)"""").find(source.readText())?.groupValues?.get(1)
        ?: throw GradleException("no $name in $source")

group = "dev.xavier.stein"
version = constant(installJava, "VERSION")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

sourceSets.main {
    java.setSrcDirs(listOf("src/main/java"))
    resources.setSrcDirs(listOf("src/main/resources"))
}
sourceSets.test {
    java.setSrcDirs(listOf("test"))
    resources.setSrcDirs(emptyList<File>())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}
// The installer opens with whatever Java the player already has: someone playing 1.8.9 through an alternative launcher
// double-clicks the .jar, and that Java is often 8. The Java 25 travels separately and runs the Patcher and the game.
tasks.compileJava { options.release = 8 }

// ---- the Bridge and the Loader ----

val bridgeBuild = gradle.includedBuild("stein-bridge")
val loaderBuild = gradle.includedBuild("stein-loader")
val bridgeDir = bridgeBuild.projectDir
val loaderDir = loaderBuild.projectDir
/**
 * The obfuscated build (the default): the payload carries the Bridge and the Loader renamed by ProGuard
 * (../bridge/build/dist, ../loader/build/dist) and the patcher rules translated to those names (../bridge/build/dist/rules).
 * That is what players get, and what a release must ship. -Pstein.dist=false builds the installer with the plain jars
 * and rules (a quicker local build, never released). The name mappings stay in each repository's build/dist-mapping:
 * nothing of them goes into the payload.
 */
val dist = providers.gradleProperty("stein.dist").map { it.isEmpty() || it.toBoolean() }.getOrElse(true)
val upstream = listOf(
    bridgeBuild.task(":assemble"), bridgeBuild.task(":runtimeLibs"), bridgeBuild.task(":patcherClasses"),
    loaderBuild.task(":assemble"),
) + if (dist) listOf(bridgeBuild.task(":dist"), loaderBuild.task(":dist")) else emptyList()

// The library versions the installer writes into the launcher profile follow the code: SteinBridge.VERSION and
// Loader.VERSION.
val versionCheck = tasks.register("versionCheck") {
    group = "verification"
    description = "Checks that Install.BRIDGE_VERSION and LOADER_VERSION match the Bridge and the Loader."
    val bridgeSource = bridgeDir.resolve("src/main/java/dev/xavier/stein/bridge/SteinBridge.java")
    val loaderSource = loaderDir.resolve("src/main/java/dev/xavier/stein/loader/Loader.java")
    inputs.files(installJava, bridgeSource, loaderSource)
    outputs.upToDateWhen { true }
    doLast {
        for ((source, key) in listOf(bridgeSource to "BRIDGE_VERSION", loaderSource to "LOADER_VERSION")) {
            val code = constant(source, "VERSION")
            val install = constant(installJava, key)
            check(code == install) { "version mismatch: ${source.name} says $code, Install.$key says $install" }
        }
    }
}

/** What the installer installs: the Bridge, the Loader, LWJGL 3 (every system's natives), the Patcher and its rules. */
val payload = tasks.register<Sync>("payload") {
    description = "Assembles the installer payload in build/payload."
    dependsOn(upstream, versionCheck)
    into(layout.buildDirectory.dir("payload"))
    inputs.property("dist", dist)
    from(bridgeDir.resolve("build/runtime-libs/lwjgl")) { into("lwjgl") }
    from(bridgeDir.resolve("build/classes/java/patcher")) { into("patcher") }
    from(bridgeDir.resolve("build/runtime-libs/asm")) { into("patcher/asm") }
    if (dist) {
        from(bridgeDir.resolve("build/dist/steinbridge.jar"))
        from(loaderDir.resolve("build/dist/steinloader.jar"))
        // The rules with the obfuscated names and no comments (name-mapping.txt, the LWJGL renames, included).
        from(bridgeDir.resolve("build/dist/rules")) { into("rules") }
    } else {
        from(bridgeDir.resolve("build/libs/steinbridge.jar"))
        from(loaderDir.resolve("build/libs/steinloader.jar"))
        from(bridgeDir.resolve("patcher")) {
            include("*.tsv")
            into("rules")
        }
        from(bridgeDir.resolve("scan/name-mapping.txt")) { into("rules") }
    }
    doLast {
        // The payload is what reaches players: nothing from build/dist-mapping, and with dist no comment of the rules.
        val out = destinationDir
        check(out.walkTopDown().none { it.isFile && (it.name.endsWith(".pro") || it.name == "codes.txt") }) {
            "the payload carries a file of build/dist-mapping"
        }
        if (dist) {
            val commented = out.resolve("rules").listFiles()!!.filter { f -> f.readLines().any { it.startsWith("#") } }
            check(commented.isEmpty()) { "rules with comments (not the obfuscated build's): $commented" }
        }
        logger.lifecycle(if (dist) "payload: obfuscated Bridge and Loader, translated rules" else
            "payload: PLAIN Bridge and Loader (-Pstein.dist=false: not for release)")
    }
}

// ---- the Java 25 of each system ----

val runtimes = Properties().apply { file("gradle/runtimes.properties").reader().use { load(it) } }
val allTargets = listOf("windows", "linux", "macos", "macos-arm64")
val targets = providers.gradleProperty("stein.targets").map { it.split(",").map(String::trim) }.getOrElse(allTargets)
val os = org.gradle.internal.os.OperatingSystem.current()
/** The system running this build: its JDK runs jlink and jpackage for every target. */
val host = when {
    os.isWindows -> "windows"
    os.isMacOsX -> if (System.getProperty("os.arch") == "aarch64") "macos-arm64" else "macos"
    else -> "linux"
}
// The modules of Java SE plus what the game and LWJGL use (Unsafe, pt_BR number formats, TLS over elliptic curves).
// The Windows certificate store (jdk.crypto.mscapi) only exists there: asking for it elsewhere breaks jlink.
val modules = "java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.management," +
    "jdk.accessibility,jdk.naming.dns,jdk.net"

/** File operations for task actions: Task.project is not available while a task runs. */
interface FileOps {
    @get:javax.inject.Inject val fs: FileSystemOperations
    @get:javax.inject.Inject val exec: ExecOperations
}
val ops = objects.newInstance<FileOps>()
val fileOps = ops.fs

fun sha256(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}

/** The JDK home inside an extracted Corretto (the macOS one sits in <name>.jdk/Contents/Home). */
fun jdkHome(dir: File): File = dir.walkTopDown().maxDepth(4).first { it.resolve("jmods").isDirectory }

/** Downloads and extracts the pinned Corretto of a system (sha256-checked) into build/tools/corretto-<system>. */
fun fetchJdk(target: String) = tasks.register("fetchJdk-$target") {
    description = "Downloads the pinned Corretto JDK for $target."
    val url = runtimes.getProperty("$target.url")
    val sum = runtimes.getProperty("$target.sha256")
    val archive = layout.buildDirectory.file("tools/corretto-$target." + if (url.endsWith(".zip")) "zip" else "tar.gz")
    val dir = layout.buildDirectory.dir("tools/corretto-$target")
    inputs.property("url", url)
    inputs.property("sha256", sum)
    outputs.dir(dir)
    doLast {
        val file = archive.get().asFile
        if (!file.isFile || sha256(file) != sum) {
            file.parentFile.mkdirs()
            logger.lifecycle("downloading ${url.substringAfterLast('/')}")
            URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val got = sha256(file)
        check(got == sum) { "sha256 of $url is $got, gradle/runtimes.properties says $sum" }
        val out = dir.get().asFile
        out.deleteRecursively()
        fileOps.copy {
            from(if (file.name.endsWith(".zip")) zipTree(file) else tarTree(resources.gzip(file)))
            into(out)
        }
    }
}


val fetched = (targets + host).distinct().associateWith { fetchJdk(it) }
val hostJdk = fetched.getValue(host).map { jdkHome(layout.buildDirectory.dir("tools/corretto-$host").get().asFile) }
fun tool(name: String) = hostJdk.get().resolve("bin/" + if (os.isWindows) "$name.exe" else name)

/** jlink from the target's jmods (not the build machine's): every system's runtime comes out of this one build. */
val runtimeZips = targets.associateWith { target ->
    val image = layout.buildDirectory.dir("runtime/$target")
    val jlink = tasks.register("jlink-$target") {
        description = "Builds the Java 25 runtime for $target with jlink."
        dependsOn(fetched.getValue(target), fetched.getValue(host))
        val mods = if (target == "windows") "$modules,jdk.crypto.mscapi" else modules
        inputs.property("modules", mods)
        inputs.property("jdk", runtimes.getProperty("version"))
        outputs.dir(image)
        doLast {
            val out = image.get().asFile
            out.deleteRecursively()
            val jmods = jdkHome(layout.buildDirectory.dir("tools/corretto-$target").get().asFile).resolve("jmods")
            ops.exec.exec {
                commandLine(tool("jlink").path, "--module-path", jmods.path, "--add-modules", mods, "--strip-debug",
                    "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", out.path)
            }
        }
    }
    // No timestamps and a fixed order: the same Java gives the same zip, byte for byte, so players do not download
    // ~50 MB again when only the installer changed (the installer marks the installed Java by the zip's sha256).
    tasks.register<Zip>("runtimeZip-$target") {
        description = "Packs the $target runtime into build/installer/runtime-$target.zip."
        from(jlink)
        destinationDirectory = layout.buildDirectory.dir("installer")
        archiveFileName = "runtime-$target.zip"
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

/** runtimes.txt inside the installer: "<system> <sha256>" of each runtime zip, checked when it is downloaded. */
val runtimesTxt = tasks.register("runtimesTxt") {
    description = "Writes the sha256 of each runtime zip for the installer."
    val zips = runtimeZips.mapValues { (_, task) -> task.flatMap { it.archiveFile } }
    zips.values.forEach { inputs.file(it) }
    val out = layout.buildDirectory.file("generated/runtimes/runtimes.txt")
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(zips.entries.joinToString("") { (t, f) -> "$t ${sha256(f.get().asFile)}\n" })
    }
}

// ---- the installer ----

/** The installer's own classes and pictures (the Stein Loader logo comes from the loader repository). */
fun CopySpec.installerContent() {
    from(sourceSets.main.map { it.output })
    from(runtimesTxt)
    from(loaderDir.resolve("src/main/resources/assets/steinloader/textures/gui/logo.png")) {
        rename { "stein-loader-logo.png" }
    }
}

val installerJar = tasks.register<Jar>("installerJar") {
    group = "build"
    description = "Builds build/installer/SteinLoader-Installer.jar, one installer for every system."
    destinationDirectory = layout.buildDirectory.dir("installer")
    archiveFileName = "SteinLoader-Installer.jar"
    manifest.attributes("Main-Class" to "dev.xavier.stein.installer.InstallerApp")
    installerContent()
    from(payload) { into("payload") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { enabled = false }

// Windows only, and the only one that still carries Java inside: it exists for people with no Java at all, who could
// not run the installer to download a runtime.
val jpackage = tasks.register("jpackage") {
    description = "Builds the Windows app (jpackage) in build/jpackage/app."
    onlyIf("built on Windows, with the windows target") { os.isWindows && "windows" in targets }
    val input = layout.buildDirectory.dir("jpackage/input")
    val app = layout.buildDirectory.dir("jpackage/app")
    val icon = layout.buildDirectory.file("jpackage/sl.ico")
    val name = "Stein Loader Installer"
    if (os.isWindows && "windows" in targets) {
        dependsOn(payload, runtimesTxt, runtimeZips.getValue("windows"), tasks.named("jlink-windows"), tasks.classes)
    }
    doLast {
        val inDir = input.get().asFile
        inDir.deleteRecursively()
        inDir.mkdirs()
        fileOps.copy {
            from(payload)
            into(inDir.resolve("payload"))
        }
        val classes = temporaryDir.resolve("classes").apply { deleteRecursively(); mkdirs() }
        fileOps.copy {
            installerContent()
            into(classes)
        }
        ops.exec.exec {
            commandLine(tool("jar").path, "--create", "--file", inDir.resolve("stein-installer.jar").path,
                "--main-class", "dev.xavier.stein.installer.InstallerApp", "-C", classes.path, ".")
        }
        ops.exec.exec {
            commandLine(tool("java").path, file("tools/MakeIco.java").path, file("src/main/resources/sl-256.png").path,
                icon.get().asFile.path)
            standardOutput = OutputStream.nullOutputStream()
        }
        app.get().asFile.deleteRecursively()
        ops.exec.exec {
            commandLine(tool("jpackage").path, "--type", "app-image", "--dest", app.get().asFile.path, "--name", name,
                "--input", inDir.path, "--main-jar", "stein-installer.jar",
                "--main-class", "dev.xavier.stein.installer.InstallerApp",
                "--runtime-image", layout.buildDirectory.dir("runtime/windows").get().asFile.path,
                "--icon", icon.get().asFile.path, "--app-version", project.version.toString(),
                "--vendor", "X4vieer", "--description", "Stein Loader installer")
        }
    }
    outputs.dir(app)
}
val windowsApp = tasks.register<Zip>("windowsApp") {
    group = "build"
    description = "Packs the Windows app (jpackage) for people without Java."
    onlyIf("built on Windows, with the windows target") { os.isWindows && "windows" in targets }
    from(jpackage)
    destinationDirectory = layout.buildDirectory.dir("installer")
    archiveFileName = "SteinLoader-Installer-windows.zip"
}

tasks.assemble { dependsOn(installerJar, runtimeZips.values, windowsApp) }
tasks.test { failOnNoDiscoveredTests = false }

spotless {
    // LF everywhere, as committed (.editorconfig); Windows checkouts too.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("src/**/*.java", "test/**/*.java", "tools/*.java")
        googleJavaFormat(libs.versions.google.java.format.get()).aosp().reorderImports(true)
    }
}
