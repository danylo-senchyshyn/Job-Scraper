package com.jobscraper.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ApiResponse {
    private Results results;

    @Getter
    @Setter
    public static class Results {
        private List<Job> jobs;
        private int count;
    }

    @Getter
    @Setter
    public static class Job {
        private boolean featured;
        private Long compensationAmountMaxCents;
        @JsonProperty("searchable_locations")
        private List<String> searchableLocations;
        @JsonProperty("created_at")
        private long createdAt;
        private int weight;
        private Long compensationAmountMinCents;
        private String source;
        private String title;
        private String workMode;
        private String url;
        private Boolean compensationOffersEquity;
        private String compensationCurrency;
        private String compensationPeriod;
        private Organization organization;
        private List<String> locations;
        @JsonProperty("has_description")
        private boolean hasDescription;
        private String slug;
        private String seniority;
        private long id;
    }

    @Getter
    @Setter
    public static class Organization {
        private String stage;
        @JsonProperty("logo_url")
        private String logoUrl;
        private List<String> topics;
        private String name;
        private int headCount;
        private long id;
        private List<String> industryTags;
        private String slug;
    }
}