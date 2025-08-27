package com.jobscraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class JobScraperApplication {

    public static void main(String[] args) {
        System.setProperty("java.io.tmpdir", "/tmp/job-scraper"); // создаст tmp специально для приложения
        new java.io.File("/tmp/job-scraper").mkdirs(); // на случай, если папка не существует
        SpringApplication.run(JobScraperApplication.class, args);

    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
