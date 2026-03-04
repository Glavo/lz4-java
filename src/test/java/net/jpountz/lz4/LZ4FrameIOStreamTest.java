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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static Path tempDir;

    @BeforeAll
    public static void setupTempDir() throws IOException {
        String property = System.getProperty("net.jpountz.lz4.test.tempDir");
        if (property == null) {
            fail("net.jpountz.lz4.test.tempDir property not set");
        }

        tempDir = Path.of(property);
        Files.createDirectories(tempDir);
    }

    private static Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(tempDir, prefix, suffix);
    }

    public static final class TestData {
        private final byte[] data;

        public TestData(int size) {
            this.data = new byte[size];

            final Random rnd = new Random(5378L);
            rnd.nextBytes(this.data);
        }

        public int size() {
            return data.length;
        }

        public InputStream newInputStream() {
            return new ByteArrayInputStream(this.data);
        }

        private void validateStreamEquals(InputStream is) throws IOException {
            int size = size();
            try (InputStream fis = newInputStream()) {
                while (size > 0) {
                    final byte[] buffer0 = new byte[Math.min(size, 1 << 10)];
                    final byte[] buffer1 = new byte[Math.min(size, 1 << 10)];
                    fillBuffer(buffer1, fis);
                    fillBuffer(buffer0, is);
                    for (int i = 0; i < buffer0.length; ++i) {
                        assertEquals(buffer0[i], buffer1[i]);
                    }
                    size -= buffer0.length;
                }
            }
        }

        private void validateStreamEqualsWithPerByteRead(InputStream is) throws IOException {
            try (InputStream fis = newInputStream()) {
                for (int size = size(); size > 0; size--) {
                    int byte0 = is.read();
                    int byte1 = fis.read();
                    assertEquals(byte0, byte1);
                    if (byte0 == -1) {
                        throw new EOFException("End of stream");
                    }
                    if (byte1 == -1) {
                        throw new EOFException("End of stream");
                    }
                }
            }
        }
    }

    private static final class TempOutputStream extends ByteArrayOutputStream {
        public TempOutputStream() {
            super(8192);
        }

        public byte[] getBytes() {
            return buf;
        }

        public ByteBuffer getByteBuffer() {
            return ByteBuffer.wrap(buf, 0, count);
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(buf, 0, count);
        }
    }

    @BeforeAll
    public static void checkLz4CLI() {
        // Check if this is running in CI (env CI=true), see https://docs.github.com/en/actions/reference/workflows-and-actions/variables#default-environment-variables
        if (!LZ4CLI.IS_AVAILABLE && "true".equals(System.getenv("CI"))) {
            fail("LZ4 CLI is not available, but should be for CI run");
        }
    }

    private static void fillBuffer(final byte[] buffer, final InputStream is) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            final int myLength = is.read(buffer, offset, buffer.length - offset);
            if (myLength < 0) {
                throw new EOFException("End of stream");
            }
            offset += myLength;
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testOutputSimple(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();

        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }
        final ByteBuffer buffer = temp.getByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(LZ4FrameOutputStream.MAGIC, buffer.getInt());
        final BitSet b = BitSet.valueOf(new byte[]{buffer.get()});
        assertFalse(b.get(0));
        assertFalse(b.get(1));
        assertFalse(b.get(2));
        assertFalse(b.get(3));
        assertFalse(b.get(4));
        assertTrue(b.get(5));
        LZ4FrameOutputStream.BD bd = LZ4FrameOutputStream.BD.fromByte(buffer.get());
        assertEquals(LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB.getIndicator() << 4, bd.toByte());
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputSimple(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();

        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }
        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            testData.validateStreamEquals(is);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputWithPerByteReadWrite(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copyWithPerByteReadWrite(testData.newInputStream(), os);
        }
        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            testData.validateStreamEqualsWithPerByteRead(is);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputSkipped(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();

        final int skipSize = 1 << 10;
        final ByteBuffer skipBuffer = ByteBuffer.allocate(skipSize + 8).order(ByteOrder.LITTLE_ENDIAN);
        skipBuffer.putInt(LZ4FrameInputStream.MAGIC_SKIPPABLE_BASE | 0x00000007); // anything 00 through FF should work
        skipBuffer.putInt(skipSize);
        final byte[] skipRandom = new byte[skipSize];
        new Random(478278L).nextBytes(skipRandom);
        skipBuffer.put(skipRandom);
        temp.write(skipBuffer.array());
        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }
        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            testData.validateStreamEquals(is);
        }
    }

    @Test
    public void testSkippableOnly() throws IOException {
        final var temp = new TempOutputStream();
        final int skipSize = 1 << 10;

        final ByteBuffer skipBuffer = ByteBuffer.allocate(skipSize + 8).order(ByteOrder.LITTLE_ENDIAN);
        skipBuffer.putInt(LZ4FrameInputStream.MAGIC_SKIPPABLE_BASE | 0x00000007); // anything 00 through FF should work
        skipBuffer.putInt(skipSize);
        final byte[] skipRandom = new byte[skipSize];
        new Random(478278L).nextBytes(skipRandom);
        skipBuffer.put(skipRandom);
        temp.write(skipBuffer.array());

        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            assertEquals(-1, is.read());
        }
        // Extra one byte at the tail
        try (InputStream is = new LZ4FrameInputStream(new SequenceInputStream(temp.getInputStream(), new ByteArrayInputStream(new byte[1])))) {
            assertThrows(IOException.class, is::read);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testStreamWithContentSize(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        final long knownSize = testData.size();
        try (OutputStream os = new LZ4FrameOutputStream(temp,
                LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                knownSize,
                LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM,
                LZ4FrameOutputStream.FLG.Bits.CONTENT_SIZE)) {
            copy(testData.newInputStream(), os);
        }
        try (LZ4FrameInputStream is = new LZ4FrameInputStream(temp.getInputStream(), true)) {
            assertEquals(knownSize, is.getExpectedContentSize());
            assertTrue(is.isExpectedContentSizeDefined());
            testData.validateStreamEquals(is);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testStreamWithoutContentSize(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp,
                LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM)) {
            copy(testData.newInputStream(), os);
        }
        try (LZ4FrameInputStream is = new LZ4FrameInputStream(temp.getInputStream(), true)) {
            assertEquals(-1L, is.getExpectedContentSize());
            assertFalse(is.isExpectedContentSizeDefined());
            testData.validateStreamEquals(is);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputWithBlockChecksum(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp,
                LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB,
                LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                LZ4FrameOutputStream.FLG.Bits.BLOCK_CHECKSUM)) {
            copy(testData.newInputStream(), os);
        }
        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            testData.validateStreamEquals(is);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testInputOutputMultipleFrames(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }
        final long oneLength = temp.size();
        for (int i = 0; i < 3; ++i) {
            try (InputStream is = temp.getInputStream()) {
                long size = oneLength;
                while (size > 0) {
                    final byte[] buff = new byte[Math.min((int) size, 1 << 10)];
                    fillBuffer(buff, is);
                    temp.write(buff);
                    size -= buff.length;
                }
            }
        }
        try (LZ4FrameInputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            assertThrows(UnsupportedOperationException.class, is::getExpectedContentSize);
            assertFalse(is.isExpectedContentSizeDefined());
            testData.validateStreamEquals(is);
            testData.validateStreamEquals(is);
            testData.validateStreamEquals(is);
            testData.validateStreamEquals(is);
        }
        try (LZ4FrameInputStream is = new LZ4FrameInputStream(temp.getInputStream(), true)) {
            assertEquals(-1L, is.getExpectedContentSize());
            assertFalse(is.isExpectedContentSizeDefined());
            testData.validateStreamEquals(is);
            assertEquals(-1, is.read());
            final byte[] tmpBuff = new byte[10];
            assertEquals(-1, is.read(tmpBuff, 0, 10));
            assertEquals(0, is.skip(1));
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testNativeCompressIfAvailable(int testSize) throws IOException, InterruptedException {
        Assumptions.assumeTrue(LZ4CLI.IS_AVAILABLE);
        final var testData = new TestData(testSize);

        Path tmpFile = createTempFile("lz4test", ".dat");
        try {
            try (OutputStream os = Files.newOutputStream(tmpFile)) {
                copy(testData.newInputStream(), os);
            }
            nativeCompress(testData, tmpFile);
            nativeCompress(testData, tmpFile, "--no-frame-crc");
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private void nativeCompress(TestData testData, Path tmpFile, String... args) throws IOException, InterruptedException {
        final Path lz4File = createTempFile("lz4test", ".lz4");
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
            assertEquals(0, retval);
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                testData.validateStreamEquals(is);
            }
        } finally {
            Files.deleteIfExists(lz4File);
        }
    }

    @Test
    public void testUncompressableEnd() throws IOException {
        final byte data = (byte) 0xEE;
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            try (final OutputStream os = new LZ4FrameOutputStream(baos, LZ4FrameOutputStream.BLOCKSIZE.SIZE_1MB)) {
                os.write(data);
            }
            final byte[] bytes = baos.toByteArray();
            try (final InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
                assertEquals(data, (byte) is.read());
            }

            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            // Make sure final "block" is a zero length block, then set it to an incompressible zero length block.
            assertEquals(0, buffer.getInt(bytes.length - (Integer.SIZE >> 3)));
            buffer.putInt(bytes.length - (Integer.SIZE >> 3), LZ4FrameOutputStream.LZ4_FRAME_INCOMPRESSIBLE_MASK);
            try (final InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
                assertEquals(data, (byte) is.read());
            }
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testNativeDecompressIfAvailable(int testSize) throws IOException, InterruptedException {
        Assumptions.assumeTrue(LZ4CLI.IS_AVAILABLE);
        final var testData = new TestData(testSize);
        final Path lz4File = createTempFile("lz4test", ".lz4");
        final Path unCompressedFile = createTempFile("lz4raw", ".dat");
        Files.deleteIfExists(unCompressedFile);
        Files.deleteIfExists(lz4File);
        try {
            try (OutputStream os = new LZ4FrameOutputStream(Files.newOutputStream(lz4File),
                    LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                    testData.size(),
                    LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_SIZE,
                    LZ4FrameOutputStream.FLG.Bits.CONTENT_CHECKSUM)) {
                copy(testData.newInputStream(), os);
            }
            try (InputStream is = new LZ4FrameInputStream(Files.newInputStream(lz4File))) {
                testData.validateStreamEquals(is);
            }

            final ProcessBuilder builder = new ProcessBuilder();
            builder.command("lz4", "-d", "-vvvvvvv", lz4File.toAbsolutePath().toString(), unCompressedFile.toAbsolutePath().toString()).inheritIO();
            Process process = builder.start();
            int retval = process.waitFor();
            assertEquals(0, retval);
            try (InputStream is = Files.newInputStream(unCompressedFile)) {
                testData.validateStreamEquals(is);
            }
        } finally {
            Files.deleteIfExists(lz4File);
            Files.deleteIfExists(unCompressedFile);
        }
    }

    @Test
    public void testEmptyLZ4Input() throws IOException {
        try (InputStream is = new LZ4FrameInputStream(InputStream.nullInputStream())) {
            assertThrows(IOException.class, is::read);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testPrematureMagicNb(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        try (InputStream is = new LZ4FrameInputStream(new ByteArrayInputStream(new byte[1]))) {
            assertThrows(IOException.class, is::read);
        }

        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }
        // Extra one byte at the tail
        try (InputStream is = new LZ4FrameInputStream(new SequenceInputStream(temp.getInputStream(), new ByteArrayInputStream(new byte[1])))) {
            testData.validateStreamEquals(is);
            assertThrows(IOException.class, is::read);
        }
    }

    @ParameterizedTest(name = "size={0}")
    @MethodSource("testSizes")
    public void testAvailable(int testSize) throws IOException {
        final var testData = new TestData(testSize);
        final var temp = new TempOutputStream();
        try (OutputStream os = new LZ4FrameOutputStream(temp)) {
            copy(testData.newInputStream(), os);
        }

        try (InputStream is = new LZ4FrameInputStream(temp.getInputStream())) {
            assertEquals(0, is.available(), "available() should be 0 before first read");

            if (is.read() != -1 && testSize > 1) {
                assertTrue(
                        is.available() > 0,
                        "After reading 1 byte, available() should report > 0 bytes ready in the buffer"
                );
            }
        }
    }
}
