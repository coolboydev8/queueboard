package com.queueboard.job.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobStatus;

/**
 * Spring Data JPA repository for {@link Job}. Derived queries only — no Spring Data
 * types ({@code Sort}, {@code Pageable}) leak into the domain layer (ADR-0002).
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    /** The worker's claim candidate: oldest waiting job first (FIFO fairness). */
    Optional<Job> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);

    /** The board: newest first. */
    List<Job> findAllByOrderByCreatedAtDesc();

    /** The board filtered by lifecycle state: newest first. */
    List<Job> findByStatusOrderByCreatedAtDesc(JobStatus status);
}
