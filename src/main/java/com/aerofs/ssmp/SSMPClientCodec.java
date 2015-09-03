/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.aerofs.ssmp.SSMPEvent.Type;
import com.google.common.util.concurrent.SettableFuture;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static com.aerofs.ssmp.SSMPDecoder.*;
import static com.aerofs.ssmp.SSMPEvent.*;

public class SSMPClientCodec extends SimpleChannelHandler {
    private final static Logger L = LoggerFactory.getLogger(SSMPClientCodec.class);

    private final EventHandler _handler;

    private final ElapsedTimer _timer = new ElapsedTimer();
    private final Queue<SettableFuture<SSMPResponse>> _responses = new ConcurrentLinkedQueue<>();

    public SSMPClientCodec(EventHandler handler) {
        _handler = handler;
    }

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
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        if (!(e instanceof ClosedChannelException)) {
            L.warn("uncaught exception {}", e.getCause());
        }
        ctx.getChannel().close();
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
        if (!ctx.getChannel().isConnected()) {
            L.info("drop msg on disconnected channel");
            return;
        }
        _timer.restart();

        ChannelBuffer b = (ChannelBuffer)me.getMessage();

        int code = readCode(b);
        skipSpace(b);
        if (code == 0) {
            SSMPIdentifier from = readIdentifier(b);
            SSMPEvent.Type type = readEventType(b);

            if (type == Type.PING) {
                L.debug("recv ping");
                ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                        new DefaultChannelFuture(ctx.getChannel(), false), PONG, null));
                return;
            } else if (type == Type.PONG) {
                L.debug("recv pong");
                return;
            }

            SSMPIdentifier to = null;
            if ((type._fields & FIELD_TO) != 0) {
                to = readIdentifier(b);
            }
            String payload = null;
            if ((type._fields & FIELD_PAYLOAD) != 0) {
                payload = readPayload(b);
                if (payload.isEmpty() && (type._fields & FIELD_OPTION) == FIELD_PAYLOAD) {
                    throw new IllegalArgumentException();
                }
            }
            L.debug("recv event {} {} {} {}", from, type, to, payload);
            _handler.eventReceived(new SSMPEvent(from, type, to, payload));
        } else {
            L.debug("recv response {}", code);
            SettableFuture<SSMPResponse> f = _responses.remove();
            f.set(new SSMPResponse(code, readPayload(b)));
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent me) {
        Object o = me.getMessage();
        if (o instanceof SSMPClient.Message) {
            SSMPClient.Message m = (SSMPClient.Message)o;
            _responses.add(m.f);

            ChannelBuffer b = ChannelBuffers.dynamicBuffer();
            b.writeBytes(m.r.type._s);
            if (m.r.to != null) {
                b.writeByte(' ');
                b.writeBytes(m.r.to.getBytes());
            }
            if (m.r.payload != null && m.r.payload.length > 0) {
                b.writeByte(' ');
                b.writeBytes(m.r.payload);
            }
            b.writeByte('\n');
            ctx.sendDownstream(new DownstreamMessageEvent(me.getChannel(), me.getFuture(), b, null));
        } else {
            ctx.sendDownstream(me);
        }
    }
}
