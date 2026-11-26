package br.com.gestorcobranca.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClass;
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

    /**
     * Portal do credor (ADR 0011): a web e a porta do {@code creditorportal} respondem só com os records do próprio módulo (lista
     * de campos permitidos) — nunca com os de {@code port/in} dos módulos donos, para que um campo novo lá não chegue ao
     * credor sem decisão.
     */
    @Test
    void creditorPortalWebAnswersOnlyWithItsOwnRecords() {
        noClasses().that().resideInAnyPackage(ROOT + ".creditorportal.web..", ROOT + ".creditorportal.application.port.in..")
                .should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage(ROOT + ".(*).application.port.in..")
                        .and(JavaClass.Predicates.resideOutsideOfPackage(ROOT + ".creditorportal..")))
                .because("a resposta ao credor é uma lista de campos permitidos (ADR 0011)")
                .check(classes);
    }

    /**
     * A nova verificação do MFA só vale na ação do portal do credor: o {@code sandbox} também declara
     * {@code identity :: creditor} (carga fictícia), mas não a usa. As portas do credor dos módulos donos
     * ({@code <modulo> :: creditor}) já são protegidas pelo {@code ModularityTest}.
     */
    @Test
    void onlyTheCreditorPortalUsesCreditorOnlyUseCases() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".creditorportal..", ROOT + ".identity..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        ROOT + ".identity.application.port.in.creditor.CreditorStepUp")
                .because("a nova verificação do MFA só vale na ação do portal do credor (ADR 0011)")
                .check(classes);
    }

    /** O comprovante da nova verificação do MFA só é emitido e conferido pelo identity (SEG-F07, decisão 1436). */
    @Test
    void onlyIdentityImplementsStepUpProofs() {
        classes().that().implement(ROOT + ".shared.security.StepUpProofs")
                .should().resideInAPackage(ROOT + ".identity..")
                .because("um comprovante emitido fora do identity dispensaria a verificação do código")
                .check(classes);
    }

    /** A leitura de acordos para a alocação (sem {@code AGREEMENT_VIEW} para a pessoa do credor) é só do payment. */
    @Test
    void onlyPaymentUsesAgreementAllocationQueries() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".payment..", ROOT + ".negotiation..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".negotiation.application.port.in.allocation..")
                .because("a projeção do acordo para a alocação nunca é exibida (decisão 1435, SEG-H01)")
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

    /**
     * Toda eliminação do titular passa pelo portão único do compliance (SEG-20261124-D01h, decisão 1481): é ele que
     * abre o aviso ao credor (LGPD art. 18 §6º) na mesma transação e, depois, confere as pendências que impedem a
     * eliminação. Outra classe chamando o debt anonimizaria sem aviso.
     */
    @Test
    void onlyErasureGateAnonymizesDebtors() {
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".compliance.application.service.ErasureGate")
                .should().callMethod(ROOT + ".debt.application.port.in.DebtorUseCases", "anonymize",
                        "java.util.UUID", "java.lang.String")
                .because("a eliminação sem o portão não avisa o credor (decisão 1481)")
                .check(classes);
    }

    /** O aviso ao credor só nasce no portão da eliminação, na transação da anonimização (decisão 1481). */
    @Test
    void onlyErasureGateOpensCreditorErasureNotices() {
        String notice = ROOT + ".compliance.domain.model.CreditorErasureNotice";
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".compliance.application.service.ErasureGate")
                .should().callMethod(ROOT + ".compliance.application.service.CreditorErasureNoticeService", "openFor",
                        "java.util.UUID", notice + "$Origin", "java.util.UUID", "java.time.Instant")
                .because("aviso aberto fora do portão poderia existir sem a anonimização (decisão 1481)")
                .check(classes);
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".compliance.application.service.CreditorErasureNoticeService")
                .should().callConstructor(notice, "java.util.UUID", "java.util.UUID", notice + "$Origin",
                        "java.util.UUID", "java.time.Instant", "java.util.List")
                .because("só o serviço do aviso, chamado pelo portão, cria o aviso (decisão 1481)")
                .check(classes);
    }

    /** O ingestion lê só o id do aviso ao credor (porta mínima); remessa e registro são da equipe do titular. */
    @Test
    void ingestionUsesOnlyTheMinimalCreditorNoticePort() {
        noClasses().that().resideInAPackage(ROOT + ".ingestion..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        ROOT + ".compliance.application.port.in.CreditorErasureNoticeUseCases")
                .because("o ingestion não registra nem monta o aviso ao credor (parecer AQ-04 da fatia 3b)")
                .check(classes);
    }
}
