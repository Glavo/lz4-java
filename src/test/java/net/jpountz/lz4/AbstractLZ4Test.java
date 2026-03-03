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

import net.jpountz.RandomContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public abstract class AbstractLZ4Test {

    public interface TesterBase<T> {

        T allocate(RandomContext context, int length);

        T copyOf(RandomContext context, byte[] array);

        byte[] copyOf(T data, int off, int len);

        int maxCompressedLength(int len);

        void fill(T instance, byte b);

        class ByteArrayTesterBase implements TesterBase<byte[]> {

            @Override
            public byte[] allocate(RandomContext context, int length) {
                return new byte[length];
            }

            @Override
            public byte[] copyOf(RandomContext context, byte[] array) {
                return Arrays.copyOf(array, array.length);
            }

            @Override
            public byte[] copyOf(byte[] data, int off, int len) {
                return Arrays.copyOfRange(data, off, off + len);
            }

            @Override
            public int maxCompressedLength(int len) {
                return LZ4Utils.maxCompressedLength(len);
            }

            @Override
            public void fill(byte[] instance, byte b) {
                Arrays.fill(instance, b);
            }
        }

        class ByteBufferTesterBase implements TesterBase<ByteBuffer> {

            @Override
            public ByteBuffer allocate(RandomContext context, int length) {
                ByteBuffer bb;
                int slice = context.nextInt(6);
                if (context.nextBoolean()) {
                    bb = ByteBuffer.allocate(length + slice);
                } else {
                    bb = ByteBuffer.allocateDirect(length + slice);
                }
                bb.position(slice);
                bb = bb.slice();
                bb.order(context.nextBoolean() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                return bb;
            }

            @Override
            public ByteBuffer copyOf(RandomContext context, byte[] array) {
                ByteBuffer bb = allocate(context, array.length).put(array);
                if (context.nextBoolean()) {
                    bb = bb.asReadOnlyBuffer();
                }
                bb.position(0);
                return bb;
            }

            @Override
            public byte[] copyOf(ByteBuffer data, int off, int len) {
                byte[] copy = new byte[len];
                data.position(off);
                data.get(copy);
                return copy;
            }

            @Override
            public int maxCompressedLength(int len) {
                return LZ4Utils.maxCompressedLength(len);
            }

            @Override
            public void fill(ByteBuffer instance, byte b) {
                for (int i = 0; i < instance.capacity(); ++i) {
                    instance.put(i, b);
                }
            }
        }
    }

    public interface Tester<T> extends TesterBase<T> {

        int compress(LZ4Compressor compressor, T src, int srcOff, int srcLen, T dest, int destOff, int maxDestLen);

        int decompress(LZ4FastDecompressor decompressor, T src, int srcOff, T dest, int destOff, int destLen);

        int decompress(LZ4SafeDecompressor decompressor, T src, int srcOff, int srcLen, T dest, int destOff, int maxDestLen);

        class ByteArrayTester extends ByteArrayTesterBase implements Tester<byte[]> {

            @Override
            public int compress(LZ4Compressor compressor, byte[] src, int srcOff,
                                int srcLen, byte[] dest, int destOff, int maxDestLen) {
                return compressor.compress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor,
                                  byte[] src, int srcOff, byte[] dest, int destOff, int destLen) {
                return decompressor.decompress(src, srcOff, dest, destOff, destLen);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor,
                                  byte[] src, int srcOff, int srcLen, byte[] dest, int destOff, int maxDestLen) {
                return decompressor.decompress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }
        }

        Tester<byte[]> BYTE_ARRAY = new ByteArrayTester();
        Tester<byte[]> BYTE_ARRAY_WITH_LENGTH = new ByteArrayTester() {
            @Override
            public int compress(LZ4Compressor compressor, byte[] src, int srcOff,
                                int srcLen, byte[] dest, int destOff, int maxDestLen) {
                return new LZ4CompressorWithLength(compressor).compress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor,
                                  byte[] src, int srcOff, byte[] dest, int destOff, int destLen) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, srcOff, dest, destOff);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor,
                                  byte[] src, int srcOff, int srcLen, byte[] dest, int destOff, int maxDestLen) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, srcOff, srcLen, dest, destOff);
            }
        };

        class ByteBufferTester extends ByteBufferTesterBase implements Tester<ByteBuffer> {

            @Override
            public int compress(LZ4Compressor compressor, ByteBuffer src, int srcOff,
                                int srcLen, ByteBuffer dest, int destOff, int maxDestLen) {
                return compressor.compress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, ByteBuffer src,
                                  int srcOff, ByteBuffer dest, int destOff, int destLen) {
                return decompressor.decompress(src, srcOff, dest, destOff, destLen);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, ByteBuffer src,
                                  int srcOff, int srcLen, ByteBuffer dest, int destOff, int maxDestLen) {
                return decompressor.decompress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }
        }

        Tester<ByteBuffer> BYTE_BUFFER = new ByteBufferTester();
        Tester<ByteBuffer> BYTE_BUFFER_WITH_LENGTH = new ByteBufferTester() {
            @Override
            public int compress(LZ4Compressor compressor, ByteBuffer src, int srcOff,
                                int srcLen, ByteBuffer dest, int destOff, int maxDestLen) {
                return new LZ4CompressorWithLength(compressor).compress(src, srcOff, srcLen, dest, destOff, maxDestLen);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, ByteBuffer src,
                                  int srcOff, ByteBuffer dest, int destOff, int destLen) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, srcOff, dest, destOff);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, ByteBuffer src,
                                  int srcOff, int srcLen, ByteBuffer dest, int destOff, int maxDestLen) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, srcOff, srcLen, dest, destOff);
            }
        };
    }

    // Tester to test a simple compress/decompress(src, dest) type of APIs
    public interface SrcDestTester<T> extends TesterBase<T> {

        int compress(LZ4Compressor compressor, T src, T dest);

        int decompress(LZ4FastDecompressor decompressor, T src, T dest);

        int decompress(LZ4SafeDecompressor decompressor, T src, T dest);

        class ByteArrayTester extends ByteArrayTesterBase implements SrcDestTester<byte[]> {

            @Override
            public int compress(LZ4Compressor compressor, byte[] src, byte[] dest) {
                return compressor.compress(src, dest);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, byte[] src, byte[] dest) {
                return decompressor.decompress(src, dest);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, byte[] src, byte[] dest) {
                return decompressor.decompress(src, dest);
            }
        }

        SrcDestTester<byte[]> BYTE_ARRAY = new ByteArrayTester();
        SrcDestTester<byte[]> BYTE_ARRAY_WITH_LENGTH = new ByteArrayTester() {
            @Override
            public int compress(LZ4Compressor compressor, byte[] src, byte[] dest) {
                return new LZ4CompressorWithLength(compressor).compress(src, dest);
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, byte[] src, byte[] dest) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, dest);
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, byte[] src, byte[] dest) {
                return new LZ4DecompressorWithLength(decompressor).decompress(src, dest);
            }
        };

        class ByteBufferTester extends ByteBufferTesterBase implements SrcDestTester<ByteBuffer> {

            @Override
            public int compress(LZ4Compressor compressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = dest.position();
                compressor.compress(src, dest);
                return dest.position() - pos;
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = src.position();
                decompressor.decompress(src, dest);
                return src.position() - pos;
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = dest.position();
                decompressor.decompress(src, dest);
                return dest.position() - pos;
            }
        }

        SrcDestTester<ByteBuffer> BYTE_BUFFER = new ByteBufferTester();
        SrcDestTester<ByteBuffer> BYTE_BUFFER_WITH_LENGTH = new ByteBufferTester() {
            @Override
            public int compress(LZ4Compressor compressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = dest.position();
                new LZ4CompressorWithLength(compressor).compress(src, dest);
                return dest.position() - pos;
            }

            @Override
            public int decompress(LZ4FastDecompressor decompressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = src.position();
                new LZ4DecompressorWithLength(decompressor).decompress(src, dest);
                return src.position() - pos;
            }

            @Override
            public int decompress(LZ4SafeDecompressor decompressor, ByteBuffer src, ByteBuffer dest) {
                final int pos = dest.position();
                new LZ4DecompressorWithLength(decompressor).decompress(src, dest);
                return dest.position() - pos;
            }
        };
    }

    protected static byte[] readResource(String resource) throws IOException {
        try (InputStream is = LZ4Test.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find " + resource);
            }
            return is.readAllBytes();
        }
    }

}
