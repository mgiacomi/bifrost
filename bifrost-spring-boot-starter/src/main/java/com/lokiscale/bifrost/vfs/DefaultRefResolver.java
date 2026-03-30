package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import java.util.Objects;

public class DefaultRefResolver implements RefResolver {

    private static final java.util.regex.Pattern STRICT_REF_PATTERN = java.util.regex.Pattern.compile("^ref://\\S+$");

    private final VirtualFileSystem virtualFileSystem;

    public DefaultRefResolver(VirtualFileSystem virtualFileSystem) {
        this.virtualFileSystem = Objects.requireNonNull(virtualFileSystem, "virtualFileSystem must not be null");
    }

    @Override
    public Object resolveArgument(Object value, BifrostSession session) {
        Objects.requireNonNull(session, "session must not be null");
        if (!(value instanceof String text) || !STRICT_REF_PATTERN.matcher(text).matches()) {
            return value;
        }
        return virtualFileSystem.resolve(session, text);
    }
}
