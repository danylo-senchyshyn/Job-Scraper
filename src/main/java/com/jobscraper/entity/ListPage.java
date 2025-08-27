package com.jobscraper.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Entity
@Table(name = "list_page")
@Getter
@Setter
public class ListPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "jobFunction")
    private String jobFunction;

    @Column(name = "countJobs")
    private Integer countJobs;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "tags", columnDefinition = "text")
    private String tags;
}