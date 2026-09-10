package com.example.inframanager;

import com.example.inframanager.deployment.BambooClient;
import com.example.inframanager.deployment.BambooDeploymentPoller;
import com.example.inframanager.jira.JiraClient;
import com.example.inframanager.notify.TelegramClient;
import com.example.inframanager.pullrequest.BitbucketClient;
import com.example.inframanager.pullrequest.BitbucketPrPoller;
import com.example.inframanager.trello.TrelloClient;
import com.example.inframanager.trello.TrelloReconciliationPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds every integration client for real -- no mocks anywhere.
 *
 * <p>This exists because a missing {@code RestClient.Builder} bean once reached a
 * running container: every other test either constructed its client by hand or
 * replaced it with {@code @MockitoBean}, so no test ever executed the actual
 * {@code @Bean} factory methods. Anything that only breaks when a client is really
 * assembled belongs here.
 *
 * <p>Scheduling is off, so nothing reaches out over the network -- the hostnames
 * below are never dialled.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",

        "infra-manager.telegram.enabled=true",
        "infra-manager.telegram.bot-token=test-bot-token",

        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.trello.reconciliation.enabled=true",

        "infra-manager.jira.enabled=true",
        "infra-manager.jira.base-url=https://jira.invalid",
        "infra-manager.jira.token=test-token",

        "infra-manager.bitbucket.enabled=true",
        "infra-manager.bitbucket.webhook-secret=test-secret",
        "infra-manager.bitbucket.base-url=https://bitbucket.invalid",
        "infra-manager.bitbucket.token=test-token",
        "infra-manager.bitbucket.poll.enabled=true",
        "infra-manager.lifecycle.repos[0].project-key=LIZA",
        "infra-manager.lifecycle.repos[0].repo-slug=liza",
        "infra-manager.lifecycle.repos[0].trello-board-id=board-1",

        // Poll rather than webhook: the webhook controller is covered elsewhere, and
        // the two modes are mutually exclusive within one context.
        "infra-manager.bamboo.source=poll",
        "infra-manager.bamboo.base-url=https://bamboo.invalid",
        "infra-manager.bamboo.token=test-token",
        "infra-manager.bamboo.poll.environments[0].id=1",
        "infra-manager.bamboo.poll.environments[0].project-name=Project",
        "infra-manager.bamboo.poll.environments[0].environment-name=stand"
})
@Import(TestcontainersConfiguration.class)
class AllIntegrationsEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void everyIntegrationClientIsActuallyConstructible() {
        assertThat(context.getBean(TelegramClient.class)).isNotNull();
        assertThat(context.getBean(TrelloClient.class)).isNotNull();
        assertThat(context.getBean(JiraClient.class)).isNotNull();
        assertThat(context.getBean(BambooClient.class)).isNotNull();
        assertThat(context.getBean(BitbucketClient.class)).isNotNull();
    }

    @Test
    void everyPollerIsWiredWhenItsModeIsOn() {
        assertThat(context.getBean(BambooDeploymentPoller.class)).isNotNull();
        assertThat(context.getBean(TrelloReconciliationPoller.class)).isNotNull();
        assertThat(context.getBean(BitbucketPrPoller.class)).isNotNull();
    }

    @Test
    void theBitbucketWebhookEndpointIsExposed() {
        assertThat(context.getBeanNamesForType(
                com.example.inframanager.pullrequest.BitbucketWebhookController.class)).isNotEmpty();
    }
}
