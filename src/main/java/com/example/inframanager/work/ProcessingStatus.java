package com.example.inframanager.work;

/**
 * Lifecycle shared by both journals ({@code inbound_event}, {@code outbound_task}).
 * Mirrored by a CHECK constraint in the schema.
 */
public enum ProcessingStatus {

    /** Waiting to be picked up, or waiting out a retry backoff. */
    PENDING,

    /** Handled successfully. Terminal. */
    DONE,

    /** Nothing was registered to handle it. Terminal, and a signal of misconfiguration. */
    SKIPPED,

    /** Gave up after exhausting attempts. Terminal, needs a human. */
    FAILED
}
