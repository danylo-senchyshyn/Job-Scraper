# Job Scraper

**Job Scraper** is a Java application for collecting job listings from the [Techstars Jobs](https://jobs.techstars.com) platform via their API and parsing job pages using Jsoup. The application saves job and company information to a PostgreSQL database and supports optimized processing with a limit on the number of jobs for faster testing.

---

## Features

- Fetch jobs via the Techstars API.
- Parse HTML job pages using Jsoup to extract:
  - Job description
  - Labor function
- Save data to PostgreSQL:
  - `Item` table — individual job listings
  - `ListPage` table — job pages
  - `Statistics` table — scraper statistics
- Multithreaded processing with HTTP request limits for speed optimization.
- Option to limit the number of processed jobs (e.g., 1000) for testing.

---

## Technology Stack

- Java 17+
- Spring Boot
- Jsoup (HTML parser)
- PostgreSQL
- Maven
- Java HTTP Client (Java 11+)
- JSON processing (org.json)
- Multithreading (ExecutorService, CompletableFuture)
- Spring Data JPA

---

## Installation & Setup

### 1. Configure PostgreSQL

Update `application.properties` with your PostgreSQL settings:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/job_scraper
spring.datasource.username=postgres
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
```

Create the database: 

```bash
createdb job_scraper
```

### 2. Build the Project 

```bash
mvn clean install
```

### 3. Run the Project 

When running, the application will ask you to select a mode: 

```text
1. with description and labor function
2. without description and labor function
Select mode (1 or 2):
```
	•	1 — fetches full job data including HTML parsing.
	•	2 — fetches only basic job info without parsing pages.

Run the project: 

```bash
mvn spring-boot:run
```

### 4. Job Limit for Testing

To speed up testing, the scraper can limit the number of processed jobs (default 1000).
This is controlled by the stopProcessing flag and the jobsParsedCounter.

## Project Structure
```
src/main/java/com/jobscraper
├── AppStartupConfig.java      # Spring Boot startup configuration
└── services
    └── JobDataService.java   # Core job scraping logic
└── entity
    ├── Item.java
    ├── ListPage.java
    └── Statistics.java
└── repository
    ├── ItemRepository.java
    ├── ListPageRepository.java
    └── StatisticsRepository.java
└── controller
    └── ApiResponse.java      # API response model (optional)
```

## Optimization & Performance

	•	Uses ExecutorService and CompletableFuture for multithreading.
	•	Limits concurrent HTTP requests with a Semaphore (e.g., 20).
	•	Batch saving to the database every 50 jobs to reduce overhead.
