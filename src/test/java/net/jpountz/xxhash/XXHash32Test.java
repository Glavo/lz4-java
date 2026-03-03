package net.jpountz.xxhash;

/*
 * Copyright 2020 Linnaea Von Lavia and the lz4-java contributors.
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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;
import java.util.stream.LongStream;

import net.jpountz.RandomContext;
import net.jpountz.util.SafeUtils;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.util.xxhash.XXH32State;
import org.lwjgl.util.xxhash.XXHash;

import static org.junit.jupiter.api.Assertions.*;

public class XXHash32Test {

    private static LongStream randomSeeds() {
        return LongStream.range(0, 20);
    }

    private static final List<Function<RandomContext, XXHash32>> PROVIDERS = List.of(
            context -> XXHashFactory.safeInstance().hash32(),
            context -> new XXHash32() {
                @Override
                public int hash(byte[] buf, int off, int len, int seed) {
                    SafeUtils.checkRange(buf, off, len);
                    int originalOff = off;
                    int remainingPasses = context.nextInt(6);
                    StreamingXXHash32 h = XXHashFactory.safeInstance().newStreamingHash32(seed);
                    final int end = off + len;
                    while (off < end) {
                        final int l = context.nextInt(off, end + 1) - off;
                        h.update(buf, off, l);
                        off += l;
                        if (remainingPasses > 0 && context.nextInt(6) == 0) {
                            h.reset();
                            --remainingPasses;
                            off = originalOff;
                        }
                        if (context.nextBoolean()) {
                            h.getValue();
                        }
                    }
                    return h.getValue();
                }

                @Override
                public int hash(ByteBuffer buf, int off, int len, int seed) {
                    byte[] bytes = new byte[len];
                    int originalPosition = buf.position();
                    try {
                        buf.position(off);
                        buf.get(bytes, 0, len);
                        return hash(bytes, 0, len, seed);
                    } finally {
                        buf.position(originalPosition);
                    }
                }
            }
    );

    @ParameterizedTest
    @MethodSource("randomSeeds")
    public void testEmpty(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int seed = context.nextInt();
        for (var provider : PROVIDERS) {
            var xxHash = provider.apply(context);
            xxHash.hash(new byte[0], 0, 0, seed);
            xxHash.hash(context.copyOf(new byte[0], 0, 0), 0, 0, seed);
        }
    }

    @ParameterizedTest
    @MethodSource("randomSeeds")
    public void testAIOOBE(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int seed = context.nextInt();
        final int max = context.nextBoolean() ? 32 : 1000;
        final int bufLen = context.nextInt(1, max + 1);
        final byte[] buf = context.nextBytes(bufLen);
        final int off = context.nextInt(buf.length);
        final int len = context.nextInt(buf.length - off + 1);
        for (var provider : PROVIDERS) {
            var xxHash = provider.apply(context);
            xxHash.hash(buf, off, len, seed);
            xxHash.hash(context.copyOf(buf, off, len), off, len, seed);
        }
    }

    @ParameterizedTest
    @MethodSource("randomSeeds")
    @Disabled
    public void testInstances(long randomSeed) {
        var context = new RandomContext(randomSeed);

        final int maxLenLog = context.nextInt(21);
        final int bufLen = context.nextInt(1 << maxLenLog);
        byte[] buf = context.nextBytes(bufLen);
        final int seed = context.nextInt();
        final int off = context.nextInt(Math.max(1, bufLen));
        final int len = context.nextInt(bufLen - off + 1);

        final int ref = XXHashFactory.nativeInstance().hash32().hash(buf, off, len, seed);
        for (var provider : PROVIDERS) {
            var hash = provider.apply(context);
            final int h = hash.hash(buf, off, len, seed);
            assertEquals(ref, h, hash.toString());
            final ByteBuffer copy = context.copyOf(buf, off, len);
            final int h2 = hash.hash(copy, off, len, seed);
            assertEquals(off, copy.position());
            assertEquals(len, copy.remaining());
            assertEquals(ref, h2, hash.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2L, 3L})
    public void test4GB(long randomSeed) {
        var context = new RandomContext(randomSeed);

        byte[] bytes = context.nextBytes(context.nextInt(1 << 22, 1 << 26));
        final int off = context.nextInt(6);
        final int len = context.nextInt(bytes.length - off - 1024, bytes.length - off);
        long totalLen = 0;
        final int seed = context.nextInt();

        ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(len);
        nativeBuffer.limit(len);

        StreamingXXHash32 hash = XXHashFactory.safeInstance().newStreamingHash32(seed);
        try (XXH32State state = XXHash.XXH32_createState()) {
            assertNotNull(state);

            if (XXHash.XXH32_reset(state, seed) != XXHash.XXH_OK) {
                fail("XXH32_reset failed");
            }

            while (totalLen < (1L << 33)) {
                nativeBuffer.put(0, bytes, off, len);
                if (XXHash.XXH32_update(state, nativeBuffer) != XXHash.XXH_OK) {
                    fail("XXH32_update failed");
                }

                hash.update(bytes, off, len);

                assertEquals(hash.getValue(), XXHash.XXH32_digest(state), "hash " + totalLen);
                totalLen += len;
            }
        }
    }
}
