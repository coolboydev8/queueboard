package com.queueboard.job.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.queueboard.job.api.dto.CreateJobRequest;
import com.queueboard.job.api.dto.JobMapper;
import com.queueboard.job.api.dto.JobResponse;
import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobService;
import com.queueboard.job.domain.JobStatus;

import jakarta.validation.Valid;

/**
 * HTTP edge for jobs — thin: validate input, delegate to {@link JobService}, map to DTOs.
 * Implements the contract in docs/api/openapi.yaml (contract-first; the spec wins).
 * Errors are translated centrally in {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        Job job = jobService.create(request.name(), request.type(), request.payload());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(job.getId())
                .toUri();
        return ResponseEntity.created(location).body(JobMapper.toResponse(job));
    }

    @GetMapping
    public List<JobResponse> list(@RequestParam(required = false) JobStatus status) {
        return jobService.findAll(status).stream().map(JobMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobMapper.toResponse(jobService.getById(id));
    }

    @PostMapping("/{id}/retry")
    public JobResponse retry(@PathVariable UUID id) {
        return JobMapper.toResponse(jobService.retry(id));
    }
}
