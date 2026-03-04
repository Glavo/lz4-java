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

import net.jpountz.util.SafeUtils;

import java.util.Arrays;

import static net.jpountz.lz4.LZ4Constants.*;
import static net.jpountz.lz4.LZ4Utils.lengthOfEncodedInteger;
import static net.jpountz.lz4.LZ4Utils.notEnoughSpace;
import static net.jpountz.lz4.LZ4Utils.sequenceLength;

enum LZ4SafeUtils {
    ;

    static boolean readIntEquals(byte[] buf, int i, int j) {
        return SafeUtils.readInt(buf, i) == SafeUtils.readInt(buf, j);
    }

    static void safeIncrementalCopy(byte[] dest, int matchOff, int dOff, int matchLen) {
        for (int i = 0; i < matchLen; ++i) {
            dest[dOff + i] = dest[matchOff + i];
        }
    }

    static void wildIncrementalCopy(byte[] dest, int matchOff, int dOff, int matchCopyEnd) {
        if (dOff - matchOff < 4) {
            for (int i = 0; i < 4; ++i) {
                dest[dOff + i] = dest[matchOff + i];
            }
            dOff += 4;
            matchOff += 4;
            int dec = 0;
            assert dOff >= matchOff && dOff - matchOff < 8;
            switch (dOff - matchOff) {
                case 1:
                    matchOff -= 3;
                    break;
                case 2:
                    matchOff -= 2;
                    break;
                case 3:
                    matchOff -= 3;
                    dec = -1;
                    break;
                case 5:
                    dec = 1;
                    break;
                case 6:
                    dec = 2;
                    break;
                case 7:
                    dec = 3;
                    break;
                default:
                    break;
            }
            SafeUtils.writeInt(dest, dOff, SafeUtils.readInt(dest, matchOff));
            dOff += 4;
            matchOff -= dec;
        } else if (dOff - matchOff < COPY_LENGTH) {
            SafeUtils.writeLong(dest, dOff, SafeUtils.readLong(dest, matchOff));
            dOff += dOff - matchOff;
        }
        while (dOff < matchCopyEnd) {
            SafeUtils.writeLong(dest, dOff, SafeUtils.readLong(dest, matchOff));
            dOff += 8;
            matchOff += 8;
        }
    }

    static int commonBytes(byte[] b, int o1, int o2, int limit) {
        int len = limit - o2;
        int mismatch = Arrays.mismatch(b, o1, o1 + len, b, o2, limit);
        return mismatch < 0 ? len : mismatch;
    }

    static int commonBytesBackward(byte[] b, int o1, int o2, int l1, int l2) {
        int count = 0;
        while (o1 > l1 && o2 > l2 && b[--o1] == b[--o2]) {
            ++count;
        }
        return count;
    }

    static void safeArraycopy(byte[] src, int sOff, byte[] dest, int dOff, int len) {
        System.arraycopy(src, sOff, dest, dOff, len);
    }

    static void wildArraycopy(byte[] src, int sOff, byte[] dest, int dOff, int len) {
        for (int i = 0; i < len; i += 8) {
            SafeUtils.writeLong(dest, dOff + i, SafeUtils.readLong(src, sOff + i));
        }
    }

    static int encodeSequence(byte[] src, int anchor, int matchOff, int matchRef, int matchLen, byte[] dest, int dOff, int destEnd) {
        final int runLen = matchOff - anchor;
        matchLen -= 4;

        int end = dOff + sequenceLength(runLen, matchLen);
        // Check for overflow
        if (end < 0 || notEnoughSpace(destEnd - end, 1 + LAST_LITERALS)) {
            throw new LZ4Exception("maxDestLen is too small");
        }
        final int tokenOff = dOff++;

        int token;
        if (runLen >= RUN_MASK) {
            token = (byte) (RUN_MASK << ML_BITS);
            dOff = writeLen(runLen - RUN_MASK, dest, dOff);
        } else {
            token = runLen << ML_BITS;
        }

        // copy literals
        wildArraycopy(src, anchor, dest, dOff, runLen);
        dOff += runLen;

        // encode offset
        final int matchDec = matchOff - matchRef;
        dest[dOff++] = (byte) matchDec;
        dest[dOff++] = (byte) (matchDec >>> 8);

        // encode match len
        if (matchLen >= ML_MASK) {
            token |= ML_MASK;
            dOff = writeLen(matchLen - RUN_MASK, dest, dOff);
        } else {
            token |= matchLen;
        }

        dest[tokenOff] = (byte) token;

        assert dOff == end;
        return dOff;
    }

    static int lastLiterals(byte[] src, int sOff, int srcLen, byte[] dest, int dOff, int destEnd) {
        final int runLen = srcLen;

        if (notEnoughSpace(destEnd - dOff, 1 + lengthOfEncodedInteger(runLen) + runLen)) {
            throw new LZ4Exception();
        }

        if (runLen >= RUN_MASK) {
            dest[dOff++] = (byte) (RUN_MASK << ML_BITS);
            dOff = writeLen(runLen - RUN_MASK, dest, dOff);
        } else {
            dest[dOff++] = (byte) (runLen << ML_BITS);
        }
        // copy literals
        System.arraycopy(src, sOff, dest, dOff, runLen);
        dOff += runLen;

        return dOff;
    }

    static int writeLen(int len, byte[] dest, int dOff) {
        while (len >= 0xFF) {
            dest[dOff++] = (byte) 0xFF;
            len -= 0xFF;
        }
        dest[dOff++] = (byte) len;
        return dOff;
    }

}
