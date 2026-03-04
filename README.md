# LZ4 Java Lightweight

This project is a fork of [yawkat/lz4-java](https://github.com/yawkat/lz4-java).

The goal of this project is to provide lightweight, fast, and safe implementations of the LZ4 and xxHash algorithms.

The API of this project is compatible with [yawkat/lz4-java](https://github.com/yawkat/lz4-java) and [lz4/lz4-java](https://github.com/lz4/lz4-java), but it only provides a pure Java implementation that does not use the `sun.misc.Unsafe` API. This reduces the JAR size from 773 KiB (`at.yawk.lz4:lz4-java:1.10.4`) to less than 80 KiB.

This project requires Java 17 or higher. It uses APIs such as `VarHandle` to accelerate the implementation of the LZ4 and xxHash algorithms, so it should be faster than the `JavaSafe` version of [yawkat/lz4-java](https://github.com/yawkat/lz4-java).

