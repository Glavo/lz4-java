package net.jpountz.lz4;

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

import java.util.Arrays;

import static net.jpountz.lz4.LZ4Constants.DEFAULT_COMPRESSION_LEVEL;
import static net.jpountz.lz4.LZ4Constants.MAX_COMPRESSION_LEVEL;

/// Entry point for the LZ4 API.
///
/// This class has 3 instances
///   - a [native][#nativeInstance()] instance which is a JNI binding to [the original LZ4 C implementation](https://github.com/lz4/lz4).
///   - a [safe Java][#safeInstance()] instance which is a pure Java port of the original C library,
///   - an [unsafe Java][#unsafeInstance()] instance which is a Java port using the unofficial [sun.misc.Unsafe] API.
///
/// Only the [safe instance][#safeInstance()] is guaranteed to work on your
/// JVM, as a consequence it is advised to use the [#fastestInstance()] or
/// [#fastestJavaInstance()] to pull a [LZ4Factory] instance.
///
/// All methods from this class are very costly, so you should get an instance
/// once, and then reuse it whenever possible. This is typically done by storing
/// a [LZ4Factory] instance in a static field.
public final class LZ4Factory {

    private static final LZ4Factory INSTANCE = new LZ4Factory("JavaSafe");

    /// Returns a [LZ4Factory] instance that returns compressors and
    /// decompressors that are native bindings to the original C library.
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
    ///   - From lz4-java version 1.6.0, a [LZ4FastDecompressor] instance
    ///     returned by [#fastDecompressor()] of this instance is SLOWER
    ///     than a [LZ4SafeDecompressor] instance returned by
    ///     [#safeDecompressor()], due to a change in the original LZ4
    ///     C implementation. The corresponding C API function is deprecated.
    ///     Hence use of [#fastDecompressor()] is deprecated
    ///     for this instance.
    ///     </ol>
    ///
    ///     @return a [LZ4Factory] instance that returns compressors and
    ///     decompressors that are native bindings to the original C library
    public static LZ4Factory nativeInstance() {
        return INSTANCE;
    }

    /// Insecure variant of [#nativeInstance()]. The JNI-based [LZ4FastDecompressor] is not secure for
    /// untrusted inputs, so [#nativeInstance()] will instead return the slower safe java implementation from
    /// [#fastDecompressor()]. If that implementation is too slow for you, it is recommended to move to
    /// [#safeDecompressor()], which is actually faster even than the JNI [#fastDecompressor()]. Only if that
    /// is not an option for you, and you can guarantee no untrusted inputs will be decompressed, should you use this
    /// method.
    ///
    /// @return An insecure, JNI-backed LZ4Factory
    /// @deprecated Never decompress untrusted inputs with this instance. Prefer [#nativeInstance()].
    @Deprecated
    public static LZ4Factory nativeInsecureInstance() {
        return INSTANCE;
    }

    /// Returns a [LZ4Factory] instance that returns compressors and
    /// decompressors that are written with Java's official API.
    ///
    /// @return a [LZ4Factory] instance that returns compressors and
    /// decompressors that are written with Java's official API.
    public static LZ4Factory safeInstance() {
        return INSTANCE;
    }

    /// Returns a [LZ4Factory] instance that returns compressors and
    /// decompressors that may use [sun.misc.Unsafe] to speed up compression
    /// and decompression.
    ///
    /// @return a [LZ4Factory] instance that returns compressors and
    /// decompressors that may use [sun.misc.Unsafe] to speed up compression
    /// and decompression.
    /// @deprecated Note: It is not yet clear which Unsafe-based implementations are secure. Out of caution, this method
    /// currently returns the [#safeInstance()]. In a future version, when security has been assessed, this method
    /// may return to Unsafe.
    @Deprecated
    public static LZ4Factory unsafeInstance() {
        return INSTANCE;
    }

    /// Insecure variant of [#unsafeInstance()]. The Unsafe-based [LZ4FastDecompressor] is not secure for
    /// untrusted inputs, so [#unsafeInstance()] will instead return the slower safe java implementation from
    /// [#fastDecompressor()]. If that implementation is too slow for you, it is recommended to move to
    /// [#safeDecompressor()]. Only if that is not an option for you, and you can guarantee no untrusted inputs will
    /// be decompressed, should you use this method.
    ///
    /// @return An insecure, Unsafe-backed LZ4Factory
    /// @deprecated Never decompress untrusted inputs with this instance. Prefer [#unsafeInstance()].
    @Deprecated
    public static LZ4Factory unsafeInsecureInstance() {
        return INSTANCE;
    }

    /// Returns the fastest available [LZ4Factory] instance which does not
    /// rely on JNI bindings. It first tries to load the
    /// [unsafe instance][#unsafeInstance()], and then the
    /// [safe Java instance][#safeInstance()] if the JVM doesn't have a
    /// working [sun.misc.Unsafe].
    ///
    /// @return the fastest available [LZ4Factory] instance which does not
    /// rely on JNI bindings.
    public static LZ4Factory fastestJavaInstance() {
        return INSTANCE;
    }

    /// Returns the fastest available [LZ4Factory] instance. If the class
    /// loader is the system class loader and if the
    /// [native instance][#nativeInstance()] loads successfully, then the
    /// [native instance][#nativeInstance()] is returned, otherwise the
    /// [fastest Java instance][#fastestJavaInstance()] is returned.
    ///
    /// Please read [javadocs of nativeInstance()][#nativeInstance()] before
    /// using this method.
    ///
    /// @return the fastest available [LZ4Factory] instance
    public static LZ4Factory fastestInstance() {
        return INSTANCE;
    }

    private final String impl;
    private final LZ4Compressor fastCompressor;
    private final LZ4Compressor highCompressor;
    private final LZ4FastDecompressor fastDecompressor;
    private final LZ4SafeDecompressor safeDecompressor;
    private final LZ4Compressor[] highCompressors = new LZ4Compressor[MAX_COMPRESSION_LEVEL + 1];

    private LZ4Factory(String impl) {
        this.impl = impl;
        this.fastCompressor = new LZ4JavaSafeCompressor();
        this.highCompressor = new LZ4HCJavaSafeCompressor();
        this.fastDecompressor = LZ4JavaSafeFastDecompressor.INSTANCE;
        this.safeDecompressor = new LZ4JavaSafeSafeDecompressor();
        this.highCompressors[DEFAULT_COMPRESSION_LEVEL] = highCompressor;
        for (int level = 1; level <= MAX_COMPRESSION_LEVEL; level++) {
            if (level == DEFAULT_COMPRESSION_LEVEL) continue;
            highCompressors[level] = new LZ4HCJavaSafeCompressor(level);
        }

        // quickly test that everything works as expected
        final byte[] original = new byte[]{'a', 'b', 'c', 'd', ' ', ' ', ' ', ' ', ' ', ' ', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'};
        for (LZ4Compressor compressor : Arrays.asList(fastCompressor, highCompressor)) {
            final int maxCompressedLength = compressor.maxCompressedLength(original.length);
            final byte[] compressed = new byte[maxCompressedLength];
            final int compressedLength = compressor.compress(original, 0, original.length, compressed, 0, maxCompressedLength);
            final byte[] restored = new byte[original.length];
            fastDecompressor.decompress(compressed, 0, restored, 0, original.length);
            if (!Arrays.equals(original, restored)) {
                throw new AssertionError();
            }
            Arrays.fill(restored, (byte) 0);
            final int decompressedLength = safeDecompressor.decompress(compressed, 0, compressedLength, restored, 0);
            if (decompressedLength != original.length || !Arrays.equals(original, restored)) {
                throw new AssertionError();
            }
        }

    }

    /// Returns a blazing fast [LZ4Compressor].
    ///
    /// @return a blazing fast [LZ4Compressor]
    public LZ4Compressor fastCompressor() {
        return fastCompressor;
    }

    /// Returns a [LZ4Compressor] which requires more memory than
    /// [#fastCompressor()] and is slower but compresses more efficiently.
    ///
    /// @return a [LZ4Compressor] which requires more memory than
    /// [#fastCompressor()] and is slower but compresses more efficiently.
    public LZ4Compressor highCompressor() {
        return highCompressor;
    }

    /// Returns a [LZ4Compressor] which requires more memory than
    /// [#fastCompressor()] and is slower but compresses more efficiently.
    /// The compression level can be customized.
    ///
    /// For current implementations, the following is true about compression level:<ol>
    ///   - It should be in range [1, 17]
    ///   - A compression level higher than 17 would be treated as 17.
    ///   - A compression level lower than 1 would be treated as 9.
    /// </ol>
    /// Note that compression levels from different implementations
    /// (native, unsafe Java, and safe Java) cannot be compared with one another.
    /// Specifically, the native implementation of a high compression level
    /// is not necessarily faster than the safe/unsafe Java implementation
    /// of the same compression level.
    ///
    /// @param compressionLevel the compression level between [1, 17]; the higher the level, the higher the compression ratio
    /// @return a [LZ4Compressor] which requires more memory than
    /// [#fastCompressor()] and is slower but compresses more efficiently.
    public LZ4Compressor highCompressor(int compressionLevel) {
        if (compressionLevel > MAX_COMPRESSION_LEVEL) {
            compressionLevel = MAX_COMPRESSION_LEVEL;
        } else if (compressionLevel < 1) {
            compressionLevel = DEFAULT_COMPRESSION_LEVEL;
        }
        return highCompressors[compressionLevel];
    }

    /// Returns a [LZ4FastDecompressor] instance.
    /// Use of this method is deprecated for the [native instance][#nativeInstance()].
    ///
    /// @return a [LZ4FastDecompressor] instance
    /// @see #nativeInstance()
    public LZ4FastDecompressor fastDecompressor() {
        return fastDecompressor;
    }

    /// Returns a [LZ4SafeDecompressor] instance.
    ///
    /// @return a [LZ4SafeDecompressor] instance
    public LZ4SafeDecompressor safeDecompressor() {
        return safeDecompressor;
    }

    /// Returns a [LZ4UnknownSizeDecompressor] instance.
    ///
    /// @return a [LZ4UnknownSizeDecompressor] instance
    /// @deprecated use [#safeDecompressor()]
    public LZ4UnknownSizeDecompressor unknownSizeDecompressor() {
        return safeDecompressor();
    }

    /// Returns a [LZ4Decompressor] instance.
    ///
    /// @return a [LZ4Decompressor] instance
    /// @deprecated use [#fastDecompressor()]
    public LZ4Decompressor decompressor() {
        return fastDecompressor();
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
        return getClass().getSimpleName() + ":" + impl;
    }

}
