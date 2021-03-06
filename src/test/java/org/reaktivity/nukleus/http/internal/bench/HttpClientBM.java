/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.http.internal.bench;

import static java.lang.String.format;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.file.FileVisitOption.FOLLOW_LINKS;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.IoUtil.ensureDirectoryExists;
import static org.reaktivity.nukleus.Configuration.DIRECTORY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.STREAMS_BUFFER_CAPACITY_PROPERTY_NAME;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.agrona.LangUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.reaktivity.nukleus.Configuration;
import org.reaktivity.nukleus.http.internal.HttpController;
import org.reaktivity.nukleus.http.internal.HttpStreams;
import org.reaktivity.nukleus.http.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.http.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.http.internal.types.stream.WindowFW;
import org.reaktivity.reaktor.Reaktor;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Fork(3)
@Warmup(iterations = 1, time = 10, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = SECONDS)
@OutputTimeUnit(SECONDS)
public class HttpClientBM
{
    static final String PAYLOAD_TEXT = "Hello, world";
    static final byte[] PAYLOAD = PAYLOAD_TEXT.getBytes(StandardCharsets.UTF_8);
    static final byte[] RESPONSE_BYTES = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Length:12\r\n" +
                            "\r\n" +
                            PAYLOAD_TEXT).getBytes(StandardCharsets.UTF_8);

    @State(Scope.Group)
    public static class SharedState
    {
        private final Configuration configuration;
        private volatile Reaktor reaktor;

        private volatile HttpStreams clientAcceptStreams;
        private volatile HttpStreams clientAcceptReplyStreams;
        private volatile HttpStreams clientConnectStreams;
        private volatile HttpStreams clientConnectReplyStreams;

        private String clientAccept;
        private long clientAcceptRef;
        private String clientConnect;
        private long clientConnectRef;

        private volatile long streamsSourced;

        {
            Properties properties = new Properties();
            properties.setProperty(DIRECTORY_PROPERTY_NAME, "target/nukleus-benchmarks");
            properties.setProperty(STREAMS_BUFFER_CAPACITY_PROPERTY_NAME, Long.toString(1024L * 1024L * 16L));

            configuration = new Configuration(properties);
            ensureDirectoryExists(configuration.directory().toFile(), configuration.directory().toString());

            reaktor = Reaktor.builder()
                         .config(configuration)
                         .nukleus("http"::equals)
                         .controller(HttpController.class::isAssignableFrom)
                         .errorHandler(ex -> ex.printStackTrace(System.err))
                         .build();
        }

        @Setup(Level.Iteration)
        public void reinit(Control control) throws Exception
        {
            try
            {
                System.out.println("\nDeleting streams files\n");
                Files.walk(configuration.directory(), FOLLOW_LINKS)
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
            catch (IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
            reaktor = Reaktor.builder()
                    .config(configuration)
                    .nukleus("http"::equals)
                    .controller(HttpController.class::isAssignableFrom)
                    .errorHandler(ex -> ex.printStackTrace(System.err))
                    .build();
            reaktor.start();
            System.out.println("Reaktor started");

            final HttpController controller = reaktor.controller(HttpController.class);

            this.streamsSourced = 0;

            final Random random = new Random();
            final long targetRef = random.nextLong();

            this.clientAccept = "source";
            this.clientConnect = "target";
            this.clientConnectRef = targetRef;
            this.clientAcceptRef = controller.routeClient("source", 0L, "target", targetRef, emptyMap()).get();

            this.clientAcceptStreams = controller.streams("source");
            this.clientConnectReplyStreams = controller.streams("target");

            // Map file streams/source/http#target created by routeOutputNew
            clientConnectStreams = controller.streams("source", "target");

            RequestWriterState writer = new RequestWriterState();
            writer.reinit(this,  control);
            boolean done = false;
            for (int i=0; i < 10 && !done; i++)
            {
                Thread.sleep(100);
                done = writer.writeRequestBegin();
            }
            if (!done)
            {
                throw new RuntimeException("SharedState.reinit: writer.writeRequest() failed");
            }

            RemoteReaderState echoReader = new RemoteReaderState();
            echoReader.reinit(this, control);
            RemoteWriterState echoWriter = new RemoteWriterState();
            echoWriter.reinit(this, control);
            int rawRequestFramesProcessed = 0;
            for (int i=0; i < 10 && rawRequestFramesProcessed < 1; i++)
            {
                Thread.sleep(100);
                rawRequestFramesProcessed += echoReader.processRequests(echoWriter);
            }
            done = writer.writeRequestDataAndEnd();
            if (!done)
            {
                throw new RuntimeException("SharedState.reinit: writer.writeRequest() failed");
            }
            rawRequestFramesProcessed = 0;
            for (int i=0; i < 10 && rawRequestFramesProcessed < 1; i++)
            {
                Thread.sleep(100);
                rawRequestFramesProcessed += echoReader.processRequests(echoWriter);
            }
            if (rawRequestFramesProcessed < 1)
            {
                throw new RuntimeException("SharedState.reinit: echoReader.processRequests() failed");
            }

            for (int i=0; i < 100 && clientAcceptReplyStreams == null; i++)
            {
                try
                {
                    // Map file streams/source/http#target
                    clientAcceptReplyStreams = controller.streams("target", "source");
                }
                catch (IllegalStateException e)
                {
                    Thread.sleep(100);
                }
            }
            ResponseReaderState reader = new ResponseReaderState();
            reader.reinit(this, control);
            int result = 0;
            for (int i=0; i < 10 && result < 1; i++)
            {
                Thread.sleep(100);
                result = reader.readResponse();
            }
            if (result <= 0)
            {
                throw new RuntimeException("SharedState.reinit: reader.readResponse() failed");
            }
            System.out.println("SharedState.reinit complete " + this);
        }

        @TearDown(Level.Iteration)
        public void reset() throws Exception
        {
            HttpController controller = reaktor.controller(HttpController.class);

            try
            {
                controller.unrouteClient(clientAccept, clientAcceptRef, clientConnect, clientConnectRef, null).get();
            }
            catch(Exception e)
            {
                System.out.println(format("\nException from unrouteOutputNew in reset(): %s\n", e));
                e.printStackTrace();
            }
            reaktor.close();

            this.clientAcceptStreams.close();
            this.clientAcceptStreams = null;
            this.clientAcceptReplyStreams.close();
            this.clientAcceptReplyStreams = null;
            this.clientConnectStreams.close();
            this.clientConnectStreams = null;
            this.clientConnectReplyStreams.close();
            this.clientConnectReplyStreams = null;
        }

        LongSupplier supplyStreamId()
        {
            return () -> streamsSourced++;
        }
    }

    @State(Scope.Thread)
    public static class RequestWriterState
    {
        private SharedState sharedState;
        private BooleanSupplier measurementEnded;

        private long nextCorrelationId;

        private final WindowFW windowRO = new WindowFW();

        private final BeginFW.Builder beginRW = new BeginFW.Builder();
        private final DataFW.Builder dataRW = new DataFW.Builder();
        private final EndFW.Builder endRW = new EndFW.Builder();

        private long streamId;
        IdleStrategy idleStrategy = new BackoffIdleStrategy(64, 64, NANOSECONDS.toNanos(64L), MICROSECONDS.toNanos(64L));
        private int availableWindow;
        int requestCount;

        @Setup(Level.Iteration)
        public void reinit(SharedState state, Control control) throws Exception
        {
            this.sharedState = state;
            this.measurementEnded = () -> control.stopMeasurement;

            final AtomicBuffer outputBeginBuffer = new UnsafeBuffer(new byte[256]);
            beginRW.wrap(outputBeginBuffer, 0, outputBeginBuffer.capacity())
            .source(state.clientAccept)
            .sourceRef(state.clientAcceptRef)
            .extension(e -> e.set((buffer, offset, limit) ->
                    new HttpBeginExFW.Builder().wrap(buffer, offset, limit)
                        .headers(hs -> hs
                            .item(h -> h.name(":scheme").value("http"))
                            .item(h -> h.name(":method").value("post"))
                            .item(h -> h.name(":path").value("/"))
                            .item(h -> h.name(":authority").value("localhost:8080"))
                            .item(h -> h.name("content-length").value(Integer.toString(PAYLOAD.length)))
                         )
                        .build()
                    .sizeof())
                .build());

            final AtomicBuffer outputDataBuffer = new UnsafeBuffer(new byte[256]);
            dataRW.wrap(outputDataBuffer, 0, outputDataBuffer.capacity())
                        .payload(p -> p.set(PAYLOAD))
                        .extension(e -> e.reset());

            final AtomicBuffer outputEndBuffer = new UnsafeBuffer(new byte[20]);
            endRW.wrap(outputDataBuffer, 0, outputEndBuffer.capacity())
                        .extension(e -> e.reset());
            prepareNextStream();
        }

        private void prepareNextStream()
        {
            streamId = sharedState.supplyStreamId().getAsLong();
            availableWindow = 0;
            beginRW.streamId(streamId).correlationId(++nextCorrelationId);
            dataRW.streamId(streamId);
            endRW.streamId(streamId);
        }

        private int writeRequest()
        {
            boolean result = writeRequestBegin();
            if (result)
            {
                result = writeRequestDataAndEnd();
            }
            return result ? 1 : 0;
        }

        private boolean writeRequestBegin()
        {
            beginRW.source(sharedState.clientAccept);
            beginRW.sourceRef(sharedState.clientAcceptRef);
            BeginFW begin = beginRW.build();
            return sharedState.clientAcceptStreams.writeStreams(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
        }

        private boolean writeRequestDataAndEnd()
        {
            DataFW data = dataRW.build();
            EndFW end = endRW.build();
            boolean result = false;
            HttpStreams clientAcceptStreams = sharedState.clientAcceptStreams;
            while (!result && !measurementEnded.getAsBoolean())
            {
                clientAcceptStreams.readThrottle(this::throttle);
                result = availableWindow >= data.length();
                if (result)
                {
                    result = clientAcceptStreams.writeStreams(data.typeId(), data.buffer(), 0, data.limit());
                    if (result)
                    {
                        availableWindow -= data.length();
                        clientAcceptStreams.writeStreams(end.typeId(), end.buffer(), 0, end.limit());
                    }
                    else
                    {
                        String error = format("write failed, availableClientAcceptWindow = %d", availableWindow);
                        System.out.println(error);
                        throw new RuntimeException(error);
                    }
                    prepareNextStream();
                }
            }
            return result;
        }

        private void throttle(
                int msgTypeId,
                MutableDirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                windowRO.wrap(buffer, index, index + length);
                if (windowRO.streamId() == streamId)
                {
                    availableWindow += windowRO.update();
                }
                break;
            case ResetFW.TYPE_ID:
                System.out.println("WARNING: reset detected in client accept throttle");
                break;
            default:
                System.out.println(format("ERROR: unexpected msgTypeId %d detected in client accept throttle",
                        msgTypeId));
                break;
            }
        }
    }

    @State(Scope.Thread)
    public static class ResponseReaderState
    {
        private SharedState sharedState;
        private MessageHandler clientAcceptReplyHandler;
        private final BeginFW beginRO = new BeginFW();
        private final DataFW dataRO = new DataFW();
        private final WindowFW.Builder windowRW = new WindowFW.Builder();
        private MutableDirectBuffer throttleBuffer;

        @Setup(Level.Iteration)
        public void reinit(SharedState state, Control control) throws Exception
        {
            this.sharedState = state;
            this.clientAcceptReplyHandler = this::processResponseFrame;
            this.throttleBuffer = new UnsafeBuffer(allocateDirect(SIZE_OF_LONG + SIZE_OF_INT));
        }

        int readResponse()
        {
            return sharedState.clientAcceptReplyStreams.readStreams(clientAcceptReplyHandler);
        }

        private void processResponseFrame(
            int msgTypeId,
            MutableDirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                beginRO.wrap(buffer, index, index + length);
                long streamId = beginRO.streamId();
                doWindow(streamId, 8192);
                break;
            case DataFW.TYPE_ID:
                dataRO.wrap(buffer, index, index + length);
                streamId = dataRO.streamId();
                final int update = dataRO.length();
                doWindow(streamId, update);
                break;
            case EndFW.TYPE_ID:
                break;
            default:
                String error = format("ResponseReader: read unexpected frame with msgTypeId=%d", msgTypeId);
                System.out.println(error);
                throw new RuntimeException(error);
            }
        }

        private void doWindow(
            final long streamId,
            final int update)
        {
            final WindowFW window = windowRW.wrap(throttleBuffer, 0, throttleBuffer.capacity())
                    .streamId(streamId)
                    .update(update)
                    .build();
            sharedState.clientAcceptReplyStreams.writeThrottle(window.typeId(), window.buffer(), window.offset(),
                    window.sizeof());
        }
    }

    @State(Scope.Thread)
    public static class RemoteReaderState
    {
        private SharedState sharedState;
        private final BeginFW beginRO = new BeginFW();
        private final DataFW dataRO = new DataFW();
        private final WindowFW.Builder windowRW = new WindowFW.Builder();
        private MutableDirectBuffer throttleBuffer;
        private MessageHandler clientConnectHandler;
        long streamId;
        private RemoteWriterState writer;

        @Setup(Level.Iteration)
        public void reinit(SharedState state, Control control) throws Exception
        {
            this.sharedState = state;
            this.clientConnectHandler = this::processRequestFrame;
            this.throttleBuffer = new UnsafeBuffer(allocateDirect(SIZE_OF_LONG + SIZE_OF_INT));
        }

        int processRequests(RemoteWriterState writer)
        {
            this.writer = writer;
            return sharedState.clientConnectStreams.readStreams(clientConnectHandler);
        }

        private void processRequestFrame(
            int msgTypeId,
            MutableDirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                beginRO.wrap(buffer, index, index + length);
                streamId = beginRO.streamId();
                long correlationId = beginRO.correlationId();
                doWindow(streamId, 8192);
                writer.writeBegin(correlationId);
                break;
            case DataFW.TYPE_ID:
                dataRO.wrap(buffer, index, index + length);
                streamId = dataRO.streamId();
                final int update = dataRO.length();
                doWindow(streamId, update);

                // The following relies upon HTTP nukleus implementation writing request content
                // in a separate data frame from the headers.
                if (update == PAYLOAD.length)
                {
                    writer.writeResponse();
                }
                break;
            case EndFW.TYPE_ID:
                break;
            default:
                String error = format("ResponseReader: read unexpected frame with msgTypeId=%d", msgTypeId);
                System.out.println(error);
                throw new RuntimeException(error);
            }
        }

        private void doWindow(
            final long streamId,
            final int update)
        {
            final WindowFW window = windowRW.wrap(throttleBuffer, 0, throttleBuffer.capacity())
                    .streamId(streamId)
                    .update(update)
                    .build();
            sharedState.clientConnectStreams.writeThrottle(window.typeId(), window.buffer(), window.offset(), window.sizeof());
        }
    }

    @State(Scope.Thread)
    public static class RemoteWriterState
    {
        private SharedState sharedState;
        private BooleanSupplier measurementEnded;
        private LongSupplier supplyStreamId;

        private final WindowFW windowRO = new WindowFW();

        private final BeginFW.Builder beginRW = new BeginFW.Builder();
        private final DataFW.Builder dataRW = new DataFW.Builder();
        private final EndFW.Builder endRW = new EndFW.Builder();

        private long streamId;
        int availableWindow;

        @Setup(Level.Iteration)
        public void reinit(SharedState state, Control control) throws Exception
        {
            this.sharedState = state;
            this.measurementEnded = () -> control.stopMeasurement;
            this.supplyStreamId = state.supplyStreamId();

            final AtomicBuffer outputBeginBuffer = new UnsafeBuffer(new byte[256]);
            beginRW.wrap(outputBeginBuffer, 0, outputBeginBuffer.capacity())
            .source(state.clientConnect)
            .sourceRef(0L)
            .extension(e -> e.reset());

            final AtomicBuffer outputDataBuffer = new UnsafeBuffer(new byte[256]);
            dataRW.wrap(outputDataBuffer, 0, outputDataBuffer.capacity())
                        .payload(p -> p.set(RESPONSE_BYTES))
                        .extension(e -> e.reset());

            final AtomicBuffer outputEndBuffer = new UnsafeBuffer(new byte[20]);
            endRW.wrap(outputDataBuffer, 0, outputEndBuffer.capacity())
                        .extension(e -> e.reset());
        }

        boolean writeBegin(long correlationId)
        {
            streamId = supplyStreamId.getAsLong();
            availableWindow = 0;
            beginRW.streamId(streamId).correlationId(correlationId);
            BeginFW begin = beginRW.build();
            boolean result = false;
            HttpStreams clientConnectReplyStreams = sharedState.clientConnectReplyStreams;
            while (!measurementEnded.getAsBoolean() && !result)
            {
                result = clientConnectReplyStreams.writeStreams(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
            }
            return result;
        }

        boolean writeResponse()
        {
            dataRW.streamId(streamId);
            DataFW data = dataRW.build();
            boolean result = false;
            HttpStreams clientConnectReplyStreams = sharedState.clientConnectReplyStreams;
            while (!result && !measurementEnded.getAsBoolean())
            {
                clientConnectReplyStreams.readThrottle(this::throttle);
                result = availableWindow >= data.length();
                if (result)
                {
                    result = clientConnectReplyStreams.writeStreams(data.typeId(), data.buffer(), 0, data.limit());
                    if (result)
                    {
                        availableWindow -= data.length();
                    }
                    else
                    {
                        String error = format("write failed, availableWindow = %d", availableWindow);
                        System.out.println(error);
                        throw new RuntimeException(error);
                    }
                }
            }
            return result;
        }

        boolean writeEnd()
        {
            endRW.streamId(streamId);
            EndFW end = endRW.build();
            boolean result = false;
            HttpStreams clientConnectReplyStreams = sharedState.clientConnectReplyStreams;
            while (!measurementEnded.getAsBoolean() && !result)
            {
                result = clientConnectReplyStreams.writeStreams(end.typeId(), end.buffer(), end.offset(), end.sizeof());
            }
            return result;
        }

        private void throttle(
                int msgTypeId,
                MutableDirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                windowRO.wrap(buffer, index, index + length);
                if (windowRO.streamId() == streamId)
                {
                    availableWindow += windowRO.update();
                }
                break;
            case ResetFW.TYPE_ID:
                System.out.println("WARNING: reset detected in remote writer throttle");
                break;
            default:
                System.out.println(format("ERROR: unexpected msgTypeId %d detected in remote writer throttle",
                        msgTypeId));
                break;
            }
        }
    }

    @Benchmark
    @Group("throughput")
    @GroupThreads(1)
    public int requestWriter(final RequestWriterState state, final Control control) throws Exception
    {
        int result;
        boolean full = false;
        state.requestCount++;
        int firstFullRequest = 0;
        while ((result = state.writeRequest()) == 0 && !control.stopMeasurement)
        {
            if (!full)
            {
                firstFullRequest = state.requestCount;
            }
            full = true;
            state.idleStrategy.idle(result);
        }
        if (full)
        {
            System.out.println(format("Ring buffer full while writing request %d", firstFullRequest));
        }
        return result;
    }

    @Benchmark
    @Group("throughput")
    @GroupThreads(1)
    public int responseReader(final ResponseReaderState state, final Control control) throws Exception
    {
        int result;
        while ((result = state.readResponse()) == 0 && !control.stopMeasurement)
        {
            Thread.yield();
        }
        return result;
    }

    @Benchmark
    @Group("throughput")
    @GroupThreads(1)
    public int remoteEcho(final RemoteReaderState reader, RemoteWriterState writer, final Control control)
            throws Exception
    {
        int result;
        while ((result = reader.processRequests(writer)) == 0 && !control.stopMeasurement)
        {
            Thread.yield();
        }
        return result;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
                .include(HttpClientBM.class.getSimpleName())
                .forks(0)
                .threads(1)
                .warmupIterations(1)
                .measurementIterations(1)
                .measurementTime(new TimeValue(10, SECONDS))
                .build();

        new Runner(opt).run();
    }
}
