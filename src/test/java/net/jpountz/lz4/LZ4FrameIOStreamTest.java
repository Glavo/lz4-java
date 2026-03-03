package net.jpountz.lz4;

/*
 * Copyright 2020 Charles Allen and the lz4-java contributors.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.SequenceInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

public class LZ4FrameIOStreamTest {
    private static void copy(InputStream in, OutputStream out) throws IOException {
        final byte[] buffer = new byte[1 << 10];
        int inSize = in.read(buffer);
        while (inSize >= 0) {
            out.write(buffer, 0, inSize);
            inSize = in.read(buffer);
        }
        out.flush();
    }

    private static void copyWithPerByteReadWrite(InputStream in, OutputStream out) throws IOException {
        int readByte;
        while ((readByte = in.read()) != -1) {
            out.write(readByte);
        }
        out.flush();
    }

    public static Stream<Integer> testSizes() {
        final List<Integer> retval = new ArrayList<>(
                Arrays.asList(
                        0,
                        1,
                        1 << 10,
                        (1 << 10) + 1,
                        1 << 16,
                        1 << 17,
                        1 << 20
                ));
        final Random rnd = new Random(78370789134L); // Chosen by lightly  smashing my keyboard a few times.
        for (int i = 0; i < 10; ++i) {
            retval.add(Math.abs(rnd.nextInt()) % (1 << 22));
        }
        return retval.stream();
    }

    Path tmpFile = null;

    private void setUp(int testSize) throws IOException {
        tmpFile = Files.createTempFile("lz4ioTest", ".dat");
        final Random rnd = new Random(5378L);
        int sizeRemaining = testSize;
        try (OutputStream os = Files.newOutputStream(tmpFile)) {
            while (sizeRemaining > 0) {
                final byte[] buff = new byte[Math.min(sizeRemaining, 1 << 10)];
                rnd.nextBytes(buff);
                os.write(buff);
                sizeRemaining -= buff.length;
            }
        }
        Assertions.assertEquals(testSize, Files.size(tmpFile));
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (tmpFile != null && Files.exists(tmpFile)) {
            Files.deleteIfExists(tmpFile);
        }
    }

    /**
     * Whether the native LZ4 CLI is available; can be used for comparing this library with the expected native LZ4 behavior
     */
    private static boolean hasLz4CLI = false;

    @BeforeAll
    public static void checkLz4CLI() {
        try {
            ProcessBuilder checkBuilder = new ProcessBuilder().command("lz4", "-V").redirectErrorStream(true);
            Process checkProcess = checkBuilder.start();
            hasLz4CLI = checkProcess.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            // lz4 CLI not available or failed to execute; treat as unavailable to allow test skip
            hasLz4CLI = false;
        }

        // Check if this is running in CI (env CI=true), see https://docs.github.com/en/actions/reference/workflows-and-actions/variables#default-environment-variables
        if (!hasLz4CLI && "true".equals(System.getenv("CI"))) {
            Assertions.fail("LZ4 CLI is not available, but should be for CI run");
        }
    }

    private void fillBuffer(final byte[] buffer, final InputStream is) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            final int myLength = is.read(buffer, offset, buffer.length - offset);
            if (myLength < 0) {
                throw new EOFException("End of stream");
            }
            offset += myLength;
        }
    }

    private void validateStreamEquals(InputStream is, Path file) throws IOException {
        int size = (int) Files.size(file);
        try (InputStream fis = Files.newInputStream(file)) {
            while (size > 0) {
                final byte[] buffer0 = new byte[Math.min(size, 1 << 10)];
                final byte[] buffer1 = new byte[Math.min(size, 1 << 10)];
                fillBuffer(buffer1, fis);
                fillBuffer(buffer0, is);
                for (int i = 0; i < buffer0.length; ++i) {
                    Assertions.assertEquals(buffer0[i], buffer1[i]);
                }
                size -= buffer0.length;
            }
        }
    }

    private void validateStreamEqualsWithPerByteRead(InputStream is, Path file) throws IOException {
        try (InputStream fis = Files.newInputStream(file)) {
            for (int size = (int) Files.size(file); size > 0; size--) {
                int byte0 = is.read();
                int byte1 = fis.read();
                Assertions.assertEquals(byte0, byte1);
                if (byte0 == -1) {
                    throw new EOFException("End of stream");
                }
                if (byte1 == -1) {
                    throw new EOFException("End of stream");
                }
            }
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testValidator(int testSize) throws IOException {
        setUp(testSize);
        try (InputStream is = Files.newInputStream(tmpFile)) {
            validateStreamEquals(is, tmpFile);
        }
        final Path file = Files.createTempFile("copyTmp", ".dat");
        try {
            try (InputStream is = Files.newInputStream(tmpFile)) {
                try (OutputStream os = Files.newOutputStream(file)) {
                    copy(is, os);
                }
            }
            try (InputStream is = Files.newInputStream(file)) {
                validateStreamEquals(is, file);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testOutputSimple(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            final ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
            try (FileChannel channel = FileChannel.open(lz4File)) {
                channel.read(buffer);
            }
            buffer.rewind();
            Assertions.assertEquals(LZ4FrameOutputStream.MAGIC, buffer.getInt());
            final BitSet b = BitSet.valueOf(new byte[]{buffer.get()});
            Assertions.assertFalse(b.get(0));
            Assertions.assertFalse(b.get(1));
            Assertions.assertFalse(b.get(2));
            Assertions.assertFalse(b.get(3));
            Assertions.assertFalse(b.get(4));
            Assertions.assertTrue(b.get(5));
            LZ4FrameOutputStream.BD bd = LZ4FrameOutputStream.BD.fromByte(buffer.get());
            Assertions.assertEquals(LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB.getIndicator() << 4, bd.toByte());
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputSimple(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputWithPerByteReadWrite(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copyWithPerByteReadWrite(is, os);
                }
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEqualsWithPerByteRead(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputSkipped(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream fos = Files.newOutputStream(lz4File)) {
                final int skipSize = 1 << 10;
                final ByteBuffer skipBuffer = ByteBuffer.allocate(skipSize + 8).order(ByteOrder.LITTLE_ENDIAN);
                skipBuffer.putInt(LZ4FrameInputStream.MAGIC_SKIPPABLE_BASE | 0x00000007); // anything 00 through FF should work
                skipBuffer.putInt(skipSize);
                final byte[] skipRandom = new byte[skipSize];
                new Random(478278L).nextBytes(skipRandom);
                skipBuffer.put(skipRandom);
                fos.write(skipBuffer.array());
                try (OutputStream os = new LZ4FrameOutputStream(fos)) {
                    try (InputStream is = Files.newInputStream(tmpFile)) {
                        copy(is, os);
                    }
                }
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testSkippableOnly(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream fos = Files.newOutputStream(lz4File)) {
                final int skipSize = 1 << 10;
                final ByteBuffer skipBuffer = ByteBuffer.allocate(skipSize + 8).order(ByteOrder.LITTLE_ENDIAN);
                skipBuffer.putInt(LZ4FrameInputStream.MAGIC_SKIPPABLE_BASE | 0x00000007); // anything 00 through FF should work
                skipBuffer.putInt(skipSize);
                final byte[] skipRandom = new byte[skipSize];
                new Random(478278L).nextBytes(skipRandom);
                skipBuffer.put(skipRandom);
                fos.write(skipBuffer.array());
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                Assertions.assertEquals(-1, is.read());
            }
            // Extra one byte at the tail
            try (InputStream is = new LZ4FrameInputStream(new SequenceInputStream(Files.newInputStream(lz4File), new ByteArrayInputStream(new byte[1])))) {
                Assertions.assertThrows(IOException.class, is::read);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testStreamWithContentSize(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            final long knownSize = Files.size(tmpFile);
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File),
                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                    knownSize,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_SIZE)) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            try (LZ4FrameInputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File), true)) {
                Assertions.assertEquals(knownSize, is.getExpectedContentSize());
                Assertions.assertTrue(is.isExpectedContentSizeDefined());
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testStreamWithoutContentSize(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File),
                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM)) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            try (LZ4FrameInputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File), true)) {
                Assertions.assertEquals(-1L, is.getExpectedContentSize());
                Assertions.assertFalse(is.isExpectedContentSizeDefined());
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputWithBlockChecksum(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File),
                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_CHECKSUM)) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputMultipleFrames(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            final long oneLength = Files.size(lz4File);
            try (OutputStream os = Files.newOutputStream(lz4File, StandardOpenOption.APPEND)) {
                for (int i = 0; i < 3; ++i) {
                    try (InputStream is = Files.newInputStream(lz4File)) {
                        long size = oneLength;
                        while (size > 0) {
                            final byte[] buff = new byte[Math.min((int) size, 1 << 10)];
                            fillBuffer(buff, is);
                            os.write(buff);
                            size -= buff.length;
                        }
                    }
                }
            }
            try (LZ4FrameInputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                Assertions.assertThrows(UnsupportedOperationException.class, is::getExpectedContentSize);
                Assertions.assertFalse(is.isExpectedContentSizeDefined());
                validateStreamEquals(is, tmpFile);
                validateStreamEquals(is, tmpFile);
                validateStreamEquals(is, tmpFile);
                validateStreamEquals(is, tmpFile);
            }
            try (LZ4FrameInputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File), true)) {
                Assertions.assertEquals(-1L, is.getExpectedContentSize());
                Assertions.assertFalse(is.isExpectedContentSizeDefined());
                validateStreamEquals(is, tmpFile);
                Assertions.assertEquals(-1, is.read());
                final byte[] tmpBuff = new byte[10];
                Assertions.assertEquals(-1, is.read(tmpBuff, 0, 10));
                Assertions.assertEquals(0, is.skip(1));
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testNativeCompressIfAvailable(int testSize) throws IOException, InterruptedException {
        setUp(testSize);
        Assumptions.assumeTrue(hasLz4CLI);
        nativeCompress();
        nativeCompress("--no-frame-crc");
    }

    private void nativeCompress(String... args) throws IOException, InterruptedException {
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        Files.deleteIfExists(lz4File);
        try {
            final ProcessBuilder builder = new ProcessBuilder();
            final ArrayList<String> cmd = new ArrayList<>();
            cmd.add("lz4");
            if (args != null) {
                cmd.addAll(Arrays.asList(args));
            }
            cmd.add(tmpFile.toAbsolutePath().toString());
            cmd.add(lz4File.toAbsolutePath().toString());
            builder.command(cmd.toArray(new String[0]));
            builder.inheritIO();
            Process process = builder.start();
            int retval = process.waitFor();
            Assertions.assertEquals(0, retval);
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testUncompressableEnd(int testSize) throws IOException {
        setUp(testSize);
        final byte data = (byte) 0xEE;
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            try (final OutputStream os = new LZ4FrameOutputStream(baos, LZ4FrameOutputStream.BLOCKSIZE.SIZE_1MB)) {
                os.write(data);
            }
            final byte[] bytes = baos.toByteArray();
            try (final InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
                Assertions.assertEquals(data, (byte) is.read());
            }

            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            // Make sure final "block" is a zero length block, then set it to an incompressible zero length block.
            Assertions.assertEquals(0, buffer.getInt(bytes.length - (Integer.SIZE >> 3)));
            buffer.putInt(bytes.length - (Integer.SIZE >> 3), LZ4FrameOutputStream.LZ4_FRAME_INCOMPRESSIBLE_MASK);
            try (final InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
                Assertions.assertEquals(data, (byte) is.read());
            }
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testNativeDecompressIfAvailable(int testSize) throws IOException, InterruptedException {
        setUp(testSize);
        Assumptions.assumeTrue(hasLz4CLI);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        final Path unCompressedFile = Files.createTempFile("lz4raw", ".dat");
        Files.deleteIfExists(unCompressedFile);
        Files.deleteIfExists(lz4File);
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File),
                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                    Files.size(tmpFile),
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_SIZE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM)) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                validateStreamEquals(is, tmpFile);
            }

            final ProcessBuilder builder = new ProcessBuilder();
            builder.command("lz4", "-d", "-vvvvvvv", lz4File.toAbsolutePath().toString(), unCompressedFile.toAbsolutePath().toString()).inheritIO();
            Process process = builder.start();
            int retval = process.waitFor();
            Assertions.assertEquals(0, retval);
            try (InputStream is = Files.newInputStream(unCompressedFile)) {
                validateStreamEquals(is, tmpFile);
            }
        } finally {
            Files.deleteIfExists(lz4File);
            Files.deleteIfExists(unCompressedFile);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testEmptyLZ4Input(int testSize) throws IOException {
        setUp(testSize);
        try (InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(new byte[0]))) {
            Assertions.assertThrows(IOException.class, is::read);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testPrematureMagicNb(int testSize) throws IOException {
        setUp(testSize);
        try (InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(new byte[1]))) {
            Assertions.assertThrows(IOException.class, is::read);
        }

        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }
            // Extra one byte at the tail
            try (InputStream is = new LZ4FrameInputStream(new SequenceInputStream(Files.newInputStream(lz4File), new ByteArrayInputStream(new byte[1])))) {
                validateStreamEquals(is, tmpFile);
                Assertions.assertThrows(IOException.class, is::read);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testAvailable(int testSize) throws IOException {
        setUp(testSize);
        final Path lz4File = Files.createTempFile("lz4test", ".lz4");
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File))) {
                try (InputStream is = Files.newInputStream(tmpFile)) {
                    copy(is, os);
                }
            }

            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                Assertions.assertEquals(0, is.available(), "available() should be 0 before first read");

                if (is.read() != -1 && testSize > 1) {
                    Assertions.assertTrue(
                            is.available() > 0,
                            "After reading 1 byte, available() should report > 0 bytes ready in the buffer"
                    );
                }
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }
}
