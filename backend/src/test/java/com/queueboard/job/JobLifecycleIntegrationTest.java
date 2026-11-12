package com.queueboard.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobService;
import com.queueboard.job.domain.JobStatus;
import com.queueboard.job.persistence.JobRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests ({@code @SpringBootTest}, "test" profile, real H2) — not just
 * trusting mocks: the full path from HTTP through service, processor, and database.
 *
 * <p>The outer tests run with the real worker polling every 50ms. Assertions are
 * scoped to the ids each test creates, and nothing is deleted, so tests cannot step
 * on each other's jobs. The nested class disables the worker for the scenarios that
 * must be deterministic (idempotent retry, attempts exhaustion, optimistic locking);
 * it gets its own Spring context and — via the {@code random.uuid} datasource URL in
 * application-test.yml — its own H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobLifecycleIntegrationTest {

    private static final Duration AWAIT_AT_MOST = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID createJob(String name, String payload) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("name", name)
                .put("type", "echo")
                .put("payload", payload)
                .toString();
        String response = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private JsonNode fetchJob(UUID id) throws Exception {
        String response = mockMvc.perform(get("/api/jobs/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode awaitStatus(UUID id, JobStatus expected) {
        await().atMost(AWAIT_AT_MOST).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(fetchJob(id).path("status").asText()).isEqualTo(expected.name()));
        try {
            return fetchJob(id);
        } catch (Exception e) {
            throw new AssertionError("Could not re-read job " + id, e);
        }
    }

    @Test
    void aSubmittedJob_isPickedUpByTheWorker_andSucceeds() throws Exception {
        UUID id = createJob("happy-path", "ordinary payload");

        JsonNode job = awaitStatus(id, JobStatus.SUCCEEDED);

        assertThat(job.path("attempts").asInt()).isEqualTo(1);
        assertThat(job.path("startedAt").isNull()).isFalse();
        assertThat(job.path("completedAt").isNull()).isFalse();
        assertThat(job.path("errorMessage").isNull()).isTrue();
        // Pin the wire format the frontend depends on: ISO-8601 instants.
        assertThat(Instant.parse(job.path("createdAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(job.path("version").asLong()).isGreaterThan(0);
    }

    @Test
    void aFlakyJob_failsWithAnErrorMessage_andRecoversAfterARetry() throws Exception {
        UUID id = createJob("flaky-then-fine", "flaky payload");

        JsonNode failed = awaitStatus(id, JobStatus.FAILED);
        assertThat(failed.path("errorMessage").asText()).contains("flaky");
        assertThat(failed.path("attempts").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/jobs/{id}/retry", id))
                .andExpect(status().isOk());

        JsonNode recovered = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(recovered.path("attempts").asInt()).isEqualTo(2);
        assertThat(recovered.path("errorMessage").isNull()).isTrue();
    }

    @Test
    void aJobWithTheFailMarker_failsEveryAttempt() throws Exception {
        UUID id = createJob("always-broken", "this will fail");

        JsonNode failed = awaitStatus(id, JobStatus.FAILED);

        assertThat(failed.path("attempts").asInt()).isEqualTo(1);
        assertThat(failed.path("errorMessage").asText()).contains("fail");
    }

    @Test
    void theJobList_containsTheCreatedJob_newestFirst() throws Exception {
        UUID newest = createJob("list-me", "ordinary");

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newest.toString()));
    }

    /**
     * Deterministic scenarios: the worker is off, so nothing races the test while it
     * drives the lifecycle through {@link JobService} directly and asserts over HTTP.
     */
    @Nested
    @TestPropertySource(properties = "queueboard.processing.enabled=false")
    class WithTheWorkerDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private JobService jobService;

        @Autowired
        private JobRepository repository;

        private UUID createFailedJob() throws Exception {
            String response = mockMvc.perform(post("/api/jobs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"to-fail","type":"echo","payload":"x"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            UUID id = UUID.fromString(objectMapper.readTree(response).path("id").asText());
            jobService.start(id);
            jobService.fail(id, "forced by test");
            return id;
        }

        @Test
        void retry_isIdempotentOverHttp_neverDoubleQueues() throws Exception {
            UUID id = createFailedJob();

            String first = mockMvc.perform(post("/api/jobs/{id}/retry", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.attempts").value(1))
                    .andReturn().getResponse().getContentAsString();
            long versionAfterRetry = objectMapper.readTree(first).path("version").asLong();

            // Re-sending the same retry is a no-op: same state, same version, no re-queue.
            mockMvc.perform(post("/api/jobs/{id}/retry", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.attempts").value(1))
                    .andExpect(jsonPath("$.version").value((int) versionAfterRetry));
        }

        @Test
        void onceAttemptsAreExhausted_retryReturns409ProblemDetails() throws Exception {
            UUID id = createFailedJob();          // attempt 1 failed
            jobService.retry(id);
            jobService.start(id);
            jobService.fail(id, "forced again");  // attempt 2 failed
            jobService.retry(id);
            jobService.start(id);
            jobService.fail(id, "forced a third time"); // attempt 3 failed — exhausted

            mockMvc.perform(post("/api/jobs/{id}/retry", id))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("exhausted")));
        }

        @Test
        void aStaleWrite_hitsOptimisticLocking_andTheStateStaysConsistent() throws Exception {
            UUID id = createFailedJob();

            // Two detached copies of the same row — two actors holding the same version.
            Job copyA = repository.findById(id).orElseThrow();
            Job copyB = repository.findById(id).orElseThrow();

            copyA.retry(3);
            Job savedA = repository.saveAndFlush(copyA); // A wins; the row's version moves on

            copyB.retry(3);
            assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                    .isThrownBy(() -> repository.saveAndFlush(copyB)); // B loses loudly

            Job reloaded = repository.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(reloaded.getVersion()).isEqualTo(savedA.getVersion());
        }

        @Test
        void anUnknownStatusFilter_returns400Problem() throws Exception {
            mockMvc.perform(get("/api/jobs").param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }
}
