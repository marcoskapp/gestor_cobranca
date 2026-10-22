package br.com.gestorcobranca.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras de camadas da arquitetura hexagonal (docs/ARCHITECTURE.md §3), válidas para todos os
 * módulos.
 */
class ArchitectureTest {

    private static final String ROOT = "br.com.gestorcobranca";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void domainIsIndependentOfOuterLayersAndFrameworksExceptJpa() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".(*).domain..")
                .and().resideOutsideOfPackage(ROOT + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..infrastructure..", "..web..",
                        "org.springframework.web..", "org.springframework.jdbc..",
                        "org.springframework.data.jpa..", "org.springframework.stereotype..",
                        "org.springframework.transaction..");
        rule.check(classes);
    }

    @Test
    void applicationDoesNotDependOnInfrastructureOrWeb() {
        noClasses().that().resideInAPackage(ROOT + ".(*).application..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".(*).infrastructure..", ROOT + ".(*).web..")
                .check(classes);
    }

    @Test
    void webOnlyTalksToUseCases() {
        noClasses().that().resideInAPackage(ROOT + ".(*).web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".(*).domain.repository..", ROOT + ".(*).infrastructure..",
                        ROOT + ".(*).application.service..", "org.springframework.data.jpa..")
                .check(classes);
    }

    @Test
    void springDataRepositoriesLiveInPersistence() {
        classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
                .should().resideInAPackage("..infrastructure.persistence..")
                .check(classes);
    }

    @Test
    void controllersLiveInWebPackage() {
        classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage("..web..")
                .check(classes);
    }

    @Test
    void noFieldInjection() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("javax.inject.Inject")
                .check(classes);
        com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noStandardStreamsOrJavaUtilLogging() {
        com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
        com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    }
}
