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
package org.reaktivity.nukleus.http.internal.routable.stream;

import static java.lang.Integer.parseInt;
import static org.reaktivity.nukleus.http.internal.routable.Route.headersMatch;
import static org.reaktivity.nukleus.http.internal.routable.stream.Slab.NO_SLOT;
import static org.reaktivity.nukleus.http.internal.router.RouteKind.OUTPUT_ESTABLISHED;
import static org.reaktivity.nukleus.http.internal.util.BufferUtil.limitOfBytes;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.http.internal.routable.Correlation;
import org.reaktivity.nukleus.http.internal.routable.Route;
import org.reaktivity.nukleus.http.internal.routable.Source;
import org.reaktivity.nukleus.http.internal.routable.Target;
import org.reaktivity.nukleus.http.internal.types.OctetsFW;
import org.reaktivity.nukleus.http.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http.internal.types.stream.FrameFW;
import org.reaktivity.nukleus.http.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.http.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.http.internal.util.function.LongObjectBiConsumer;

public final class SourceInputStreamFactory
{
    private static final byte[] CRLFCRLF_BYTES = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final FrameFW frameRO = new FrameFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();

    private final Source source;
    private final LongFunction<List<Route>> supplyRoutes;
    private final LongSupplier supplyStreamId;
    private final Function<String, Target> supplyTarget;
    private final LongObjectBiConsumer<Correlation<?>> correlateNew;
    private final int maximumHeadersSize;
    private final Slab slab;
    private final MutableDirectBuffer temporarySlot;

    public SourceInputStreamFactory(
        Source source,
        LongFunction<List<Route>> supplyRoutes,
        LongSupplier supplyStreamId,
        Function<String, Target> supplyTarget,
        LongObjectBiConsumer<Correlation<?>> correlateNew,
        Slab slab)
    {
        this.source = source;
        this.supplyRoutes = supplyRoutes;
        this.supplyStreamId = supplyStreamId;
        this.supplyTarget = supplyTarget;
        this.correlateNew = correlateNew;
        this.slab = slab;
        this.maximumHeadersSize = slab.slotCapacity();
        this.temporarySlot = new UnsafeBuffer(ByteBuffer.allocateDirect(slab.slotCapacity()));
    }

    public MessageHandler newStream()
    {
        return new SourceInputStream()::handleStream;
    }

    private final class SourceInputStream
    {
        private MessageHandler streamState;
        private MessageHandler throttleState;
        private DecoderState decoderState;
        private int slotIndex = NO_SLOT;
        private int slotOffset = 0;
        private int slotPosition;
        private boolean endDeferred;

        private long sourceId;
        private long sourceCorrelationId;

        private Target target;
        private long targetId;
        private long sourceRef;
        private int window;
        private int contentRemaining;
        private int availableTargetWindow;
        private boolean hasUpgrade;
        private Correlation<OutputEstablishedState> correlation;

        @Override
        public String toString()
        {
            return String.format("%s[source=%s, sourceId=%016x, window=%d, targetId=%016x]",
                    getClass().getSimpleName(), source.routableName(), sourceId, window, targetId);
        }

        private SourceInputStream()
        {
            this.streamState = this::streamBeforeBegin;
            this.throttleState = this::throttleIgnoreWindow;
        }

        private void handleStream(
            int msgTypeId,
            MutableDirectBuffer buffer,
            int index,
            int length)
        {
            streamState.onMessage(msgTypeId, buffer, index, length);
        }

        private void streamBeforeBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                processBegin(buffer, index, length);
            }
            else
            {
                processUnexpected(buffer, index, length);
            }
        }

        private void streamWithDeferredData(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                deferAndProcessData(buffer, index, length);
                break;
            case EndFW.TYPE_ID:
                deferEnd(buffer, index, length);
                break;
            default:
                processUnexpected(buffer, index, length);
                break;
            }
        }

        private void streamAfterBeginOrData(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                processData(buffer, index, length);
                if (slotIndex != NO_SLOT)
                {
                    streamState = this::streamWithDeferredData;
                }
                break;
            case EndFW.TYPE_ID:
                processEnd(buffer, index, length);
                break;
            default:
                processUnexpected(buffer, index, length);
                break;
            }
        }

        private void streamAfterEnd(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            processUnexpected(buffer, index, length);
        }

        private void streamAfterRejectOrReset(
            int msgTypeId,
            MutableDirectBuffer buffer,
            int index,
            int length)
        {
            if (msgTypeId == DataFW.TYPE_ID)
            {
                dataRO.wrap(buffer, index, index + length);
                final long streamId = dataRO.streamId();

                source.doWindow(streamId, dataRO.length());
            }
            else if (msgTypeId == EndFW.TYPE_ID)
            {
                endRO.wrap(buffer, index, index + length);
                final long streamId = endRO.streamId();

                source.removeStream(streamId);

                this.streamState = this::streamAfterEnd;
            }
        }

        private void processUnexpected(
            DirectBuffer buffer,
            int index,
            int length)
        {
            frameRO.wrap(buffer, index, index + length);
            long streamId = frameRO.streamId();

            processUnexpected(streamId);
        }

        private void processUnexpected(
            long streamId)
        {
            source.doReset(streamId);

            this.streamState = this::streamAfterRejectOrReset;
        }

        private void processInvalidRequest(
            int requestBytes,
            String payloadChars)
        {
            Target rejectTarget = supplyTarget.apply(source.name());
            this.target = rejectTarget;

            final long newTargetId = supplyStreamId.getAsLong();

            // TODO: replace with connection pool (start)
            rejectTarget.doBegin(newTargetId, 0L, sourceCorrelationId);
            rejectTarget.setThrottle(newTargetId, this::handleThrottle);
            // TODO: replace with connection pool (end)

            DirectBuffer payload = new UnsafeBuffer(payloadChars.getBytes(StandardCharsets.UTF_8));

            this.decoderState = this::decodeHttpBegin;
            this.streamState = this::streamAfterRejectOrReset;
            this.throttleState = new MessageHandler()
            {
                int offset = 0;

                @Override
                public void onMessage(int msgTypeId, MutableDirectBuffer buffer, int index, int length)
                {
                    switch (msgTypeId)
                    {
                    case WindowFW.TYPE_ID:
                        windowRO.wrap(buffer, index, index + length);
                        int update = windowRO.update();
                        int writableBytes = Math.min(update, payload.capacity() - offset);
                        rejectTarget.doData(newTargetId, payload, offset, writableBytes);
                        offset += writableBytes;
                        if (offset == payload.capacity())
                        {
                            throttleState = SourceInputStream.this::throttleIgnoreWindow;
                        }
                        break;
                    case ResetFW.TYPE_ID:
                        processReset(buffer, index, length);
                        break;
                    default:
                        // ignore
                        break;
                    }
                }
            };
            doSourceWindow(requestBytes);
        }

        private void processBegin(
            DirectBuffer buffer,
            int index,
            int length)
        {
            beginRO.wrap(buffer, index, index + length);

            this.sourceId = beginRO.streamId();
            this.sourceRef = beginRO.referenceId();
            this.sourceCorrelationId = beginRO.correlationId();

            this.streamState = this::streamAfterBeginOrData;
            this.decoderState = this::decodeHttpBegin;

            doSourceWindow(maximumHeadersSize);
        }

        private void processData(
            DirectBuffer buffer,
            int index,
            int length)
        {
            dataRO.wrap(buffer, index, index + length);

            window -= dataRO.length();

            if (window < 0)
            {
                processUnexpected(buffer, index, length);
            }
            else
            {
                final OctetsFW payload = dataRO.payload();
                final int limit = payload.limit();
                int offset = payload.offset();
                decode(payload.buffer(), offset, limit);
            }
        }

        private void decode(DirectBuffer buffer, int offset, int limit)
        {
            while (offset < limit)
            {
                offset = decoderState.decode(buffer, offset, limit);
            }
        }

        private void processEnd(
            DirectBuffer buffer,
            int index,
            int length)
        {
            endRO.wrap(buffer, index, index + length);
            final long streamId = endRO.streamId();
            assert streamId == sourceId;
            doEnd();
        }

        private void doEnd()
        {
            decoderState = (b, o, l) -> o;
            streamState = this::streamAfterEnd;

            source.removeStream(sourceId);
            target.removeThrottle(targetId);
            slab.release(slotIndex);

            if (correlation != null)
            {
                correlation.state().doEnd(supplyTarget);
            }
        }

        private void deferAndProcessData(
            DirectBuffer buffer,
            int index,
            int length)
        {
            dataRO.wrap(buffer, index, index + length);

            window -= dataRO.length();

            if (window < 0)
            {
                processUnexpected(buffer, index, length);
            }
            else
            {
                final OctetsFW payload = dataRO.payload();
                final int offset = payload.offset();
                final int dataLength = payload.limit() - offset;
                if (slotPosition + dataLength > slab.slotCapacity())
                {
                    alignSlotData();
                }
                MutableDirectBuffer slot = slab.buffer(slotIndex);
                slot.putBytes(slotPosition, payload.buffer(), offset, dataLength);
                slotPosition += dataLength;
                processDeferredData();
            }
        }

        private void processDeferredData()
        {
            MutableDirectBuffer slot = slab.buffer(slotIndex);
            decode(slot, slotOffset, slotPosition);
            if (slotOffset == slotPosition)
            {
                slab.release(slotIndex);
                slotIndex = NO_SLOT;
                streamState = this::streamAfterBeginOrData;
                if (endDeferred)
                {
                    doEnd();
                }
            }
        }

        private void deferEnd(
            DirectBuffer buffer,
            int index,
            int length)
        {
            endRO.wrap(buffer, index, index + length);
            final long streamId = endRO.streamId();
            assert streamId == sourceId;

            endDeferred = true;
        }

        private void alignSlotData()
        {
            int dataLength = slotPosition - slotOffset;
            MutableDirectBuffer slot = slab.buffer(slotIndex);
            temporarySlot.putBytes(0, slot, slotOffset, dataLength);
            slot.putBytes(0, temporarySlot, 0, dataLength);
            slotOffset = 0;
            slotPosition = dataLength;
        }

        private int decodeHttpBegin(
            final DirectBuffer payload,
            final int offset,
            final int limit)
        {
            int result = limit;
            final int endOfHeadersAt = limitOfBytes(payload, offset, limit, CRLFCRLF_BYTES);
            if (endOfHeadersAt == -1)
            {
                int length = limit - offset;
                if (slotIndex == NO_SLOT)
                {
                    // Incomplete request, not yet cached
                    slotIndex = slab.acquire(sourceId);
                    slotOffset = 0;
                    MutableDirectBuffer buffer = slab.buffer(slotIndex);
                    buffer.putBytes(0, payload, offset, length);
                    slotPosition = length;
                }
                if (window == 0)
                {
                    // Increase source window to ensure we can receive the largest possible request headers
                    ensureSourceWindow(maximumHeadersSize - length);
                    if (window < 2)
                    {
                        slab.release(slotIndex);
                        processInvalidRequest(limit - offset, "HTTP/1.1 431 Request Header Fields Too Large\r\n\r\n");
                        source.doReset(sourceId);
                    }
                }
            }
            else
            {
                decodeCompleteHttpBegin(payload, offset, endOfHeadersAt - offset);
                result = slotOffset = endOfHeadersAt;
            }
            return result;
        }

        private void decodeCompleteHttpBegin(
            final DirectBuffer payload,
            final int offset,
            final int length)
        {
            // TODO: replace with lightweight approach (start)
            String[] lines = payload.getStringWithoutLengthUtf8(offset, length).split("\r\n");
            String[] start = lines[0].split("\\s+");

            if (start.length != 3)
            {
                processInvalidRequest(length, "HTTP/1.1 400 Bad Request\r\n\r\n");
                return;
            }

            Pattern versionPattern = Pattern.compile("HTTP/1\\.(\\d)");
            Matcher versionMatcher = versionPattern.matcher(start[2]);
            if (!versionMatcher.matches())
            {
                processInvalidRequest(length, "HTTP/1.1 505 HTTP Version Not Supported\r\n\r\n");
            }
            else
            {
                final URI requestURI = URI.create(start[1]);

                final Map<String, String> headers = decodeHttpHeaders(start, lines, requestURI);
                // TODO: replace with lightweight approach (end)

                if (headers.get(":authority") == null || requestURI.getUserInfo() != null)
                {
                    processInvalidRequest(length, "HTTP/1.1 400 Bad Request\r\n\r\n");
                }
                else
                {
                    final Optional<Route> optional = resolveTarget(sourceRef, headers);
                    if (optional.isPresent())
                    {

                        final Route route = optional.get();
                        final Target newTarget = route.target();
                        final long targetRef = route.targetRef();
                        final long newTargetId = supplyStreamId.getAsLong();

                        if (correlation == null)
                        {
                            long newOutputEstablishedStreamId = supplyStreamId.getAsLong();
                            OutputEstablishedState state = new OutputEstablishedState(newOutputEstablishedStreamId,
                                    newTarget.name());
                            this.correlation = new Correlation<OutputEstablishedState>(sourceCorrelationId, source.routableName(),
                                    OUTPUT_ESTABLISHED, state);
                        }
                        else
                        {
                            correlation.state().target = newTarget.name();
                        }
                        final long targetCorrelationId = newTargetId;
                        correlation.state().pendingRequests++;
                        correlateNew.accept(targetCorrelationId, correlation);

                        availableTargetWindow = 0;
                        newTarget.doHttpBegin(newTargetId, targetRef, newTargetId,
                                hs -> headers.forEach((k, v) -> hs.item(i -> i.name(k).value(v))));
                        newTarget.setThrottle(newTargetId, this::handleThrottle);

                        this.target = newTarget;
                        this.targetId = newTargetId;

                        hasUpgrade = headers.containsKey("upgrade");
                        if (hasUpgrade)
                        {
                            // TODO: wait for 101 first
                            decoderState = this::decodeHttpDataAfterUpgrade;
                            throttleState = this::throttleForHttpDataAfterUpgrade;
                        }
                        else if ((contentRemaining = parseInt(headers.getOrDefault("content-length", "0"))) > 0)
                        {
                            decoderState = this::decodeHttpData;
                            throttleState = this::throttleForHttpData;
                        }
                        else
                        {
                            // no content
                            target.doHttpEnd(targetId);
                        }
                    }
                    else
                    {
                        processInvalidRequest(length, "HTTP/1.1 404 Not Found\r\n\r\n");
                    }
                }
            }
        }

        private Map<String, String> decodeHttpHeaders(
            String[] start,
            String[] lines,
            URI requestURI)
        {
            String authority = requestURI.getAuthority();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(":scheme", "http");
            headers.put(":method", start[0]);
            headers.put(":path", requestURI.getPath());

            if (authority != null)
            {
                headers.put(":authority", authority);
            }

            Pattern headerPattern = Pattern.compile("([^\\s:]+)\\s*:\\s*(.*)");
            for (int i = 1; i < lines.length; i++)
            {
                Matcher headerMatcher = headerPattern.matcher(lines[i]);
                if (!headerMatcher.matches())
                {
                    throw new IllegalStateException("illegal http header syntax: " + lines[i]);
                }

                String name = headerMatcher.group(1).toLowerCase();
                String value = headerMatcher.group(2);

                // rfc7230#section-5.5
                if ("host".equals(name))
                {
                    if (authority == null)
                    {
                        headers.put(":authority", value);
                    }
                }
                else
                {
                    headers.put(name, value);
                }
            }

            return headers;
        }

        private int decodeHttpData(
            DirectBuffer payload,
            final int offset,
            final int limit)
        {
            final int length = limit - offset;

            // TODO: consider chunks
            int writableBytes = Math.min(length, contentRemaining);
            writableBytes = Math.min(availableTargetWindow, writableBytes);

            if (writableBytes > 0)
            {
                target.doHttpData(targetId, payload, offset, writableBytes);
                availableTargetWindow -= writableBytes;
                contentRemaining -= writableBytes;
                slotOffset += writableBytes;
            }
            if (slotIndex == NO_SLOT && writableBytes < length)
            {
                slotIndex = slab.acquire(sourceId);
                slotOffset = 0;
                MutableDirectBuffer buffer = slab.buffer(slotIndex);
                buffer.putBytes(0, payload, offset, length);
                slotPosition = length;
            }
            if (contentRemaining == 0)
            {
                target.doHttpEnd(targetId);
                decoderState = this::decodeHttpBegin;
                throttleState = this::throttleIgnoreWindow;
                if (writableBytes < length)
                {
                    processDeferredData();
                }
            }
            return limit;
        }

        private int decodeHttpDataAfterUpgrade(
            DirectBuffer payload,
            int offset,
            int limit)
        {
            final int length = limit - offset;
            int writableBytes = Math.min(length, availableTargetWindow);
            if (writableBytes > 0)
            {
                target.doHttpData(targetId, payload, offset, writableBytes);
                availableTargetWindow -= writableBytes;
                slotOffset += writableBytes;
            }
            if (slotIndex == NO_SLOT && writableBytes < length)
            {
                slotIndex = slab.acquire(sourceId);
                slotOffset = 0;
                MutableDirectBuffer buffer = slab.buffer(slotIndex);
                buffer.putBytes(0, payload, offset, length);
                slotPosition = length;
            }
            return limit;
        }

        @SuppressWarnings("unused")
        private int decodeHttpEnd(
            DirectBuffer payload,
            int offset,
            int limit)
        {
            // TODO: consider chunks, trailers
            target.doHttpEnd(targetId);
            return limit;
        }

        private Optional<Route> resolveTarget(
            long sourceRef,
            Map<String, String> headers)
        {
            final List<Route> routes = supplyRoutes.apply(sourceRef);
            final Predicate<Route> predicate = headersMatch(headers);

            return routes.stream().filter(predicate).findFirst();
        }

        private void handleThrottle(
            int msgTypeId,
            MutableDirectBuffer buffer,
            int index,
            int length)
        {
            // Ignore frames from a previous target input stream that has now ended
            frameRO.wrap(buffer, index, index + length);
            long streamId = frameRO.streamId();
            if (streamId == targetId)
            {
                throttleState.onMessage(msgTypeId, buffer, index, length);
            }
        }

        private void throttleIgnoreWindow(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case ResetFW.TYPE_ID:
                processReset(buffer, index, length);
                break;
            default:
                // ignore
                break;
            }
        }

        private void throttleForHttpData(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                processWindowForHttpData(buffer, index, length);
                break;
            case ResetFW.TYPE_ID:
                processReset(buffer, index, length);
                break;
            default:
                // ignore
                break;
            }
        }

        private void throttleForHttpDataAfterUpgrade(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                processWindowForHttpDataAfterUpgrade(buffer, index, length);
                break;
            case ResetFW.TYPE_ID:
                processReset(buffer, index, length);
                break;
            default:
                // ignore
                break;
            }
        }

        private void throttlePropagateWindow(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                propagateWindow(buffer, index, length);
                break;
            case ResetFW.TYPE_ID:
                processReset(buffer, index, length);
                break;
            default:
                // ignore
                break;
            }
        }

        private void processWindowForHttpData(DirectBuffer buffer, int index, int length)
        {
            windowRO.wrap(buffer, index, index + length);
            int update = windowRO.update();

            availableTargetWindow += update;
            if (slotIndex != NO_SLOT)
            {
                processDeferredData();
            }
            ensureSourceWindow(Math.min(availableTargetWindow, slab.slotCapacity()));
        }

        private void processWindowForHttpDataAfterUpgrade(DirectBuffer buffer, int index, int length)
        {
            windowRO.wrap(buffer, index, index + length);
            int update = windowRO.update();
            availableTargetWindow += update;
            if (slotIndex != NO_SLOT)
            {
                processDeferredData();
            }
            if (slotIndex == NO_SLOT)
            {
                ensureSourceWindow(availableTargetWindow);
                if (window == availableTargetWindow)
                {
                    throttleState = this::throttlePropagateWindow;
                }
            }
        }

        private void propagateWindow(
            DirectBuffer buffer,
            int index,
            int length)
        {
            windowRO.wrap(buffer, index, index + length);
            int update = windowRO.update();
            availableTargetWindow += update;
            doSourceWindow(update);
        }

        private void ensureSourceWindow(int requiredWindow)
        {
            if (requiredWindow > window)
            {
                int update = requiredWindow - window;
                doSourceWindow(update);
            }
        }

        private void doSourceWindow(int update)
        {
            this.window += update;
            source.doWindow(sourceId, update);
        }

        private void processReset(
            DirectBuffer buffer,
            int index,
            int length)
        {
            resetRO.wrap(buffer, index, index + length);
            slab.release(slotIndex);
            source.doReset(sourceId);
        }
    }

    @FunctionalInterface
    private interface DecoderState
    {
        int decode(DirectBuffer buffer, int offset, int length);
    }
}
