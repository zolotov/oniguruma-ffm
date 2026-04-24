# Oniguruma FFM

[![Maven central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-ffm.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-ffm)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/zolotov/oniguruma-ffm/build.yaml)](https://github.com/zolotov/oniguruma-ffm/actions/workflows/build.yaml)
[![GitHub License](https://img.shields.io/github/license/zolotov/oniguruma-ffm)](https://github.com/zolotov/oniguruma-ffm/blob/main/LICENSE)

A Java Foreign Function & Memory (FFM) based wrapper for the Oniguruma regular expression library.
This library is primarily designed to support syntax highlighting in [IntelliJ](https://www.jetbrains.com/idea/)-based IDEs through the [`textmate-core`](https://github.com/JetBrains/intellij-community/tree/master/plugins/textmate/core) library.

## Installation

Add the following dependency to your project:

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-ffm:$version")
}
```

## Usage

### Basic Setup

```java
import me.zolotov.oniguruma.Oniguruma;

import java.nio.file.Path;

var oniguruma = Oniguruma.createFromResources();

// Or point the scaffold at a specific native library file
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
   µ try (var regex = oniguruma.createRegex(pattern)) {
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
3. Build the project using Gradle:
   ```bash
   ./gradlew build
   ```

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## Acknowledgments

- Oniguruma library developers

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.
