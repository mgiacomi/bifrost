package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import org.springframework.core.io.Resource;

public interface VirtualFileSystem
{
    default Resource resolve(BifrostSession session, String ref)
    {
        return resolve(session, VfsRef.parse(ref));
    }

    Resource resolve(BifrostSession session, VfsRef ref);
}
