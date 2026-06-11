import me.zolotov.oniguruma.buildscripts.Arch
import me.zolotov.oniguruma.buildscripts.CompileOnigurumaTask
import me.zolotov.oniguruma.buildscripts.Os
import me.zolotov.oniguruma.buildscripts.Platform
import me.zolotov.oniguruma.buildscripts.buildPlatformNativeTarget
import me.zolotov.oniguruma.buildscripts.currentPlatform
import me.zolotov.oniguruma.buildscripts.normalizedName
import me.zolotov.oniguruma.buildscripts.onigurumaLibraryName
import org.gradle.api.attributes.Attribute
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.net.URI

plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

group = "me.zolotov.oniguruma"
description = """
    A Java Foreign Function & Memory (FFM) scaffold for the Oniguruma regular expression library.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()


repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    jmhImplementation(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    modularity.inferModulePath.set(true)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named("jmhRunBytecodeGenerator") {
    enabled = false
}

val onigurumaVersion = "6.9.10"
val onigurumaSourceUrl = "https://github.com/kkos/oniguruma/releases/download/v$onigurumaVersion/onig-$onigurumaVersion.tar.gz"
val onigurumaArchive = layout.buildDirectory.file("downloads/oniguruma-$onigurumaVersion.tar.gz")
val onigurumaSourceRoot = layout.buildDirectory.dir("native-src/oniguruma")

val downloadOnigurumaSource = tasks.register("downloadOnigurumaSource") {
    inputs.property("onigurumaVersion", onigurumaVersion)
    inputs.property("onigurumaSourceUrl", onigurumaSourceUrl)
    outputs.file(onigurumaArchive)

    doLast {
        val destination = onigurumaArchive.get().asFile
        destination.parentFile.mkdirs()
        URI.create(onigurumaSourceUrl)
            .toURL()
            .openStream()
            .use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
    }
}

val unpackOnigurumaSource = tasks.register<Sync>("unpackOnigurumaSource") {
    dependsOn(downloadOnigurumaSource)
    from(tarTree(resources.gzip(onigurumaArchive)))
    into(onigurumaSourceRoot)
    eachFile {
        val segments = relativePath.segments.drop(1)
        if (segments.isEmpty()) {
            exclude()
        } else {
            relativePath = RelativePath(true, *segments.toTypedArray())
        }
    }
    includeEmptyDirs = false
}

val currentPlatform = currentPlatform()
val nativeBuildMode = providers.gradleProperty("nativeBuildMode")
    .orElse(providers.environmentVariable("NATIVE_BUILD_MODE"))
val nativeBuildModeValue = nativeBuildMode.orNull ?: "current"
require(nativeBuildModeValue in setOf("current", "all", "skip")) {
    "Unsupported native build mode '$nativeBuildModeValue'. Use 'current', 'all', or 'skip'."
}
val nativePlatforms = listOf(
    Platform(Os.MACOS, Arch.AARCH64),
    Platform(Os.MACOS, Arch.X86_64),
    Platform(Os.WINDOWS, Arch.AARCH64),
    Platform(Os.WINDOWS, Arch.X86_64),
    Platform(Os.LINUX, Arch.AARCH64),
    Platform(Os.LINUX, Arch.X86_64),
)
val nativeResourcePlatforms = when (nativeBuildModeValue) {
    "all", "skip" -> nativePlatforms
    else -> listOf(currentPlatform)
}
val nativeBuildType = "Release"

fun nativeLibraryFile(platform: Platform) =
    layout.buildDirectory.file(
        "target/${buildPlatformNativeTarget(platform)}/${nativeBuildType.lowercase()}/${onigurumaLibraryName(platform)}"
    )

fun isNativeCompilationEnabled(platform: Platform) = when (nativeBuildModeValue) {
    "skip" -> false
    "all" -> true
    else -> currentPlatform == platform
}

val compileNativeTaskByPlatform = nativePlatforms.associateWith { platform ->
    tasks.register<CompileOnigurumaTask>("compileNative-${buildPlatformNativeTarget(platform)}") {
        dependsOn(unpackOnigurumaSource)
        sourceDirectory.set(onigurumaSourceRoot)
        targetPlatform.set(platform)
        buildType.set(nativeBuildType)
        enabled = isNativeCompilationEnabled(platform)
    }
}

val generateNativeResources = tasks.register<Sync>("generateResourcesDir") {
    destinationDir = layout.buildDirectory.dir("native").get().asFile
    if (nativeBuildModeValue != "skip") {
        dependsOn(nativeResourcePlatforms.map { compileNativeTaskByPlatform.getValue(it) })
    }

    nativeResourcePlatforms.forEach { platform ->
        from(nativeLibraryFile(platform)) {
            into("native/${platform.normalizedName}")
        }
    }
}

tasks.processResources {
    dependsOn(generateNativeResources)
}

sourceSets {
    main {
        resources.srcDirs(generateNativeResources.map { it.destinationDir })
    }
}

val verifyNativeResources = tasks.register("verifyNativeResources") {
    group = "verification"
    description = "Verifies that bundled native resources required by the active native build mode are present."

    dependsOn(generateNativeResources)
    inputs.property("nativeBuildMode", nativeBuildModeValue)

    doLast {
        val missingLibraries = nativeResourcePlatforms
            .map { platform ->
                layout.buildDirectory
                    .file("native/native/${platform.normalizedName}/${onigurumaLibraryName(platform)}")
                    .get()
                    .asFile
            }
            .filterNot { it.isFile }

        if (missingLibraries.isNotEmpty()) {
            error(
                "Missing bundled Oniguruma native libraries:\n" +
                    missingLibraries.joinToString(separator = "\n") { " - ${it.relativeTo(projectDir)}" } +
                    "\nBuild the current-platform library with './gradlew test', or download/build all CI native artifacts before packaging with NATIVE_BUILD_MODE=skip or -PnativeBuildMode=skip."
            )
        }
    }
}

tasks.named<Jar>("jar") {
    dependsOn(verifyNativeResources)
}

tasks.named<Jar>("sourcesJar") {
    exclude("**/native")
}

val slimJar = tasks.register<Jar>("slimJar") {
    group = "build"
    description = "Assembles a jar archive without native libraries"

    archiveClassifier.set("slim")
    from(sourceSets.main.map { it.output.classesDirs })
    from(sourceSets.main.map { it.output.resourcesDir }) {
        exclude("**/native")
    }

    manifest {
        from(tasks.jar.get().manifest)
    }
    dependsOn(tasks.processResources)
}

val packagingAttribute = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

configurations {
    apiElements {
        attributes {
            attribute(packagingAttribute, "full")
        }
    }

    runtimeElements {
        attributes {
            attribute(packagingAttribute, "full")
        }
    }
}

val javaComponent = components.findByName("java") as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(configurations.consumable("slim") {
    attributes {
        // Deliberately the only attribute on this variant. Consumers that do not request the
        // packaging attribute must keep resolving runtimeElements/apiElements: they match all
        // standard requested attributes while this variant matches none, so Gradle's
        // "longest match" disambiguation picks them. Mirroring the standard attributes here
        // (usage, category, target JVM, ...) would make full and slim equally good matches for
        // default consumers and fail resolution with an ambiguity error.
        attribute(packagingAttribute, "slim")
    }
    outgoing {
        artifact(slimJar)
    }
}.get()) {}

compileNativeTaskByPlatform.forEach { (platform, task) ->
    val platformAttribute = Attribute.of("me.zolotov.oniguruma.platform", String::class.java)
    val configuration = configurations.consumable("bindings_${platform.normalizedName}") {
        attributes {
            attribute(platformAttribute, platform.normalizedName)
        }
        outgoing {
            artifact(task.map { it.libraryFile }) {
                classifier = platform.normalizedName
                builtBy(task)
            }
        }
    }.get()

    // Variants must be registered at configuration time: modifying the component after the
    // publication metadata has been populated fails in Gradle 9.
    if (isNativeCompilationEnabled(platform)) {
        javaComponent.addVariantsFromConfiguration(configuration) {}
    }
}

