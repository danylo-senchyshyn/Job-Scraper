package com.jobscraper.services;

import com.jobscraper.controller.ApiResponse;
import com.jobscraper.entity.Item;
import com.jobscraper.entity.ListPage;
import com.jobscraper.entity.Statistics;
import com.jobscraper.repository.ItemRepository;
import com.jobscraper.repository.ListPageRepository;
import com.jobscraper.repository.StatisticsRepository;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class JobDataService {

    private static final String URL_JOBS = "https://api.getro.com/api/v2/collections/89/search/jobs";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ListPageRepository listPageRepository;
    private final ItemRepository itemRepository;
    private final StatisticsRepository statisticsRepository;

    private final ExecutorService industryExecutor = Executors.newFixedThreadPool(3); // 3 индустрии одновременно
    private final ExecutorService jobExecutor = Executors.newCachedThreadPool();      // динамический пул вакансий

    private  final List<String> industries = List.of("Accounting & Finance", "Administration", "Compliance / Regulatory", "Customer Service", "Data Science", "Design", "IT", "Legal", "Marketing & Communications", "Operations", "Other Engineering", "People & HR", "Product", "Quality Assurance", "Sales & Business Development", "Software Engineering");

    private final AtomicInteger jobsParsedCounter = new AtomicInteger(0);

    public JobDataService(ListPageRepository listPageRepository, ItemRepository itemRepository, StatisticsRepository statisticsRepository) {
        this.listPageRepository = listPageRepository;
        this.itemRepository = itemRepository;
        this.statisticsRepository = statisticsRepository;
    }

    public void fetchAndSaveAllListPages() {
        long start = System.currentTimeMillis();
        itemRepository.deleteAll();
        listPageRepository.deleteAll();
        jobsParsedCounter.set(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String industry : industries) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> fetchIndustry(industry), industryExecutor);
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        //shutdownExecutors();

        long end = System.currentTimeMillis();
        long duration = end - start;

        String formatted = formatDuration(duration);
        System.out.println("✅ Все вакансии сохранены за " + formatted);
        System.out.println("✅ Всего обработано вакансий: " + jobsParsedCounter.get());

        Statistics stats = new Statistics();
        stats.setTotalJobsParsed(jobsParsedCounter.get());
        stats.setTotalTimeMs(duration);
        stats.setLastFetch(LocalDateTime.now());
        statisticsRepository.save(stats);
    }

    private void fetchIndustry(String industry) {
        int page = 0;
        boolean hasMore = true;
        while (hasMore) {
            int currentPage = page;
            try {
                hasMore = fetchPage(industry, currentPage).join();
            } catch (Exception e) {
                System.err.println("Ошибка на странице " + currentPage + " индустрии " + industry);
                hasMore = false;
            }
            page++;
        }
    }

    private CompletableFuture<Boolean> fetchPage(String industry, int page) {
        Map<String, Object> body = Map.of(
                "hitsPerPage", 12,
                "page", page,
                "query", "",
                "filters", Map.of("job_functions", new String[]{industry})
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL_JOBS))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONObject(body).toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() != 200) {
                        System.err.println("Ошибка запроса: HTTP " + response.statusCode());
                        return CompletableFuture.completedFuture(false);
                    }

                    String responseBody = response.body(); // <- новое имя
                    if (responseBody == null || responseBody.isEmpty()) {
                        System.err.println("Пустой ответ от сервера");
                        return CompletableFuture.completedFuture(false);
                    }

                    try {
                        JSONObject json = new JSONObject(responseBody); // используем responseBody
                        JSONArray jobs = json.getJSONObject("results").getJSONArray("jobs");
                        if (jobs.isEmpty()) return CompletableFuture.completedFuture(false);

                        List<CompletableFuture<Void>> jobFutures = new ArrayList<>();
                        for (int i = 0; i < jobs.length(); i++) {
                            JSONObject jobJson = jobs.getJSONObject(i);
                            jobFutures.add(CompletableFuture.runAsync(() -> processJob(jobJson, industry), jobExecutor));
                        }

                        return CompletableFuture.allOf(jobFutures.toArray(new CompletableFuture[0]))
                                .thenApply(v -> true);
                    } catch (JSONException e) {
                        System.err.println("Ошибка парсинга JSON: " + responseBody);
                        e.printStackTrace();
                        return CompletableFuture.completedFuture(false);
                    }
                });
    }

    private void processJob(JSONObject jobJson, String industry) {
        long start = System.currentTimeMillis();
        try {
            JSONObject orgJson = jobJson.optJSONObject("organization");

            ListPage listPage = new ListPage();
            listPage.setJobFunction(industry);
            listPage.setUrl(jobJson.optString("url", ""));
            listPage.setCountJobs(jobJson.optJSONObject("results") != null
                    ? jobJson.getJSONObject("results").optInt("count", 0)
                    : 0);
            listPage.setTags(getTags(industry, jobJson));

            Item item = new Item();
            item.setPositionName(jobJson.optString("title", ""));
            String url = "https://jobs.techstars.com/companies/"
                    + jobJson.getJSONObject("organization").getString("slug")
                    + "/jobs/"
                    + jobJson.getString("slug");
            item.setUrl(url);
            item.setLogoUrl(orgJson != null ? orgJson.optString("logo_url", "") : "");
            item.setOrganizationTitle(orgJson != null ? orgJson.optString("name", "") : "");

            // locations
            JSONArray locations = jobJson.optJSONArray("searchable_locations");
            if (locations != null) {
                List<String> locs = new ArrayList<>();
                for (int i = 0; i < locations.length(); i++) {
                    locs.add(locations.optString(i, ""));
                }
                item.setAddress(String.join(", ", locs));
            }

            // posted date
            long createdAt = jobJson.optLong("created_at", 0);
            if (createdAt > 0) {
                item.setPostedDate(new Date(createdAt * 1000));
            }

            // description and labor function
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

            //System.out.println(jobJson.getBoolean("has_description") + " - " + jobJson.optString("title", ""));
            if (doc != null) {
                Elements laborFunctions = doc.select("div.sc-beqWaB.bpXRKw");
                if (laborFunctions.size() > 1) {
                    item.setLaborFunction(laborFunctions.get(1).text());
                } else {
                    item.setLaborFunction(industry);
                }

                //System.out.print(jobJson.getBoolean("has_description"));
                if (jobJson.getBoolean("has_description")) {
                    Element descriptionElement = doc.selectFirst("div.sc-beqWaB.fmCCHr");
                    if (descriptionElement != null) {
                        String description = descriptionElement.text().trim();
                        item.setDescription(description.isEmpty() ? null : description);
                    } else {
                        System.out.println("Не найдно описание - " + item.getPositionName() + " - " + url + " industry: " + industry);
                    }
                }
            }

            CompletableFuture.runAsync(() -> listPageRepository.save(listPage), jobExecutor);
            CompletableFuture.runAsync(() -> itemRepository.save(item), jobExecutor);
        } catch (JSONException e) {
            e.printStackTrace();
        } finally {
            jobsParsedCounter.incrementAndGet();

            long end = System.currentTimeMillis();
            long duration = end - start;
            String formatted = formatDuration(duration);
            System.out.println(formatted + jobJson.optString("title", ""));
        }
    }

    private String getTags(String industry, JSONObject jobJson) {
        List<String> tags = new ArrayList<>();

        // Добавляем индустрию
        if (industry != null && !industry.isBlank()) {
            tags.add(industry);
        }

        // Тэги организации
        JSONObject orgJson = jobJson.optJSONObject("organization");
        if (orgJson != null) {
            JSONArray industryTags = orgJson.optJSONArray("industryTags");
            if (industryTags != null) {
                for (int i = 0; i < industryTags.length(); i++) {
                    tags.add(industryTags.optString(i, ""));
                }
            }

            int headCount = orgJson.optInt("headCount", 0);
            switch (headCount) {
                case 1 -> tags.add("1-10 employees");
                case 2 -> tags.add("11-50 employees");
                case 3 -> tags.add("51-200 employees");
                case 4 -> tags.add("201-1000 employees");
                case 5 -> tags.add("1000-5000 employees");
                case 6 -> tags.add("5001+ employees");
            }

            String stage = formatTag(orgJson.optString("stage", ""));
            if (!stage.isBlank()) {
                tags.add(stage);
            }
        }

        // Тэг уровня вакансии
        String seniority = jobJson.optString("seniority", "");
        if (!seniority.isBlank()) {
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

    public void shutdownExecutors() {
        industryExecutor.shutdown();
        jobExecutor.shutdown();
    }

    public String formatDuration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long ms = millis % 1000;

        if (minutes > 0) {
            return String.format("%d мин %d сек %d мс - ", minutes, seconds, ms);
        } else if (seconds > 0) {
            return String.format("%d сек %d мс - ", seconds, ms);
        } else {
            return String.format("%d мс - ", ms);
        }
    }
}
