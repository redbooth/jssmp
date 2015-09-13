/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.aerofs.ssmp.SSMPRequestDecoder.ChannelData;
import com.aerofs.ssmp.SSMPRequestDecoder.IdAddress;
import com.aerofs.ssmp.SSMPEvent.Type;
import com.aerofs.ssmp.SSMPRequest.SubscriptionFlag;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.Timer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiConsumer;

public class SSMPServer extends SimpleChannelUpstreamHandler {
    private final InetSocketAddress _addr;
    private final ServerBootstrap _bootstrap;

    private Channel _listenChannel;

    private final Map<SSMPIdentifier, Connection> _clients = new ConcurrentHashMap<>();
    private final Map<SSMPIdentifier, Topic> _topics = new ConcurrentHashMap<>();


    private static class Connection {
        private final Channel c;
        private final SSMPIdentifier id;
        private final AtomicBoolean _closed = new AtomicBoolean();
        private final Map<SSMPIdentifier, Topic> sub = new ConcurrentHashMap<>();

        Connection(Channel c) {
            this.c = c;
            this.id = ((ChannelData)c.getAttachment()).id;
            c.getCloseFuture().addListener(future -> close());
        }

        void close() {
            if (!_closed.compareAndSet(false, true)) return;
            for (Topic t : sub.values()) {
                t.remove(id, this);
            }
            if (c.isConnected()) c.close();
        }
    }

    private static class Subscription {
        private final Connection c;
        private final boolean presence;

        Subscription(Connection c, boolean presence) {
            this.c = c;
            this.presence = presence;
        }
    }

    private static class Topic {
        private final SSMPIdentifier _id;
        private final SSMPServer _server;
        private final ReadWriteLock _l = new StampedLock().asReadWriteLock();
        private final Map<SSMPIdentifier, Subscription> sub = new HashMap<>();

        Topic(SSMPIdentifier id, SSMPServer server) {
            _id = id;
            _server = server;
        }

        boolean add(SSMPIdentifier id, Connection c, boolean presence) {
            _l.writeLock().lock();
            try {
                return sub.putIfAbsent(id, new Subscription(c, presence)) == null;
            } finally {
                _l.writeLock().unlock();
            }
        }

        void remove(SSMPIdentifier id, Connection c) {
            _l.writeLock().lock();
            try {
                sub.remove(id);
                if (sub.isEmpty()) {
                    _server._topics.remove(_id, c);
                }
            } finally {
                _l.writeLock().unlock();
            }
        }

        void forEach(BiConsumer<SSMPIdentifier, Subscription> c) {
            _l.readLock().lock();
            try {
                sub.forEach(c);
            } finally {
                _l.readLock().unlock();
            }
        }
    }


    public SSMPServer(InetSocketAddress addr, Timer timer, ChannelFactory channelFactory,
                      SslHandlerFactory sslHandlerFactory, Authenticator auth) {
        _addr = addr;
        _bootstrap = new ServerBootstrap(channelFactory);
        _bootstrap.setPipelineFactory(() -> Channels.pipeline(
                sslHandlerFactory.newSslHandler(),
                new IdleStateHandler(timer, 30, 0, 0, TimeUnit.SECONDS),
                new SSMPRequestDecoder(auth),
                new SSMPResponseEncoder(),
                this
        ));
    }

    public void start() {
        _listenChannel = _bootstrap.bind(_addr);
    }

    public void stop() {
        _listenChannel.close().awaitUninterruptibly();
        for (Connection c : _clients.values()) {
            c.c.close();
        }
    }

    public int getListeningPort()
    {
        return ((InetSocketAddress)_listenChannel.getLocalAddress()).getPort();
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        SSMPIdentifier id = ((ChannelData) ctx.getChannel().getAttachment()).id;
        if (id.equals(SSMPIdentifier.ANONYMOUS)) return;
        Connection prev = _clients.put(id, new Connection(ctx.getChannel()));
        if (prev != null) {
            prev.c.close();
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
        requestReceived(ctx.getChannel(), ((IdAddress)ctx.getChannel().getAttachment()).id,
                (SSMPRequest)me.getMessage());
    }

    private Topic getOrCreate(SSMPIdentifier topic) {
        Topic n = new Topic(topic, this);
        Topic t = _topics.putIfAbsent(topic, n);
        return t != null ? t : n;
    }

    protected void requestReceived(Channel channel, SSMPIdentifier from, SSMPRequest r) {
        switch (r.type) {
        case SUBSCRIBE: {
            if (from.equals(SSMPIdentifier.ANONYMOUS)) {
                channel.write(new SSMPResponse(SSMPResponse.NOT_ALLOWED, null));
                return;
            }
            Topic t = getOrCreate(r.to);
            Connection c = _clients.get(from);
            boolean presence = Arrays.equals(r.payload, SubscriptionFlag.PRESENCE._s);
            if (!t.add(from, c, presence)) {
                channel.write(new SSMPResponse(SSMPResponse.CONFLICT, null));
                break;
            }
            c.sub.put(r.to, t);

            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            t.forEach((id, s) -> {
                if (id.equals(from)) return;
                if (s.presence) {
                    s.c.c.write(new SSMPEvent(from, Type.SUBSCRIBE, r.to,
                            presence ? SubscriptionFlag.PRESENCE.name()
                                    .getBytes(StandardCharsets.US_ASCII) : null));
                }
                if (presence) {
                    channel.write(new SSMPEvent(s.c.id, Type.SUBSCRIBE, r.to,
                            s.presence ? SubscriptionFlag.PRESENCE.name()
                                    .getBytes(StandardCharsets.US_ASCII) : null));
                }
            });
            break;
        }
        case UNSUBSCRIBE: {
            if (from.equals(SSMPIdentifier.ANONYMOUS)) {
                channel.write(new SSMPResponse(SSMPResponse.NOT_ALLOWED, null));
                return;
            }
            Connection c = _clients.get(from);
            Topic t = c.sub.remove(r.to);
            if (t == null) {
                channel.write(new SSMPResponse(SSMPResponse.NOT_FOUND, null));
                break;
            }
            t.sub.remove(from, c);
            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            t.forEach((id, s) -> {
                if (s.presence) {
                    s.c.c.write(new SSMPEvent(from, Type.UNSUBSCRIBE, r.to, null));
                }
            });
            break;
        }
        case UCAST: {
            Connection c = _clients.get(r.to);
            if (c == null) {
                channel.write(new SSMPResponse(SSMPResponse.NOT_FOUND, null));
                return;
            }
            c.c.write(new SSMPEvent(from, Type.UCAST, r.to, r.payload, r.binary));
            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            break;
        }
        case MCAST: {
            Topic t = _topics.get(r.to);
            if (t != null) {
                t.forEach((id, s) -> {
                    s.c.c.write(new SSMPEvent(from, Type.MCAST, r.to, r.payload, r.binary));
                });
            }
            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            break;
        }
        case BCAST: {
            if (from.equals(SSMPIdentifier.ANONYMOUS)) {
                channel.write(new SSMPResponse(SSMPResponse.NOT_ALLOWED, null));
                return;
            }
            Connection c = _clients.get(from);
            Set<SSMPIdentifier> ids = new HashSet<>();
            for (Topic t : c.sub.values()) {
                t.forEach((id, s) -> {
                    if (id.equals(from) || !ids.add(id)) return;
                    s.c.c.write(new SSMPEvent(from, Type.BCAST, null, r.payload, r.binary));
                });
            }
            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            break;
        }
        case CLOSE:
            channel.write(new SSMPResponse(SSMPResponse.OK, null));
            channel.close();
            break;
        default:
            channel.write(new SSMPResponse(SSMPResponse.NOT_IMPLEMENTED, null));
            break;
        }
    }
}
