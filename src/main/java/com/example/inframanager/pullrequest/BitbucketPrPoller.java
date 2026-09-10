package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Recovers pull request events by polling, for installations where Bitbucket cannot
 * reach us.
 *
 * <p>A webhook says what happened. Polling shows only the present, so the event is
 * reconstructed by comparing each pull request against {@link PrPollState}. The
 * result is written in exactly the shape a webhook body has, which means everything
 * downstream -- {@link PrCardService}, the card lifecycle, idempotency -- is shared
 * between the two ingestion paths rather than duplicated.
 *
 * <p>Only repositories listed in {@code infra-manager.lifecycle.repos} are polled:
 * events from a repository we do not mirror would have nobody to act on them.
 */
public class BitbucketPrPoller {

    private static final Logger log = LoggerFactory.getLogger(BitbucketPrPoller.class);

    private final BitbucketClient client;
    private final BitbucketProperties properties;
    private final LifecycleProperties lifecycle;
    private final PrPollStateRepository stateRepository;
    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public BitbucketPrPoller(BitbucketClient client,
                             BitbucketProperties properties,
                             LifecycleProperties lifecycle,
                             PrPollStateRepository stateRepository,
                             InboundEventIngestService ingestService,
                             ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.stateRepository = stateRepository;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /** @return how many events this pass reconstructed */
    @Transactional
    public int runOnce() {
        int emitted = 0;
        for (LifecycleProperties.RepoBoard repo : lifecycle.repos()) {
            try {
                emitted += pollRepository(repo);
            } catch (DataAccessException e) {
                // A database failure has already marked this transaction rollback-only;
                // swallowing it would surface later as an unexplained UnexpectedRollback.
                throw e;
            } catch (Exception e) {
                // One unreachable repository must not stop the others.
                log.warn("Failed to poll {}/{}", repo.projectKey(), repo.repoSlug(), e);
            }
        }
        return emitted;
    }

    private int pollRepository(LifecycleProperties.RepoBoard repo) {
        BitbucketClient.PullRequestPage page = client.pullRequests(
                repo.projectKey(), repo.repoSlug(), "ALL", "NEWEST", properties.poll().maxResults());

        List<BitbucketPrEvent.PullRequest> pullRequests =
                page == null || page.values() == null ? List.of() : page.values();

        int emitted = 0;
        for (BitbucketPrEvent.PullRequest pullRequest : pullRequests) {
            emitted += observe(repo, pullRequest);
        }
        return emitted;
    }

    private int observe(LifecycleProperties.RepoBoard repo, BitbucketPrEvent.PullRequest raw) {
        BitbucketPrEvent.PullRequest pullRequest = withRepository(raw, repo);
        PullRequestRef ref = new PullRequestRef(repo.projectKey(), repo.repoSlug(), pullRequest.id());
        BitbucketPrEvent snapshot = new BitbucketPrEvent(null, null, pullRequest);

        PrPollState previous = stateRepository.find(ref).orElse(null);
        String eventKey = deriveEvent(previous, snapshot);

        // Populate before saving: the id is IDENTITY-generated, so save() inserts
        // immediately and a not-yet-observed row would violate NOT NULL on state.
        PrPollState state = previous != null ? previous : new PrPollState(ref);
        state.observe(pullRequest.state(), pullRequest.version(),
                snapshot.latestCommit(), snapshot.reviewerDigest());
        if (previous == null) {
            stateRepository.save(state);
        }

        if (eventKey == null) {
            return 0;
        }

        String payload = objectMapper.writeValueAsString(new BitbucketPrEvent(eventKey, null, pullRequest));
        String externalId = externalId(ref, eventKey, snapshot, pullRequest);
        boolean ingested = ingestService.ingest(EventSource.BITBUCKET, externalId, eventKey, payload);
        if (ingested) {
            log.info("Reconstructed {} for {} from polling", eventKey, ref.asKey());
        }
        return ingested ? 1 : 0;
    }

    /**
     * Works out what changed since the last pass.
     *
     * <p>A pull request seen for the first time only produces an event if it is still
     * open. Otherwise the first poll of a busy repository would announce every merge
     * in its history.
     *
     * @return the Bitbucket event key to raise, or null when nothing worth acting on changed
     */
    private String deriveEvent(PrPollState previous, BitbucketPrEvent snapshot) {
        String state = snapshot.state();

        if (previous == null) {
            if (!"OPEN".equalsIgnoreCase(state)) {
                return null;
            }
            // Not blindly pr:opened: an open pull request already has a review status,
            // and reporting it as freshly opened would drop a card that is sitting in
            // "changes requested" back into the review column.
            return currentReviewEvent(snapshot);
        }

        if (!Objects.equals(previous.getState(), state)) {
            if ("MERGED".equalsIgnoreCase(state)) {
                return "pr:merged";
            }
            if ("DECLINED".equalsIgnoreCase(state)) {
                return "pr:declined";
            }
            if ("OPEN".equalsIgnoreCase(state)) {
                // Reopened after being declined.
                return "pr:opened";
            }
        }

        // New commits outrank a review change: pushing invalidates the review anyway.
        if (changed(previous.getLatestCommit(), snapshot.latestCommit())) {
            return "pr:from_ref_updated";
        }

        if (changed(previous.getReviewerDigest(), truncate(snapshot.reviewerDigest()))) {
            if (snapshot.hasChangesRequested()) {
                return "pr:reviewer:changes_requested";
            }
            return snapshot.hasApproval() ? "pr:reviewer:approved" : "pr:reviewer:unapproved";
        }

        if (snapshot.pullRequest().version() != null
                && !Objects.equals(previous.getVersion(), snapshot.pullRequest().version())) {
            return "pr:modified";
        }
        return null;
    }

    /**
     * The list endpoint may omit the repository on a ref; the card is keyed on it, so
     * it is filled in from configuration rather than left to fail downstream.
     */
    private BitbucketPrEvent.PullRequest withRepository(BitbucketPrEvent.PullRequest pullRequest,
                                                        LifecycleProperties.RepoBoard repo) {
        BitbucketPrEvent.Repository fallback = new BitbucketPrEvent.Repository(
                repo.repoSlug(), repo.repoSlug(), new BitbucketPrEvent.Project(repo.projectKey(), repo.projectKey()));

        BitbucketPrEvent.Ref toRef = pullRequest.toRef();
        BitbucketPrEvent.Ref resolved = toRef == null
                ? new BitbucketPrEvent.Ref(null, null, null, fallback)
                : new BitbucketPrEvent.Ref(toRef.id(), toRef.displayId(), toRef.latestCommit(),
                        toRef.repository() != null ? toRef.repository() : fallback);

        return new BitbucketPrEvent.PullRequest(
                pullRequest.id(), pullRequest.title(), pullRequest.description(), pullRequest.state(),
                pullRequest.version(), pullRequest.updatedDate(), pullRequest.fromRef(), resolved,
                pullRequest.author(), pullRequest.reviewers(), pullRequest.links());
    }

    /** Identifies the state transition, so re-polling an unchanged pull request is a duplicate. */
    private String externalId(PullRequestRef ref, String eventKey, BitbucketPrEvent snapshot,
                              BitbucketPrEvent.PullRequest pullRequest) {
        String fingerprint = String.join("|", ref.asKey(), eventKey,
                String.valueOf(pullRequest.state()), String.valueOf(pullRequest.version()),
                String.valueOf(snapshot.latestCommit()), String.valueOf(snapshot.reviewerDigest()));
        return "poll:" + sha256(fingerprint);
    }

    /** Where an open pull request belongs right now, judged only by its reviewers. */
    private String currentReviewEvent(BitbucketPrEvent snapshot) {
        if (snapshot.hasChangesRequested()) {
            return "pr:reviewer:changes_requested";
        }
        return snapshot.hasApproval() ? "pr:reviewer:approved" : "pr:opened";
    }

    private static boolean changed(String previous, String current) {
        return current != null && !Objects.equals(previous, current);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
