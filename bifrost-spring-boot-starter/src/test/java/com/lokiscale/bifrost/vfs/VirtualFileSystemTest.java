package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualFileSystemTest {

    @TempDir
    Path tempDir;

    @Test
    void isolatesRefsBySessionNamespace() throws Exception {
        SessionLocalVirtualFileSystem vfs = new SessionLocalVirtualFileSystem(tempDir);
        BifrostSession first = new BifrostSession("session-1", 2);
        BifrostSession second = new BifrostSession("session-2", 2);

        Path firstFile = vfs.sessionRoot(first).resolve("artifacts/message.txt");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "hello", StandardCharsets.UTF_8);

        assertThat(vfs.resolve(first, "ref://artifacts/message.txt").getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("hello");
        assertThatThrownBy(() -> vfs.resolve(second, "ref://artifacts/message.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session-2");
    }

    @Test
    void preservesRawBinaryBytesWithoutTextDecoding() throws Exception {
        SessionLocalVirtualFileSystem vfs = new SessionLocalVirtualFileSystem(tempDir);
        BifrostSession session = new BifrostSession("session-binary", 2);

        byte[] expected = new byte[]{0x00, 0x01, (byte) 0xFE, (byte) 0xFF, 0x41};
        Path binaryFile = vfs.sessionRoot(session).resolve("artifacts/payload.bin");
        Files.createDirectories(binaryFile.getParent());
        Files.write(binaryFile, expected);

        Resource resource = vfs.resolve(session, "ref://artifacts/payload.bin");

        assertThat(StreamUtils.copyToByteArray(resource.getInputStream())).containsExactly(expected);
    }

    @Test
    void rejectsRefsThatEscapeSessionNamespace() {
        SessionLocalVirtualFileSystem vfs = new SessionLocalVirtualFileSystem(tempDir);
        BifrostSession session = new BifrostSession("session-escape", 2);

        assertThatThrownBy(() -> vfs.resolve(session, "ref://../other-session/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the session namespace");
    }

    @Test
    void rejectsInvalidOrMissingRefSyntax() {
        SessionLocalVirtualFileSystem vfs = new SessionLocalVirtualFileSystem(tempDir);
        BifrostSession session = new BifrostSession("session-invalid", 2);

        assertThatThrownBy(() -> vfs.resolve(session, "artifacts/message.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref://");
        assertThatThrownBy(() -> vfs.resolve(session, "ref://"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref://");
    }

    @Test
    void resolvesNestedPathsInsideSameSessionNamespace() throws Exception {
        SessionLocalVirtualFileSystem vfs = new SessionLocalVirtualFileSystem(tempDir);
        BifrostSession session = new BifrostSession("session-nested", 2);

        Path nestedFile = vfs.sessionRoot(session).resolve("artifacts/reports/2026/summary.txt");
        Files.createDirectories(nestedFile.getParent());
        Files.writeString(nestedFile, "nested hello", StandardCharsets.UTF_8);

        Resource resource = vfs.resolve(session, "ref://artifacts/reports/2026/summary.txt");

        assertThat(readUtf8(resource)).isEqualTo("nested hello");
    }

    private String readUtf8(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
