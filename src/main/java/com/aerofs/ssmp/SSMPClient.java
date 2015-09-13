/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.google.common.util.concurrent.*;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SSMPClient {
    private final static Logger L = LoggerFactory.getLogger(SSMPClient.class);

    private final InetSocketAddress _addr;
    private final ClientBootstrap _bootstrap;

    private final AtomicReference<Channel> _channel = new AtomicReference<>();

    public interface ConnectionListener {
        void connected();
        void disconnected();
    }

    public SSMPClient(String host, int port, Timer timer,
                      ChannelFactory channelFactory,
                      SslHandlerFactory sslHandlerFactory,
                      EventHandler handler) {
        this(InetSocketAddress.createUnresolved(host, port), timer, channelFactory, sslHandlerFactory, handler);
    }

    public SSMPClient(InetSocketAddress addr, Timer timer,
                      ChannelFactory channelFactory,
                      SslHandlerFactory sslHandlerFactory,
                      EventHandler handler)
    {
        _addr = addr;
        _bootstrap = new ClientBootstrap(channelFactory);
        _bootstrap.setOption("connectTimeoutMillis", 5000);
        _bootstrap.setPipelineFactory(() -> Channels.pipeline(
                sslHandlerFactory.newSslHandler(),
                new IdleStateHandler(timer, 30, 0, 0, TimeUnit.SECONDS),
                new SSMPResponseDecoder(),
                new Dispatcher(handler)
        ));
    }

    public void connect(SSMPIdentifier id, SSMPIdentifier scheme, String cred, ConnectionListener l) {
        if (_channel.get() != null) throw new IllegalStateException();
        L.info("connecting {}", _addr);
        ChannelFuture cf = _bootstrap.connect(new InetSocketAddress(_addr.getHostName(), _addr.getPort()));
        cf.addListener(f -> {
            if (f.isSuccess()) {
                L.info("connected");
                _channel.set(f.getChannel());
                Futures.addCallback(request(SSMPRequest.login(id, scheme, cred)),
                        new FutureCallback<SSMPResponse>() {
                    @Override
                    public void onSuccess(SSMPResponse r) {
                        if (r.code != SSMPResponse.OK) {
                            L.warn("login failure {}", r.code);
                            f.getChannel().close();
                            return;
                        }
                        L.info("logged in");
                        l.connected();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        f.getChannel().close();
                    }
                }, MoreExecutors.sameThreadExecutor());
            } else {
                L.info("failed to connect", f.getCause());
            }
        });
        cf.getChannel().getCloseFuture().addListener(f -> {
            L.info("disconnected");
            _channel.set(null);
            l.disconnected();
        });
    }

    public void disconnect() {
        Channel c = _channel.get();
        if (c != null) c.close().awaitUninterruptibly();
    }

    static class Message {
        final SSMPRequest r;
        final SettableFuture<SSMPResponse> f;
        Message(SSMPRequest r, SettableFuture<SSMPResponse> f) {
            this.r = r;
            this.f = f;
        }
    }

    public ListenableFuture<SSMPResponse> request(SSMPRequest r) {
        SettableFuture<SSMPResponse> f = SettableFuture.create();
        Channel c = _channel.get();
        if (c == null) {
            f.setException(new ClosedChannelException());
        } else {
            c.write(new Message(r, f));
        }
        return f;
    }

    private static class Dispatcher extends SimpleChannelHandler {
        private final EventHandler _handler;
        private final Queue<SettableFuture<SSMPResponse>> _responses = new ConcurrentLinkedQueue<>();

        Dispatcher(EventHandler handler) { _handler = handler; }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
            SettableFuture<SSMPResponse> f;
            while ((f = _responses.poll()) != null) {
                f.setException(new ClosedChannelException());
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
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) throws Exception {
            Object o = me.getMessage();
            if (o instanceof SSMPEvent) {
                SSMPEvent ev = (SSMPEvent) o;
                if (L.isDebugEnabled()) {
                    L.debug("recv event {} {} {} {}", ev.from, ev.type, ev.to, ev.binary
                            ? ev.payload : (ev.payload != null
                                    ? new String(ev.payload, StandardCharsets.UTF_8) : null));
                }
                _handler.eventReceived(ev);
            } else if (o instanceof SSMPResponse) {
                SSMPResponse r = (SSMPResponse) o;
                L.debug("recv response {}", r.code);
                SettableFuture<SSMPResponse> f = _responses.remove();
                f.set(r);
            } else {
                super.messageReceived(ctx, me);
            }
        }

        @Override
        public void writeRequested(ChannelHandlerContext ctx, MessageEvent me) {
            Object o = me.getMessage();
            if (o instanceof SSMPClient.Message) {
                SSMPClient.Message m = (SSMPClient.Message) o;
                if (!ctx.getChannel().isConnected()) {
                    m.f.setException(new ClosedChannelException());
                    return;
                }
                _responses.add(m.f);

                ChannelBuffer b = ChannelBuffers.dynamicBuffer();
                b.writeBytes(m.r.type._s);
                if (m.r.to != null) {
                    b.writeByte(' ');
                    b.writeBytes(m.r.to.getBytes());
                }
                if (m.r.payload != null && m.r.payload.length > 0) {
                    if (m.r.payload.length > SSMPDecoder.MAX_PAYLOAD_LENGTH) {
                        throw new IllegalArgumentException("text payload too large");
                    }
                    b.writeByte(' ');
                    if (m.r.binary) {
                        int sz = m.r.payload.length - 1;
                        b.writeByte(sz >> 8);
                        b.writeByte(sz & 0xff);
                    }
                    b.writeBytes(m.r.payload);
                }
                b.writeByte('\n');
                ctx.sendDownstream(new DownstreamMessageEvent(me.getChannel(), me.getFuture(), b, null));
            } else {
                ctx.sendDownstream(me);
            }
        }
    }
}
