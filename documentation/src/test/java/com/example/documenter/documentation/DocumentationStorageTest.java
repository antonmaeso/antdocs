package com.example.documenter.documentation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.documentation.domain.DocumentationArtifact;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;

/**
 * Unit tests for {@link DocumentationStorage}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationStorageTest {

    @Mock
    private VCSProvider vcsProvider;

    private DocumentationStorage storage;

    @BeforeEach
    void setUp() {
        storage = new DocumentationStorage(vcsProvider);
    }

    @Test
    void store_createsRepoWhenNotFound() {
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenThrow(new VcsApiException("Not found", 404));

        DocumentationArtifact artifact = new DocumentationArtifact("src/Main.java", "# Main\nDocs", "Main docs");

        storage.store(List.of(artifact), "owner", "repo");

        verify(vcsProvider).createRepository("owner", "repo_ai_documentation", false);
        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                eq("src/Main.java.md"), eq("# Main\nDocs"), anyString());
    }

    @Test
    void store_doesNotCreateRepoWhenAlreadyExists() {
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        DocumentationArtifact artifact = new DocumentationArtifact("src/Main.java", "# Main\nDocs", "Main docs");

        storage.store(List.of(artifact), "owner", "repo");

        verify(vcsProvider, never()).createRepository(anyString(), anyString(), anyBoolean());
    }

    @Test
    void store_pushFailureLoggedAndContinues() {
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        DocumentationArtifact artifact1 = new DocumentationArtifact("src/A.java", "# A\nDocs", "A docs");
        DocumentationArtifact artifact2 = new DocumentationArtifact("src/B.java", "# B\nDocs", "B docs");

        // First push fails, second succeeds
        org.mockito.Mockito.doThrow(new VcsApiException("Server error", 500))
                .when(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                        eq("src/A.java.md"), anyString(), anyString());

        assertDoesNotThrow(() -> storage.store(List.of(artifact1, artifact2), "owner", "repo"));

        // Both pushes were attempted
        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                eq("src/A.java.md"), anyString(), anyString());
        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                eq("src/B.java.md"), anyString(), anyString());
    }

    @Test
    void store_correctPathDerivation() {
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        DocumentationArtifact artifact = new DocumentationArtifact(
                "src/main/java/App.java", "# App\nDocs", "App docs");

        storage.store(List.of(artifact), "owner", "repo");

        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                eq("src/main/java/App.java.md"), eq("# App\nDocs"),
                eq("docs: update src/main/java/App.java.md"));
    }

    @Test
    void store_readmePathPreserved() {
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        DocumentationArtifact readme = new DocumentationArtifact("README.md", "# Repo\nOverview", "Overview");

        storage.store(List.of(readme), "owner", "repo");

        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"),
                eq("README.md"), eq("# Repo\nOverview"),
                eq("docs: update README.md"));
    }

    @Test
    void deriveTargetPath_appendsMdForSourceFiles() {
        assertEquals("src/Main.java.md", DocumentationStorage.deriveTargetPath("src/Main.java"));
        assertEquals("lib/utils.py.md", DocumentationStorage.deriveTargetPath("lib/utils.py"));
    }

    @Test
    void deriveTargetPath_readmeKeptAsIs() {
        assertEquals("README.md", DocumentationStorage.deriveTargetPath("README.md"));
    }
}
