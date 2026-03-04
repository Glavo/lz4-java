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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import net.jpountz.RandomContext;
import net.jpountz.xxhash.XXHashFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

public abstract class LZ4BlockStreamingTest extends AbstractLZ4Test {

    public static final class FastTest extends LZ4BlockStreamingTest {
        @Override
        protected void setDecompressor(LZ4BlockInputStream.Builder builder) {
            builder.withDecompressor(LZ4Factory.fastestInstance().fastDecompressor());
        }
    }

    public static final class SafeTest extends LZ4BlockStreamingTest {
        @Override
        protected void setDecompressor(LZ4BlockInputStream.Builder builder) {
            builder.withDecompressor(LZ4Factory.fastestInstance().safeDecompressor());
        }
    }

    protected abstract void setDecompressor(LZ4BlockInputStream.Builder builder);

    private LZ4BlockInputStream.Builder lz4BlockInputStreamBuilder() {
        var builder = LZ4BlockInputStream.newBuilder();
        setDecompressor(builder);
        return builder;
    }

    // An input stream that might read less data than it is able to
    static final class MockInputStream extends FilterInputStream {

        private final RandomContext context;

        MockInputStream(InputStream in, RandomContext context) {
            super(in);
            this.context = context;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, context.nextInt(len + 1));
        }

        @Override
        public long skip(long n) throws IOException {
            return super.skip(context.nextInt((int) Math.min(n + 1, Integer.MAX_VALUE)));
        }

    }

    // an output stream that delays the actual writes
    static final class MockOutputStream extends FilterOutputStream {

        private final RandomContext context;
        private final byte[] buffer;
        private int delayedBytes;

        MockOutputStream(OutputStream out, RandomContext context) {
            super(out);
            this.context = context;
            buffer = new byte[context.nextInt(10, 1000)];
            delayedBytes = 0;
        }

        private void flushIfNecessary() throws IOException {
            if (delayedBytes == buffer.length) {
                flushPendingData();
            }
        }

        private void flushPendingData() throws IOException {
            out.write(buffer, 0, delayedBytes);
            delayedBytes = 0;
        }

        @Override
        public void write(int b) throws IOException {
            if (context.rarely()) {
                flushPendingData();
            } else {
                flushIfNecessary();
            }
            buffer[delayedBytes++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (context.rarely()) {
                flushPendingData();
            }
            if (len + delayedBytes > buffer.length) {
                flushPendingData();
                delayedBytes = context.nextInt(Math.min(len, buffer.length) + 1);
                out.write(b, off, len - delayedBytes);
                System.arraycopy(b, off + len - delayedBytes, buffer, 0, delayedBytes);
            } else {
                System.arraycopy(b, off, buffer, delayedBytes, len);
                delayedBytes += len;
            }
        }

        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void flush() throws IOException {
            // no-op
        }

        @Override
        public void close() throws IOException {
            flushPendingData();
            out.close();
        }

    }

    private InputStream open(RandomContext context, byte[] data) {
        return new MockInputStream(new ByteArrayInputStream(data), context);
    }

    private OutputStream wrap(RandomContext context, OutputStream other) {
        return new MockOutputStream(other, context);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L})
    public void testRoundtripGeo(long randomSeed) throws IOException {
        var context = new RandomContext(randomSeed);
        testRoundTrip(context, "/calgary/geo");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L})
    public void testRoundtripBook1(long randomSeed) throws IOException {
        var context = new RandomContext(randomSeed);
        testRoundTrip(context, "/calgary/book1");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L})
    public void testRoundtripPic(long randomSeed) throws IOException {
        var context = new RandomContext(randomSeed);
        testRoundTrip(context, "/calgary/pic");
    }

    public void testRoundTrip(RandomContext context, String resource) throws IOException {
        testRoundTrip(context, readResource(resource));
    }

    public void testRoundTrip(RandomContext context, byte[] data) throws IOException {
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        final int blockSize = switch (context.nextInt(3)) {
            case 0 -> LZ4BlockOutputStream.MIN_BLOCK_SIZE;
            case 1 -> LZ4BlockOutputStream.MAX_BLOCK_SIZE;
            default -> context.nextInt(LZ4BlockOutputStream.MIN_BLOCK_SIZE, LZ4BlockOutputStream.MAX_BLOCK_SIZE + 1);
        };
        final LZ4Compressor compressor = context.nextBoolean()
                ? LZ4Factory.fastestInstance().fastCompressor()
                : LZ4Factory.fastestInstance().highCompressor();
        final Checksum checksum = switch (context.nextInt(3)) {
            case 0 -> new Adler32();
            case 1 -> new CRC32();
            default -> XXHashFactory.fastestInstance().newStreamingHash32(context.nextInt()).asChecksum();
        };
        final boolean syncFlush = context.nextBoolean();
        final LZ4BlockOutputStream os = new LZ4BlockOutputStream(wrap(context, compressed), blockSize, compressor, checksum, syncFlush);
        final int half = data.length / 2;
        switch (context.nextInt(3)) {
            case 0:
                os.write(data, 0, half);
                for (int i = half; i < data.length; ++i) {
                    os.write(data[i]);
                }
                break;
            case 1:
                for (int i = 0; i < half; ++i) {
                    os.write(data[i]);
                }
                os.write(data, half, data.length - half);
                break;
            case 2:
                os.write(data, 0, data.length);
                break;
        }
        os.close();

        final LZ4BlockInputStream.Builder builder = lz4BlockInputStreamBuilder()
                .withStopOnEmptyBlock(true)
                .withChecksum(checksum);
        InputStream is = builder.build(open(context, compressed.toByteArray()));
        Assertions.assertFalse(is.markSupported());
        try {
            is.mark(1);
            is.reset();
            Assertions.fail();
        } catch (IOException e) {
            // OK
        }
        byte[] restored = new byte[data.length + 1000];
        int read = 0;
        while (true) {
            if (context.percentage(1)) {
                final int r = is.read(restored, read, restored.length - read);
                if (r == -1) {
                    break;
                } else {
                    read += r;
                }
            } else {
                final int b = is.read();
                if (b == -1) {
                    break;
                } else {
                    restored[read++] = (byte) b;
                }
            }
        }
        is.close();
        Assertions.assertEquals(data.length, read);
        Assertions.assertArrayEquals(data, Arrays.copyOf(restored, read));

        // test skip
        final int offset = data.length <= 1 ? 0 : context.nextInt(data.length);
        final int length = context.nextInt(data.length - offset + 1);
        is = builder.build(open(context, compressed.toByteArray()));
        restored = new byte[length + 1000];
        read = 0;
        while (read < offset) {
            final long skipped = is.skip(offset - read);
            Assertions.assertTrue(skipped >= 0);
            read += skipped;
        }
        read = 0;
        while (read < length) {
            final int r = is.read(restored, read, length - read);
            Assertions.assertTrue(r >= 0);
            read += r;
        }
        is.close();
        Assertions.assertArrayEquals(Arrays.copyOfRange(data, offset, offset + length), Arrays.copyOfRange(restored, 0, length));
    }


    private static final long[] RANDOM_SEEDS_20 = LongStream.range(0, 20).toArray();

    @ParameterizedTest
    @FieldSource("RANDOM_SEEDS_20")
    public void testRoundtripRandom(long randomSeed) throws IOException {
        var context = new RandomContext(randomSeed);

        final int size = context.percentage(1) ? context.nextInt(6) : context.nextInt(1 << 20);
        final byte[] data = context.nextBytes(size, context.nextBoolean() ? context.nextInt(1, 6) : context.nextInt(6, 100));
        testRoundTrip(context, data);
    }

    @Test
    public void testRoundtripEmpty() throws IOException {
        testRoundTrip(new RandomContext(0L), new byte[0]);
    }

    @Test
    public void testDoubleClose() throws IOException {
        final byte[] testBytes = "Testing!".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LZ4BlockOutputStream out = new LZ4BlockOutputStream(bytes);

        out.write(testBytes);

        out.close();
        out.close();

        LZ4BlockInputStream in = lz4BlockInputStreamBuilder()
                .withStopOnEmptyBlock(true)
                .build(new ByteArrayInputStream(bytes.toByteArray()));
        byte[] actual = new byte[testBytes.length];
        in.read(actual);

        Assertions.assertArrayEquals(testBytes, actual);

        in.close();
        in.close();
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int total;
        int result;
        for (total = 0; total < b.length; total += result) {
            result = in.read(b, total, b.length - total);
            if (result == -1) {
                break;
            }
        }
        return total;
    }

    @Test
    public void testConcatenationOfSerializedStreams() throws IOException {
        var context = new RandomContext(0L);
        final byte[] testBytes1 = context.nextBytes(64, 256);
        final byte[] testBytes2 = context.nextBytes(64, 256);
        byte[] expected = new byte[128];
        System.arraycopy(testBytes1, 0, expected, 0, 64);
        System.arraycopy(testBytes2, 0, expected, 64, 64);

        ByteArrayOutputStream bytes1os = new ByteArrayOutputStream();
        LZ4BlockOutputStream out1 = new LZ4BlockOutputStream(bytes1os);
        out1.write(testBytes1);
        out1.close();

        ByteArrayOutputStream bytes2os = new ByteArrayOutputStream();
        LZ4BlockOutputStream out2 = new LZ4BlockOutputStream(bytes2os);
        out2.write(testBytes2);
        out2.close();

        byte[] bytes1 = bytes1os.toByteArray();
        byte[] bytes2 = bytes2os.toByteArray();
        byte[] concatenatedBytes = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, concatenatedBytes, 0, bytes1.length);
        System.arraycopy(bytes2, 0, concatenatedBytes, bytes1.length, bytes2.length);

        // In a default behaviour, we can read the first block of the concatenated bytes only
        LZ4BlockInputStream in1 = lz4BlockInputStreamBuilder()
                .withStopOnEmptyBlock(true)
                .build(new ByteArrayInputStream(concatenatedBytes));
        byte[] actual1 = new byte[128];
        Assertions.assertEquals(64, readFully(in1, actual1));
        Assertions.assertEquals(-1, in1.read());
        in1.close();

        // Check if we can read concatenated byte stream
        LZ4BlockInputStream in2 = lz4BlockInputStreamBuilder()
                .withStopOnEmptyBlock(false)
                .build(new ByteArrayInputStream(concatenatedBytes));
        byte[] actual2 = new byte[128];
        Assertions.assertEquals(128, readFully(in2, actual2));
        Assertions.assertEquals(-1, in2.read());
        in2.close();

        Assertions.assertArrayEquals(expected, actual2);
    }

    @Test
    public void testCorruptedStream() {
        byte[] bytesWrongCompressed = {
                76, 90, 52, 66, 108, 111, 99, 107, 32,
                // Compressed length
                6, 0, 0, 0, // malformed, should be 5
                // Decompressed length
                4, 0, 0, 0,
                -125, -47, -30, 10, 64, 0, 1, 2, 3, 76, 90, 52, 66, 108, 111, 99, 107, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        var e = assertThrows(IOException.class, () -> lz4BlockInputStreamBuilder().build(new ByteArrayInputStream(bytesWrongCompressed)).readAllBytes());
        Assertions.assertEquals("Stream is corrupted", e.getMessage());

        byte[] bytesWrongDecompressed = {
                76, 90, 52, 66, 108, 111, 99, 107, 32,
                // Compressed length
                5, 0, 0, 0,
                // Decompressed length
                5, 0, 0, 0, // malformed, should be 4
                -125, -47, -30, 10, 64, 0, 1, 2, 3, 76, 90, 52, 66, 108, 111, 99, 107, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        e = assertThrows(IOException.class, () -> lz4BlockInputStreamBuilder().build(new ByteArrayInputStream(bytesWrongDecompressed)).readAllBytes());
        Assertions.assertEquals("Stream is corrupted", e.getMessage());
    }
}
