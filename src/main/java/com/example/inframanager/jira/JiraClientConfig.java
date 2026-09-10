package com.example.inframanager.jira;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.jira", name = "enabled", havingValue = "true")
public class JiraClientConfig {

    private final JiraProperties properties;

    public JiraClientConfig(JiraProperties properties) {
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.token())) {
            throw new IllegalStateException(
                    "infra-manager.jira.enabled=true requires JIRA_BASE_URL and JIRA_TOKEN");
        }
        this.properties = properties;
    }

    @Bean
    JiraClient jiraClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                // Data Center PATs are bearer tokens, not basic auth.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(JiraClient.class);
    }
}
