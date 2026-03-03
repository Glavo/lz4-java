/*
 * Copyright 2026 Glavo
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
package net.jpountz;

import java.nio.ByteBuffer;
import java.util.Random;

public final class RandomContext {
    private final Random random;

    public RandomContext(long seed) {
        this.random = new Random(seed);
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    public byte nextByte() {
        return (byte) random.nextInt(256);
    }

    public int nextInt() {
        return random.nextInt();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public int nextInt(int min, int max) {
        assert max > min : "max must be > min: " + min + ", " + max;

        long range = (long) max - (long) min;
        if (range < Integer.MAX_VALUE) {
            return min + random.nextInt((int) range);
        } else {
            return (int) (min + random.nextLong(range));
        }
    }

    public long nextLong() {
        return random.nextLong();
    }

    public float nextFloat() {
        return random.nextFloat();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public void nextBytes(byte[] bytes) {
        random.nextBytes(bytes);
    }

    public byte[] nextBytes(int len) {
        byte[] result = new byte[len];
        random.nextBytes(result);
        return result;
    }

    public ByteBuffer copyOf(byte[] bytes, int offset, int length) {
        ByteBuffer buffer;
        if (random.nextBoolean()) {
            buffer = ByteBuffer.allocate(bytes.length);
        } else {
            buffer = ByteBuffer.allocateDirect(bytes.length);
        }
        buffer.put(bytes);
        buffer.position(offset);
        buffer.limit(offset + length);
        if (random.nextBoolean()) {
            buffer = buffer.asReadOnlyBuffer();
        }
        return buffer;
    }
}
