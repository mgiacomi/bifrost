package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.util.Objects;

public class SessionLocalVirtualFileSystem implements VirtualFileSystem {

    private final Path rootDirectory;

    public SessionLocalVirtualFileSystem(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null").toAbsolutePath().normalize();
    }

    @Override
    public Resource resolve(BifrostSession session, String ref) {
        Objects.requireNonNull(session, "session must not be null");
        Path sessionRoot = sessionRoot(session);
        String relativeRef = normalizeRef(ref);
        Path resolved = sessionRoot.resolve(relativeRef).normalize();
        if (!resolved.startsWith(sessionRoot)) {
            throw new IllegalArgumentException("Ref '" + ref + "' escapes the session namespace");
        }
        Resource resource = new FileSystemResource(resolved);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Unknown ref '" + ref + "' for session '" + session.getSessionId() + "'");
        }
        return resource;
    }

    public Path sessionRoot(BifrostSession session) {
        return rootDirectory.resolve(session.getSessionId()).normalize();
    }

    private String normalizeRef(String ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        if (!ref.startsWith("ref://") || ref.length() <= "ref://".length()) {
            throw new IllegalArgumentException("Ref must use the 'ref://' scheme");
        }
        return ref.substring("ref://".length());
    }
}
