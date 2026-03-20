package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefResolverTest {

    @Test
    void resolvesOnlyExactRefStrings() {
        BifrostSession session = new BifrostSession("session-1", 2);
        DefaultRefResolver resolver = new DefaultRefResolver((ignoredSession, ref) -> new ByteArrayResource(("resolved:" + ref).getBytes()));

        assertThat(readResource(resolver.resolveArgument("ref://artifacts/abc123", session)))
                .isEqualTo("resolved:ref://artifacts/abc123");
        assertThat(resolver.resolveArgument("prefix ref://artifacts/abc123", session))
                .isEqualTo("prefix ref://artifacts/abc123");
        assertThat(resolver.resolveArgument("ref://artifacts/abc123 trailing", session))
                .isEqualTo("ref://artifacts/abc123 trailing");
        assertThat(resolver.resolveArgument(" ref://artifacts/abc123", session))
                .isEqualTo(" ref://artifacts/abc123");
        assertThat(resolver.resolveArgument("hello", session)).isEqualTo("hello");
        assertThat(resolver.resolveArgument(42, session)).isEqualTo(42);
    }

    @Test
    void resolvesExactRefStringsInsideNestedMapsAndLists() {
        BifrostSession session = new BifrostSession("session-1", 2);
        DefaultRefResolver resolver = new DefaultRefResolver((ignoredSession, ref) -> new ByteArrayResource(("resolved:" + ref).getBytes()));

        Map<String, Object> resolved = resolver.resolveArguments(Map.of(
                "direct", "ref://artifacts/root.txt",
                "nested", Map.of(
                        "list", List.of(
                                "hello",
                                "ref://artifacts/child.txt",
                                Map.of("deep", "ref://artifacts/deep.txt")),
                        "unchanged", "prefix ref://artifacts/not-a-ref"),
                "array", new Object[]{"ref://artifacts/array.txt", 42}), session);

        assertThat(readResource(resolved.get("direct"))).isEqualTo("resolved:ref://artifacts/root.txt");
        assertThat(readNestedResources(resolved.get("nested"))).isEqualTo(Map.of(
                "list", List.of(
                        "hello",
                        "resolved:ref://artifacts/child.txt",
                        Map.of("deep", "resolved:ref://artifacts/deep.txt")),
                "unchanged", "prefix ref://artifacts/not-a-ref"));
        assertThat(readNestedResources(resolved.get("array"))).isEqualTo(List.of("resolved:ref://artifacts/array.txt", 42));
    }

    @Test
    void preservesBinaryResourcePayloadsForLaterConsumers() throws Exception {
        BifrostSession session = new BifrostSession("session-binary", 2);
        byte[] expected = new byte[]{0x00, 0x01, (byte) 0xFE, (byte) 0xFF, 0x41};
        DefaultRefResolver resolver = new DefaultRefResolver((ignoredSession, ref) -> new ByteArrayResource(expected));

        Object resolved = resolver.resolveArgument("ref://artifacts/raw.bin", session);

        assertThat(resolved).isInstanceOf(Resource.class);
        try (InputStream stream = ((Resource) resolved).getInputStream()) {
            assertThat(StreamUtils.copyToByteArray(stream)).containsExactly(expected);
        }
    }

    @Test
    void leavesNonLeafContainersUntouchedWhenNoExactRefLeafExists() {
        BifrostSession session = new BifrostSession("session-nonleaf", 2);
        DefaultRefResolver resolver = new DefaultRefResolver((ignoredSession, ref) -> new ByteArrayResource(("resolved:" + ref).getBytes()));

        Map<String, Object> resolved = resolver.resolveArguments(Map.of(
                "prefixed", "see ref://artifacts/not-exact",
                "nested", Map.of(
                        "spaced", "ref://artifacts/not exact",
                        "list", List.of("hello", "suffix ref://artifacts/nope")),
                "array", new Object[]{" ref://artifacts/nope", "ref://artifacts/real.txt "} ), session);

        assertThat(resolved).isEqualTo(Map.of(
                "prefixed", "see ref://artifacts/not-exact",
                "nested", Map.of(
                        "spaced", "ref://artifacts/not exact",
                        "list", List.of("hello", "suffix ref://artifacts/nope")),
                "array", List.of(" ref://artifacts/nope", "ref://artifacts/real.txt ")));
    }

    @Test
    void propagatesUnderlyingResolutionFailures() {
        BifrostSession session = new BifrostSession("session-errors", 2);
        DefaultRefResolver resolver = new DefaultRefResolver((ignoredSession, ref) -> {
            throw new IllegalArgumentException("Unknown ref '" + ref + "'");
        });

        assertThatThrownBy(() -> resolver.resolveArgument("ref://artifacts/missing.txt", session))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ref");
    }

    private Object readNestedResources(Object value) {
        if (value instanceof Resource) {
            return readResource(value);
        }
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<Object, Object> converted = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> converted.put(key, readNestedResources(nested)));
            return converted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::readNestedResources).toList();
        }
        return value;
    }

    private String readResource(Object value) {
        assertThat(value).isInstanceOf(Resource.class);
        try {
            return StreamUtils.copyToString(((Resource) value).getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
