/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import org.jboss.netty.buffer.ChannelBuffer;

import java.io.EOFException;

public class SSMPDecoder {
    public static final int CODE_LENGTH = 3;
    public static final int MAX_VERB_LENGTH = 16;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_PAYLOAD_LENGTH = 1024;

    public static boolean atEnd(ChannelBuffer b) {
        return b.readable() && b.getByte(b.readerIndex()) == '\n';
    }

    public static boolean next(ChannelBuffer b) throws EOFException {
        if (!b.readable()) throw new EOFException();
        byte c = b.readByte();
        if (c == '\n') return false;
        if (c != ' ') throw new IllegalArgumentException();
        return true;
    }

    public static int readCode(ChannelBuffer b) throws EOFException {
        if (b.readableBytes() < 4) throw new EOFException();
        int n = 0;
        for (int i = 0; i < CODE_LENGTH; ++i) {
            byte c = b.getByte(b.readerIndex() + i);
            if (c < '0' || c > '9') throw new IllegalArgumentException();
            n = 10 * n + (c - '0');
        }
        byte c = b.getByte(b.readerIndex() + CODE_LENGTH);
        if (c != ' ' && c != '\n') throw new IllegalArgumentException();
        b.skipBytes(3);
        return n;
    }

    public static byte[] read(ChannelBuffer b, ByteSet s, int max) throws EOFException {
        int n = 0;
        while (true) {
            if (n == max) throw new IllegalArgumentException();
            if (n == b.readableBytes()) throw new EOFException();
            byte c = b.getByte(b.readerIndex() + n);
            if (c == ' ' || c == '\n') {
                if (n == 0) throw new IllegalArgumentException();
                byte[] id = new byte[n];
                b.readBytes(id);
                return id;
            }
            if (!s.contains(c)) throw new IllegalArgumentException();
            ++n;
        }
    }

    public static SSMPIdentifier readIdentifier(ChannelBuffer b) throws EOFException {
        return new SSMPIdentifier(read(b, SSMPIdentifier.ALLOWED, MAX_ID_LENGTH));
    }

    public static final ByteSet VERB = new ByteSet(ByteSet.Range('A', 'Z'));

    public static byte[] readVerb(ChannelBuffer b) throws EOFException {
        return read(b, VERB, MAX_VERB_LENGTH);
    }

    public static boolean isBinaryPayload(ChannelBuffer b) throws EOFException {
        if (!b.readable()) return false;
        byte c = b.getByte(b.readerIndex());
        return c >= 0 && c <= 3;
    }

    public static byte[] readPayload(ChannelBuffer b) throws EOFException {
        int n = b.readableBytes();
        if (n < 1) throw new EOFException();
        byte c = b.getByte(b.readerIndex());
        if (c >= 0 && c <= 3) {
            if (n < 2) throw new EOFException();
            int sz = 1 + ((int)c << 8) + ((int)b.getByte(b.readerIndex() + 1) & 0xff);
            if (sz > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException();
            if (n < sz + 1) throw new EOFException();
            if (b.getByte(b.readerIndex() + 2 + sz) != '\n') throw new IllegalArgumentException();
            byte[] v = new byte[sz];
            b.skipBytes(2);
            b.readBytes(v);
            return v;
        }
        int i = b.indexOf(b.readerIndex(), b.readerIndex() + n, (byte)'\n');
        if (i == -1) {
            if (n > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException();
            throw new EOFException();
        }
        int sz = i - b.readerIndex();
        if (sz == 0 || sz > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException();
        byte[] v = new byte[sz];
        b.readBytes(v);
        return v;
    }

    public static void skipCompat(ChannelBuffer b) throws EOFException {
        try {
            readIdentifier(b);
            if (!next(b)) return;
        } catch (IllegalArgumentException e) {}
        readPayload(b);
    }
}
