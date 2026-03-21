package com.lokiscale.bifrost.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostSessionRunnerTest {

    @Test
    void createsDistinctSessionsAcrossConcurrentVirtualThreads() throws Exception {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() ->
                    sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));
            Future<String> second = executor.submit(() ->
                    sessionRunner.callWithNewSession(session -> BifrostSession.getCurrentSession().getSessionId()));

            assertThat(Set.of(first.get(), second.get())).hasSize(2);
        }
    }

    @Test
    void isolatesFrameMutationAcrossConcurrentVirtualThreads() throws InterruptedException, ExecutionException {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.pushFrame(frame("frame-1", "route.one"));
                return session.getSessionId() + ":" + session.getFramesSnapshot().size() + ":" + session.peekFrame().route();
            }));
            Future<String> second = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.pushFrame(frame("frame-2", "route.two"));
                return session.getSessionId() + ":" + session.getFramesSnapshot().size() + ":" + session.peekFrame().route();
            }));

            assertThat(first.get()).contains(":1:route.one");
            assertThat(second.get()).contains(":1:route.two");
            assertThat(first.get()).isNotEqualTo(second.get());
        }
    }

    @Test
    void isolatesJournalMutationAcrossConcurrentVirtualThreads() throws InterruptedException, ExecutionException {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "first");
                session.logToolExecution(Instant.parse("2026-03-15T12:00:01Z"), Map.of("route", "tool.one"));
                return session.getSessionId()
                        + ":"
                        + session.getJournalSnapshot().size()
                        + ":"
                        + session.getJournalSnapshot().get(0).payload().textValue();
            }));
            Future<String> second = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
                session.logThought(Instant.parse("2026-03-15T12:00:02Z"), "second");
                session.logError(Instant.parse("2026-03-15T12:00:03Z"), Map.of("message", "boom"));
                return session.getSessionId()
                        + ":"
                        + session.getJournalSnapshot().size()
                        + ":"
                        + session.getJournalSnapshot().get(0).payload().textValue();
            }));

            assertThat(first.get()).contains(":2:first");
            assertThat(second.get()).contains(":2:second");
            assertThat(first.get()).isNotEqualTo(second.get());
        }
    }

    @Test
    void seedsAuthenticationIntoNewSessionWhenProvided() {
        BifrostSessionRunner sessionRunner = new BifrostSessionRunner(4);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED"));

        String authority = sessionRunner.callWithNewSession(authentication, session ->
                session.getAuthentication()
                        .orElseThrow()
                        .getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority());

        assertThat(authority).isEqualTo("ROLE_ALLOWED");
    }

    private static ExecutionFrame frame(String frameId, String route) {
        return new ExecutionFrame(
                frameId,
                null,
                OperationType.CAPABILITY,
                route,
                Map.of("route", route),
                Instant.parse("2026-03-15T12:00:00Z"));
    }
}
