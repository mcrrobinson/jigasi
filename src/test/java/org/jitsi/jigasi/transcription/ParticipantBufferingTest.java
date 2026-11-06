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

import org.jitsi.jigasi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;
import org.junit.jupiter.api.*;

import javax.media.*;
import javax.media.format.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real {@link Participant} on a real {@link Transcriber} with 20 ms,
 * 16 kHz mono packets (what the Whisper service gets) and records exactly
 * which bytes reach the transcription service.
 * <p>
 * Each packet is filled with its own non-zero value, so stale audio, zero
 * padding, duplicates and gaps all show up as a mismatch with what was fed in.
 */
public class ParticipantBufferingTest
{
    private static final int PACKET = 640;

    private static final AudioFormat FORMAT
        = new AudioFormat(AudioFormat.LINEAR, 16000, 16, 1);

    private RecordingService service;

    private Transcriber transcriber;

    private Participant participant;

    /**
     * Transcriber reads its configuration through the bundle activator. Give
     * it a configuration service that answers every lookup with its default.
     */
    @BeforeAll
    public static void defaultConfiguration() throws Exception
    {
        ConfigurationService config = (ConfigurationService) Proxy.newProxyInstance(
            ConfigurationService.class.getClassLoader(),
            new Class<?>[] { ConfigurationService.class },
            (proxy, method, args) ->
            {
                Class<?> type = method.getReturnType();
                if (args != null && args.length == 2 && type.isInstance(box(type, args[1])))
                {
                    return args[1];
                }
                if (type == boolean.class)
                {
                    return false;
                }
                if (type == int.class || type == long.class || type == double.class)
                {
                    return box(type, 0);
                }
                if (List.class.isAssignableFrom(type))
                {
                    return new ArrayList<>();
                }
                return null;
            });
        Field f = JigasiBundleActivator.class.getDeclaredField("configService");
        f.setAccessible(true);
        f.set(null, config);
    }

    private static Object box(Class<?> type, Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (type == int.class && value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        if (type == long.class && value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        if (type == double.class && value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        if (type == boolean.class && value instanceof Boolean)
        {
            return value;
        }
        return value;
    }

    @BeforeEach
    public void setUp()
    {
        service = new RecordingService();
        Logger logger = new LoggerImpl(ParticipantBufferingTest.class.getName());
        transcriber = new Transcriber(service, new CallContext(this), logger);
        transcriber.start();
        participant = new Participant(transcriber, "p1", false);
        participant.joined();
    }

    @AfterEach
    public void tearDown()
    {
        transcriber.executorService.shutdownNow();
    }

    private static byte[] packet(int index)
    {
        byte[] p = new byte[PACKET];
        Arrays.fill(p, (byte) (index % 250 + 1));
        return p;
    }

    private byte[] feed(int from, int count)
    {
        ByteArrayOutputStream fed = new ByteArrayOutputStream();
        for (int i = from; i < from + count; i++)
        {
            byte[] p = packet(i);
            Buffer b = new Buffer();
            b.setFormat(FORMAT);
            b.setData(p);
            b.setLength(p.length);
            participant.giveBuffer(b);
            fed.write(p, 0, p.length);
        }
        return fed.toByteArray();
    }

    /**
     * Waits until the transcriber's executor has run everything queued so far.
     */
    private void settle() throws Exception
    {
        for (int i = 0; i < 3; i++)
        {
            transcriber.executorService.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] concat(List<byte[]> blocks)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : blocks)
        {
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private static int packetsIn(byte[] block)
    {
        return block.length / PACKET;
    }

    @Test
    public void endOfUtteranceIsSentOnceTheSpeakerGoesQuiet() throws Exception
    {
        // 100 packets = 2 s of speech, then the bridge stops forwarding audio
        byte[] fed = feed(0, 100);
        settle();
        assertEquals(1, service.requests.size(), "one full block during speech");
        assertEquals(73, packetsIn(service.requests.get(0)));

        Thread.sleep(700);
        settle();

        assertEquals(2, service.requests.size(), "the last 27 packets sent after ~300 ms of quiet");
        assertEquals(27, packetsIn(service.requests.get(1)));
        assertArrayEquals(fed, concat(service.requests), "exactly the audio fed in, in order");
    }

    @Test
    public void mutingLaterSendsNothingMore() throws Exception
    {
        byte[] fed = feed(0, 100);
        Thread.sleep(700);
        settle();
        int before = service.requests.size();

        // flushBuffer() runs on every presence update while muted
        participant.flushBuffer();
        participant.flushBuffer();
        participant.flushBuffer();
        settle();

        assertEquals(before, service.requests.size(), "no stale audio resent on mute");
        assertArrayEquals(fed, concat(service.requests));
    }

    @Test
    public void mutingStraightAfterSpeechSendsOnlyTheNewTail() throws Exception
    {
        byte[] first = feed(0, 100);
        Thread.sleep(700);
        settle();
        byte[] tail = feed(100, 10);

        participant.flushBuffer();
        participant.flushBuffer();
        settle();
        Thread.sleep(700);
        settle();

        byte[] last = service.requests.get(service.requests.size() - 1);
        assertEquals(10, packetsIn(last), "the flush sends the 10 new packets, nothing older");
        assertArrayEquals(tail, last);
        byte[] all = new byte[first.length + tail.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(tail, 0, all, first.length, tail.length);
        assertArrayEquals(all, concat(service.requests), "nothing duplicated by the later idle flush");
    }

    @Test
    public void steadySpeechIsNotSplitByTheIdleFlush() throws Exception
    {
        // 1 s of speech at the real 20 ms packet rate: no gap reaches 300 ms
        ByteArrayOutputStream fed = new ByteArrayOutputStream();
        for (int i = 0; i < 50; i++)
        {
            byte[] p = feed(i, 1);
            fed.write(p, 0, p.length);
            Thread.sleep(20);
        }
        settle();
        assertEquals(0, service.requests.size(), "nothing sent mid-utterance");

        Thread.sleep(700);
        settle();

        assertEquals(1, service.requests.size());
        assertArrayEquals(fed.toByteArray(), service.requests.get(0));
    }

    /**
     * A streaming transcription service that records the audio of every
     * request.
     */
    private static class RecordingService
        extends AbstractTranscriptionService
    {
        final List<byte[]> requests = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsFragmentTranscription()
        {
            return false;
        }

        @Override
        public void sendSingleRequest(TranscriptionRequest request, Consumer<TranscriptionResult> resultConsumer)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supportsStreamRecognition()
        {
            return true;
        }

        @Override
        public boolean supportsLanguageRouting()
        {
            return false;
        }

        @Override
        public StreamingRecognitionSession initStreamingSession(Participant participant)
        {
            return new StreamingRecognitionSession()
            {
                @Override
                public void sendRequest(TranscriptionRequest request)
                {
                    requests.add(request.getAudio().clone());
                }

                @Override
                public void end()
                {
                }

                @Override
                public boolean ended()
                {
                    return false;
                }

                @Override
                public void addTranscriptionListener(TranscriptionListener listener)
                {
                }
            };
        }

        @Override
        public boolean isConfiguredProperly()
        {
            return true;
        }
    }
}
