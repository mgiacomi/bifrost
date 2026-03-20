package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import org.springframework.core.io.Resource;

public interface VirtualFileSystem {

    Resource resolve(BifrostSession session, String ref);
}
