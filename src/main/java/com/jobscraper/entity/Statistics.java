package com.jobscraper.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "statistics")
public class Statistics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "total_jobs_parsed", nullable = false)
    private int totalJobsParsed;

    @Column(name = "total_time_ms", nullable = false)
    private long totalTimeMs;

    @Column(name = "last_fetch", nullable = false)
    private LocalDateTime lastFetch;
}