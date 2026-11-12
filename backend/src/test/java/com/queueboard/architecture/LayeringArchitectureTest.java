package com.queueboard.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import com.queueboard.job.domain.Job;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Machine-enforced layering (ADR-0002) — the conventions reviewers expect
 * (thin controllers, rules in services, JPA in repositories) as failing tests,
 * not tribal knowledge.
 *
 * <p>Written against the ArchUnit core API ({@code ClassFileImporter} + plain JUnit)
 * rather than the {@code @AnalyzeClasses} engine, which predates the JUnit Platform
 * version Spring Boot 4 manages.
 */
class LayeringArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.queueboard");

    @Test
    void nothingOutsideTheApiPackage_dependsOnTheApiPackage() {
        noClasses().that().resideOutsideOfPackage("..job.api..")
                .should().dependOnClassesThat().resideInAPackage("..job.api..")
                .check(CLASSES);
    }

    @Test
    void theDomain_doesNotDependOnTheApiOrTheProcessor() {
        noClasses().that().resideInAPackage("..job.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..job.api..", "..job.processing..")
                .check(CLASSES);
    }

    @Test
    void theDomain_touchesPersistenceOnlyThroughRepositoryInterfaces() {
        noClasses().that().resideInAPackage("..job.domain..")
                .should().dependOnClassesThat(
                        resideInAPackage("..job.persistence..").and(not(INTERFACES)))
                .check(CLASSES);
    }

    @Test
    void onlyThePersistenceLayer_andTheEntity_touchJakartaPersistence() {
        noClasses().that().resideOutsideOfPackage("..job.persistence..")
                .and().doNotBelongToAnyOf(Job.class)
                .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                .check(CLASSES);
    }

    @Test
    void onlyThePersistenceLayer_touchesSpringData() {
        noClasses().that().resideOutsideOfPackage("..job.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data..")
                .check(CLASSES);
    }

    @Test
    void controllers_doNotUseRepositoriesDirectly() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAPackage("..job.persistence..")
                .check(CLASSES);
    }
}
