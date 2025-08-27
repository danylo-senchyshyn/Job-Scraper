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
    private int totalJobsParsed;
    private long totalTimeMs;
    private LocalDateTime lastFetch;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean decriptionsAndLaborFunctions;
}