package com.aerofs.ssmp;

import com.aerofs.ssmp.SSMPRequest.Type;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.aerofs.ssmp.SSMPDecoder.*;
import static com.aerofs.ssmp.SSMPRequest.*;

public class SSMPRequestDecoder extends FrameDecoder {
    private final static Logger L = LoggerFactory.getLogger(SSMPRequestDecoder.class);

    private final Authenticator _auth;

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

    public SSMPRequestDecoder(Authenticator auth) {
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

    private void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) {
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
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer b)
            throws Exception {
        final int readerIndex = b.readerIndex();
        Object o = ctx.getChannel().getAttachment();
        try {
            byte[] verb = readVerb(b);
            SSMPRequest.Type type = Type.byName(verb);

            if (o instanceof ChannelFuture) {
                if (type != SSMPRequest.Type.LOGIN || !next(b)) {
                    throw new IllegalArgumentException();
                }
                SSMPIdentifier id = readIdentifier(b);
                if (!next(b)) throw new IllegalArgumentException();
                SSMPIdentifier scheme = readIdentifier(b);
                String cred = "";
                if (next(b)) {
                    cred = new String(readPayload(b), StandardCharsets.UTF_8);
                    if (next(b)) throw new IllegalArgumentException();
                }
                if (_auth.authenticate(id, scheme, cred)) {
                    ChannelData d = new ChannelData(id);
                    ctx.getChannel().setAttachment(d);
                    ctx.sendUpstream(new UpstreamChannelStateEvent(ctx.getChannel(),
                            ChannelState.CONNECTED, true));
                    ((ChannelFuture)o).setSuccess();
                    sendDownstream(ctx, OK);
                } else {
                    sendDownstream(ctx, _auth.unauthorized());
                    ctx.getChannel().close();
                }
                return null;
            }

            if (type == null) {
                skipCompat(b);
                sendDownstream(ctx, NOT_IMPLEMENTED);
                return null;
            } else if (type == SSMPRequest.Type.LOGIN) {
                sendDownstream(ctx, NOT_ALLOWED);
                ctx.getChannel().close();
                return null;
            } else if (type == SSMPRequest.Type.PING) {
                if (next(b)) throw new IllegalArgumentException();
                L.debug("recv ping");
                sendDownstream(ctx, PONG);
                return null;
            } else if (type == SSMPRequest.Type.PONG) {
                if (next(b)) throw new IllegalArgumentException();
                L.debug("recv pong");
                return null;
            }

            SSMPIdentifier to = null;
            if ((type._fields & SSMPRequest.FIELD_ID) != 0) {
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
            return new SSMPRequest(type, to, payload, binary);
        } catch (EOFException e) {
            // reset reader index if we failed to read a full message
            b.readerIndex(readerIndex);
        } catch (IllegalArgumentException e) {
            sendDownstream(ctx, BAD_REQUEST);
            ctx.getChannel().close();
        }
        return null;
    }
}
