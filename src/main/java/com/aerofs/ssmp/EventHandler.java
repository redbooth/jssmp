/*
 * Copyright (c) 2015, Air Computing Inc. <oss@aerofs.com>
 * All rights reserved.
 */

package com.aerofs.ssmp;

public interface EventHandler {
    void eventReceived(SSMPEvent e);
}
