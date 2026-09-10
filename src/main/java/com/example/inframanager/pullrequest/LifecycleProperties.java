package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The card lifecycle: which repositories mirror to which board, and which pull
 * request event lands the card in which column.
 *
 * <p>Kept in configuration rather than the database because it is edited by hand and
 * there is no admin UI to edit rows with.
 */
@ConfigurationProperties("infra-manager.lifecycle")
public record LifecycleProperties(

        @DefaultValue List<RepoBoard> repos,

        /**
         * Bitbucket event key to Trello list name. An event that is absent here still
         * refreshes the card's title and description, it just does not move it --
         * which is what {@code pr:modified} should do.
         *
         * <p>A list of pairs rather than a map because event keys contain colons, and
         * a colon is not a legal character in a Spring configuration property name.
         * As a map this would need {@code "[pr:opened]": Review} in YAML, which looks
         * like a mistake and silently binds to nothing once someone "fixes" it.
         */
        @DefaultValue List<EventList> eventToList,

        /** Whether new commits send an already-approved card back for review. */
        @DefaultValue("true") boolean reopenOnNewCommits,

        /** Where a card is created if {@code pr:opened} is not mapped. */
        @DefaultValue("Review") String defaultList) {

    public record RepoBoard(String projectKey, String repoSlug, String trelloBoardId) {

        boolean matches(PullRequestRef ref) {
            return projectKey.equalsIgnoreCase(ref.projectKey())
                    && repoSlug.equalsIgnoreCase(ref.repoSlug());
        }
    }

    public record EventList(String event, String list) {
    }

    public Optional<RepoBoard> boardFor(PullRequestRef ref) {
        return repos.stream().filter(repo -> repo.matches(ref)).findFirst();
    }

    /** Where a card goes when it is first created. */
    public String createInList() {
        return listNamed("pr:opened").orElse(defaultList);
    }

    /**
     * @return the list this event moves the card to, or empty to leave it in place
     */
    public Optional<String> listFor(String eventKey) {
        if (reopenOnNewCommits && "pr:from_ref_updated".equals(eventKey)) {
            // New commits invalidate an approval, so the card goes back to where a
            // freshly opened PR would sit.
            return listNamed("pr:opened");
        }
        return listNamed(eventKey);
    }

    private Optional<String> listNamed(String eventKey) {
        return eventToList.stream()
                .filter(mapping -> mapping.event().equals(eventKey))
                .map(EventList::list)
                .findFirst();
    }
}
