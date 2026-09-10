package com.example.inframanager.trello;

import com.example.inframanager.pullrequest.PullRequestRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What gets stored in {@code outbound_task.payload} for a TRELLO task: the desired
 * state of one card, not a step to perform.
 *
 * <p>Declaring the target state rather than "create" or "move" makes the task safe
 * to replay and safe to process out of order -- applying it twice lands on the same
 * card in the same list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloCardCommand(

        PullRequestRef pullRequest,

        String boardId,

        /** Null means leave the card where it is and only refresh its content. */
        String moveToListName,

        /** Where a card goes when it does not exist yet. Never null. */
        String createInListName,

        String title,

        String description,

        /** Recorded on the link when known; null when the branch carries no key. */
        String issueKey,

        /**
         * Label names, created on the board if absent. Null leaves whatever labels
         * the card already has alone -- which is what reconciliation wants.
         */
        java.util.List<String> labels,

        /**
         * Identifiers of the author, most specific first. Matched against existing
         * board members; unlike labels, members cannot be created, so an author with
         * no Trello account simply leaves the card unassigned.
         */
        java.util.List<String> memberCandidates,

        boolean archive) {
}
