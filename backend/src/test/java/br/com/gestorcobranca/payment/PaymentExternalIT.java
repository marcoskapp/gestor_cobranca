package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReviewQueueFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.AllocationView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceContent;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReallocateCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pagamentos externos contra PostgreSQL real: evidência obrigatória (tipo conferido pelo conteúdo),
 * confirmação automática até o limite e segregação acima dele (quem informa não aprova), idempotência,
 * títulos avulsos lançados no razão, sobra e devolução, estorno manual e realocação do valor não
 * aplicado pelo negotiation (acordo encerrado).
 */
class PaymentExternalIT extends PaymentTestSupport {

    @Autowired
    PaymentSettingsUseCases settings;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    WebApplicationContext webApplicationContext;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private ExternalPaymentCommand toTitleB(String amount, String reference) {
        return new ExternalPaymentCommand(creditorB, debtor, new BigDecimal(amount), paidAt,
                "TRANSFER", "AGENCY_ACCOUNT", reference, "Depósito identificado", new TargetInput("TITLES", null,
                List.of(titleB), null));
    }

    @Test
    void evidenceDualControlTitlesLedgerLeftoverRefundAndManualReversal() throws Exception {
        provision("external");

        // evidência obrigatória e conferida pelo conteúdo
        assertThatThrownBy(() -> asOperator(() -> payments.registerExternal(toTitleB("100.00", null), null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Evidência");
        byte[] fake = "não é um PDF".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> asOperator(() -> payments.registerExternal(toTitleB("100.00", null),
                new EvidenceUpload("x.pdf", "application/pdf", fake.length, new ByteArrayInputStream(fake)), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não corresponde");

        // até o limite (R$ 500,00): confirmado no registro, lançado no razão do título avulso
        PaymentView small = asOperator(() -> payments.registerExternal(toTitleB("100.00", "DOC-1"), pdf(), "idem-1"));
        assertThat(small.status()).isEqualTo("CONFIRMED");
        assertThat(small.reviewMode()).isEqualTo("AUTO_LOW_RISK");
        assertThat(small.evidenceSha256()).hasSize(64);
        assertThat(small.allocations()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("TITLES");
            assertThat(a.outcome()).isEqualTo("NOT_APPLICABLE");
        });
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        awaitConsumed("collection.payment-confirmed", 1);
        // mesma chave de idempotência e mesmo conteúdo → mesmo pagamento, sem nova evidência nem baixa
        PaymentView repeated = asOperator(() -> payments.registerExternal(toTitleB("100.00", "DOC-1"), pdf(), "idem-1"));
        assertThat(repeated.id()).isEqualTo(small.id());
        assertThat(count("select count(*) from payment.payment")).isEqualTo(1);
        // SEG-20261125-05: o hash do pedido guardado para a idempotência não leva a referência crua (seria recuperável
        // por força bruta depois da anonimização); leva o HMAC
        ExternalPaymentCommand sent = toTitleB("100.00", "DOC-1");
        String rawCanonical = String.join("|", String.valueOf(sent.creditorId()), String.valueOf(sent.debtorId()),
                sent.amount().stripTrailingZeros().toPlainString(), String.valueOf(sent.paidAt()),
                String.valueOf(sent.method()), String.valueOf(sent.origin()), "DOC-1", String.valueOf(sent.target()));
        assertThat(count("select count(*) from shared.idempotency_key where request_hash = ?", sha256Hex(rawCanonical)))
                .isZero();
        assertThat(count("select count(*) from shared.idempotency_key where resource_id = ?", small.id())).isEqualTo(1);

        // acima do limite: aguarda segunda pessoa com PAYMENT_REVIEW; quem informou não revisa
        PaymentView big = asFinance1(() -> payments.registerExternal(toTitleB("900.00", "DOC-2"), pdf(), null));
        assertThat(big.status()).isEqualTo("INFORMED");
        assertThat(big.reviewThresholdExceeded()).isTrue();
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        assertThat(asFinance1(() -> payments.reviewQueue(ReviewQueueFilter.ALL, PageQuery.of(0, 10))).content()).isEmpty();
        assertThat(asFinance2(() -> payments.reviewQueue(ReviewQueueFilter.ALL, PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(big.id());
        // quem aprova vê o destino solicitado antes de existir alocação (na fila e no detalhe)
        assertThat(big.allocations()).isEmpty();
        assertThat(asFinance2(() -> payments.reviewQueue(ReviewQueueFilter.ALL, PageQuery.of(0, 10))).content().getFirst().requestedTarget())
                .satisfies(t -> {
                    assertThat(t.kind()).isEqualTo("TITLES");
                    assertThat(t.agreementId()).isNull();
                    assertThat(t.itemIds()).containsExactly(titleB);
                    assertThat(t.strategy()).isEqualTo("OLDEST_FIRST");
                });
        assertThat(asFinance2(() -> payments.get(big.id())).requestedTarget().itemIds()).containsExactly(titleB);
        // filtros da fila (credor e fonte) e quanto passa do limite: R$ 900,00 contra R$ 500,00 por pagamento
        assertThat(asFinance2(() -> payments.reviewQueue(new ReviewQueueFilter(creditorB, "AGENCY_ACCOUNT"),
                PageQuery.of(0, 10))).content()).extracting(PaymentView::id).containsExactly(big.id());
        assertThat(asFinance2(() -> payments.reviewQueue(new ReviewQueueFilter(null, "CREDITOR_DIRECT"),
                PageQuery.of(0, 10))).content()).isEmpty();
        assertThatThrownBy(() -> asFinance2(() -> payments.reviewQueue(new ReviewQueueFilter(null, "XPTO"),
                PageQuery.of(0, 10)))).isInstanceOf(BusinessRuleException.class);
        PaymentUseCases.ReviewAssessmentView assessment = asFinance2(() -> payments.reviewAssessment(big.id()));
        assertThat(assessment.reason()).isEqualTo("AMOUNT");
        assertThat(assessment.limit()).isEqualByComparingTo("500.00");
        assertThat(assessment.consideredAmount()).isEqualByComparingTo("900.00");
        assertThat(assessment.excess()).isEqualByComparingTo("400.00");
        assertThat(assessment.ownPayment()).isFalse();
        assertThat(asFinance1(() -> payments.reviewAssessment(big.id())).ownPayment()).isTrue();
        assertThatThrownBy(() -> asFinance2(() -> payments.reviewAssessment(small.id())))
                .isInstanceOf(BusinessRuleException.class);
        // escopo e permissão: credor fora do escopo no filtro → 403; pagamento fora do escopo → 404; sem PAYMENT_REVIEW
        // (operador) → recusado
        assertThatThrownBy(() -> as(finance2, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorA)),
                () -> payments.reviewQueue(new ReviewQueueFilter(creditorB, null), PageQuery.of(0, 10))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(as(finance2, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorA)),
                () -> payments.reviewQueue(ReviewQueueFilter.ALL, PageQuery.of(0, 10))).content()).isEmpty();
        assertThatThrownBy(() -> as(finance2, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorA)),
                () -> payments.reviewAssessment(big.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(() -> payments.reviewAssessment(big.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asFinance1(() -> payments.approve(big.id(), null, false)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não pode revisá-lo");
        assertThatThrownBy(() -> asOperator(() -> payments.approve(big.id(), null, false)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView approved = asFinance2(() -> payments.approve(big.id(), "Comprovante conferido no extrato", false));
        assertThat(approved.status()).isEqualTo("CONFIRMED");
        assertThat(approved.reviewedBy()).isEqualTo(finance2);
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("SETTLED");

        // recusa exige motivo e não produz baixa
        PaymentView doubtful = asOperator(() -> payments.registerExternal(toTitleB("600.00", "DOC-3"), pdf(), null));
        assertThatThrownBy(() -> asFinance1(() -> payments.reject(doubtful.id(), null))).isInstanceOf(DomainException.class);
        assertThat(asFinance1(() -> payments.reject(doubtful.id(), "Comprovante ilegível")).status()).isEqualTo("REJECTED");

        // título já quitado: o valor fica como sobra (não alocado) e entra na fila de realocação
        PaymentView extra = asOperator(() -> payments.registerExternal(toTitleB("50.00", "DOC-4"), pdf(), null));
        assertThat(extra.status()).isEqualTo("CONFIRMED");
        assertThat(extra.allocations()).isEmpty();
        assertThat(extra.unallocatedAmount()).isEqualByComparingTo("50.00");
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(extra.id());
        // devolução ao pagador registrada (com referência) zera a sobra
        PaymentView refunded = asFinance1(() -> payments.reallocate(extra.id(), new ReallocateCommand(null,
                new BigDecimal("50.00"), null, "TED-DEVOLUCAO-1", "Valor pago a maior")));
        assertThat(refunded.unallocatedAmount()).isEqualByComparingTo("0");
        assertThat(refunded.allocations()).singleElement().satisfies(a -> assertThat(a.kind()).isEqualTo("REFUND"));
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).isEmpty();

        // evidência: download registrado no log de acesso
        EvidenceContent evidence = asFinance1(() -> payments.openEvidence(small.id()));
        try (InputStream in = evidence.content()) {
            assertThat(in.readAllBytes()).isEqualTo(PDF);
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED'")).isEqualTo(1);

        // estorno manual (PAYMENT_REVERSE): compensação no razão do título, que volta a ter saldo
        assertThatThrownBy(() -> asOperator(() -> payments.reverse(approved.id(), new ReverseCommand("CHARGEBACK", "x"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView reversed = asFinance1(() -> payments.reverse(approved.id(), new ReverseCommand("BANK_RETURN",
                "Transferência devolvida pelo banco")));
        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("OPEN");
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        assertThat(count("select count(*) from debt.title_ledger_entry where entry_type = 'REVERSAL' and reference_id = ?",
                reversed.allocations().getFirst().id())).isPositive();
        assertThatThrownBy(() -> asFinance1(() -> payments.reverse(approved.id(), new ReverseCommand("OTHER", "de novo"))))
                .isInstanceOf(DomainException.class);
        awaitConsumed("collection.payment-reversed", 1);
        // estorno é append-only no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update payment.payment_reversal set reason = 'OTHER'")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        for (String type : List.of("payment.informed", "payment.confirmed", "payment.rejected", "payment.reversed",
                "payment.reallocated")) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type)).as(type).isPositive();
        }
        String attributes = system(() -> jdbcTemplate.queryForObject("select string_agg(attributes::text, ' ') "
                + "from shared.business_event where event_type like 'payment%'", String.class));
        // ids (UUID v7, gerados pelo relógio) saem antes: um trecho do documento pode aparecer dentro de um id por acaso
        assertThat(attributes.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>"))
                .doesNotContain("Depósito identificado", "Maria", "529");
    }

    /**
     * Decisão do responsável à 856: quem só informa pagamento externo (perfil personalizado sem {@code PAYMENT_VIEW}
     * nem {@code ORG_SETTINGS_MANAGE}) recebe o limite da segunda conferência — e só ele — para a tela avisar antes
     * do envio; os demais parâmetros continuam fechados.
     */
    @Test
    void registerOnlyProfileReceivesJustTheSecondReviewThreshold() throws Exception {
        provision("limite");
        asAdmin(() -> settings.update(new SettingsCommand(new BigDecimal("750.00"), 86_400, 30, 3)));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        ActorAuthentication registerOnly = auth(EnumSet.of(Permission.PAYMENT_REGISTER_EXTERNAL, Permission.AGREEMENT_VIEW,
                Permission.DEBTOR_VIEW));

        mvc.perform(get("/api/v1/payment/settings/external-review").with(authentication(registerOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalReviewThreshold").value(750.0))
                .andExpect(jsonPath("$.*", hasSize(1)));
        // os parâmetros completos continuam exigindo PAYMENT_VIEW ou ORG_SETTINGS_MANAGE
        mvc.perform(get("/api/v1/payment/settings").with(authentication(registerOnly)))
                .andExpect(status().isForbidden());
        // sem a permissão de informar pagamento externo, nem o limite
        mvc.perform(get("/api/v1/payment/settings/external-review")
                        .with(authentication(auth(EnumSet.of(Permission.PAYMENT_VIEW, Permission.AGREEMENT_VIEW)))))
                .andExpect(status().isForbidden());
        // o caso de uso também confere a permissão (chamada fora da web)
        assertThat(asOperator(() -> settings.externalReviewLimit()).externalReviewThreshold())
                .isEqualByComparingTo("750.00");
        assertThatThrownBy(() -> tenantExecution.callAs(new Actor(Actor.ActorType.USER, manager, tenant, Ids.newId(),
                "PERSONALIZADO", EnumSet.of(Permission.PAYMENT_VIEW), CreditorScope.ALL), settings::externalReviewLimit))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    private ActorAuthentication auth(Set<Permission> permissions) {
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                permissions, CreditorScope.ALL));
    }

    @Test
    void splittingBelowTheThresholdStillNeedsASecondPersonForTheAccumulatedTotal() {
        provision("fracionado");

        // limite R$ 500,00 por pagamento e, por padrão, também para o acumulado do devedor no credor em 24 h
        PaymentView first = asOperator(() -> payments.registerExternal(toTitleB("450.00", "F-1"), pdf(), null));
        assertThat(first.status()).isEqualTo("CONFIRMED");
        PaymentView second = asOperator(() -> payments.registerExternal(toTitleB("450.00", "F-2"), pdf(), null));
        assertThat(second.status()).isEqualTo("INFORMED");
        assertThat(second.reviewThresholdExceeded()).isTrue();
        // o que aguarda revisão não consome o acumulado; o que cabe continua automático
        PaymentView third = asOperator(() -> payments.registerExternal(toTitleB("40.00", "F-3"), pdf(), null));
        assertThat(third.status()).isEqualTo("CONFIRMED");
        assertThat(system(() -> paymentRepository.sumExternalInformedBy(operator, Instant.now().minus(1, ChronoUnit.DAYS)))
                .amount()).isEqualByComparingTo("490.00");
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.informed'"
                + " and attributes->>'reviewReason' = 'DESTINATION_CUMULATIVE'")).isEqualTo(1);
        // a segunda pessoa decide; quem informou não
        assertThatThrownBy(() -> asOperator(() -> payments.approve(second.id(), null, false)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asFinance1(() -> payments.approve(second.id(), "Conferido no extrato", false)).status()).isEqualTo("CONFIRMED");
    }

    /**
     * H07 (decisão 1466): a integração do credor (token da API pública) só informa o que o credor recebeu; o canal
     * vem do tipo do ator e fica gravado; abaixo dos limites confirma sem revisão; o teto por credor soma todos os
     * tokens do credor (e nada dos operadores).
     */
    @Test
    void creditorIntegrationRecordsTheChannelAndTheCreditorTotalSumsAllItsTokens() {
        provision("integracao");
        UUID tokenA = Ids.newId();
        UUID tokenB = Ids.newId();
        java.util.function.Function<String, ExternalPaymentCommand> direct = reference -> new ExternalPaymentCommand(
                creditorB, debtor, new BigDecimal("100.00"), paidAt, "PIX", "CREDITOR_DIRECT", reference, null,
                new TargetInput("TITLES", null, List.of(titleB), null));

        // a integração não informa o que entrou na conta da assessoria
        assertThatThrownBy(() -> asIntegration(tokenA, () -> payments.registerExternal(toTitleB("100.00", "I-0"), pdf(), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("recebido diretamente pelo credor");

        PaymentView first = asIntegration(tokenA, () -> payments.registerExternal(direct.apply("I-1"), pdf(), null));
        assertThat(first.status()).isEqualTo("CONFIRMED");
        assertThat(first.reviewMode()).isEqualTo("AUTO_LOW_RISK");
        assertThat(first.informedBy()).isEqualTo(tokenA);
        // o token não é de um cadastro do publicapi aqui: o pagamento traz o id sem nome
        assertThat(first.integration()).isNotNull();
        assertThat(first.integration().tokenId()).isEqualTo(tokenA);
        assertThat(first.integration().tokenName()).isNull();
        assertThat(count("select count(*) from payment.payment where id = ? and informed_channel = 'INTEGRATION'",
                first.id())).isEqualTo(1);
        asIntegration(tokenB, () -> payments.registerExternal(direct.apply("I-2"), pdf(), null));
        // o operador que informa um recebimento do credor não entra no canal nem no teto da integração
        PaymentView byOperator = asOperator(() -> payments.registerExternal(direct.apply("I-3"), pdf(), null));
        assertThat(byOperator.integration()).isNull();

        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        assertThat(system(() -> paymentRepository.sumIntegrationInformedForCreditor(creditorB, since)).amount())
                .isEqualByComparingTo("200.00");
        assertThat(system(() -> paymentRepository.sumIntegrationInformedForCreditor(creditorA, since)).amount())
                .isEqualByComparingTo("0");
    }

    /**
     * Token só com {@code payments:write} (sem {@code AGREEMENT_VIEW}) registra para parcela de acordo do próprio credor
     * (decisão 1518); o acordo de outro credor não existe para ele (404) e nada é gravado.
     */
    @Test
    void creditorIntegrationReachesOnlyItsOwnCreditorsAgreements() {
        provision("integracao-acordo");
        AgreementView agreement = formalizeAgreement(2);
        UUID installment = agreement.installments().getFirst().id();
        ExternalPaymentCommand toAgreement = new ExternalPaymentCommand(creditorA, debtor, new BigDecimal("10.00"),
                paidAt, "PIX", "CREDITOR_DIRECT", "IA-1", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(installment), null));
        PaymentView ok = asIntegration(Ids.newId(), creditorA, () -> payments.registerExternal(toAgreement, pdf(), null));
        assertThat(ok.status()).isEqualTo("CONFIRMED");
        assertThat(ok.integration()).isNotNull();

        ExternalPaymentCommand cross = new ExternalPaymentCommand(creditorB, debtor, new BigDecimal("10.00"), paidAt,
                "PIX", "CREDITOR_DIRECT", "IA-2", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(installment), null));
        assertThatThrownBy(() -> asIntegration(Ids.newId(), creditorB, () -> payments.registerExternal(cross, pdf(), null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from payment.payment where external_reference = 'IA-2'")).isZero();
    }

    /**
     * A dispensa de {@code AGREEMENT_VIEW} na alocação vale só para o token de integração (decisão 1518,
     * SEG-20261125-J01): a pessoa da assessoria com {@code PAYMENT_REGISTER_EXTERNAL} e sem {@code AGREEMENT_VIEW} não
     * informa pagamento nem cria instrução de pagamento para parcela de acordo, e nada é gravado.
     */
    @Test
    void staffWithoutAgreementViewCannotTargetAnAgreement() {
        provision("pessoa-sem-acordo");
        AgreementView agreement = formalizeAgreement(1);
        ExternalPaymentCommand toAgreement = new ExternalPaymentCommand(creditorA, debtor, new BigDecimal("10.00"),
                paidAt, "PIX", "CREDITOR_DIRECT", "PSA-1", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(agreement.installments().getFirst().id()), null));
        Actor staff = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                EnumSet.of(Permission.PAYMENT_REGISTER_EXTERNAL, Permission.PAYMENT_VIEW), CreditorScope.ALL);
        assertThatThrownBy(() -> tenantExecution.callAs(staff, () -> payments.registerExternal(toAgreement, pdf(), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from payment.payment where external_reference = 'PSA-1'")).isZero();
        // nem cria instrução de pagamento (Pix ou boleto) para a parcela sem poder ver o acordo
        Actor instructor = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                EnumSet.of(Permission.PAYMENT_INSTRUCTION_CREATE, Permission.PAYMENT_REGISTER_EXTERNAL,
                        Permission.PAYMENT_VIEW), CreditorScope.ALL);
        long before = count("select count(*) from payment.payment_instruction");
        assertThatThrownBy(() -> tenantExecution.callAs(instructor, () -> instructions.create(
                new br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand(
                        "PIX_IMMEDIATE", agreement.id(), agreement.installments().getFirst().id(), null, null, null, null,
                        "WHATSAPP"), null))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from payment.payment_instruction")).isEqualTo(before);
    }

    /**
     * Pagamento recebido diretamente pelo credor (H07, decisões 1466 e 1521): o razão do título grava quem recebeu, o
     * título mostra o total pago ao credor (selo "Pago ao credor" e filtro da lista), o acordo marca o pagamento, e o
     * estorno tira o selo. O pagamento na conta da assessoria não leva o selo.
     */
    @Test
    void paymentReceivedByTheCreditorMarksTitleAndAgreementUntilReversed() {
        provision("pago-ao-credor");
        PaymentView agency = asOperator(() -> payments.registerExternal(toTitleB("50.00", "PC-0"), pdf(), null));
        assertThat(agency.status()).isEqualTo("CONFIRMED");
        assertThat(system(() -> debtTitles.get(titleB)).creditorPaidTotal()).isEqualByComparingTo("0");

        PaymentView direct = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("120.00"), paidAt, "PIX", "CREDITOR_DIRECT", "PC-1", null,
                new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));
        assertThat(direct.status()).isEqualTo("CONFIRMED");
        assertThat(system(() -> debtTitles.get(titleB)).creditorPaidTotal()).isEqualByComparingTo("120.00");
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("170.00");
        assertThat(count("""
                select count(*) from debt.title_ledger_entry
                 where title_id = ? and entry_type = 'PAYMENT' and received_by = 'CREDITOR'""", titleB)).isPositive();
        assertThat(count("""
                select count(*) from debt.title_ledger_entry
                 where title_id = ? and entry_type = 'PAYMENT' and received_by = 'AGENCY'""", titleB)).isPositive();
        assertThat(system(() -> debtTitles.search(new DebtTitleUseCases.TitleSearch(null, null, null, null, null, null,
                null, null, null, true), PageQuery.of(0, 50))).content()).extracting(DebtTitleUseCases.TitleView::id)
                .containsExactly(titleB);
        // o filtro respeita o escopo por credor: quem só vê o credor A não acha o título do B
        Actor onlyA = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                EnumSet.of(Permission.DEBTOR_VIEW), CreditorScope.of(List.of(creditorA)));
        assertThat(tenantExecution.callAs(onlyA, () -> debtTitles.search(new DebtTitleUseCases.TitleSearch(null, null,
                null, null, null, null, null, null, null, true), PageQuery.of(0, 50))).content())
                .extracting(DebtTitleUseCases.TitleView::id).doesNotContain(titleB);

        // parcela de acordo paga diretamente ao credor: o acordo marca o pagamento e o lançamento chega ao razão
        AgreementView agreement = formalizeAgreement(2);
        PaymentView toAgreement = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                new BigDecimal("10.00"), paidAt, "PIX", "CREDITOR_DIRECT", "PC-2", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(agreement.installments().getFirst().id()), null)),
                pdf(), null));
        assertThat(toAgreement.status()).isEqualTo("CONFIRMED");
        awaitConsumed("payment.agreement-payment-recorded", 1);
        settle();
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.receivedBy()).isEqualTo("CREDITOR"));
        assertThat(count("""
                select count(*) from debt.title_ledger_entry e join debt.debt_title t on t.id = e.title_id
                 where t.creditor_id = ? and e.entry_type = 'PAYMENT' and e.received_by = 'CREDITOR'
                   and t.creditor_paid_total > 0""", creditorA)).isPositive();

        // estorno: o selo some (o estorno repete o receptor do pagamento)
        asFinance1(() -> payments.reverse(direct.id(), new ReverseCommand("BANK_RETURN", "Credor devolveu ao devedor")));
        assertThat(system(() -> debtTitles.get(titleB)).creditorPaidTotal()).isEqualByComparingTo("0");
        assertThat(count("""
                select count(*) from debt.title_ledger_entry
                 where title_id = ? and entry_type = 'REVERSAL' and received_by = 'CREDITOR'""", titleB)).isPositive();
    }

    private <T> T asIntegration(UUID tokenId, java.util.function.Supplier<T> action) {
        return asIntegration(tokenId, creditorB, action);
    }

    /** Ator do token de integração com as permissões do escopo {@code payments:write} ({@code TokenScope}). */
    private <T> T asIntegration(UUID tokenId, UUID creditorId, java.util.function.Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.INTEGRATION, tokenId, tenant, null,
                "integration:" + tokenId.toString().substring(0, 8),
                EnumSet.of(Permission.PAYMENT_REGISTER_EXTERNAL, Permission.PAYMENT_VIEW),
                CreditorScope.of(List.of(creditorId))), action);
    }

    @Test
    void concurrentSplitRegistrationsCannotBothEscapeReview() throws Exception {
        provision("concorrente");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PaymentView>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String reference = "C-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return asOperator(() -> payments.registerExternal(toTitleB("300.00", reference), pdf(), null));
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<PaymentView> r : results) {
                statuses.add(r.get(30, TimeUnit.SECONDS).status());
            }
            assertThat(statuses).containsExactlyInAnyOrder("CONFIRMED", "INFORMED");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * RS-101 (SEG-02 d): dois tokens do mesmo credor registrando ao mesmo tempo, cada pagamento abaixo do limite por
     * pagamento, por token e por devedor, mas a soma passando do teto acumulado por credor (4 × R$ 500,00): a trava por
     * credor serializa os dois — exatamente um confirma sem revisão e o outro vai para a aprovação com o motivo
     * {@code CREDITOR_CUMULATIVE}. Registros cruzando devedores e tokens terminam sem deadlock.
     */
    @Test
    void twoTokensOfTheSameCreditorCannotBothEscapeTheCreditorCeiling() throws Exception {
        provision("teto-credor");
        UUID[][] debtors = new UUID[7][];
        for (int i = 0; i < debtors.length; i++) {
            debtors[i] = debtorWithTitle(creditorB, portfolioB, 40 + i, "Devedor Ficticio " + i, "TC-" + i, "800.00");
        }
        java.util.function.BiFunction<Integer, String, ExternalPaymentCommand> toDebtor = (i, amount) ->
                new ExternalPaymentCommand(creditorB, debtors[i][0], new BigDecimal(amount), paidAt, "PIX",
                        "CREDITOR_DIRECT", "TC-REF-" + i + "-" + amount, null,
                        new TargetInput("TITLES", null, List.of(debtors[i][1]), null));
        // já confirmados na janela por um terceiro token: R$ 1.500,00 (abaixo dos 2.000,00 do credor)
        UUID preload = Ids.newId();
        for (int i = 0; i < 3; i++) {
            int debtorIndex = i;
            assertThat(asIntegration(preload, () -> payments.registerExternal(toDebtor.apply(debtorIndex, "500.00"),
                    pdf(), null)).status()).isEqualTo("CONFIRMED");
        }
        UUID tokenA = Ids.newId();
        UUID tokenB = Ids.newId();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PaymentView>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                start.await();
                return asIntegration(tokenA, () -> payments.registerExternal(toDebtor.apply(3, "300.00"), pdf(), null));
            }));
            results.add(pool.submit(() -> {
                start.await();
                return asIntegration(tokenB, () -> payments.registerExternal(toDebtor.apply(4, "300.00"), pdf(), null));
            }));
            start.countDown();
            List<PaymentView> views = new ArrayList<>();
            for (Future<PaymentView> r : results) {
                views.add(r.get(30, TimeUnit.SECONDS));
            }
            assertThat(views).extracting(PaymentView::status).containsExactlyInAnyOrder("CONFIRMED", "INFORMED");
            assertThat(views).filteredOn(v -> "CONFIRMED".equals(v.status())).singleElement()
                    .satisfies(v -> assertThat(v.reviewMode()).isEqualTo("AUTO_LOW_RISK"));
            UUID informed = views.stream().filter(v -> "INFORMED".equals(v.status())).findFirst().orElseThrow().id();
            assertThat(count("select count(*) from payment.payment where id = ? and review_reason = 'CREDITOR_CUMULATIVE'",
                    informed)).isEqualTo(1);

            // cruzando devedores e tokens (A→5, B→6, A→6, B→5): todos terminam, sem deadlock
            CountDownLatch cross = new CountDownLatch(1);
            List<Future<PaymentView>> crossed = new ArrayList<>();
            int[][] pairs = {{0, 5}, {1, 6}, {0, 6}, {1, 5}};
            for (int[] pair : pairs) {
                UUID token = pair[0] == 0 ? tokenA : tokenB;
                int debtorIndex = pair[1];
                String amount = "1" + pair[0] + ".00";
                crossed.add(pool.submit(() -> {
                    cross.await();
                    return asIntegration(token, () -> payments.registerExternal(toDebtor.apply(debtorIndex, amount),
                            pdf(), null));
                }));
            }
            cross.countDown();
            for (Future<PaymentView> r : crossed) {
                assertThat(r.get(30, TimeUnit.SECONDS).status()).isIn("CONFIRMED", "INFORMED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unappliedAgreementPaymentIsReallocatedToTitlesWithCompensation() {
        provision("unapplied");
        asAdmin(() -> settings.update(new SettingsCommand(new BigDecimal("100000.00"), 86_400, 30, 3)));
        AgreementView agreement = formalizeAgreement(1);
        BigDecimal total = agreement.installments().getFirst().amount();

        // pagamento confirmado para o acordo e cancelamento do acordo na mesma transação: o negotiation recebe o
        // pagamento já com o acordo encerrado e devolve "não aplicado"
        PaymentView payment = asAdmin(() -> {
            PaymentView p = payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor, total,
                    Instant.now().minus(1, ChronoUnit.HOURS), "PIX", "AGENCY_ACCOUNT", "E2E-EXTERNO", null,
                    new TargetInput("AGREEMENT", agreement.id(), List.of(), null)), pdf(), null);
            agreements.cancel(agreement.id(), "Devedor desistiu");
            return p;
        });
        assertThat(payment.status()).isEqualTo("CONFIRMED");
        assertThat(payment.allocations()).singleElement().satisfies(a -> assertThat(a.outcome()).isEqualTo("PENDING"));
        // destino solicitado: acordo sem parcelas escolhidas (= todas em aberto), sem estratégia de títulos
        assertThat(payment.requestedTarget()).satisfies(t -> {
            assertThat(t.kind()).isEqualTo("AGREEMENT");
            assertThat(t.agreementId()).isEqualTo(agreement.id());
            assertThat(t.itemIds()).isEmpty();
            assertThat(t.strategy()).isNull();
        });
        awaitConsumed("payment.agreement-payment-recorded", 1);
        AllocationView unapplied = asFinance1(() -> payments.get(payment.id())).allocations().getFirst();
        assertThat(unapplied.outcome()).isEqualTo("UNAPPLIED");
        assertThat(unapplied.availableForReallocation()).isEqualByComparingTo(total);
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(payment.id());
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo("UNAPPLIED"));

        // realocação do não aplicado para os títulos (liberados pelo cancelamento): razão do título + compensação
        UUID allocationId = unapplied.id();
        assertThatThrownBy(() -> asOperator(() -> payments.reallocate(payment.id(), new ReallocateCommand(allocationId,
                total, new TargetInput("TITLES", null, List.of(title60, title30), null), null, "Realocação"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView reallocated = asFinance1(() -> payments.reallocate(payment.id(), new ReallocateCommand(allocationId,
                total, new TargetInput("TITLES", null, List.of(title60, title30), "OLDEST_FIRST"), null,
                "Acordo cancelado antes da baixa")));
        // os títulos recebem até o saldo do razão (principal); o que exceder continua na fila de realocação
        BigDecimal expected = total.min(new BigDecimal("1500.00"));
        assertThat(reallocated.pendingReallocation()).isEqualByComparingTo(total.subtract(expected));
        assertThat(reallocated.allocations()).hasSize(2);
        AllocationView titles = reallocated.allocations().get(1);
        assertThat(titles.origin()).isEqualTo("REALLOCATION");
        assertThat(titles.sourceAllocationId()).isEqualTo(allocationId);
        assertThat(titles.lines().getFirst().titleId()).isEqualTo(title60);
        BigDecimal paid = system(() -> debtTitles.get(title60)).paidTotal().add(system(() -> debtTitles.get(title30))
                .paidTotal());
        assertThat(paid).isEqualByComparingTo(expected);
        assertThat(titles.amount()).isEqualByComparingTo(expected);
        // compensação publicada para a alocação liberada (o negotiation ignora; o incentive retira a base)
        awaitConsumed("negotiation.payment-reversed", 1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reallocated'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'payment.unapplied'")).isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationEventPublisher publisher;

    @org.springframework.beans.factory.annotation.Autowired
    br.com.gestorcobranca.shared.storage.FileStorage fileStorage;

    @org.springframework.beans.factory.annotation.Autowired
    br.com.gestorcobranca.shared.crypto.BlindIndex blindIndex;

    @Test
    void debtorAnonymizationRemovesEvidenceFileAndFreeTextButKeepsTheHash() {
        provision("anonymize");
        PaymentView paid = asOperator(() -> payments.registerExternal(toTitleB("100.00", "DOC-A"), pdf(), null));
        String key = system(() -> jdbcTemplate.queryForObject("select evidence_key from payment.payment where id = ?",
                String.class, paid.id()));
        assertThat(paid.evidenceAvailable()).isTrue();
        assertThat(evidenceStored(key)).isTrue();
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("BANK_RETURN", "Devolvido; falei com a Maria")));

        UUID eventId = br.com.gestorcobranca.shared.domain.Ids.newId();
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(
                new br.com.gestorcobranca.shared.events.debt.DebtorAnonymized(eventId, tenant, Instant.now(), debtor)));
        awaitTrue("consumo do payment", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'payment.debtor-anonymized' and event_id = ?", eventId) == 1);

        PaymentView after = asFinance1(() -> payments.get(paid.id()));
        assertThat(after.evidenceAvailable()).isFalse();
        assertThat(after.evidenceSha256()).isEqualTo(paid.evidenceSha256());
        assertThat(after.note()).isNull();
        assertThat(after.externalReference()).isNull();
        assertThat(after.status()).isEqualTo("REVERSED");
        // H04: o texto da referência sai; fica só a forma HMAC (com chave e assessoria), que o extrato ainda reconhece
        assertThat(count("select count(*) from payment.payment where id = ? and external_reference = ?", paid.id(),
                "anon:" + blindIndex.of(tenant, "payment.external_reference", "DOC-A"))).isEqualTo(1);
        // nem a trilha selada guarda o texto da referência
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and metadata::text like '%DOC-A%'",
                paid.id())).isZero();
        assertThat(count("select count(*) from payment.payment where id = ? and evidence_key is null "
                + "and evidence_name is null and note_encrypted is null and review_note_encrypted is null", paid.id()))
                .isEqualTo(1);
        // apagado dentro da transação do consumo: já não existe quando o consumo aparece confirmado
        assertThat(evidenceStored(key)).isFalse();
        assertThat(count("select count(*) from payment.payment_reversal where payment_id = ? and note_encrypted is null",
                paid.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'payment.debtor_anonymized' "
                + "and entity_id = ?", debtor)).isEqualTo(1);
    }

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean evidenceStored(String key) {
        return system(() -> {
            try (java.io.InputStream in = fileStorage.open(key)) {
                return in.read() >= 0;
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                return false;
            }
        });
    }
}
