/*
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.util;

/**
 * Thread-local float buffer for text measurement.
 * <p>
 * Layout tasks run concurrently on a thread pool. The previous implementation used a single
 * process-wide slot guarded by a global lock, so parallel wordwrap subtasks serialized on the
 * lock and kept allocating new arrays. A thread-local buffer removes the contention entirely.
 */
public class TemporaryFloatBuffer {

    private static final ThreadLocal<float[]> sCache = new ThreadLocal<>();
    private static final int MAX_CACHED_LEN = 8192;

    public static float[] obtain(int len) {
        float[] buf = sCache.get();
        if (buf == null || buf.length < len) {
            buf = new float[len];
            sCache.set(buf);
        }
        return buf;
    }

    public static void recycle(float[] temp) {
        if (temp.length > MAX_CACHED_LEN) {
            sCache.remove();
        }
    }

}
