package com.github.courtandrey.sudrfscraper;

import com.github.courtandrey.sudrfscraper.configuration.courtconfiguration.CourtConfiguration;
import com.github.courtandrey.sudrfscraper.configuration.courtconfiguration.SearchPattern;
import com.github.courtandrey.sudrfscraper.configuration.courtconfiguration.StrategyName;
import com.github.courtandrey.sudrfscraper.configuration.searchrequest.Field;
import com.github.courtandrey.sudrfscraper.configuration.searchrequest.SearchRequest;
import com.github.courtandrey.sudrfscraper.configuration.searchrequest.article.*;
import com.github.courtandrey.sudrfscraper.service.ConfigurationLoader;
import com.github.courtandrey.sudrfscraper.service.Constant;
import com.github.courtandrey.sudrfscraper.service.ConstantsGetter;
import com.github.courtandrey.sudrfscraper.service.URLCreator;
import com.github.courtandrey.sudrfscraper.strategy.Connection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
@Slf4j
class SudrfscraperApplicationTests {

	private static final String CAPTCHA_ELEMENT = "captcha";
	private static final String SELENIUM_ELEMENT = "show-sf";
	public static final String WRONG_FORMAT = "НЕВЕРНЫЙ ФОРМАТ ЗАПРОСА!";

	private static int totalLinks = 0;
	private static int processedLinks = 0;

	@Autowired
	private HttpClientFactory httpClientFactory;

	private static CloseableHttpClient httpClient;

	@BeforeEach
	void setUp(@Value("${proxy.host}") String host, @Value("${proxy.port}") Integer port) {
		httpClient = httpClientFactory.getCloseableHttpClient(host, port);
	}

	@AfterEach
	void tearDown() throws IOException {
		httpClient.close();
	}

	@Test
    void checkCaptcha(@Value("${test.url.part.captcha}") String s, @Value("${test.regions}") String regionsString) throws IOException {
		List<Integer> regions = parseRegions(regionsString);

		List<CourtConfiguration> courtConfigurations = ConfigurationLoader.getCourtConfigurations().stream()
				.filter(cc -> !cc.isHasCaptcha())
				.filter(cc -> Objects.isNull(regions) || regions.isEmpty() || regions.contains(cc.getRegion()))
				.toList();

		Set<Integer> ids = courtConfigurations.stream().map(CourtConfiguration::getId).collect(Collectors.toSet());

		totalLinks = courtConfigurations.size();

		List<CourtConfiguration> updatedConfigurations = checkAndUpdateConfigurations(ids, s, CAPTCHA_ELEMENT);

		ConfigurationLoader.refresh(updatedConfigurations);
    }

	@Test
	void checkInterface(@Value("${test.url.part.interface}") String s, @Value("${test.regions}") String regionsString) throws IOException {
		List<Integer> regions = parseRegions(regionsString);

		List<CourtConfiguration> courtConfigurations = ConfigurationLoader.getCourtConfigurations().stream()
				.filter(cc -> cc.getId() != 2260)
				.filter(cc -> Objects.isNull(regions) || regions.isEmpty() || regions.contains(cc.getRegion()))
				.toList();

		Set<Integer> ids = courtConfigurations.stream().map(CourtConfiguration::getId).collect(Collectors.toSet());

		totalLinks = courtConfigurations.size();

		List<CourtConfiguration> updatedConfigurations = checkAndUpdateConfigurations(ids, s, SELENIUM_ELEMENT);

		ConfigurationLoader.refresh(updatedConfigurations);
	}

	@Test
	void checkPatternV2(@Value("${test.regions}") String regionsString, @Value("${test.case}") String testCase) throws IOException {
		List<Integer> regions = parseRegions(regionsString);

		List<CourtConfiguration> courtConfigurations = ConfigurationLoader.getCourtConfigurations().stream()
				.filter(cc -> cc.getId() != 2260)
				.filter(cc -> Objects.isNull(regions) || regions.isEmpty() || regions.contains(cc.getRegion()))
				.toList();

		log.info("Courts count {}", courtConfigurations.size());

		SearchRequest searchRequest = SearchRequest.getInstance();
		searchRequest.setResultDateFrom(LocalDate.of(2022, 1, 1));
		setArticleForSearchRequest(searchRequest, testCase);

		Map<CourtConfiguration, HashMap<String, Boolean>> map = new HashMap<>();

		ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

		try {
			List<Future<Map.Entry<CourtConfiguration, HashMap<String, Boolean>>>> futures = courtConfigurations.stream()
					.map(cc -> executorService.submit(() -> createAndCheckURLsForCC(cc)))
					.toList();

			for (Future<Map.Entry<CourtConfiguration, HashMap<String, Boolean>>> future : futures) {
				Map.Entry<CourtConfiguration, HashMap<String, Boolean>> entry = future.get();
				map.put(entry.getKey(), entry.getValue());
			}
		} catch (InterruptedException | ExecutionException e) {
			log.error("Error in parallel execution", e);
		} finally {
			executorService.shutdown();
		}

		map.entrySet().stream()
				.filter(entry -> entry.getValue().values().stream().anyMatch(r -> r))
				.map(Map.Entry::getKey)
				.forEach(cc -> System.out.println(cc.getSearchString() + " " + cc.getSearchPattern()));

        List<CourtConfiguration> updatedConfigurations = ConfigurationLoader.getCourtConfigurations().stream()
                .peek(cc -> {
                    Map<String, Boolean> results = map.get(cc);
                    if (results != null && results.values().stream().anyMatch(Boolean.TRUE::equals)) {
                        cc.setSearchPattern(SearchPattern.PRIMARY_PATTERN);
                    }
                }).toList();

        ConfigurationLoader.refresh(updatedConfigurations);
	}

	private Map.Entry<CourtConfiguration, HashMap<String, Boolean>> createAndCheckURLsForCC(CourtConfiguration cc) {
		HashMap<String, Boolean> urlResults = new HashMap<>();
		List<String> urls = List.of(new URLCreator(cc).createUrls());
		for (String url : urls) {
			urlResults.put(url, checkElement(url));
		}
		log.info("Court checked: {}", cc);
		return Map.entry(cc, urlResults);
	}

	private static void setArticleForSearchRequest(SearchRequest searchRequest, String testCase) {
		switch (testCase) {
			case "criminal" -> {
				searchRequest.setField(Field.CRIMINAL);
				CriminalArticle article = new CriminalArticle();
				article.setArticle(105);
				searchRequest.setArticle(article);
            }
			case "admin" -> {
				searchRequest.setField(Field.ADMIN);
				AdminArticle article = new AdminArticle();
				article.setChapter(20);
				article.setArticle(3);
				article.setSubArticle(3);
				searchRequest.setArticle(article);
			}
			case "civil" -> {
				searchRequest.setField(Field.CIVIL);
				CivilArticle article = new CivilArticle();
				article.setPartOfCas("О расторжении брака супругов");
				article.setMosgorsudCode("1ad1f0a");
				searchRequest.setArticle(article);
			}
			case "cas" -> {
				searchRequest.setField(Field.CAS);
				CASArticle article = new CASArticle();
				article.setPartOfCas("О взыскании налогов и сборов");
				article.setMosgorsudCode("198");
				searchRequest.setArticle(article);
			}
			case "material" -> {
				searchRequest.setField(Field.MATERIAL_PROCEEDING);
				MaterialProceedingArticle article = new MaterialProceedingArticle();
				article.setPartOfUPK("жалобы на действия (бездействия) и решения должностных лиц, осуществляющих уголовное производство (ст.125 УПК РФ)");
				article.setMosgorsudCode("7db3ac3");
				searchRequest.setArticle(article);
			}
		}

		log.info("Request: {}", searchRequest);
	}

	private boolean checkElement(String url) {
        HttpGet request = new HttpGet(url);
        request.addHeader(HttpHeaders.USER_AGENT, ConstantsGetter.getStringConstant(Constant.UA));
        CloseableHttpResponse response;
        try {
            response = httpClient.execute(request);

			String html = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

			Document document = Jsoup.parse(html);

			if (StringUtils.containsIgnoreCase(document.body().text(), "заблокирован")) {
				log.error("url blocked! - {}", url);
			}

			Element element = document.getElementById("content");

			if (element != null) {
				return StringUtils.containsIgnoreCase(element.text(), WRONG_FORMAT);
			}
		} catch (IOException e) {
			log.error("Connect error: {}, {}", url, e.getMessage());
			return false;
		}
        return false;
    }

	private List<CourtConfiguration> checkAndUpdateConfigurations(Set<Integer> ids, String s, String element) throws IOException {
		int numThreads = Runtime.getRuntime().availableProcessors();
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		List<Future<CourtConfiguration>> futures = ConfigurationLoader.getCourtConfigurations().stream()
				.map(config -> executor.submit(() -> {
                    if (ids.contains(config.getId())) {
                        return checkAndUpdateConfiguration(config, s, element);
                    } else {
                        return config;
                    }
                }))
				.toList();

		List<CourtConfiguration> updatedConfigurations = futures.stream()
				.map(future -> {
					try {
						return future.get();
					} catch (InterruptedException | ExecutionException e) {
						log.error("Error updating configuration", e);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

		executor.shutdown();

		try {
			if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
		}

		return updatedConfigurations;
	}

	private CourtConfiguration checkAndUpdateConfiguration(CourtConfiguration config, String s, String element) {
		String url = config.getSearchString() + s;

        try {
			HttpGet request = new HttpGet(url);
			request.addHeader(HttpHeaders.USER_AGENT, ConstantsGetter.getStringConstant(Constant.UA));
            CloseableHttpResponse response = httpClient.execute(request);
			log.info("{} - code {}", url, response.getStatusLine().getStatusCode());

			String html = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
			Document document = Jsoup.parse(html);

			Element e = document.getElementById(element);

			if (element.equals(CAPTCHA_ELEMENT)) {
				config.setHasCaptcha(Objects.nonNull(e));
				if (e != null) {
					config.setStrategyName(StrategyName.CAPTCHA_STRATEGY);
					log.info("{} set Captcha", config.getSearchString());
                }
			} else if (element.equals(SELENIUM_ELEMENT)) {
				config.setConnection(Objects.nonNull(e) ? Connection.SELENIUM : Connection.REQUEST);
			}

			synchronized (this) {
				processedLinks++;
				printProgress(processedLinks, totalLinks);
			}
		} catch (Exception e) {
            log.error("Error processing URL: {}", url, e);
			synchronized (this) {
				processedLinks++;
				printProgress(processedLinks, totalLinks);
			}
		}

        return config;
    }

	private static List<Integer> parseRegions(String regionsString) {
		List<Integer> list = null;

		try {
			list = Arrays.stream(regionsString.split(","))
					.map(Integer::parseInt)
					.toList();

            log.info("Selected regions: {}", regionsString);
		} catch (Exception ignored) {
		}

		return list;
	}

	private static void printProgress(int current, int total) {
		int progressBarLength = 100;
		int progress = (int) ((double) current / total * progressBarLength);

		StringBuilder progressBar = new StringBuilder();
		for (int i = 0; i < progressBarLength; i++) {
			if (i < progress) {
				progressBar.append("#");
			} else {
				progressBar.append(".");
			}
		}

		System.out.printf("\r[%s] %d%%", progressBar, (current * 100 / total));

		if (current == total) System.out.println();
	}
}
