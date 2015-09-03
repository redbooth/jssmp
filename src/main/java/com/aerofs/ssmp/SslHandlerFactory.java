/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

import org.jboss.netty.handler.ssl.SslHandler;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface SslHandlerFactory {
    SslHandler newSslHandler() throws IOException, GeneralSecurityException;
}
