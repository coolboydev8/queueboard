package com.queueboard.job.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobService;
import com.queueboard.job.domain.JobStatus;
import com.queueboard.job.domain.exception.JobNotFoundException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;

/**
 * Web-slice tests ({@code @WebMvcTest}) — the HTTP contract against a mocked service:
 * status codes, Location header, Problem Details shape, validation errors.
 * The contract under test is docs/api/openapi.yaml.
 */
@WebMvcTest(JobController.class)
class JobControllerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    // ---- create ---------------------------------------------------------------------

    @Test
    void create_withValidBody_returns201_withLocationHeader_andPendingJob() throws Exception {
        UUID id = UUID.randomUUID();
        Job job = new Job("nightly-report", "report", "{}");
        ReflectionTestUtils.setField(job, "id", id); // the id is database-generated in production
        when(jobService.create("nightly-report", "report", "{}")).thenReturn(job);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nightly-report","type":"report","payload":"{}"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/jobs/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("nightly-report"))
                .andExpect(jsonPath("$.type").value("report"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void create_withBlankName_returns400_withFieldError() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","type":"report"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void create_withMissingType_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"a job"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    void create_withOversizedPayload_returns400() throws Exception {
        String oversized = "x".repeat(10_001);
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"big","type":"echo","payload":"%s"}
                                """.formatted(oversized)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("payload"));
    }

    @Test
    void create_withMalformedJson_returns400Problem() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON));
    }

    // ---- read -----------------------------------------------------------------------

    @Test
    void list_delegatesTheStatusFilterAsAnEnum() throws Exception {
        when(jobService.findAll(JobStatus.FAILED)).thenReturn(List.of());

        mockMvc.perform(get("/api/jobs").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(jobService).findAll(JobStatus.FAILED);
    }

    @Test
    void list_withInvalidStatus_returns400Problem() throws Exception {
        mockMvc.perform(get("/api/jobs").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("BOGUS")));
    }

    @Test
    void get_withUnknownId_returns404Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.getById(id)).thenThrow(new JobNotFoundException("No job with id " + id));

        mockMvc.perform(get("/api/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Job not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void get_withMalformedUuid_returns404Problem() throws Exception {
        // An id that cannot exist is "not found" — the contract declares no 400 here,
        // and the frontend renders 404 as its "Job not found" page.
        mockMvc.perform(get("/api/jobs/{id}", "not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Job not found"));
    }

    // ---- retry ----------------------------------------------------------------------

    @Test
    void retry_whenNotAllowed_returns409Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.retry(id)).thenThrow(
                new RetryNotAllowedException("Job %s has exhausted its 3 attempts".formatted(id)));

        mockMvc.perform(post("/api/jobs/{id}/retry", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(containsString("exhausted")));
    }

    @Test
    void retry_onConcurrentModification_returns409Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.retry(id)).thenThrow(
                new ObjectOptimisticLockingFailureException(Job.class, id));

        mockMvc.perform(post("/api/jobs/{id}/retry", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("modified concurrently")));
    }

    @Test
    void unknownUrl_returns404_notA500() throws Exception {
        // The catch-all handler must not swallow framework ErrorResponses into 500s.
        mockMvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound());
    }
}
