package me.zolotov.oniguruma.buildscripts

import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

abstract class CompileOnigurumaTask @Inject constructor(
    objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    projectLayout: ProjectLayout,
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    val sourceDirectory = objectFactory.directoryProperty()

    @get:Nested
    val targetPlatform = objectFactory.property(Platform::class.java)

    @get:Input
    val buildType = objectFactory.property(String::class.java).convention("Release")

    @get:Internal
    val cmakeBuildDirectory = projectLayout.buildDirectory.dir(
        providerFactory.provider {
            "cmake/${buildPlatformNativeTarget(targetPlatform.get())}/${buildType.get().lowercase()}"
        }
    )

    @get:Internal
    val autotoolsBuildDirectory = projectLayout.buildDirectory.dir(
        providerFactory.provider {
            "autotools/${buildPlatformNativeTarget(targetPlatform.get())}/${buildType.get().lowercase()}"
        }
    )

    @get:OutputFile
    val libraryFile = providerFactory.provider {
        val platform = targetPlatform.get()
        val directory = projectLayout.buildDirectory
            .dir("target/${buildPlatformNativeTarget(platform)}/${buildType.get().lowercase()}")
            .get()
            .asFile

        directory.resolve(onigurumaLibraryName(platform))
    }

    @TaskAction
    fun compile() {
        val platform = targetPlatform.get()
        if (platform.os != Os.WINDOWS && sourceDirectory.file("configure").get().asFile.exists()) {
            compileWithAutotools(platform)
        } else {
            compileWithCmake(platform)
        }
    }

    private fun compileWithCmake(platform: Platform) {
        val cmake = execOperations.findCommand("cmake")
            ?: error("Unable to find 'cmake'. Install CMake to build Oniguruma native libraries.")

        val buildDirectory = cmakeBuildDirectory.get().asFile.toPath()
        buildDirectory.createDirectories()

        execOperations.exec {
            commandLine(
                listOf(
                    cmake.toString(),
                    "-S",
                    sourceDirectory.get().asFile.absolutePath,
                    "-B",
                    buildDirectory.toString(),
                ) + cmakePlatformArguments(platform) + listOf(
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DBUILD_TESTING=OFF",
                    "-DCMAKE_BUILD_TYPE=${buildType.get()}",
                )
            )
        }

        execOperations.exec {
            commandLine(
                cmake.toString(),
                "--build",
                buildDirectory.toString(),
                "--config",
                buildType.get(),
                "--parallel",
                Runtime.getRuntime().availableProcessors().toString(),
            )
        }

        val output = libraryFile.get().toPath()
        output.parent.createDirectories()
        findBuiltLibrary(buildDirectory, output.fileName.toString()).copyTo(output, overwrite = true)
    }

    private fun compileWithAutotools(platform: Platform) {
        val make = execOperations.findCommand("make")
            ?: error("Unable to find 'make'. Install command line build tools to build Oniguruma native libraries.")

        val buildDirectory = autotoolsBuildDirectory.get().asFile.toPath()
        buildDirectory.createDirectories()
        refreshAutotoolsGeneratedFileTimestamps()

        execOperations.exec {
            workingDir = buildDirectory.toFile()
            environment(autotoolsEnvironment(platform))
            commandLine(
                listOf(
                    sourceDirectory.file("configure").get().asFile.absolutePath,
                    "--enable-shared",
                    "--disable-static",
                    "--disable-dependency-tracking",
                ) + autotoolsConfigureArguments(platform)
            )
        }

        execOperations.exec {
            workingDir = buildDirectory.toFile()
            commandLine(make.toString(), "-j${Runtime.getRuntime().availableProcessors()}")
        }

        val output = libraryFile.get().toPath()
        output.parent.createDirectories()
        findBuiltLibrary(buildDirectory, output.fileName.toString()).copyTo(output, overwrite = true)
    }

    private fun refreshAutotoolsGeneratedFileTimestamps() {
        val sourceRoot = sourceDirectory.get().asFile.toPath()
        val now = FileTime.fromMillis(System.currentTimeMillis())
        listOf(
            "aclocal.m4",
            "configure",
            "Makefile.in",
            "src/Makefile.in",
            "src/config.h.in",
            "test/Makefile.in",
            "sample/Makefile.in",
        )
            .map(sourceRoot::resolve)
            .filter(Files::exists)
            .forEach { Files.setLastModifiedTime(it, now) }
    }

    private fun cmakePlatformArguments(platform: Platform): List<String> {
        return when (platform.os) {
            Os.LINUX -> when (platform.arch) {
                Arch.X86_64 -> emptyList()
                Arch.AARCH64 -> listOf(
                    "-DCMAKE_SYSTEM_NAME=Linux",
                    "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
                    "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
                )
            }

            Os.MACOS -> listOf(
                "-DCMAKE_OSX_ARCHITECTURES=${if (platform.arch == Arch.AARCH64) "arm64" else "x86_64"}",
            )

            Os.WINDOWS -> listOf(
                "-A",
                if (platform.arch == Arch.AARCH64) "ARM64" else "x64",
            )
        }
    }

    private fun autotoolsConfigureArguments(platform: Platform): List<String> {
        return when (platform.os) {
            Os.LINUX -> when (platform.arch) {
                Arch.X86_64 -> emptyList()
                Arch.AARCH64 -> listOf("--host=aarch64-linux-gnu")
            }

            Os.MACOS -> emptyList()
            Os.WINDOWS -> emptyList()
        }
    }

    private fun autotoolsEnvironment(platform: Platform): Map<String, String> {
        return when (platform.os) {
            Os.LINUX -> when (platform.arch) {
                Arch.X86_64 -> emptyMap()
                Arch.AARCH64 -> mapOf(
                    "CC" to "aarch64-linux-gnu-gcc",
                    "AR" to "aarch64-linux-gnu-ar",
                    "RANLIB" to "aarch64-linux-gnu-ranlib",
                    "STRIP" to "aarch64-linux-gnu-strip",
                )
            }

            Os.MACOS -> {
                val archFlag = if (platform.arch == Arch.AARCH64) "arm64" else "x86_64"
                mapOf(
                    "CFLAGS" to "-O3 -arch $archFlag",
                    "LDFLAGS" to "-arch $archFlag",
                )
            }

            Os.WINDOWS -> emptyMap()
        }
    }

    private fun findBuiltLibrary(buildDirectory: Path, libraryName: String): Path {
        Files.walk(buildDirectory).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().equals(libraryName, ignoreCase = currentOs() == Os.WINDOWS) }
                .findFirst()
                .orElseThrow {
                    IllegalStateException("Unable to find built Oniguruma library '$libraryName' in $buildDirectory")
                }
        }
    }
}

internal fun ExecOperations.findCommand(command: String): Path? {
    val output = ByteArrayOutputStream()
    val result = exec {
        val cmd = when (currentOs()) {
            Os.MACOS, Os.LINUX -> listOf("/bin/sh", "-c", "command -v $command")
            Os.WINDOWS -> listOf("cmd.exe", "/c", "where", command)
        }

        commandLine(cmd)
        standardOutput = output
        isIgnoreExitValue = true
    }

    val out = output.toString().trim().takeIf { it.isNotBlank() }
    return when {
        result.exitValue != 0 -> null
        out == null -> error("failed to resolve absolute path of command '$command'")
        else -> Path.of(out.lines().first())
    }
}
