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

import static net.jpountz.lz4.Instances.COMPRESSORS;
import static net.jpountz.lz4.Instances.FAST_DECOMPRESSORS;
import static net.jpountz.lz4.Instances.SAFE_DECOMPRESSORS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.stream.LongStream;

import net.jpountz.RandomContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.util.lz4.LZ4;

public class LZ4Test extends AbstractLZ4Test {

    private static final long[] RANDOM_SEEDS_50 = LongStream.range(0, 50).toArray();

    @ParameterizedTest
    @FieldSource("RANDOM_SEEDS_50")
    public void testMaxCompressedLength(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int len = context.nextBoolean() ? context.nextInt(17) : context.nextInt(1 << 30);

        for (LZ4Compressor compressor : COMPRESSORS) {
            assertEquals(LZ4.LZ4_COMPRESSBOUND(len), compressor.maxCompressedLength(len));
        }
    }

    private static byte[] getCompressedWorstCase(byte[] decompressed) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int len = decompressed.length;
        if (len >= LZ4Constants.RUN_MASK) {
            baos.write(LZ4Constants.RUN_MASK << LZ4Constants.ML_BITS);
            len -= LZ4Constants.RUN_MASK;
            while (len >= 255) {
                baos.write(255);
                len -= 255;
            }
            baos.write(len);
        } else {
            baos.write(len << LZ4Constants.ML_BITS);
        }
        try {
            baos.write(decompressed);
        } catch (IOException e) {
            throw new AssertionError();
        }
        return baos.toByteArray();
    }

    @Test
    public void testEmpty() {
        testRoundTrip(new RandomContext(0L), new byte[0]);
    }

    public void testUncompressWorstCase(RandomContext context, LZ4FastDecompressor decompressor) {
        final int len = context.nextInt(100 * 1024);
        final int max = context.nextInt(1, 256);
        byte[] decompressed = context.nextBytes(len, max);
        byte[] compressed = getCompressedWorstCase(decompressed);
        byte[] restored = new byte[decompressed.length];
        int cpLen = decompressor.decompress(compressed, 0, restored, 0, decompressed.length);
        assertEquals(compressed.length, cpLen);
        Assertions.assertArrayEquals(decompressed, restored);
    }

    @Test
    public void testUncompressWorstCase() {
        var context = new RandomContext(0L);
        for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
            testUncompressWorstCase(context, decompressor);
        }
    }

    public void testUncompressWorstCase(RandomContext context, LZ4SafeDecompressor decompressor) {
        final int len = context.nextInt(100 * 1024);
        final int max = context.nextInt(1, 256);
        byte[] decompressed = context.nextBytes(len, max);
        byte[] compressed = getCompressedWorstCase(decompressed);
        byte[] restored = new byte[decompressed.length];
        int uncpLen = decompressor.decompress(compressed, 0, compressed.length, restored, 0);
        assertEquals(decompressed.length, uncpLen);
        Assertions.assertArrayEquals(decompressed, restored);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0L, 1L, 2L, 3L, 4L,
            8847L, 15370L // Seed that generates len < 16
    })
    public void testUncompressSafeWorstCase(long randomSeed) {
        var context = new RandomContext(randomSeed);
        for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
            testUncompressWorstCase(context, decompressor);
        }
    }

    public void testRoundTrip(RandomContext context, byte[] data, int off, int len,
                              LZ4Compressor compressor,
                              LZ4FastDecompressor decompressor,
                              LZ4SafeDecompressor decompressor2) {
        for (Tester<?> tester : Arrays.asList(Tester.BYTE_ARRAY, Tester.BYTE_BUFFER, Tester.BYTE_ARRAY_WITH_LENGTH, Tester.BYTE_BUFFER_WITH_LENGTH)) {
            testRoundTrip(context, tester, data, off, len, compressor, decompressor, decompressor2);
        }
        if (data.length == len && off == 0) {
            for (SrcDestTester<?> tester : Arrays.asList(SrcDestTester.BYTE_ARRAY, SrcDestTester.BYTE_BUFFER, SrcDestTester.BYTE_ARRAY_WITH_LENGTH, SrcDestTester.BYTE_BUFFER_WITH_LENGTH)) {
                testRoundTrip(context, tester, data, compressor, decompressor, decompressor2);
            }
        }
    }

    public <T> void testRoundTrip(
            RandomContext context, Tester<T> tester,
            byte[] data, int off, int len,
            LZ4Compressor compressor,
            LZ4FastDecompressor decompressor,
            LZ4SafeDecompressor decompressor2) {
        final int maxCompressedLength = tester.maxCompressedLength(len);
        // "maxCompressedLength + 1" for the over-estimated compressed length test below
        final T compressed = tester.allocate(context, maxCompressedLength + 1);
        final int compressedLen = tester.compress(compressor,
                tester.copyOf(context, data), off, len,
                compressed, 0, maxCompressedLength);

        // test decompression
        final T restored = tester.allocate(context, len);
        assertEquals(compressedLen, tester.decompress(decompressor, compressed, 0, restored, 0, len));
        Assertions.assertArrayEquals(Arrays.copyOfRange(data, off, off + len), tester.copyOf(restored, 0, len));

        // make sure it fails if the compression dest is not large enough
        tester.fill(restored, context.nextByte());
        final T compressed2 = tester.allocate(context, compressedLen - 1);
        try {
            final int compressedLen2 = tester.compress(compressor,
                    tester.copyOf(context, data), off, len,
                    compressed2, 0, compressedLen - 1);
            // Compression can succeed even with the smaller dest
            // because the compressor is allowed to return different compression results
            // even when it is invoked with the same input data.
            // In this case, just make sure the compressed data can be successfully decompressed.
            assertEquals(compressedLen2, tester.decompress(decompressor, compressed2, 0, restored, 0, len));
            Assertions.assertArrayEquals(Arrays.copyOfRange(data, off, off + len), tester.copyOf(restored, 0, len));
        } catch (LZ4Exception e) {
            // OK
        }

        if (tester != Tester.BYTE_ARRAY_WITH_LENGTH && tester != Tester.BYTE_BUFFER_WITH_LENGTH) {
            // LZ4DecompressorWithLength will succeed in decompression
            // because it ignores destLen.

            if (len > 0) {
                // decompression dest is too small
                try {
                    tester.decompress(decompressor, compressed, 0, restored, 0, len - 1);
                    Assertions.fail();
                } catch (LZ4Exception e) {
                    // OK
                }
            }

            // decompression dest is too large
            final T restored2 = tester.allocate(context, len + 1);
            try {
                final int cpLen = tester.decompress(decompressor, compressed, 0, restored2, 0, len + 1);
                Assertions.fail("compressedLen=" + cpLen);
            } catch (LZ4Exception e) {
                // OK
            }
        }

        // try decompression when only the size of the compressed buffer is known
        if (len > 0) {
            tester.fill(restored, context.nextByte());
            assertEquals(len, tester.decompress(decompressor2, compressed, 0, compressedLen, restored, 0, len));
            Assertions.assertArrayEquals(Arrays.copyOfRange(data, off, off + len), tester.copyOf(restored, 0, len));
            tester.fill(restored, context.nextByte());
        } else {
            assertEquals(0, tester.decompress(decompressor2, compressed, 0, compressedLen, tester.allocate(context, 1), 0, 1));
        }

        // over-estimated compressed length
        try {
            final int decompressedLen = tester.decompress(decompressor2, compressed, 0, compressedLen + 1, tester.allocate(context, len + 100), 0, len + 100);
            Assertions.fail("decompressedLen=" + decompressedLen);
        } catch (LZ4Exception e) {
            // OK
        }

        // under-estimated compressed length
        try {
            final int decompressedLen = tester.decompress(decompressor2, compressed, 0, compressedLen - 1, tester.allocate(context, len + 100), 0, len + 100);
            Assertions.fail("decompressedLen=" + decompressedLen);
        } catch (LZ4Exception e) {
            // OK
        }
    }

    public <T> void testRoundTrip(RandomContext context, SrcDestTester<T> tester,
                                  byte[] data,
                                  LZ4Compressor compressor,
                                  LZ4FastDecompressor decompressor,
                                  LZ4SafeDecompressor decompressor2) {
        final T original = tester.copyOf(context, data);
        final int maxCompressedLength = tester.maxCompressedLength(data.length);
        final T compressed = tester.allocate(context, maxCompressedLength);
        final int compressedLen = tester.compress(compressor,
                original,
                compressed);
        if (original instanceof ByteBuffer) {
            assertEquals(data.length, ((ByteBuffer) original).position());
            assertEquals(compressedLen, ((ByteBuffer) compressed).position());
            ((ByteBuffer) original).rewind();
            ((ByteBuffer) compressed).rewind();
        }

        // test decompression
        final T restored = tester.allocate(context, data.length);
        assertEquals(compressedLen, tester.decompress(decompressor, compressed, restored));
        if (original instanceof ByteBuffer) {
            assertEquals(compressedLen, ((ByteBuffer) compressed).position());
            assertEquals(data.length, ((ByteBuffer) restored).position());
        }
        Assertions.assertArrayEquals(data, tester.copyOf(restored, 0, data.length));
        if (original instanceof ByteBuffer) {
            ((ByteBuffer) compressed).rewind();
            ((ByteBuffer) restored).rewind();
        }

        // try decompression when only the size of the compressed buffer is known
        tester.fill(restored, context.nextByte());
        // Truncate the compressed buffer to the exact size
        final T compressedExactSize = tester.copyOf(context, tester.copyOf(compressed, 0, compressedLen));
        assertEquals(data.length, tester.decompress(decompressor2, compressedExactSize, restored));
        if (original instanceof ByteBuffer) {
            assertEquals(compressedLen, ((ByteBuffer) compressedExactSize).position());
            assertEquals(data.length, ((ByteBuffer) restored).position());
        }
        Assertions.assertArrayEquals(data, tester.copyOf(restored, 0, data.length));
        if (original instanceof ByteBuffer) {
            ((ByteBuffer) compressedExactSize).rewind();
            ((ByteBuffer) restored).rewind();
        }
    }

    public void testRoundTrip(RandomContext context, byte[] data, int off, int len, LZ4Factory compressorFactory, LZ4Factory decompressorFactory) {
        for (LZ4Compressor compressor : Arrays.asList(
                compressorFactory.fastCompressor(), compressorFactory.highCompressor())) {
            testRoundTrip(context, data, off, len, compressor, decompressorFactory.fastDecompressor(), decompressorFactory.safeDecompressor());
        }
    }

    public void testRoundTrip(RandomContext context, byte[] data, int off, int len) {
        testRoundTrip(context, data, off, len, LZ4Factory.safeInstance(), LZ4Factory.safeInstance());
    }

    public void testRoundTrip(RandomContext context, byte[] data) {
        testRoundTrip(context, data, 0, data.length);
    }

    public void testRoundTrip(RandomContext context, String resource) throws IOException {
        final byte[] data = readResource(resource);
        testRoundTrip(context, data);
    }

    @Test
    public void testRoundtripGeo() throws IOException {
        testRoundTrip(new RandomContext(0L), "/calgary/geo");
    }

    @Test
    public void testRoundtripBook1() throws IOException {
        testRoundTrip(new RandomContext(0L), "/calgary/book1");
    }

    @Test
    public void testRoundtripPic() throws IOException {
        testRoundTrip(new RandomContext(0L), "/calgary/pic");
    }

    @Test
    public void testNullMatchDec() {
        // 1 literal, 4 matchs with matchDec=0, 8 literals
        final byte[] invalid = new byte[]{16, 42, 0, 0, (byte) 128, 42, 42, 42, 42, 42, 42, 42, 42};
        // decompression should neither throw an exception nor loop indefinitely
        for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
            decompressor.decompress(invalid, 0, new byte[13], 0, 13);
        }
        for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
            decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
        }
    }

    @Test
    public void testEndsWithMatch() {
        // 6 literals, 4 matchs
        final byte[] invalid = new byte[]{96, 42, 43, 44, 45, 46, 47, 5, 0};
        final int decompressedLength = 10;

        for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
            try {
                // it is invalid to end with a match, should be at least 5 literals
                decompressor.decompress(invalid, 0, new byte[decompressedLength], 0, decompressedLength);
                Assertions.assertTrue(false, decompressor.toString());
            } catch (LZ4Exception e) {
                // OK
            }
        }

        for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
            try {
                // it is invalid to end with a match, should be at least 5 literals
                decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
                Assertions.assertTrue(false);
            } catch (LZ4Exception e) {
                // OK
            }
        }
    }

    @Test
    public void testEndsWithLessThan5Literals() {
        // 6 literals, 4 matchs
        final byte[] invalidBase = new byte[]{96, 42, 43, 44, 45, 46, 47, 5, 0};

        for (int i = 1; i < 5; ++i) {
            final byte[] invalid = Arrays.copyOf(invalidBase, invalidBase.length + 1 + i);
            invalid[invalidBase.length] = (byte) (i << 4); // i literals at the end

            for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
                try {
                    // it is invalid to end with a match, should be at least 5 literals
                    decompressor.decompress(invalid, 0, new byte[20], 0, 20);
                    Assertions.assertTrue(false, decompressor.toString());
                } catch (LZ4Exception e) {
                    // OK
                }
            }

            for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
                try {
                    // it is invalid to end with a match, should be at least 5 literals
                    decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
                    Assertions.assertTrue(false);
                } catch (LZ4Exception e) {
                    // OK
                }
            }
        }
    }

    @Test
    public void testWriteToReadOnlyBuffer() {
        var context = new RandomContext(0L);
        for (LZ4Compressor compressor : COMPRESSORS) {
            ByteBuffer in = Tester.BYTE_BUFFER.copyOf(context, new byte[]{2, 3});
            ByteBuffer out = Tester.BYTE_BUFFER.allocate(context, 100).asReadOnlyBuffer();
            try {
                compressor.compress(in, out);
                Assertions.fail();
            } catch (ReadOnlyBufferException e) {
                // ok
            }
        }
        for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
            ByteBuffer in = Tester.BYTE_BUFFER.copyOf(context, COMPRESSORS[0].compress(new byte[]{2, 3}));
            ByteBuffer out = Tester.BYTE_BUFFER.allocate(context, 100).asReadOnlyBuffer();
            try {
                decompressor.decompress(in, out);
                Assertions.fail();
            } catch (ReadOnlyBufferException e) {
                // ok
            }
        }
        for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
            ByteBuffer in = Tester.BYTE_BUFFER.copyOf(context, COMPRESSORS[0].compress(new byte[]{2, 3}));
            ByteBuffer out = Tester.BYTE_BUFFER.allocate(context, 100).asReadOnlyBuffer();
            out.limit(2);
            try {
                decompressor.decompress(in, out);
                Assertions.fail();
            } catch (ReadOnlyBufferException e) {
                // ok
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L})
    public void testAllEqual(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int len = context.nextBoolean() ? context.nextInt(20) : context.nextInt(100000);
        final byte[] buf = new byte[len];
        Arrays.fill(buf, context.nextByte());
        testRoundTrip(context, buf);
    }

    @Test
    public void testMaxDistance() {
        var context = new RandomContext(0L);

        final int len = context.nextInt(1 << 17, 1 << 18);
        final int off = context.nextInt(len - (1 << 16) - (1 << 15));
        final byte[] buf = new byte[len];
        for (int i = 0; i < (1 << 15); ++i) {
            buf[off + i] = context.nextByte();
        }
        System.arraycopy(buf, off, buf, off + 65535, 1 << 15);
        testRoundTrip(context, buf);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L, 6L, 7L, 8L, 9L})
    public void testRandomData(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int n = context.nextInt(1, 16);
        final int off = context.nextInt(1000);
        final int len = context.nextBoolean() ? context.nextInt(1 << 16) : context.nextInt(1 << 20);
        final byte[] data = context.nextBytes(off + len + context.nextInt(100), n);
        testRoundTrip(context, data, off, len);
    }

    @Test
    // https://github.com/jpountz/lz4-java/issues/12
    public void testRoundtripIssue12() {
        var context = new RandomContext(0L);
        byte[] data = new byte[]{
                14, 72, 14, 85, 3, 72, 14, 85, 3, 72, 14, 72, 14, 72, 14, 85, 3, 72, 14, 72, 14, 72, 14, 72, 14, 72, 14, 72, 14, 85, 3, 72,
                14, 85, 3, 72, 14, 85, 3, 72, 14, 85, 3, 72, 14, 85, 3, 72, 14, 85, 3, 72, 14, 50, 64, 0, 46, -1, 0, 0, 0, 29, 3, 85,
                8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3,
                0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113,
                0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113,
                0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 50, 64, 0, 47, -105, 0, 0, 0, 30, 3, -97, 6, 0, 68, -113,
                0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, 85,
                8, -113, 0, 68, -97, 3, 0, 2, -97, 6, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97,
                6, 0, 68, -113, 0, 120, 64, 0, 48, 4, 0, 0, 0, 31, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72,
                33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72,
                43, 72, 19, 72, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72,
                28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72,
                35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72,
                41, 72, 32, 72, 18, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 39, 24, 32, 34, 124, 0, 120, 64, 0, 48, 80, 0, 0, 0, 31, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72,
                35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72,
                41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72,
                40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72,
                31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72,
                26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72,
                37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72,
                36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72,
                20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72,
                22, 72, 31, 72, 43, 72, 19, 72, 34, 72, 29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72,
                38, 72, 26, 72, 28, 72, 42, 72, 24, 72, 27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 72, 34, 72,
                29, 72, 37, 72, 35, 72, 45, 72, 23, 72, 46, 72, 20, 72, 40, 72, 33, 72, 25, 72, 39, 72, 38, 72, 26, 72, 28, 72, 42, 72, 24, 72,
                27, 72, 36, 72, 41, 72, 32, 72, 18, 72, 30, 72, 22, 72, 31, 72, 43, 72, 19, 50, 64, 0, 49, 20, 0, 0, 0, 32, 3, -97, 6, 0,
                68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97,
                6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2,
                3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2,
                3, -97, 6, 0, 50, 64, 0, 50, 53, 0, 0, 0, 34, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -113, 0, 2, 3, -97,
                6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3,
                -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97,
                3, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3,
                85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0,
                2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3,
                -97, 6, 0, 50, 64, 0, 51, 85, 0, 0, 0, 36, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97,
                6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, -97, 5, 0, 2, 3, 85, 8, -113, 0, 68,
                -97, 3, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0,
                68, -113, 0, 2, 3, -97, 6, 0, 50, -64, 0, 51, -45, 0, 0, 0, 37, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6,
                0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, -97, 6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -113, 0, 2, 3, -97,
                6, 0, 68, -113, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 2, 3, 85, 8, -113, 0, 68, -97, 3, 0, 120, 64, 0, 52, -88, 0, 0,
                0, 39, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72,
                13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 72, 13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85,
                5, 72, 13, 85, 5, 72, 13, 72, 13, 72, 13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85,
                5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85,
                5, 72, 13, 85, 5, 72, 13, 72, 13, 72, 13, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 85, 5, 72, 13, 72, 13, 85, 5, 72, 13, 72,
                13, 85, 5, 72, 13, 72, 13, 85, 5, 72, 13, -19, -24, -101, -35
        };
        testRoundTrip(context, data, 9, data.length - 9);
    }

}
