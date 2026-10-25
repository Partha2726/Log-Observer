package com.ielop.platform.logsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Elasticsearch client for the read-only {@code backend_reader} user (section 6.6).
 * Creating the client does not connect, so the backend starts while Elasticsearch
 * is down (NFR-06); each request then fails fast within its timeout.
 */
@Configuration
@EnableConfigurationProperties(LogSearchProperties.class)
public class EsClientConfig {

    @Bean(destroyMethod = "close")
    public Rest5Client elasticsearchRestClient(LogSearchProperties props) {
        var builder = Rest5Client.builder(URI.create(props.url()))
                .setRequestConfigCallback(r -> r
                        .setResponseTimeout(Timeout.of(props.timeout()))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2)))
                .setConnectionConfigCallback(c -> c.setConnectTimeout(Timeout.ofSeconds(2)));
        if (props.username() != null && !props.username().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (props.username() + ":" + (props.password() == null ? "" : props.password())).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new Header[]{new BasicHeader("Authorization", "Basic " + token)});
        }
        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(Rest5Client restClient) {
        return new ElasticsearchClient(new Rest5ClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
