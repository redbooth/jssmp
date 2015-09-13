/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import com.google.common.collect.ImmutableMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

import static com.aerofs.ssmp.SSMPDecoder.MAX_PAYLOAD_LENGTH;

public class SSMPRequest {
    final static int NO_FIELD = 0;
    final static int FIELD_ID = 1;
    final static int FIELD_PAYLOAD = 2;
    final static int FIELD_OPTION = 6;

    public enum Type {
        LOGIN("LOGIN", -1),
        SUBSCRIBE("SUBSCRIBE", FIELD_ID | FIELD_OPTION),
        UNSUBSCRIBE("UNSUBSCRIBE", FIELD_ID),
        UCAST("UCAST", FIELD_ID | FIELD_PAYLOAD),
        MCAST("MCAST", FIELD_ID | FIELD_PAYLOAD),
        BCAST("BCAST", FIELD_PAYLOAD),
        PING("PING", NO_FIELD),
        PONG("PONG", NO_FIELD),
        CLOSE("CLOSE", NO_FIELD),
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

    public enum SubscriptionFlag {
        NONE(""),
        PRESENCE("PRESENCE"),
        ;

        byte[] _s;
        SubscriptionFlag(String value) {
            _s = value.getBytes(StandardCharsets.US_ASCII);
        }
    }

    public final Type type;
    public final @Nullable SSMPIdentifier to;
    public final @Nullable byte[] payload;
    public final boolean binary;

    SSMPRequest(Type type, @Nullable SSMPIdentifier to, @Nullable byte[] payload, boolean binary) {
        this.type = type;
        this.to = to;
        this.payload = payload;
        this.binary = binary;
    }

    private static boolean isValidText(String payload) {
        for (int i = 0; i < payload.length(); ++i) {
            char c = payload.charAt(i);
            if (c == '\n' || (c >= 0 && c <= 3)) return false;
        }
        return true;
    }

    private static void checkTextPayload(String payload) {
        checkPayloadLength(payload.length());
        if (!isValidText(payload)) {
            throw new IllegalArgumentException();
        }
    }

    private static void checkPayloadLength(int length) {
        if (length <= 0 || length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String toString() {
        return type + " " + to + " "
                + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null);
    }

    public static SSMPRequest login(@Nonnull SSMPIdentifier id, @Nonnull SSMPIdentifier scheme,
                                    @Nonnull String cred) {
        return new SSMPRequest(Type.LOGIN, id, (scheme + (cred.isEmpty() ? cred : " " + cred))
                .getBytes(StandardCharsets.UTF_8), false);
    }

    public static SSMPRequest subscribe(@Nonnull SSMPIdentifier topic, SubscriptionFlag flag) {
        return new SSMPRequest(Type.SUBSCRIBE, topic, flag._s, false);
    }

    public static SSMPRequest unsubscribe(@Nonnull SSMPIdentifier topic) {
        return new SSMPRequest(Type.UNSUBSCRIBE, topic, null, false);
    }

    public static SSMPRequest ucast(@Nonnull SSMPIdentifier user, @Nonnull String payload) {
        checkTextPayload(payload);
        return new SSMPRequest(Type.UCAST, user, payload.getBytes(StandardCharsets.UTF_8), false);
    }

    public static SSMPRequest ucast(@Nonnull SSMPIdentifier user, @Nonnull byte[] payload) {
        checkPayloadLength(payload.length);
        return new SSMPRequest(Type.UCAST, user, payload, true);
    }

    public static SSMPRequest mcast(@Nonnull SSMPIdentifier topic, @Nonnull String payload) {
        checkTextPayload(payload);
        return new SSMPRequest(Type.MCAST, topic, payload.getBytes(StandardCharsets.UTF_8), false);
    }

    public static SSMPRequest mcast(@Nonnull SSMPIdentifier user, @Nonnull byte[] payload) {
        checkPayloadLength(payload.length);
        return new SSMPRequest(Type.MCAST, user, payload, true);
    }

    public static SSMPRequest bcast(@Nonnull String payload) {
        checkTextPayload(payload);
        return new SSMPRequest(Type.BCAST, null, payload.getBytes(StandardCharsets.UTF_8), false);
    }

    public static SSMPRequest bcast(@Nonnull byte[] payload) {
        checkPayloadLength(payload.length);
        return new SSMPRequest(Type.BCAST, null, payload, true);
    }

    public static SSMPRequest close() {
        return new SSMPRequest(Type.CLOSE, null, null, false);
    }
}
