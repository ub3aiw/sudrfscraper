package com.github.courtandrey.sudrfscraper;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class HttpClientFactory {
    private static final int TIMEOUT = 60;

    public CloseableHttpClient getCloseableHttpClient(String host, Integer port) {
        HttpHost proxy = null;
        if (!StringUtils.isEmpty(host) && Objects.nonNull(port)) {
            proxy = new HttpHost(host, port);
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT * 1000)
                .setConnectionRequestTimeout(TIMEOUT * 1000)
                .setSocketTimeout(TIMEOUT * 1000)
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(20);
        connectionManager.closeIdleConnections(60, TimeUnit.SECONDS);

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(Objects.nonNull(proxy) ? new DefaultProxyRoutePlanner(proxy) : null)
                .setDefaultRequestConfig(config)
                .build();
    }
}
