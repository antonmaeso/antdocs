package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.aigateway.domain.DocUpdate;
import com.example.documenter.temporalworkflows.activity.UpdateDocumentationActivity;
import com.example.documenter.vcsgateway.VCSProvider;

import java.util.List;

/**
 * Implementation of {@link UpdateDocumentationActivity}.
 *
 * <p>Pushes each documentation update to the companion repository via the VCS provider.</p>
 */
public class UpdateDocumentationActivityImpl implements UpdateDocumentationActivity {

    private final VCSProvider vcsProvider;

    public UpdateDocumentationActivityImpl(VCSProvider vcsProvider) {
        this.vcsProvider = vcsProvider;
    }

    @Override
    public void update(List<DocUpdate> updates, String owner, String repo) {
        String companionRepo = repo + "_ai_documentation";
        for (DocUpdate update : updates) {
            vcsProvider.pushFile(owner, companionRepo, update.path(), update.newContent(),
                    "Update documentation: " + update.path());
        }
    }
}
