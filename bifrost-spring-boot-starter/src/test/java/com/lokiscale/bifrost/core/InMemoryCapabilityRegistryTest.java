package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCapabilityRegistryTest {

    @Test
    void returnsNullWhenCapabilityIsMissing() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();

        assertThat(registry.getCapability("missing-capability")).isNull();
    }

    @Test
    void registersAndRetrievesCapabilityByName() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityMetadata metadata = metadata("calculator.add", args ->
                ((Number) args.get("left")).intValue() + ((Number) args.get("right")).intValue());

        registry.register(metadata.name(), metadata);

        CapabilityMetadata stored = registry.getCapability(metadata.name());
        assertThat(stored).isNotNull();
        assertThat(stored.id()).isEqualTo("calculatorBean#add");
        assertThat(stored.description()).isEqualTo("Adds two integers.");
        assertThat(stored.modelPreference()).isEqualTo(ModelPreference.LIGHT);
        assertThat(stored.rbacRoles()).containsExactly("math-user");
        assertThat(stored.invoker().invoke(Map.of("left", 2, "right", 3))).isEqualTo(5);
        assertThat(registry.getAllCapabilities()).containsExactly(metadata);
    }

    @Test
    void throwsCollisionExceptionForDuplicateCapabilityName() {
        CapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityMetadata first = metadata("calculator.add", args -> 1);
        CapabilityMetadata duplicate = new CapabilityMetadata(
                "calculatorBean#sum",
                "calculator.add",
                "Duplicate add operation.",
                ModelPreference.HEAVY,
                SkillExecutionDescriptor.none(),
                Set.of("math-admin"),
                args -> 2);

        registry.register(first.name(), first);

        assertThatThrownBy(() -> registry.register(duplicate.name(), duplicate))
                .isInstanceOf(CapabilityCollisionException.class)
                .hasMessageContaining("calculator.add");
    }

    @Test
    void supportsConcurrentRegistrationAndReads() throws Exception {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        int capabilityCount = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Void>> registrationTasks = new ArrayList<>();
        for (int i = 0; i < capabilityCount; i++) {
            int index = i;
            registrationTasks.add(() -> {
                start.await();
                CapabilityMetadata metadata = new CapabilityMetadata(
                        "bean" + index + "#method" + index,
                        "capability." + index,
                        "Capability number " + index,
                        ModelPreference.LIGHT,
                        SkillExecutionDescriptor.none(),
                        Set.of("role-" + index),
                        args -> index);
                registry.register(metadata.name(), metadata);
                assertThat(registry.getCapability(metadata.name())).isNotNull();
                return null;
            });
        }

        Callable<Void> readTask = () -> {
            start.await();
            for (int i = 0; i < capabilityCount; i++) {
                registry.getAllCapabilities();
            }
            return null;
        };

        List<Future<Void>> futures = new ArrayList<>();
        registrationTasks.forEach(task -> futures.add(executor.submit(task)));
        futures.add(executor.submit(readTask));

        start.countDown();

        for (Future<Void> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.getAllCapabilities()).hasSize(capabilityCount);
    }

    private static CapabilityMetadata metadata(String name, CapabilityInvoker invoker) {
        return new CapabilityMetadata(
                "calculatorBean#add",
                name,
                "Adds two integers.",
                null,
                null,
                Set.of("math-user"),
                invoker);
    }
}
