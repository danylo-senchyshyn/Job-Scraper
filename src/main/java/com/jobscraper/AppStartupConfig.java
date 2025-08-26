package com.jobscraper;

import com.jobscraper.services.JobDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AppStartupConfig {

    private final JobDataService jobDataService;

    public AppStartupConfig(JobDataService jobDataService) {
        this.jobDataService = jobDataService;
    }

    @Bean
    public CommandLineRunner runOnStartup() {
        return args -> {
            jobDataService.fetchAndSaveAllListPages();
            jobDataService.closeBrowser();
            System.out.println("Детали вакансий успешно распарсены и сохранены!");
        };
    }
}