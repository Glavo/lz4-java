package net.jpountz.xxhash;

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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class XXHashFactoryTest {

    @Test
    public void test() {
        assertSame(XXHash32JavaSafe.INSTANCE, XXHashFactory.safeInstance().hash32());
        assertInstanceOf(StreamingXXHash32JavaSafe.class, XXHashFactory.safeInstance().newStreamingHash32(0));
        assertSame(XXHash64JavaSafe.INSTANCE, XXHashFactory.safeInstance().hash64());
        assertInstanceOf(StreamingXXHash64JavaSafe.class, XXHashFactory.safeInstance().newStreamingHash64(0));
    }
}
