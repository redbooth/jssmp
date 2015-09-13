/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.aerofs.ssmp.SSMPEvent.Type;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.aerofs.ssmp.SSMPDecoder.*;
import static com.aerofs.ssmp.SSMPEvent.*;

public class SSMPResponseDecoder extends FrameDecoder {
    private final static Logger L = LoggerFactory.getLogger(SSMPResponseDecoder.class);

    private final ElapsedTimer _timer = new ElapsedTimer();

    @Override
    public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
        if (e instanceof IdleStateEvent) {
            channelIdle(ctx, (IdleStateEvent) e);
        } else {
            super.handleUpstream(ctx, e);
        }
    }

    private static final ChannelBuffer PING = ChannelBuffers.wrappedBuffer(
            "PING\n".getBytes(StandardCharsets.US_ASCII));
    private static final ChannelBuffer PONG = ChannelBuffers.wrappedBuffer(
            "PONG\n".getBytes(StandardCharsets.US_ASCII));

    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) {
        if (e.getState() == IdleState.READER_IDLE) {
            if (_timer.elapsed() > TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS)) {
                ctx.getChannel().close();
            } else {
                L.debug("send ping");
                ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                        new DefaultChannelFuture(ctx.getChannel(), false), PING, null));
            }
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) throws Exception {
        if (!ctx.getChannel().isConnected()) {
            L.info("drop msg on disconnected channel");
            return;
        }
        _timer.restart();
        super.messageReceived(ctx, me);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer b)
            throws Exception {
        final int readerIndex = b.readerIndex();
        try {
            int code = readCode(b);
            if (code == 0) {
                if (!next(b)) throw new IllegalArgumentException();
                SSMPIdentifier from = readIdentifier(b);
                if (!next(b)) throw new IllegalArgumentException();
                byte[] verb = readVerb(b);
                SSMPEvent.Type type = Type.byName(verb);

                if (type == Type.PING) {
                    if (next(b)) throw new IllegalArgumentException();
                    L.debug("recv ping");
                    ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                            new DefaultChannelFuture(ctx.getChannel(), false), PONG, null));
                    return null;
                } else if (type == Type.PONG) {
                    if (next(b)) throw new IllegalArgumentException();
                    L.debug("recv pong");
                    return null;
                }

                SSMPIdentifier to = null;
                if ((type._fields & FIELD_TO) != 0) {
                    if (!next(b)) throw new IllegalArgumentException();
                    to = readIdentifier(b);
                }
                byte[] payload = null;
                boolean binary = false;
                if ((type._fields & FIELD_PAYLOAD) != 0) {
                    boolean atEnd = atEnd(b);
                    if (atEnd) {
                        if ((type._fields & FIELD_OPTION) != FIELD_OPTION) {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        next(b);
                        binary = isBinaryPayload(b);
                        payload = readPayload(b);
                    }
                }
                if (next(b)) throw new IllegalArgumentException();
                return new SSMPEvent(from, type, to, payload, binary);
            }

            String payload = null;
            if (next(b)) {
                payload = new String(readPayload(b), StandardCharsets.UTF_8);
                if (next(b)) throw new IllegalArgumentException();
            }
            return new SSMPResponse(code, payload);
        } catch (EOFException e) {
            // reset reader index if we failed to read a full message
            b.readerIndex(readerIndex);
        } catch (IllegalArgumentException e) {
            L.info("invalid message: {}",
                    b.slice(readerIndex, b.writerIndex() - readerIndex).copy().array());
            ctx.getChannel().close();
        }
        return null;
    }

}
