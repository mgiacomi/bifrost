package com.lokiscale.bifrost.internal.core;

import com.lokiscale.bifrost.internal.runtime.input.SkillInputContract;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySkillImplementationTargetRegistryTest
{
    @Test
    void returnsNullWhenTargetIsMissing()
    {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        assertThat(registry.getTarget(null)).isNull();
        assertThat(registry.getTarget(" ")).isNull();
        assertThat(registry.getTarget("bean#missing")).isNull();
    }

    @Test
    void registersAndRetrievesTargetById()
    {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        SkillImplementationTarget target = target("bean#method");
        registry.register(target);
        assertThat(registry.getTarget("bean#method")).isSameAs(target);
    }

    @Test
    void enumeratesTargetsWithoutMutableBackingState()
    {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        registry.register(target("bean#method"));
        assertThatThrownBy(() -> registry.getAllTargets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.getAllTargets()).hasSize(1);
    }

    @Test
    void rejectsDuplicateTargetIdBeforeMutation()
    {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        SkillImplementationTarget original = target("bean#method");
        registry.register(original);

        assertThatThrownBy(() -> registry.register(target("bean#method")))
                .isInstanceOf(SkillImplementationTargetCollisionException.class)
                .hasMessageContaining("bean#method")
                .hasMessageContaining("unique beanName#methodName ID");
        assertThat(registry.getTarget("bean#method")).isSameAs(original);
    }

    @Test
    void supportsConcurrentRegistrationAndReads() throws Exception
    {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        int count = 32;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            var futures = IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        String id = "bean#method" + index;
                        registry.register(target(id));
                        return registry.getTarget(id);
                    }))
                    .toList();
            start.countDown();
            for (var future : futures)
            {
                assertThat(future.get()).isNotNull();
            }
        }
        assertThat(registry.getAllTargets()).hasSize(count);
    }

    private SkillImplementationTarget target(String id)
    {
        return new SkillImplementationTarget(id, "description", arguments -> "ok",
                "{\"type\":\"object\"}", SkillInputContract.genericObject());
    }
}
