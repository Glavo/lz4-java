# LZ4 Java Lightweight

[![Gradle Check](https://github.com/Glavo/lz4-java/actions/workflows/check.yml/badge.svg)](https://github.com/Glavo/lz4-java/actions/workflows/check.yml)
[![codecov](https://codecov.io/gh/Glavo/lz4-java/graph/badge.svg?token=50PHPEMS9R)](https://codecov.io/gh/Glavo/lz4-java)

This project is a fork of [yawkat/lz4-java](https://github.com/yawkat/lz4-java).

The goal of this project is to provide lightweight, fast, and safe implementations of the LZ4 and xxHash algorithms.

The API of this project is compatible with [yawkat/lz4-java](https://github.com/yawkat/lz4-java)
and [lz4/lz4-java](https://github.com/lz4/lz4-java), but it only provides a pure Java implementation that does not use
the `sun.misc.Unsafe` API.
This reduces the JAR size from 773 KiB (`at.yawk.lz4:lz4-java:1.10.4`) to less than 80 KiB.

For compatibility, `LZ4Factory` and `XXHashFactory` in this project still provide methods like `nativeInstance()` and
`unsafeInstance()`, but they are actually delegated to `safeInstance()`.

This project requires Java 17 or higher. It uses APIs such as `VarHandle` to accelerate the implementation of the LZ4
and xxHash algorithms, so it should be faster than the `JavaSafe` version
of [yawkat/lz4-java](https://github.com/yawkat/lz4-java).

## Download

Gradle:

```kotlin
dependencies {
    implementation("org.glavo:lz4-java:1.10.4.1")
}
```

Maven:
```xml
<dependency>
    <groupId>org.glavo</groupId>
    <artifactId>lz4-java</artifactId>
    <version>1.10.4.1</version>
</dependency>
```
