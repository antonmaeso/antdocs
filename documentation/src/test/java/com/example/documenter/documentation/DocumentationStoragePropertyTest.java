package com.example.documenter.documentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.documenter.documentation.domain.DocumentationArtifact;
import com.example.documenter.vcsgateway.VCSProvider;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DocumentationStorage}.
 *
 * <p>Uses jqwik to verify universal invariants across randomly generated inputs.
 */
class DocumentationStoragePropertyTest {

    private static final String[] EXTENSIONS = {".java", ".kt", ".py", ".ts", ".js", ".go"};

    @Provide
    Arbitrary<String> filePaths() {
        Arbitrary<String> dirs = Arbitraries.of("src", "lib", "test", "src/main/java", "src/test");
        Arbitrary<String> names = Arbitraries.of("Main", "App", "Utils", "Config", "Service");
        Arbitrary<String> exts = Arbitraries.of(EXTENSIONS);
        return Combinators.combine(dirs, names, exts).as((dir, name, ext) -> dir + "/" + name + ext);
    }

    @Provide
    Arbitrary<List<DocumentationArtifact>> artifacts() {
        Arbitrary<DocumentationArtifact> artifactArb = Combinators.combine(
                filePaths(),
                Arbitraries.of("# Doc\nContent here.", "# Module\nDescription of module.", "# API\nEndpoint docs."),
                Arbitraries.of("Short desc 1", "Short desc 2", "Short desc 3")
        ).as(DocumentationArtifact::new);

        return artifactArb.list().ofMinSize(1).ofMaxSize(10)
                .map(list -> list.stream()
                        .collect(Collectors.toMap(DocumentationArtifact::sourcePath, a -> a, (a, b) -> a))
                        .values().stream().toList());
    }

    // ---- Property 7: allArtifactsArePushed ----

    /**
     * For any list of DocumentationArtifact objects, DocumentationStorage calls
     * VCSProvider.pushFile exactly once for each artifact, and the set of pushed paths
     * equals the set of artifact source paths (with .md extension).
     *
     * <p>Validates: Requirement 3.1
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 7: allArtifactsArePushed
    void allArtifactsArePushed(@ForAll("artifacts") List<DocumentationArtifact> artifactList) {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        List<String> pushedPaths = new ArrayList<>();
        doAnswer(invocation -> {
            pushedPaths.add(invocation.getArgument(2));
            return null;
        }).when(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"), anyString(), anyString(), anyString());

        DocumentationStorage storage = new DocumentationStorage(vcsProvider);
        storage.store(artifactList, "owner", "repo");

        assertEquals(artifactList.size(), pushedPaths.size(),
                "Number of pushed files should equal number of artifacts");

        Set<String> expectedPaths = artifactList.stream()
                .map(a -> DocumentationStorage.deriveTargetPath(a.sourcePath()))
                .collect(Collectors.toSet());
        Set<String> actualPaths = Set.copyOf(pushedPaths);

        assertEquals(expectedPaths, actualPaths,
                "Pushed paths should match derived target paths");
    }

    // ---- Property 8: directoryStructureIsMirrored ----

    /**
     * For any source file at path a/b/c.java, the corresponding documentation file
     * is pushed to path a/b/c.java.md, preserving the full directory hierarchy.
     *
     * <p>Validates: Requirement 3.3
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 8: directoryStructureIsMirrored
    void directoryStructureIsMirrored(@ForAll("artifacts") List<DocumentationArtifact> artifactList) {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        List<String> pushedPaths = new ArrayList<>();
        doAnswer(invocation -> {
            pushedPaths.add(invocation.getArgument(2));
            return null;
        }).when(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"), anyString(), anyString(), anyString());

        DocumentationStorage storage = new DocumentationStorage(vcsProvider);
        storage.store(artifactList, "owner", "repo");

        for (DocumentationArtifact artifact : artifactList) {
            String expectedPath = artifact.sourcePath() + ".md";
            assertTrue(pushedPaths.contains(expectedPath),
                    "Expected pushed path " + expectedPath + " for source " + artifact.sourcePath());

            // Verify directory structure is preserved
            if (artifact.sourcePath().contains("/")) {
                String sourceDir = artifact.sourcePath().substring(0, artifact.sourcePath().lastIndexOf('/'));
                assertTrue(expectedPath.startsWith(sourceDir + "/"),
                        "Pushed path should preserve directory structure: " + expectedPath);
            }
        }
    }

    // ---- Property 9: pushIsIdempotent ----

    /**
     * For any DocumentationArtifact, pushing it twice results in pushFile being called
     * with the new content on the second invocation (overwrite semantics).
     *
     * <p>Validates: Requirement 3.4
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 9: pushIsIdempotent
    void pushIsIdempotent(@ForAll("artifacts") List<DocumentationArtifact> artifactList) {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        List<String> pushedContents = new ArrayList<>();
        List<String> pushedPaths = new ArrayList<>();
        doAnswer(invocation -> {
            pushedPaths.add(invocation.getArgument(2));
            pushedContents.add(invocation.getArgument(3));
            return null;
        }).when(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"), anyString(), anyString(), anyString());

        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        // Push twice
        storage.store(artifactList, "owner", "repo");
        storage.store(artifactList, "owner", "repo");

        // Each artifact should have been pushed exactly twice
        assertEquals(artifactList.size() * 2, pushedPaths.size(),
                "Each artifact should be pushed twice");

        // For each artifact, verify the second push has the same content
        for (DocumentationArtifact artifact : artifactList) {
            String targetPath = DocumentationStorage.deriveTargetPath(artifact.sourcePath());

            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < pushedPaths.size(); i++) {
                if (pushedPaths.get(i).equals(targetPath)) {
                    indices.add(i);
                }
            }

            assertEquals(2, indices.size(),
                    "Each path should be pushed exactly twice: " + targetPath);

            // Both pushes should have the same content (idempotent)
            assertEquals(pushedContents.get(indices.get(0)), pushedContents.get(indices.get(1)),
                    "Content should be the same on both pushes for: " + targetPath);
        }
    }
}
