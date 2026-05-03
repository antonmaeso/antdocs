package com.example.documenter.temporalworkflows.activity;

import com.example.documenter.aigateway.domain.DocUpdate;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Temporal activity that pushes updated documentation files to the companion repository.
 */
@ActivityInterface
public interface UpdateDocumentationActivity {

    /**
     * Pushes each documentation update to the companion repository via the VCS provider.
     *
     * @param updates  the list of documentation file updates to apply
     * @param owner    the repository owner
     * @param repo     the repository name
     */
    @ActivityMethod
    void update(List<DocUpdate> updates, String owner, String repo);
}
