package com.aerofs.ssmp;

import org.jboss.netty.buffer.ChannelBuffer;

public interface Authenticator {
    boolean authenticate(SSMPIdentifier id, SSMPIdentifier scheme, String cred);

    ChannelBuffer unauthorized();
}
