package com.example.documenter.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests that enforce module dependency rules at build time.
 *
 * <p>The core rule: no module may depend on a concrete VCS provider implementation
 * class directly. All VCS access must go through the {@code VCSProvider} interface.
 *
 * <p>Concrete provider classes live in {@code com.example.documenter.vcsgateway.provider}.
 * The modules under test are: crawler, documentation, prreviewer, temporalworkflows.
 *
 * <p>ArchUnit dependency: see the {@code app} module's pom.xml (archunit-junit5).
 */
@AnalyzeClasses(packages = "com.example.documenter")
public class ModuleDependencyTest {

    private static final String GITHUB_PROVIDER =
            "com.example.documenter.vcsgateway.provider.GitHubProvider";
    private static final String GITLAB_PROVIDER =
            "com.example.documenter.vcsgateway.provider.GitLabProvider";
    private static final String BITBUCKET_PROVIDER =
            "com.example.documenter.vcsgateway.provider.BitbucketProvider";

    /**
     * Classes in the {@code crawler} package must not depend on any concrete VCS provider.
     * They must only interact with VCS through the {@code VCSProvider} interface.
     */
    @ArchTest
    public static final ArchRule crawlerDoesNotDependOnConcreteProviders =
            noClasses()
                    .that().resideInAPackage("..crawler..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(GITHUB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(GITLAB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(BITBUCKET_PROVIDER)
                    .as("Crawler classes must not depend on concrete VCS provider implementations");

    /**
     * Classes in the {@code documentation} package must not depend on any concrete VCS provider.
     * They must only interact with VCS through the {@code VCSProvider} interface.
     */
    @ArchTest
    public static final ArchRule documentationDoesNotDependOnConcreteProviders =
            noClasses()
                    .that().resideInAPackage("..documentation..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(GITHUB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(GITLAB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(BITBUCKET_PROVIDER)
                    .as("Documentation classes must not depend on concrete VCS provider implementations");

    /**
     * Classes in the {@code prreviewer} package must not depend on any concrete VCS provider.
     * They must only interact with VCS through the {@code VCSProvider} interface.
     */
    @ArchTest
    public static final ArchRule prReviewerDoesNotDependOnConcreteProviders =
            noClasses()
                    .that().resideInAPackage("..prreviewer..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(GITHUB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(GITLAB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(BITBUCKET_PROVIDER)
                    .as("PR Reviewer classes must not depend on concrete VCS provider implementations");

    /**
     * Classes in the {@code temporalworkflows} package must not depend on any concrete VCS provider.
     * They must only interact with VCS through the {@code VCSProvider} interface.
     */
    @ArchTest
    public static final ArchRule temporalWorkflowsDoesNotDependOnConcreteProviders =
            noClasses()
                    .that().resideInAPackage("..temporalworkflows..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(GITHUB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(GITLAB_PROVIDER)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(BITBUCKET_PROVIDER)
                    .as("Temporal Workflow classes must not depend on concrete VCS provider implementations");
}
