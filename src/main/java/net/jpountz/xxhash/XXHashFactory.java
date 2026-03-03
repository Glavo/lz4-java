package net.jpountz.xxhash;

/*
 * Copyright 2020 Adrien Grand and the lz4-java contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Random;

/// Entry point to get [XXHash32] and [StreamingXXHash32] instances.
///
/// This class has 3 instances
///   - a [native][#nativeInstance()] instance which is a JNI binding to [the original XXHash C implementation](http://code.google.com/p/xxhash/).
///   - a [safe Java][#safeInstance()] instance which is a pure Java port of the original C library,
///   - an [unsafe Java][#unsafeInstance()] instance which is a Java port using the unofficial [sun.misc.Unsafe] API.
///
///
/// Only the [safe instance][#safeInstance()] is guaranteed to work on your
/// JVM, as a consequence it is advised to use the [#fastestInstance()] or
/// [#fastestJavaInstance()] to pull a [XXHashFactory] instance.
///
/// All methods from this class are very costly, so you should get an instance
/// once, and then reuse it whenever possible. This is typically done by storing
/// a [XXHashFactory] instance in a static field.
public final class XXHashFactory {

    private static final XXHashFactory INSTANCE = new XXHashFactory();

    /// Returns a [XXHashFactory] that returns [XXHash32] instances that
    /// are native bindings to the original C API.
    ///
    /// Please note that this instance has some traps you should be aware of:<ol>
    ///   - Upon loading this instance, files will be written to the temporary
    ///     directory of the system. Although these files are supposed to be deleted
    ///     when the JVM exits, they might remain on systems that don't support
    ///     removal of files being used such as Windows.
    ///   - The instance can only be loaded once per JVM. This can be a problem
    ///     if your application uses multiple class loaders (such as most servlet
    ///     containers): this instance will only be available to the children of the
    ///     class loader which has loaded it. As a consequence, it is advised to
    ///     either not use this instance in webapps or to put this library in the lib
    ///     directory of your servlet container so that it is loaded by the system
    ///     class loader.
    ///     </ol>
    ///
    /// @return a [XXHashFactory] that returns [XXHash32] instances that
    ///     are native bindings to the original C API.
    public static XXHashFactory nativeInstance() {
        return INSTANCE;
    }

    /// Returns a [XXHashFactory] that returns [XXHash32] instances that
    /// are written with Java's official API.
    ///
    /// @return a [XXHashFactory] that returns [XXHash32] instances that
    /// are written with Java's official API.
    public static XXHashFactory safeInstance() {
        return INSTANCE;
    }

    /// Returns a [XXHashFactory] that returns [XXHash32] instances that
    /// may use [sun.misc.Unsafe] to speed up hashing.
    ///
    /// @return a [XXHashFactory] that returns [XXHash32] instances that
    /// may use [sun.misc.Unsafe] to speed up hashing.
    public static XXHashFactory unsafeInstance() {
        return INSTANCE;
    }

    /// Returns the fastest available [XXHashFactory] instance which does not
    /// rely on JNI bindings. It first tries to load the
    /// [unsafe instance][#unsafeInstance()], and then the
    /// [safe Java instance][#safeInstance()] if the JVM doesn't have a
    /// working [sun.misc.Unsafe].
    ///
    /// @return the fastest available [XXHashFactory] instance which does not
    /// rely on JNI bindings.
    public static XXHashFactory fastestJavaInstance() {
        return INSTANCE;
    }

    /// Returns the fastest available [XXHashFactory] instance. If the class
    /// loader is the system class loader and if the
    /// [native instance][#nativeInstance()] loads successfully, then the
    /// [native instance][#nativeInstance()] is returned, otherwise the
    /// [fastest Java instance][#fastestJavaInstance()] is returned.
    ///
    /// Please read [javadocs of nativeInstance()][#nativeInstance()] before
    /// using this method.
    ///
    /// @return the fastest available [XXHashFactory] instance.
    public static XXHashFactory fastestInstance() {
        return INSTANCE;
    }

    private final XXHash32 hash32;
    private final XXHash64 hash64;

    private XXHashFactory() throws SecurityException, IllegalArgumentException {
        this.hash32 = XXHash32JavaSafe.INSTANCE;
        this.hash64 = XXHash64JavaSafe.INSTANCE;
    }

    /// Returns a [XXHash32] instance.
    ///
    /// @return a [XXHash32] instance.
    public XXHash32 hash32() {
        return hash32;
    }

    /// Returns a [XXHash64] instance.
    ///
    /// @return a [XXHash64] instance.
    public XXHash64 hash64() {
        return hash64;
    }

    /// Return a new [StreamingXXHash32] instance.
    ///
    /// @param seed the seed to use
    /// @return a [StreamingXXHash32] instance
    public StreamingXXHash32 newStreamingHash32(int seed) {
        return new StreamingXXHash32JavaSafe(seed);
    }

    /// Return a new [StreamingXXHash64] instance.
    ///
    /// @param seed the seed to use
    /// @return a [StreamingXXHash64] instance
    public StreamingXXHash64 newStreamingHash64(long seed) {
        return new StreamingXXHash64JavaSafe(seed);
    }

    /// Prints the fastest instance.
    ///
    /// @param args no argument required
    public static void main(String[] args) {
        System.out.println("Fastest instance is " + fastestInstance());
        System.out.println("Fastest Java instance is " + fastestJavaInstance());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ":JavaSafe";
    }

}
