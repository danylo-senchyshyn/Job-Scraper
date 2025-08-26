package com.jobscraper.services;

import com.jobscraper.controller.ApiResponse;
import com.jobscraper.entity.Item;
import com.jobscraper.entity.ListPage;
import com.jobscraper.repository.ItemRepository;
import com.jobscraper.repository.ListPageRepository;
import com.microsoft.playwright.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class JobDataService {

    private static final String URL_JOBS = "https://api.getro.com/api/v2/collections/89/search/jobs";
    private static final int MAX_SCROLLS = 20;

    private final RestTemplate restTemplate;
    private final ListPageRepository listPageRepository;
    private final ItemRepository itemRepository;
    private final Playwright playwright;
    private final Browser browser;

    public JobDataService(RestTemplate restTemplate, ListPageRepository listPageRepository, ItemRepository itemRepository) {
        this.restTemplate = restTemplate;
        this.listPageRepository = listPageRepository;
        this.itemRepository = itemRepository;

        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    public void fetchAndSaveAllListPages(String industry, int totalPages) throws InterruptedException {
        ExecutorService pageExecutor = Executors.newFixedThreadPool(5); // пул для страниц
        AtomicInteger totalSavedCount = new AtomicInteger(0);

        for (int page = 0; page < totalPages; page++) {
            int currentPage = page;
            pageExecutor.submit(() -> {
                try {
                    fetchAndSaveListPagesByIndustryAndPage(industry, currentPage, totalSavedCount);
                } catch (Exception e) {
                    System.err.println("Ошибка при обработке страницы " + currentPage + ": " + e.getMessage());
                }
            });
        }

        pageExecutor.shutdown();
        pageExecutor.awaitTermination(1, TimeUnit.HOURS);

        System.out.println("Всего сохранено вакансий: " + totalSavedCount.get());
    }

    public boolean fetchAndSaveListPagesByIndustryAndPage(String industry, int page, AtomicInteger totalSavedCount) {
        HttpHeaders headers = createHeaders(industry);

        Map<String, Object> filters = new HashMap<>();
        filters.put("job_functions", new String[]{industry});

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("hitsPerPage", 12);
        requestBody.put("page", page);
        requestBody.put("query", "");
        requestBody.put("filters", filters);

        HttpEntity<Map<String, Object>> postRequest = new HttpEntity<>(requestBody, headers);
        ResponseEntity<ApiResponse> responseJobs = restTemplate.exchange(
                URL_JOBS,
                HttpMethod.POST,
                postRequest,
                ApiResponse.class
        );

        ApiResponse apiResponse = responseJobs.getBody();
        if (apiResponse == null || apiResponse.getResults() == null) return false;

        List<ApiResponse.Job> jobs = apiResponse.getResults().getJobs();
        if (jobs == null || jobs.isEmpty()) return false;

        ExecutorService jobExecutor = Executors.newFixedThreadPool(10); // пул для вакансий
        List<Future<?>> futures = new ArrayList<>();

        for (ApiResponse.Job job : jobs) {
            futures.add(jobExecutor.submit(() -> processJob(job, industry, apiResponse.getResults().getCount(), totalSavedCount)));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { e.printStackTrace(); }
        }

        jobExecutor.shutdown();

        return true;
    }

    public void processJob(ApiResponse.Job job, String industry, int totalCount, AtomicInteger savedCount) {
        ApiResponse.Organization organization = job.getOrganization();
        if (organization == null) return;

        String jobUrl = job.getUrl();
        if (jobUrl == null || jobUrl.isBlank()) return;

        ListPage listPage = new ListPage();
        listPage.setJobFunction(industry);
        listPage.setCountJobs(totalCount);
        listPage.setUrl(jobUrl);
        listPage.setTags(getTags(organization, job));

        Item item = new Item();
        item.setPositionName(job.getTitle());
        String url = "https://jobs.techstars.com/companies/" + job.getOrganization().getSlug() + "/jobs/" + job.getSlug();
        item.setUrl(url);
        item.setLogoUrl(organization.getLogoUrl());
        item.setOrganizationTitle(organization.getName());
        item.setAddress(String.join(", ", job.getSearchableLocations()));
        item.setPostedDate(new Date(job.getCreatedAt() * 1000));

        Document doc = null;
        int attempts = 0;
        while (doc == null && attempts < 2) { // попробуем до 3 раз
            try {
                doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                        .timeout(15000) // 15 секунд
                        .get();
            } catch (IOException e) {
                attempts++;
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        if (doc != null) {
            Elements laborFunctions = doc.select("div.sc-beqWaB.bpXRKw");
            if (laborFunctions.size() > 1) item.setLaborFunction(laborFunctions.get(1).text());

            if (job.isHasDescription()) {
                Element descriptionElement = doc.selectFirst("div.sc-beqWaB.fmCCHr");
                if (descriptionElement != null) {
                    String description = descriptionElement.text().trim();
                    item.setDescription(description.isEmpty() ? null : description);
                }
            }
        } else {
            System.err.println("Пропущена вакансия, страница не загружена: " + url);
        }

        listPageRepository.save(listPage);
        itemRepository.save(item);
        savedCount.incrementAndGet();
    }

    public void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private HttpHeaders createHeaders(String refererIndustry) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("accept", "application/json");
        headers.set("accept-language", "uk,ru-UA;q=0.9,ru-RU;q=0.8,ru;q=0.7,en-US;q=0.6,en;q=0.5");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("dnt", "1");
        headers.set("origin", "https://jobs.techstars.com");
        headers.set("priority", "u=1, i");
        if (refererIndustry != null) {
            headers.set("referer", jobFunctionUrls().getOrDefault(refererIndustry, ""));
        }
        headers.set("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"");
        headers.set("sec-ch-ua-mobile", "?0");
        headers.set("sec-ch-ua-platform", "\"macOS\"");
        headers.set("sec-fetch-dest", "empty");
        headers.set("sec-fetch-mode", "cors");
        headers.set("sec-fetch-site", "cross-site");
        headers.set("sec-gpc", "1");
        headers.set("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
        return headers;
    }

    public void fetchAndSaveAllListPages() throws InterruptedException {
        List<String> industries = List.of(
                "Accounting & Finance", "Administration", "Compliance / Regulatory",
                "Customer Service", "Data Science", "Design", "IT",
                "Legal", "Marketing & Communications", "Operations",
                "Other Engineering", "People & HR", "Product",
                "Quality Assurance", "Sales & Business Development",
                "Software Engineering"
        );

        ExecutorService industryExecutor = Executors.newFixedThreadPool(3); // 3 индустрии одновременно

        for (String industry : industries) {
            industryExecutor.submit(() -> {
                int page = 0;
                boolean hasMore = true;
                AtomicInteger totalSavedCount = new AtomicInteger(0);

                while (hasMore) {
                    hasMore = fetchAndSaveListPagesByIndustryAndPage(industry, page, totalSavedCount);
                    page++;
                }
                System.out.println("Данные по индустрии " + industry + " загружены и сохранены! Всего вакансий: " + totalSavedCount.get());
            });
        }

        industryExecutor.shutdown();
        industryExecutor.awaitTermination(2, TimeUnit.HOURS);
    }

    public String getTags(ApiResponse.Organization organization, ApiResponse.Job job) {
        List<String> tags = new ArrayList<>();

        if (organization.getIndustryTags() != null && !organization.getIndustryTags().isEmpty()) {
            tags.addAll(organization.getIndustryTags());
        }

        int count = organization.getHeadCount();
        switch (count) {
            case 1 -> tags.add("1-10 employees");
            case 2 -> tags.add("11-50 employees");
            case 3 -> tags.add("51-200 employees");
            case 4 -> tags.add("201-1000 employees");
            case 5 -> tags.add("1000-5000 employees");
            case 6 -> tags.add("5001+ employees");
        }

        String stage = formatTag(organization.getStage());
        if (!stage.isBlank()) {
            tags.add(stage);
        }

        String seniority = job.getSeniority();
        if (seniority != null && !seniority.isBlank()) {
            tags.add(seniority);
        }

        return String.join(", ", tags);
    }

    public static String formatTag(String tag) {
        if (tag == null || tag.isBlank()) return "";
        tag = tag.replace("_plus", "+");
        String[] parts = tag.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.equals("+")) {
                sb.append("+");
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase())
                    .append(" ");
        }
        String result = sb.toString().trim();
        result = result.replace(" +", "+");
        return result;
    }

    public Map<String, String> jobFunctionUrls() {
        Map<String, String> jobFunctionUrls = new LinkedHashMap<>();

        jobFunctionUrls.put("Accounting & Finance", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkFjY291bnRpbmclMjAlMjYlMjBGaW5hbmNlIl19");
        jobFunctionUrls.put("Administration", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkFkbWluaXN0cmF0aW9uIl19");
        jobFunctionUrls.put("Compliance / Regulatory", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkNvbXBsaWFuY2UlMjAlMkYlMjBSZWd1bGF0b3J5Il19");
        jobFunctionUrls.put("Customer Service", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkN1c3RvbWVyJTIwU2VydmljZSJdfQ%3D%3D");
        jobFunctionUrls.put("Data Science", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkRhdGElMjBTY2llbmNlIl19");
        jobFunctionUrls.put("Design", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkRlc2lnbiJdfQ%3D%3D");
        jobFunctionUrls.put("IT", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIklUIl19");
        jobFunctionUrls.put("Legal", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIkxlZ2FsIl19");
        jobFunctionUrls.put("Marketing & Communications", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIk1hcmtldGluZyUyMCUyNiUyMENvbW11bmljYXRpb25zIl19");
        jobFunctionUrls.put("Operations", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIk9wZXJhdGlvbnMiXX0%3D");
        jobFunctionUrls.put("Other Engineering", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIk90aGVyJTIwRW5naW5lZXJpbmciXX0%3D");
        jobFunctionUrls.put("People & HR", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIlBlb3BsZSUyMCUyNiUyMEhSIl19");
        jobFunctionUrls.put("Product", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIlByb2R1Y3QiXX0%3D");
        jobFunctionUrls.put("Quality Assurance", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIlF1YWxpdHklMjBBc3N1cmFuY2UiXX0%3D");
        jobFunctionUrls.put("Sales & Business Development", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIlNhbGVzJTIwJTI2JTIwQnVzaW5lc3MlMjBEZXZlbG9wbWVudCJdfQ%3D%3D");
        jobFunctionUrls.put("Software Engineering", "https://jobs.techstars.com/jobs?filter=eyJqb2JfZnVuY3Rpb25zIjpbIlNvZnR3YXJlJTIwRW5naW5lZXJpbmciXX0%3D");

        return jobFunctionUrls;
    }

    public List<String> laborFunctions() {
        List<String> laborFunctions = List.of(
                "Software Engineering",
                "Sales &amp; Business Development",
                "Operations",
                "IT",
                "Product",
                "Marketing &amp; Communications",
                "Data Science",
                "Design",
                "Customer Service",
                "Accounting &amp; Finance",
                "Other Engineering",
                "Quality Assurance",
                "People &amp; HR",
                "Administration",
                "Legal",
                "Compliance / Regulatory"
        );
        return laborFunctions;
    }
}