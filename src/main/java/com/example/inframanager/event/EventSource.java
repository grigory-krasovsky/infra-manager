package com.example.inframanager.event;

/** Systems that push webhooks at us. */
public enum EventSource {

    /** Pull request events; drives Trello cards. */
    BITBUCKET,

    /** Deployment results; drives Telegram notifications. */
    BAMBOO
}
