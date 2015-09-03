/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

public class SSMPEvent {
    final static int NO_FIELD = 0;
    final static int FIELD_TO = 1;
    final static int FIELD_PAYLOAD = 2;
    final static int FIELD_OPTION = 6;

    public enum Type {
        SUBSCRIBE("SUBSCRIBE", FIELD_TO | FIELD_OPTION),
        UNSUBSCRIBE("UNSUBSCRIBE", FIELD_TO),
        UCAST("UCAST", FIELD_TO | FIELD_PAYLOAD),
        MCAST("MCAST", FIELD_TO | FIELD_PAYLOAD),
        BCAST("BCAST", FIELD_PAYLOAD),
        PING("PING", NO_FIELD),
        PONG("PONG", NO_FIELD),
        ;

        final byte[] _s;
        final int _fields;

        private final static ImmutableMap<String, Type> _m;
        static {
            ImmutableMap.Builder<String, Type> b = ImmutableMap.builder();
            for (Type t : values()) {
                b.put(t.name(), t);
            }
            _m = b.build();
        }

        static Type byName(byte[] n) {
            return _m.get(new String(n, StandardCharsets.US_ASCII));
        }

        Type(String s, int fields) {
            _s = s.getBytes(StandardCharsets.US_ASCII);
            _fields = fields;
        }
    }

    public final SSMPIdentifier from;
    public final Type type;
    public final @Nullable SSMPIdentifier to;
    public final @Nullable String payload;

    public SSMPEvent(SSMPIdentifier from, Type type, @Nullable SSMPIdentifier to, @Nullable String payload) {
        this.from = from;
        this.type = type;
        this.to = to;
        this.payload = payload;
    }

    @Override
    public String toString() {
        return Joiner.on(" ").join(from, type, to, payload);
    }
}
