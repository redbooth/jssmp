/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.aerofs.ssmp.SSMPDecoder.*;
import static com.aerofs.ssmp.SSMPDecoder.readIdentifier;
import static com.aerofs.ssmp.SSMPDecoder.readPayload;

public class SSMPServerCodec extends SimpleChannelHandler {
    private final static Logger L = LoggerFactory.getLogger(SSMPServerCodec.class);

    private final Authenticator _auth;

    public interface Authenticator {
        boolean authenticate(SSMPIdentifier id, SSMPIdentifier scheme, String cred);

        ChannelBuffer unauthorized();
    }

    public static class IdAddress extends SocketAddress {
        static final long serialVersionUID = -1;
        public final SSMPIdentifier id;
        IdAddress(SSMPIdentifier id) {
            this.id = id;
        }
    }

    static class ChannelData extends IdAddress {
        static final long serialVersionUID = -1;
        private final ElapsedTimer timer = new ElapsedTimer();
        ChannelData(SSMPIdentifier id) {
            super(id);
        }
    }

    public SSMPServerCodec(Authenticator auth) {
        _auth = auth;
    }

    @Override
    public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
        if (e instanceof IdleStateEvent) {
            channelIdle(ctx, (IdleStateEvent) e);
        } else {
            super.handleUpstream(ctx, e);
        }
    }

    private static final ChannelBuffer OK = ChannelBuffers.wrappedBuffer(
            "200\n".getBytes(StandardCharsets.US_ASCII));
    private static final ChannelBuffer BAD_REQUEST = ChannelBuffers.wrappedBuffer(
            "400\n".getBytes(StandardCharsets.US_ASCII));
    private static final ChannelBuffer NOT_ALLOWED = ChannelBuffers.wrappedBuffer(
            "405\n".getBytes(StandardCharsets.US_ASCII));
    private static final ChannelBuffer NOT_IMPLEMENTED = ChannelBuffers.wrappedBuffer(
            "501\n".getBytes(StandardCharsets.US_ASCII));

    private static final ChannelBuffer PING = ChannelBuffers.wrappedBuffer(
            "000 . PING\n".getBytes(StandardCharsets.US_ASCII));
    private static final ChannelBuffer PONG = ChannelBuffers.wrappedBuffer(
            "000 . PONG\n".getBytes(StandardCharsets.US_ASCII));

    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) {
        if (e.getState() == IdleState.READER_IDLE) {
            Object a = ctx.getChannel().getAttachment();
            // connect/login timeout
            if (a == null || a instanceof ChannelFuture) {
                ctx.getChannel().close();
                return;
            }
            ChannelData d = (ChannelData)a;
            if (d.timer.elapsed() > TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS)) {
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
        L.warn("uncaught exception", e.getCause());
        ctx.getChannel().close();
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        // drop, wait for LOGIN
        Object d = ctx.getChannel().getAttachment();
        if (d != null) throw new IllegalStateException();
        ctx.getChannel().setAttachment(e.getFuture());
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        Object d = ctx.getChannel().getAttachment();
        if (d != null && d instanceof ChannelFuture) {
            ((ChannelFuture) d).setFailure(new ClosedChannelException());
        }
        ctx.sendUpstream(e);
    }

    private void sendDownstream(ChannelHandlerContext ctx, ChannelBuffer r) {
        ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                new DefaultChannelFuture(ctx.getChannel(), false), r, null));
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
        Object o = ctx.getChannel().getAttachment();

        ChannelBuffer b = (ChannelBuffer)me.getMessage();

        try {
            SSMPRequest.Type type = readRequestType(b);

            if (o instanceof ChannelFuture) {
                if (type != SSMPRequest.Type.LOGIN) {
                    sendDownstream(ctx, BAD_REQUEST);
                    ctx.getChannel().close();
                    return;
                }

                SSMPIdentifier id = readIdentifier(b);
                SSMPIdentifier scheme = readIdentifier(b);
                String cred = readPayload(b);
                if (_auth.authenticate(id, scheme, cred)) {
                    ChannelData d = new ChannelData(id);
                    ctx.getChannel().setAttachment(d);
                    ctx.sendUpstream(new UpstreamChannelStateEvent(me.getChannel(),
                            ChannelState.CONNECTED, true));
                    ((ChannelFuture)o).setSuccess();
                    sendDownstream(ctx, OK);
                } else {
                    sendDownstream(ctx, _auth.unauthorized());
                    ctx.getChannel().close();
                }
                return;
            }

            if (type == null) {
                sendDownstream(ctx, NOT_IMPLEMENTED);
                return;
            } else if (type == SSMPRequest.Type.LOGIN) {
                sendDownstream(ctx, NOT_ALLOWED);
                return;
            } else if (type == SSMPRequest.Type.PING) {
                L.debug("recv ping");
                sendDownstream(ctx, PONG);
                return;
            } else if (type == SSMPRequest.Type.PONG) {
                L.debug("recv pong");
                return;
            }

            ChannelData d = (ChannelData)o;

            SSMPIdentifier to = null;
            if ((type._fields & SSMPRequest.FIELD_ID) != 0) {
                to = readIdentifier(b);
            }
            byte[] payload = null;
            if ((type._fields & SSMPRequest.FIELD_PAYLOAD) != 0) {
                payload = readPayloadBytes(b);
                if (payload.length == 0
                        && (type._fields & SSMPRequest.FIELD_OPTION) != SSMPRequest.FIELD_OPTION) {
                    throw new IllegalArgumentException();
                }
            }

            ctx.sendUpstream(new UpstreamMessageEvent(me.getChannel(),
                    new SSMPRequest(type, to, payload), d));
        } catch (IllegalArgumentException e) {
            sendDownstream(ctx, BAD_REQUEST);
        }
    }

    private final static byte[] EVENT_CODE = "000 ".getBytes(StandardCharsets.US_ASCII);

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent me) {
        Object o = me.getMessage();
        if (o instanceof SSMPResponse) {
            SSMPResponse r = (SSMPResponse)o;

            ChannelBuffer b = ChannelBuffers.dynamicBuffer();
            if (r.code < 0 || r.code > 999) {
                throw new IllegalArgumentException("invalid response code: " + r.code);
            }

            int n = r.code;
            b.writeByte('0' + (byte)(n / 100));
            b.writeByte('0' + (byte)(n / 10 % 10));
            b.writeByte('0' + (byte)(n % 10));
            if (r.payload != null && !r.payload.isEmpty()) {
                b.writeByte(' ');
                b.writeBytes(r.payload.getBytes(StandardCharsets.UTF_8));
            }
            b.writeByte('\n');
            ctx.sendDownstream(new DownstreamMessageEvent(me.getChannel(), me.getFuture(), b, null));
        } else if (o instanceof SSMPEvent) {
            SSMPEvent ev = (SSMPEvent)o;

            ChannelBuffer b = ChannelBuffers.dynamicBuffer();
            b.writeBytes(EVENT_CODE);
            b.writeBytes(ev.from.getBytes());
            b.writeByte(' ');
            b.writeBytes(ev.type.toString().getBytes(StandardCharsets.US_ASCII));
            if (ev.to != null) {
                b.writeByte(' ');
                b.writeBytes(ev.to.getBytes());
            }
            if (ev.payload != null && !ev.payload.isEmpty()) {
                b.writeByte(' ');
                b.writeBytes(ev.payload.getBytes(StandardCharsets.UTF_8));
            }
            b.writeByte('\n');
            ctx.sendDownstream(new DownstreamMessageEvent(me.getChannel(), me.getFuture(), b, null));
        } else {
            ctx.sendDownstream(me);
        }
    }
}
