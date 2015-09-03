/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

public class ByteSet {
    private final long[] d = new long[4];

    interface Initializer {
        void init(ByteSet s);
    }

    public ByteSet(Initializer... ii) {
        for (Initializer i : ii) i.init(this);
    }

    public static Initializer Range(char a, char b) {
        return Range((byte)a, (byte)b);
    }

    public static Initializer Range(byte a, byte b) {
        return s -> {
            for (byte c = a; c <= b; ++c) s.set(c);
        };
    }

    public static Initializer All(String l) {
        return s -> {
            for (int i = 0; i < l.length(); ++i) s.set((byte)l.charAt(i));
        };
    }
    public static Initializer All(byte... l) {
        return s -> {
            for (byte c : l) s.set(c);
        };
    }

    private void set(byte b) {
        d[b / 64] |= (1L << (b & 63));
    }

    public boolean contains(byte b) {
        return (d[b / 64] & (1L << (b & 63))) != 0;
    }
}
