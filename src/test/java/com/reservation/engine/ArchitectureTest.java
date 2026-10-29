package com.reservation.engine;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Package rules that keep the core independent of the delivery and storage details.
 * If one of these fails, move the code rather than loosening the rule.
 */
@AnalyzeClasses(packages = "com.reservation.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.reservation.engine.";

    @ArchTest
    static final ArchRule domainHasNoSpring = noClasses()
            .that().resideInAPackage(BASE + "domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainDoesNotKnowOuterLayers = noClasses()
            .that().resideInAPackage(BASE + "domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    BASE + "api..", BASE + "application..", BASE + "persistence..", BASE + "inventory..",
                    BASE + "payment..", BASE + "outbox..", BASE + "security..", BASE + "config..",
                    BASE + "waitlist..", BASE + "idempotency..");

    @ArchTest
    static final ArchRule onlyApiTouchesWebLayer = noClasses()
            .that().resideOutsideOfPackage(BASE + "api..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "api..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage(BASE + "api..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "persistence..");

    @ArchTest
    static final ArchRule outsideInventoryOnlySpi = noClasses()
            .that().resideOutsideOfPackages(BASE + "inventory..", BASE + "config..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    BASE + "inventory.capacity..", BASE + "inventory.unit..",
                    BASE + "inventory.range..", BASE + "inventory.slot..");

    @ArchTest
    static final ArchRule extensionsUseContractsOnly = classes()
            .that().resideInAPackage(BASE + "extensions..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    BASE + "extensions..", BASE + "domain..", BASE + "resourcetype..", BASE + "inventory.spi..",
                    "java..", "org.springframework.stereotype..");

    @ArchTest
    static final ArchRule restControllersLiveInApi = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(BASE + "api")
            .andShould().haveSimpleNameEndingWith("Controller");
}
