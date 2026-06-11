# Oniguruma FFM

[![Maven central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-ffm.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-ffm)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/zolotov/oniguruma-ffm/build.yaml)](https://github.com/zolotov/oniguruma-ffm/actions/workflows/build.yaml)
[![GitHub License](https://img.shields.io/github/license/zolotov/oniguruma-ffm)](https://github.com/zolotov/oniguruma-ffm/blob/main/LICENSE)

A Java Foreign Function & Memory (FFM) based wrapper for the Oniguruma regular expression library.
This library is primarily designed to support syntax highlighting in [IntelliJ](https://www.jetbrains.com/idea/)-based IDEs through the [`textmate-core`](https://github.com/JetBrains/intellij-community/tree/master/plugins/textmate/core) library.

## Performance

Quick comparison against [oniguruma-jni](https://github.com/zolotov/oniguruma-jni) (JNI + Rust `onig` crate).

| Operation               | oniguruma-jni (JNI) |      oniguruma-ffm (FFM) |
|-------------------------|--------------------:|-------------------------:|
| `match`                 |        3,105 ops/ms |  **4,524 ops/ms (+46%)** |
| `createRegex`           |        1,820 ops/ms |  **2,277 ops/ms (+25%)** |
| `createString` (43 B)   |        6,967 ops/ms | **40,024 ops/ms (5.7×)** |
| `createString` (64 KiB) |          492 ops/ms |               526 ops/ms |


## Installation

The library is published in two flavors:

- **full** (default) — the jar bundles Oniguruma native libraries for all supported platforms,
  ready to be loaded with `Oniguruma.createFromResources()`.
- **slim** — the same classes without bundled native libraries. Use it when you want to provide
  `libonig` yourself (a system-wide installation or a custom build) and keep foreign binaries
  out of your dependency tree.

### Full jar (default)

The full jar is resolved whenever you do not explicitly opt in to slim: a plain Gradle dependency
without the packaging attribute, or any Maven dependency without a classifier.

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-ffm:$version")
}
```

```xml
<dependency>
    <groupId>me.zolotov.oniguruma</groupId>
    <artifactId>oniguruma-ffm</artifactId>
    <version>${version}</version>
</dependency>
```

### Slim jar

Gradle consumers select the slim jar with the `me.zolotov.oniguruma.packaging` dependency attribute:

```kotlin
val onigurumaPackaging = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-ffm:$version") {
        attributes {
            attribute(onigurumaPackaging, "slim")
        }
    }
}
```

Maven consumers cannot use dependency attributes (they are part of Gradle module metadata) and
should use the `slim` classifier instead:

```xml
<dependency>
    <groupId>me.zolotov.oniguruma</groupId>
    <artifactId>oniguruma-ffm</artifactId>
    <version>${version}</version>
    <classifier>slim</classifier>
</dependency>
```

With the slim jar, load the library from an explicit path via `Oniguruma.createFromFile(path)`,
or rely on `Oniguruma.createFromResources()` falling back to well-known system locations.


## Usage

### Basic Setup

```java
import me.zolotov.oniguruma.Oniguruma;

import java.nio.file.Path;

// Loads a bundled native library for the current platform.
var oniguruma = Oniguruma.createFromResources();

// Or point the FFM bindings at a specific native library file.
var fromFile = Oniguruma.createFromFile(Path.of("/path/to/libonig.dylib"));
```

### Pattern Matching

```java
import me.zolotov.oniguruma.Oniguruma;
import me.zolotov.oniguruma.OnigurumaMatchRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

var pattern = "to".getBytes(StandardCharsets.UTF_8);
var text = "text to match".getBytes(StandardCharsets.UTF_8);

try (var oniguruma = Oniguruma.createFromResources()) {
    try (var regex = oniguruma.createRegex(pattern)) {
        try (var string = oniguruma.createString(text)) {
            var result = oniguruma.match(
                regex,
                string,
                new OnigurumaMatchRequest(0, true, false)
            );
            System.out.println(result.captureCount());
            System.out.println(Arrays.toString(result.regionOffsets()));
        }
    }
}
```

### Resource Lifecycle

`Oniguruma`, `OnigurumaRegex`, and `OnigurumaString` are thin native resource wrappers. The library
does not track closed state and does not protect against use-after-close, double-close, or closing
resources concurrently with matching. Clients must close every regex and string exactly once before
closing the owning `Oniguruma` instance, and must not use a regex or string after it has been closed.

## Building from Source

1. Clone the repository
2. Ensure you have the following prerequisites:
    - JDK 25 or later
    - CMake
    - A native C compiler for your current platform
3. Run tests using Gradle. This builds Oniguruma for your current platform and loads it from test resources:
   ```bash
   ./gradlew test
   ```
4. Build the project using Gradle:
   ```bash
   ./gradlew build
   ```

The default build compiles only the current platform native library. CI builds and archives all supported native libraries, then assembles the final jar with:

```bash
NATIVE_BUILD_MODE=skip ./gradlew build
```

Supported bundled native platforms are:

- `windows-x86_64`
- `windows-aarch64`
- `linux-x86_64`
- `linux-aarch64`
- `macos-x86_64`
- `macos-aarch64`

See [Installation](#installation) for choosing between the full jar and the slim jar without bundled native libraries.

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## Acknowledgments

- Oniguruma library developers

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.
