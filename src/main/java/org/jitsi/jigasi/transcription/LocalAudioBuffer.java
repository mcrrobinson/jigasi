/*
 * Jigasi, the JItsi GAteway to SIP.
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
package org.jitsi.jigasi.transcription;

import java.util.*;

/**
 * The local audio buffer a {@link Participant} fills before sending audio to
 * the transcription service.
 * <p>
 * Every block it hands out is a copy of exactly the bytes buffered since the
 * last block. Sending the whole backing array instead (as
 * {@code Participant.flushBuffer()} used to) also sends whatever older audio
 * is still in the rest of the array, which the transcription service then
 * transcribes as speech: fragments of earlier sentences, repeated once per
 * flush.
 * <p>
 * Not thread safe. {@link Participant} only uses it from the transcriber's
 * single-threaded executor.
 */
final class LocalAudioBuffer
{
    private final byte[] data;

    /**
     * A block is sent once less than this many bytes are free.
     */
    private final int minFree;

    private int position = 0;

    private long lastPutMillis = 0;

    /**
     * @param capacity the size of the buffer in bytes
     * @param minFree a block is sent once fewer than this many bytes are free
     */
    LocalAudioBuffer(int capacity, int minFree)
    {
        if (capacity <= 0 || minFree < 0 || minFree > capacity)
        {
            throw new IllegalArgumentException(
                "capacity=" + capacity + " minFree=" + minFree);
        }
        this.data = new byte[capacity];
        this.minFree = minFree;
    }

    /**
     * Adds audio to the buffer.
     *
     * @param audio the audio to add
     * @param nowMillis the current time, used by {@link #idleMillis(long)}
     * @return the blocks that are ready to send, in order (usually none)
     */
    List<byte[]> put(byte[] audio, long nowMillis)
    {
        lastPutMillis = nowMillis;
        List<byte[]> ready = new ArrayList<>(1);
        if (audio.length > data.length - position)
        {
            byte[] pending = drain();
            if (pending != null)
            {
                ready.add(pending);
            }
            if (audio.length > data.length)
            {
                ready.add(audio.clone());
                return ready;
            }
        }
        System.arraycopy(audio, 0, data, position, audio.length);
        position += audio.length;
        if (data.length - position < minFree)
        {
            ready.add(drain());
        }
        return ready;
    }

    /**
     * Empties the buffer.
     *
     * @return a copy of the buffered bytes, or {@code null} if it was empty
     */
    byte[] drain()
    {
        if (position == 0)
        {
            return null;
        }
        byte[] block = Arrays.copyOf(data, position);
        position = 0;
        return block;
    }

    /**
     * @return the number of bytes waiting to be sent
     */
    int size()
    {
        return position;
    }

    /**
     * @param nowMillis the current time
     * @return how long ago audio was last added, in milliseconds
     */
    long idleMillis(long nowMillis)
    {
        return nowMillis - lastPutMillis;
    }
}
