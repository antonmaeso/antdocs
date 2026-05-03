package com.example.documenter.documentation.domain;

/**
 * Represents the current status of a documentation generation run.
 */
public enum RunStatus {

    /** The run is currently in progress. */
    IN_PROGRESS,

    /** The run completed successfully. */
    COMPLETED,

    /** The run failed due to an error. */
    FAILED
}
