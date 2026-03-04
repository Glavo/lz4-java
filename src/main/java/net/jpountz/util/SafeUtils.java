package net.jpountz.util;

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

import java.lang.invoke.VarHandle;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * <b>FOR INTERNAL USE ONLY</b>
 */
public enum SafeUtils {
    ;

    public static final VarHandle SHORT_LE = byteArrayViewVarHandle(short[].class, LITTLE_ENDIAN);
    public static final VarHandle INT_LE = byteArrayViewVarHandle(int[].class, LITTLE_ENDIAN);
    public static final VarHandle LONG_LE = byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN);

    public static final VarHandle SHORT_BE = byteArrayViewVarHandle(short[].class, BIG_ENDIAN);
    public static final VarHandle INT_BE = byteArrayViewVarHandle(int[].class, BIG_ENDIAN);
    public static final VarHandle LONG_BE = byteArrayViewVarHandle(long[].class, BIG_ENDIAN);

    public static final VarHandle SHORT_NE = Utils.NATIVE_BYTE_ORDER == LITTLE_ENDIAN ? SHORT_LE : SHORT_BE;
    public static final VarHandle INT_NE = Utils.NATIVE_BYTE_ORDER == LITTLE_ENDIAN ? INT_LE : INT_BE;
    public static final VarHandle LONG_NE = Utils.NATIVE_BYTE_ORDER == LITTLE_ENDIAN ? LONG_LE : LONG_BE;

    public static void checkRange(byte[] buf, int off) {
        if (off < 0 || off >= buf.length) {
            throw new ArrayIndexOutOfBoundsException(off);
        }
    }

    public static void checkRange(byte[] buf, int off, int len) {
        checkLength(len);
        if (len > 0) {
            checkRange(buf, off);
            checkRange(buf, off + len - 1);
        }
    }

    public static void checkLength(int len) {
        if (len < 0) {
            throw new IllegalArgumentException("lengths must be >= 0");
        }
    }

    public static byte readByte(byte[] buf, int i) {
        return buf[i];
    }

    public static int readIntBE(byte[] buf, int i) {
        return (int) INT_BE.get(buf, i);
    }

    public static int readIntLE(byte[] buf, int i) {
        return (int) INT_LE.get(buf, i);
    }

    public static int readInt(byte[] buf, int i) {
        return (int) INT_NE.get(buf, i);
    }

    public static void writeInt(byte[] buf, int i, int v) {
        INT_NE.set(buf, i, v);
    }

    public static long readLongLE(byte[] buf, int i) {
        return (long) LONG_LE.get(buf, i);
    }

    public static long readLong(byte[] buf, int i) {
        return (long) LONG_NE.get(buf, i);
    }

    public static void writeLong(byte[] buf, int i, long v) {
        LONG_NE.set(buf, i, v);
    }

    public static void writeShortLE(byte[] buf, int off, int v) {
        SHORT_LE.set(buf, off, (short) v);
    }

    public static void writeInt(int[] buf, int off, int v) {
        buf[off] = v;
    }

    public static int readInt(int[] buf, int off) {
        return buf[off];
    }

    public static void writeByte(byte[] dest, int off, int i) {
        dest[off] = (byte) i;
    }

    public static void writeShort(short[] buf, int off, int v) {
        buf[off] = (short) v;
    }

    public static int readShortLE(byte[] buf, int i) {
        return Short.toUnsignedInt((short) SHORT_LE.get(buf, i));
    }

    public static int readShort(short[] buf, int off) {
        return buf[off] & 0xFFFF;
    }
}
