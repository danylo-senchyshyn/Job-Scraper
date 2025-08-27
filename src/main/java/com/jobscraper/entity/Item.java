package com.jobscraper.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Date;

@Entity
@Table(name = "item")
@Data
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "positionName")
    private String positionName;
    
    @Column(name = "url", columnDefinition = "text")
    private String url;

    @JsonProperty("logo_url")
    @Column(name = "logoUrl")
    private String logoUrl;

    @Column(name = "organizationTitle")
    private String organizationTitle;

    @Column(name = "laborFunction")
    private String laborFunction;
    
    @Column(name = "address", columnDefinition = "text")
    private String address;

    @Column(name = "postedDate")
    @Temporal(TemporalType.DATE)
    private Date postedDate;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}