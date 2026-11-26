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
     * A nova verificação do MFA só vale na ação do portal do credor (a carga do sandbox chama a API HTTP do portal, que
     * a usa do jeito normal). As portas do credor dos módulos donos
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

    /**
     * Portas da pessoa do credor ({@code <modulo>.application.port.in.creditor}, ADR 0011): fora do módulo dono, só o
     * {@code creditorportal} as usa (a carga do sandbox age como credor só pela API HTTP do portal, fatia 8) — nenhum outro módulo lê dados com o
     * credor do ator. Complementa o {@code ModularityTest} (que confere as interfaces declaradas) também para o código
     * do próprio pacote raiz de um módulo que venha a declará-las por engano.
     */
    @Test
    void creditorPortsAreUsedOnlyByTheCreditorPortalAndTheirOwners() {
        java.util.Set<String> owners = classes.stream().map(JavaClass::getPackageName)
                .filter(p -> p.matches(java.util.regex.Pattern.quote(ROOT) + "\\.[a-z]+\\.application\\.port\\.in\\.creditor"))
                .map(p -> p.substring(ROOT.length() + 1, p.indexOf('.', ROOT.length() + 1)))
                .collect(java.util.stream.Collectors.toSet());
        org.assertj.core.api.Assertions.assertThat(owners)
                .containsExactlyInAnyOrder("identity", "settlement", "collection", "payment", "analytics");
        for (String owner : owners) {
            noClasses().that().resideOutsideOfPackages(ROOT + ".creditorportal..", ROOT + "." + owner + "..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + "." + owner + ".application.port.in.creditor..")
                    .because("as portas do credor tiram o credor do ator e só servem ao portal do credor (ADR 0011)")
                    .check(classes);
        }
    }

    /**
     * Nenhum endpoint do portal do credor recebe o credor do cliente (RS-06): o credor vem sempre do ator. Confere os
     * parâmetros dos métodos mapeados (nome, {@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader},
     * {@code @RequestPart}) e os componentes dos records de requisição do próprio módulo.
     */
    @Test
    void creditorPortalEndpointsNeverTakeACreditorId() {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        classes.stream().filter(c -> c.getPackageName().startsWith(ROOT + ".creditorportal.web")).forEach(c -> {
            for (java.lang.reflect.Method m : c.reflect().getDeclaredMethods()) {
                if (!isMapped(m)) {
                    continue;
                }
                for (java.lang.reflect.Parameter p : m.getParameters()) {
                    java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(p.getName()));
                    for (java.lang.annotation.Annotation a : p.getAnnotations()) {
                        names.addAll(bindingNames(a));
                    }
                    if (p.getType().isRecord() && p.getType().getPackageName().startsWith(ROOT + ".creditorportal")) {
                        for (java.lang.reflect.RecordComponent rc : p.getType().getRecordComponents()) {
                            names.add(rc.getName());
                        }
                    }
                    names.stream().filter(n -> n.toLowerCase(java.util.Locale.ROOT).replace("_", "").contains("creditorid"))
                            .forEach(n -> offenders.add(c.getSimpleName() + "#" + m.getName() + "(" + n + ")"));
                }
            }
        });
        org.assertj.core.api.Assertions.assertThat(offenders)
                .as("o credor vem do ator, nunca do cliente (RS-06)").isEmpty();
    }

    private static boolean isMapped(java.lang.reflect.Method m) {
        return java.util.Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType()
                .isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class)
                || a.annotationType() == org.springframework.web.bind.annotation.RequestMapping.class);
    }

    private static java.util.List<String> bindingNames(java.lang.annotation.Annotation a) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String attribute : new String[]{"value", "name"}) {
            try {
                Object v = a.annotationType().getMethod(attribute).invoke(a);
                if (v instanceof String s && !s.isEmpty()) {
                    names.add(s);
                }
            } catch (ReflectiveOperationException ignored) {
                // anotação sem o atributo
            }
        }
        return names;
    }

    /**
     * Opção D do MFA no sandbox (fatia 8, RS-80/81): o segredo do autenticador da pessoa do credor só nasce no fluxo de
     * ativação do próprio identity — ninguém mais inicia a ativação com um segredo escolhido de fora.
     */
    @Test
    void onlyTheCreditorIdentityServicesStartTheActivation() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".identity.application.service.creditor..")
                .should().callMethodWhere(callTo(br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser.class,
                        "startActivation"))
                .because("o segredo do autenticador do credor só é gerado na ativação do identity (opção D, RS-80)")
                .check(classes);
    }

    /** Nenhuma parte do identity conhece o sandbox (a carga fictícia usa as portas e a API, nunca o contrário). */
    @Test
    void identityNeverDependsOnTheSandbox() {
        noClasses().that().resideInAPackage(ROOT + ".identity..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".sandbox..")
                .because("nenhum atalho de MFA ou de sessão do credor para a carga fictícia (RS-81)")
                .check(classes);
    }

    /**
     * Nenhuma porta da pessoa do credor recebe um segredo de MFA de fora (RS-81): nem parâmetro nem componente de record
     * de comando com "secret" no nome.
     */
    @Test
    void creditorIdentityPortsNeverTakeASecret() {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        classes.stream().filter(c -> c.getPackageName().equals(ROOT + ".identity.application.port.in.creditor"))
                .filter(JavaClass::isInterface).forEach(c -> {
                    for (java.lang.reflect.Method m : c.reflect().getDeclaredMethods()) {
                        for (java.lang.reflect.Parameter p : m.getParameters()) {
                            java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(p.getName()));
                            if (p.getType().isRecord()) {
                                for (java.lang.reflect.RecordComponent rc : p.getType().getRecordComponents()) {
                                    names.add(rc.getName());
                                }
                            }
                            names.stream().filter(n -> n.toLowerCase(java.util.Locale.ROOT).contains("secret"))
                                    .forEach(n -> offenders.add(c.getSimpleName() + "#" + m.getName() + "(" + n + ")"));
                        }
                    }
                });
        org.assertj.core.api.Assertions.assertThat(offenders)
                .as("o segredo do autenticador nunca vem de fora (opção D)").isEmpty();
    }

    /**
     * O ator do credor nasce só da sessão real, no resolvedor do token (RS-83): nem a carga do sandbox nem outro
     * código monta {@code Actor.creditor(...)}.
     */
    @Test
    void onlyTheCreditorTokenResolverBuildsTheCreditorActor() {
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".identity.infrastructure.security.CreditorTokenResolver")
                .should().callMethodWhere(callTo(br.com.gestorcobranca.shared.security.Actor.class, "creditor"))
                .because("o ator do credor vem sempre de uma sessão real conferida a cada requisição (RS-83)")
                .check(classes);
    }

    @Test
    void onlyIdentitySecurityReferencesTheCreditorActorType() {
        // fecha o construtor canônico do record Actor: new Actor(CREDITOR, ...) fora da sessão real (RS-83)
        noClasses().that().resideOutsideOfPackages(ROOT + ".shared.security..", ROOT + ".identity.infrastructure..")
                .should().accessFieldWhere(com.tngtech.archunit.base.DescribedPredicate.describe("ActorType.CREDITOR",
                        (com.tngtech.archunit.core.domain.JavaFieldAccess access) ->
                                access.getTargetOwner().isEquivalentTo(br.com.gestorcobranca.shared.security.Actor.ActorType.class)
                                        && access.getName().equals("CREDITOR")
                                        // tabela sintética de um switch sobre o enum (Classe$1.<clinit>): só lê o valor
                                        && !(access.getOrigin().getName().equals("<clinit>")
                                                && access.getOriginOwner().getName().matches(".*\\$\\d+"))))
                .because("o ator do credor só nasce da sessão real conferida a cada requisição (RS-83)")
                .check(classes);
    }

    /** Chamada a qualquer sobrecarga do método {@code name} declarado em {@code owner}. */
    private static com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethodCall> callTo(
            Class<?> owner, String name) {
        return com.tngtech.archunit.base.DescribedPredicate.describe(owner.getSimpleName() + "." + name,
                call -> call.getTargetOwner().isEquivalentTo(owner) && call.getName().equals(name));
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

    /**
     * A recusa do titular eliminado a pedido (decisão 1471): só o compliance implementa a SPI; no debt, só a criação do
     * devedor e a consulta da carga a usam; na carga, só o processador consulta o bloco. A consulta não tem permissão
     * própria e nunca pode virar caminho de enumeração de documentos (SEG-20261126-E06).
     */
    @Test
    void erasedSubjectsAreCheckedOnlyByTheirOwners() {
        String spi = ROOT + ".debt.application.port.spi.ErasedSubjects";
        classes().that().implement(spi)
                .should().resideInAPackage(ROOT + ".compliance..")
                .because("o dono do pedido do titular é o compliance")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".debt..")
                .and().doNotHaveFullyQualifiedName(ROOT + ".debt.application.service.DebtorRegistry")
                .and().doNotHaveFullyQualifiedName(ROOT + ".debt.application.service.AnonymizedSubjectQueryService")
                .should().dependOnClassesThat().haveFullyQualifiedName(spi)
                .because("a recusa vale na criação do devedor e na carga, e em nenhum outro lugar (decisão 1471)")
                .check(classes);
        noClasses().that().resideOutsideOfPackages(ROOT + ".debt..", ROOT + ".compliance..")
                .should().dependOnClassesThat().haveFullyQualifiedName(spi)
                .because("só o debt consulta e só o compliance implementa (decisão 1471)")
                .check(classes);
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".ingestion.application.service.ImportBatchProcessor")
                .and().doNotHaveFullyQualifiedName(ROOT + ".ingestion.application.service.ImportBatchProcessor$SliceRun")
                .should().callMethod(ROOT + ".debt.application.port.in.AnonymizedSubjectQueries", "erasedSubjectsAmong",
                        "java.util.UUID", "java.util.UUID", "java.util.Map")
                .because("só a recusa por bloco da carga consulta documentos em lote (SEG-20261126-E06)")
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
