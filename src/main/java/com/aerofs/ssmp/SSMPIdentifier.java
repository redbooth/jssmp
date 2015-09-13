/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.aerofs.ssmp.ByteSet.All;
import static com.aerofs.ssmp.ByteSet.Range;

public class SSMPIdentifier {
    public static final SSMPIdentifier ANONYMOUS = new SSMPIdentifier(".");

    private final byte[] _id;

    public static final ByteSet ALLOWED = new ByteSet(
            Range('a', 'z'),
            Range('A', 'Z'),
            Range('0', '9'),
            All(".@:/-_+=~")
    );

    public boolean isAnonymous() {
        return ANONYMOUS.equals(this);
    }

    public static class InvalidIdentifier extends Exception {
        public static final long serialVersionUID = -1;
        InvalidIdentifier() {}
    }

    private SSMPIdentifier(String id) { _id = id.getBytes(StandardCharsets.US_ASCII); }
    SSMPIdentifier(byte[] id) { _id = id; }

    public static boolean isValid(String id) {
        if (id.length() > SSMPDecoder.MAX_ID_LENGTH) return false;
        for (int i = 0; i < id.length(); ++i) {
            if (!ALLOWED.contains((byte) id.charAt(i))) return false;
        }
        return true;
    }

    public static SSMPIdentifier fromInternal(String id) {
        if (!isValid(id)) throw new IllegalArgumentException();
        return new SSMPIdentifier(id);
    }

    public static SSMPIdentifier fromExternal(String id) throws InvalidIdentifier {
        if (!isValid(id)) throw new InvalidIdentifier();
        return new SSMPIdentifier(id);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SSMPIdentifier && Arrays.equals(((SSMPIdentifier) o)._id, _id);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(_id);
    }

    public byte[] getBytes() { return _id; }

    @Override
    public String toString() {
        return new String(_id, StandardCharsets.US_ASCII);
    }
}
