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
 * Thread-local char buffer for temporary text measurement.
 * <p>
 * Previously this used a single process-wide slot guarded by a global lock. Layout tasks run
 * concurrently on a thread pool, so every worker contended on the same lock and repeatedly
 * allocated a new array whenever another thread had already taken the shared slot. The result
 * was severe lock contention plus GC pressure during a full-document wordwrap. A thread-local
 * buffer removes both problems and stays allocation-free for repeated measurements on the same
 * thread.
 */
public class TemporaryCharBuffer {

    private static final ThreadLocal<char[]> sTemp = new ThreadLocal<>();
    private static final int MAX_CACHED_LEN = 8192;

    public static char[] obtain(int len) {
        char[] buf = sTemp.get();
        if (buf == null || buf.length < len) {
            buf = new char[len];
            sTemp.set(buf);
        }
        return buf;
    }

    public static void recycle(char[] temp) {
        if (temp.length > MAX_CACHED_LEN) {
            sTemp.remove();
        }
    }
}