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

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LocalAudioBuffer}, sized as {@link Participant} uses it:
 * 48000 bytes, sending once fewer than 1920 bytes are free. Whisper gets
 * 16 kHz mono audio, so each 20 ms packet is 640 bytes.
 */
public class LocalAudioBufferTest
{
    private static final int CAPACITY = 1920 * 25;

    private static final int MIN_FREE = 1920;

    private static final int PACKET = 640;

    private static byte[] packet(int value)
    {
        byte[] p = new byte[PACKET];
        Arrays.fill(p, (byte) value);
        return p;
    }

    private static void assertAll(byte[] block, int value)
    {
        for (int i = 0; i < block.length; i++)
        {
            assertEquals((byte) value, block[i], "byte " + i);
        }
    }

    @Test
    public void drainReturnsOnlyTheBufferedBytes()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        assertTrue(buffer.put(packet(1), 0).isEmpty());
        assertTrue(buffer.put(packet(1), 20).isEmpty());

        byte[] block = buffer.drain();

        assertEquals(2 * PACKET, block.length);
        assertAll(block, 1);
        assertEquals(0, buffer.size());
    }

    @Test
    public void drainAfterAFullBlockDoesNotResendOlderAudio()
    {
        // flushBuffer() used to send the whole backing array, so after a
        // mute the transcriber got the new tail plus older speech from the
        // previous block.
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; sent.isEmpty(); i++)
        {
            sent.addAll(buffer.put(packet(7), i * 20L));
        }
        assertEquals(1, sent.size());
        assertAll(sent.get(0), 7);

        buffer.put(packet(9), 2000);
        byte[] tail = buffer.drain();

        assertEquals(PACKET, tail.length);
        assertAll(tail, 9);
    }

    @Test
    public void repeatedDrainsSendNothing()
    {
        // flushBuffer() runs on every presence update while muted; each extra
        // flush used to resend the same array, producing repetition loops.
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        buffer.put(packet(3), 0);

        assertNotNull(buffer.drain());
        assertNull(buffer.drain());
        assertNull(buffer.drain());
    }

    @Test
    public void emptyBufferDrainsToNull()
    {
        assertNull(new LocalAudioBuffer(CAPACITY, MIN_FREE).drain());
    }

    @Test
    public void fullBlocksAreSentAtTheSameFillLevelAsBefore()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        List<byte[]> sent = new ArrayList<>();
        int packets = 0;
        while (sent.isEmpty())
        {
            sent.addAll(buffer.put(packet(1), packets * 20L));
            packets++;
        }
        // 73 x 640 = 46720 bytes leaves 1280 free (< 1920): the same point
        // the old code sent at, minus the 1280 never-written bytes it padded
        // each block with.
        assertEquals(73, packets);
        assertEquals(73 * PACKET, sent.get(0).length);
        assertEquals(0, buffer.size());
    }

    @Test
    public void fullBlockAt48kHzPacketsIsTheWholeBuffer()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        byte[] packet48k = new byte[1920];
        List<byte[]> sent = new ArrayList<>();
        int packets = 0;
        while (sent.isEmpty())
        {
            sent.addAll(buffer.put(packet48k, packets * 20L));
            packets++;
        }
        assertEquals(25, packets);
        assertEquals(CAPACITY, sent.get(0).length);
    }

    @Test
    public void audioThatDoesNotFitSendsWhatIsBufferedFirst()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(1000, 100);
        buffer.put(new byte[600], 0);
        byte[] big = new byte[500];
        Arrays.fill(big, (byte) 5);

        List<byte[]> sent = buffer.put(big, 20);

        assertEquals(1, sent.size());
        assertEquals(600, sent.get(0).length);
        assertEquals(500, buffer.size());
        assertAll(buffer.drain(), 5);
    }

    @Test
    public void audioLargerThanTheBufferIsSentOnItsOwn()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(1000, 100);
        buffer.put(new byte[10], 0);
        byte[] huge = new byte[1500];

        List<byte[]> sent = buffer.put(huge, 20);

        assertEquals(2, sent.size());
        assertEquals(10, sent.get(0).length);
        assertEquals(1500, sent.get(1).length);
        assertNotSame(huge, sent.get(1));
        assertEquals(0, buffer.size());
    }

    @Test
    public void idleTimeIsMeasuredFromTheLastPut()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        buffer.put(packet(1), 1000);
        buffer.put(packet(1), 1020);

        assertEquals(0, buffer.idleMillis(1020));
        assertEquals(300, buffer.idleMillis(1320));
    }

    @Test
    public void drainedBlocksAreCopies()
    {
        LocalAudioBuffer buffer = new LocalAudioBuffer(CAPACITY, MIN_FREE);
        buffer.put(packet(1), 0);
        byte[] first = buffer.drain();

        buffer.put(packet(2), 20);
        buffer.drain();

        assertAll(first, 1);
    }
}
