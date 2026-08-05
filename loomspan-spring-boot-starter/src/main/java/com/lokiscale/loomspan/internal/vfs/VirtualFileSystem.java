package com.lokiscale.loomspan.internal.vfs;

import com.lokiscale.loomspan.internal.core.LoomspanSession;
import org.springframework.core.io.Resource;

public interface VirtualFileSystem
{
    default Resource resolve(LoomspanSession session, String ref)
    {
        return resolve(session, VfsRef.parse(ref));
    }

    Resource resolve(LoomspanSession session, VfsRef ref);
}
