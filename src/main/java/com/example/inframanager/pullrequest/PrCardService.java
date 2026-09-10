package com.example.inframanager.pullrequest;

import java.util.Optional;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEvent;
import com.example.inframanager.event.InboundEventHandler;
import com.example.inframanager.jira.IssueKeyExtractor;
import com.example.inframanager.jira.JiraEnricher;
import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import com.example.inframanager.trello.TrelloCardCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a stored Bitbucket pull request event into a queued Trello card update.
 *
 * <p>Decides <em>what the card should look like</em> and hands that to the queue;
 * it never calls Trello itself. That keeps the inbound worker fast and puts every
 * Trello call behind one rate limiter.
 */
@Component
public class PrCardService implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PrCardService.class);
    private static final String DELETED_EVENT = "pr:deleted";

    private final LifecycleProperties lifecycle;
    private final PrCardContentRenderer renderer;
    private final OutboundTaskService taskService;
    private final IssueKeyExtractor issueKeyExtractor;
    private final JiraEnricher jiraEnricher;
    private final ObjectMapper objectMapper;

    public PrCardService(LifecycleProperties lifecycle,
                         PrCardContentRenderer renderer,
                         OutboundTaskService taskService,
                         IssueKeyExtractor issueKeyExtractor,
                         JiraEnricher jiraEnricher,
                         ObjectMapper objectMapper) {
        this.lifecycle = lifecycle;
        this.renderer = renderer;
        this.taskService = taskService;
        this.issueKeyExtractor = issueKeyExtractor;
        this.jiraEnricher = jiraEnricher;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(EventSource source) {
        return source == EventSource.BITBUCKET;
    }

    @Override
    public void handle(InboundEvent event) {
        BitbucketPrEvent parsed = objectMapper.readValue(event.getPayload(), BitbucketPrEvent.class);

        Optional<PullRequestRef> maybeRef = parsed.ref();
        if (maybeRef.isEmpty()) {
            // Not retryable: no amount of waiting adds a repository to the payload.
            throw new IllegalArgumentException(
                    "Bitbucket payload has no target repository; cannot identify the pull request");
        }
        PullRequestRef ref = maybeRef.get();

        Optional<LifecycleProperties.RepoBoard> board = lifecycle.boardFor(ref);
        if (board.isEmpty()) {
            // Expected whenever the webhook is enabled on more repos than we mirror.
            log.debug("No Trello board configured for {}; ignoring {}", ref.asKey(), event.getEventType());
            return;
        }

        String eventKey = event.getEventType();
        boolean archive = DELETED_EVENT.equals(eventKey);

        String issueKey = issueKeyExtractor
                .extract(parsed.sourceBranch(),
                        parsed.pullRequest() == null ? null : parsed.pullRequest().title())
                .orElse(null);
        // Never throws: a card with a plainer title beats no card at all.
        String issueSummary = jiraEnricher.summaryFor(issueKey).orElse(null);

        TrelloCardCommand command = new TrelloCardCommand(
                ref,
                board.get().trelloBoardId(),
                lifecycle.listFor(eventKey).orElse(null),
                lifecycle.createInList(),
                renderer.title(parsed, ref, issueKey, issueSummary),
                renderer.description(parsed, ref),
                issueKey,
                archive);

        // Keyed on the inbound event, so replaying that event does not queue a second
        // identical card update.
        String dedupKey = "trello:%s:%s".formatted(ref.asKey(), event.getExternalId());
        taskService.enqueue(OutboundTarget.TRELLO, "syncCard", dedupKey,
                objectMapper.writeValueAsString(command));

        log.info("Queued card sync for {} after {}{}", ref.asKey(), eventKey,
                command.moveToListName() == null ? " (content only)" : " -> '" + command.moveToListName() + "'");
    }
}
