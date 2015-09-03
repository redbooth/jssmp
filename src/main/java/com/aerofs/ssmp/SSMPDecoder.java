/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import org.jboss.netty.buffer.ChannelBuffer;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

public class SSMPDecoder {
    public static int readCode(ChannelBuffer b) {
        if (b.readableBytes() < 3) throw new IllegalArgumentException();
        int n = 0;
        for (int i = 0; i < 3; ++i) {
            byte c = b.readByte();
            if (c < '0' || c > '9') throw new IllegalArgumentException();
            n = 10 * n + (c - '0');
        }
        return n;
    }

    public static byte[] read(ChannelBuffer b, ByteSet s) {
        int n = 0;
        while (n < b.readableBytes()) {
            byte c = b.getByte(b.readerIndex() + n);
            if (c == ' ') break;
            if (!s.contains(c)) throw new IllegalArgumentException();
            ++n;
        }
        if (n <= 0) throw new IllegalArgumentException();
        byte[] id = new byte[n];
        b.readBytes(id);
        skipSpace(b);
        return id;
    }

    public static SSMPIdentifier readIdentifier(ChannelBuffer b) {
        byte[] id = read(b, SSMPIdentifier.ALLOWED);
        return new SSMPIdentifier(id);
    }

    private static final ByteSet VERB = new ByteSet(ByteSet.Range('A', 'Z'));

    public static SSMPEvent.Type readEventType(ChannelBuffer b) {
        byte[] verb = read(b, VERB);
        SSMPEvent.Type t = SSMPEvent.Type.byName(verb);
        if (t == null) throw new IllegalArgumentException();
        return t;
    }
    public static @Nullable SSMPRequest.Type readRequestType(ChannelBuffer b) {
        byte[] verb = read(b, VERB);
        return SSMPRequest.Type.byName(verb);
    }

    public static byte[] readPayloadBytes(ChannelBuffer b) {
        byte[] array = new byte[b.readableBytes()];
        b.readBytes(array);
        return array;
    }

    public static String readPayload(ChannelBuffer b) {
        return b.readBytes(b.readableBytes()).toString(StandardCharsets.UTF_8);
    }

    public static void skipSpace(ChannelBuffer b) {
        int i = 0;
        while (i < b.readableBytes() && b.getByte(b.readerIndex() + i) == ' ') ++i;
        if (i == 0 && b.readable()) throw new IllegalArgumentException();
        b.skipBytes(i);
    }
}
