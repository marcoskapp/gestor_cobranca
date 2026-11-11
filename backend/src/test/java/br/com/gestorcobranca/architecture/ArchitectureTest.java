package br.com.gestorcobranca.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras de camadas da arquitetura hexagonal (docs/arquitetura/visao-geral.md §3), válidas para todos os
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

    /**
     * Método {@code default} numa interface de caso de uso roda no objeto-alvo, não no proxy do Spring: a chamada
     * interna que ele faz ao método implementado escapa do {@code @Transactional} (e de qualquer outro aspecto) da
     * classe de serviço. Overloads de conveniência ficam no chamador ou na implementação, nunca na porta.
     */
    @Test
    void useCasePortsHaveNoDefaultMethods() {
        methods().that().areDeclaredInClassesThat().resideInAPackage(ROOT + ".(*).application.port.in..")
                .and().areDeclaredInClassesThat().areInterfaces()
                .and().doNotHaveModifier(JavaModifier.STATIC)
                .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
                .should().haveModifier(JavaModifier.ABSTRACT)
                .because("default em porta de caso de uso escapa do proxy transacional (decisão 848)")
                .allowEmptyShould(true)
                .check(classes);
    }

    /**
     * O envio a pedido do devedor fica fora dos limites de frequência da cobrança (decisões 1082 e 1083): só o portal,
     * que atende ao pedido feito naquele instante (código de acesso, confirmação do pedido de atendimento), chama.
     */
    @Test
    void onlyThePortalSendsOnDebtorRequest() {
        String port = ROOT + ".communication.application.port.in.MessageUseCases";
        String command = port + "$SendMessageCommand";
        noClasses().that().resideOutsideOfPackages(ROOT + ".portal..", ROOT + ".communication..")
                .should().callMethod(port, "sendOnDebtorRequest", command)
                .orShould().callMethod(port, "sendAccessCode", command)
                .because("mensagem a pedido do devedor não pode virar cobrança sem limites (decisões 1082 e 1083)")
                .check(classes);
    }

    /**
     * Só o communication (mensagem com a categoria gravada) e o próprio compliance escolhem a categoria do contato: os
     * demais módulos usam o construtor sem categoria (cobrança). Decisões 792, 1081 e 1083.
     */
    @Test
    void onlyCommunicationChoosesTheContactCategory() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".communication..", ROOT + ".compliance..")
                .should().callConstructor(ROOT + ".compliance.application.port.in.ContactGate$ContactRequest",
                        "java.util.UUID", "java.util.UUID", "java.lang.String", "java.lang.String", "java.time.Instant",
                        "java.lang.String", "java.lang.String", "java.util.UUID", "java.lang.String")
                .because("categoria fora da cobrança dispensa limites ou horário (decisões 792, 1081 e 1083)")
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
