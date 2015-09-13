/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;

import java.nio.charset.StandardCharsets;

public class SSMPResponseEncoder extends SimpleChannelDownstreamHandler {
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
            if (ev.payload != null && ev.payload.length > 0) {
                if (ev.payload.length > SSMPDecoder.MAX_PAYLOAD_LENGTH) {
                    throw new IllegalArgumentException("binary payload too large");
                }
                b.writeByte(' ');
                if (ev.binary) {
                    int sz = ev.payload.length - 1;
                    b.writeByte(sz >> 8);
                    b.writeByte(sz & 0xff);
                }
                b.writeBytes(ev.payload);
            }
            b.writeByte('\n');
            ctx.sendDownstream(new DownstreamMessageEvent(me.getChannel(), me.getFuture(), b, null));
        } else {
            ctx.sendDownstream(me);
        }
    }
}
