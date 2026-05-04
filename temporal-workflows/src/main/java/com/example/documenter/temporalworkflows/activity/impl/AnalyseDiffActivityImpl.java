package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.aigateway.domain.DocFileContent;
import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.activity.AnalyseDiffActivity;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import com.example.documenter.vcsgateway.VCSProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link AnalyseDiffActivity}.
 *
 * <p>Fetches the full content of each relevant documentation file from the companion
 * repository, then delegates to the AI gateway to analyse the diff and produce a
 * review decision.</p>
 */
public class AnalyseDiffActivityImpl implements AnalyseDiffActivity {

    private final VCSProvider vcsProvider;
    private final AIGateway aiGateway;

    public AnalyseDiffActivityImpl(VCSProvider vcsProvider, AIGateway aiGateway) {
        this.vcsProvider = vcsProvider;
        this.aiGateway = aiGateway;
    }

    @Override
    public ReviewDecision analyse(String diffPatch, List<String> relevantPaths,
                                  List<ConversationTurn> history, String owner, String repo) {
        String companionRepo = repo + "_ai_documentation";

        List<DocFileContent> relevantDocs = new ArrayList<>();
        for (String path : relevantPaths) {
            try {
                String content = vcsProvider.fetchFileContent(owner, companionRepo, path, "main");
                relevantDocs.add(new DocFileContent(path, content));
            } catch (Exception e) {
                // Log and continue — partial failures are acceptable
            }
        }

        // Map workflow ConversationTurn to ai-gateway ConversationTurn
        List<com.example.documenter.aigateway.domain.ConversationTurn> aiHistory = history.stream()
                .map(turn -> new com.example.documenter.aigateway.domain.ConversationTurn(
                        turn.question(), turn.reply()))
                .toList();

        return aiGateway.analyseAndDecide(diffPatch, relevantDocs, aiHistory);
    }
}
