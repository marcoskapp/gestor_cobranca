package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries;
import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries.ReportedTitleFilter;
import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries.ReportedTitleItem;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases.ReportedPaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases.ReportedPaymentsPage;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases.ReportedPaymentItem;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Baixas informadas pelo credor" (H07 parte 2b) contra PostgreSQL real: escopo por credor nas linhas e nos totais das
 * duas abas, filtros de token/lote só dentro do escopo, assessoria isolada (RS-94, RS-95); minimização por permissão e
 * titular anonimizado (RS-96, RS-97, RS-106); validação do filtro (RS-105); canal na situação de revisão sem o token
 * (RS-99); aprovação com token inativo exige confirmação (RS-100); o que a carga grava no título (RS-98).
 */
class CreditorReportedIsolationIT extends PaymentTestSupport {

    @Autowired
    CreditorReportedPaymentsUseCases settlements;
    @Autowired
    CreditorReportedTitlesQueries importSettlements;
    @Autowired
    PaymentReviewStatusUseCases reviewStatus;
    @Autowired
    DebtorUseCases debtorUseCases;
    @Autowired
    WebApplicationContext webApplicationContext;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private ReportedPaymentFilter period() {
        return new ReportedPaymentFilter(today.minusDays(2), today.plusDays(1), null, null, null, null, null, null);
    }

    private ReportedTitleFilter importPeriod() {
        return new ReportedTitleFilter(today.minusDays(2), today.plusDays(1), null, null, null);
    }

    private ExternalPaymentCommand direct(UUID creditorId, UUID titleId, String amount, String origin, String ref) {
        return new ExternalPaymentCommand(creditorId, debtor, new BigDecimal(amount), paidAt, "PIX", origin, ref, null,
                new TargetInput("TITLES", null, List.of(titleId), null));
    }

    private <T> T asIntegration(UUID tokenId, UUID creditorId, Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.INTEGRATION, tokenId, tenant, null,
                "integration:" + tokenId.toString().substring(0, 8),
                EnumSet.of(Permission.PAYMENT_REGISTER_EXTERNAL, Permission.PAYMENT_VIEW),
                CreditorScope.of(List.of(creditorId))), action);
    }

    private Actor user(Set<Permission> permissions, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions), scope);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void bothTabsFollowTheCreditorScopeAndFiltersNeverWidenIt() throws Exception {
        provision("baixas-escopo");
        PaymentView toB = asOperator(() -> payments.registerExternal(direct(creditorB, titleB, "120.00",
                "CREDITOR_DIRECT", "BX-1"), pdf(), null));
        PaymentView toA = asOperator(() -> payments.registerExternal(direct(creditorA, title60, "80.00",
                "CREDITOR_REPORT", "BX-2"), pdf(), null));
        // pago na conta da assessoria: fora da lista
        PaymentView agency = asOperator(() -> payments.registerExternal(direct(creditorB, titleB, "30.00",
                "AGENCY_ACCOUNT", "BX-3"), pdf(), null));
        UUID tokenB = Ids.newId();
        PaymentView byToken = asIntegration(tokenB, creditorB, () -> payments.registerExternal(direct(creditorB, titleB,
                "50.00", "CREDITOR_DIRECT", "BX-4"), pdf(), null));
        // título zerado pela carga do credor A (redução do principal ao que já foi pago na conta da assessoria)
        asOperator(() -> payments.registerExternal(direct(creditorA, title30, "100.00", "AGENCY_ACCOUNT", "BX-5"), pdf(),
                null));
        UUID batchA = Ids.newId();
        system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-30", null, 2,
                today.minusDays(30), new BigDecimal("100.00"), null, null), batchA));

        ReportedPaymentsPage all = asAdmin(() -> settlements.search(period(), PageQuery.of(0, 50)));
        assertThat(all.items().content()).extracting(ReportedPaymentItem::paymentId)
                .containsExactlyInAnyOrder(toB.id(), toA.id(), byToken.id()).doesNotContain(agency.id());
        assertThat(all.totals().count()).isEqualTo(3);
        assertThat(all.totals().amount()).isEqualByComparingTo("250.00");
        assertThat(all.items().content()).filteredOn(i -> i.paymentId().equals(byToken.id())).singleElement()
                .satisfies(i -> assertThat(i.channel()).isEqualTo("INTEGRATION"));
        assertThat(asAdmin(() -> settlements.search(new ReportedPaymentFilter(today.minusDays(2), today.plusDays(1),
                null, "OPERATOR", null, null, null, null), PageQuery.of(0, 50))).totals().count()).isEqualTo(2);

        // usuário só do credor A: linhas e totais só do A; pedir o B é recusado como nas outras leituras
        Actor onlyA = user(Set.of(Permission.PAYMENT_VIEW, Permission.DEBTOR_VIEW), CreditorScope.of(List.of(creditorA)));
        ReportedPaymentsPage scoped = as(onlyA, () -> settlements.search(period(), PageQuery.of(0, 50)));
        assertThat(scoped.items().content()).extracting(ReportedPaymentItem::paymentId).containsExactly(toA.id());
        assertThat(scoped.totals().amount()).isEqualByComparingTo("80.00");
        Class<? extends Throwable> otherListRefusal = refusal(() -> as(onlyA, () -> payments.get(toB.id())));
        assertThatThrownBy(() -> as(onlyA, () -> settlements.search(new ReportedPaymentFilter(today.minusDays(2),
                today.plusDays(1), creditorB, null, null, null, null, null), PageQuery.of(0, 50))))
                .isInstanceOf(otherListRefusal);
        assertThatThrownBy(() -> as(onlyA, () -> importSettlements.search(new ReportedTitleFilter(today.minusDays(2),
                today.plusDays(1), creditorB, null, null), PageQuery.of(0, 50)))).isInstanceOf(otherListRefusal);

        // token de outro credor e token inexistente: a mesma página vazia, sem rótulo (RS-95)
        Actor onlyAWithTokens = user(Set.of(Permission.PAYMENT_VIEW, Permission.DEBTOR_VIEW,
                Permission.PUBLIC_API_TOKEN_VIEW), CreditorScope.of(List.of(creditorA)));
        ReportedPaymentsPage foreignToken = as(onlyAWithTokens, () -> settlements.search(new ReportedPaymentFilter(
                today.minusDays(2), today.plusDays(1), null, null, tokenB, null, null, null), PageQuery.of(0, 50)));
        ReportedPaymentsPage randomToken = as(onlyAWithTokens, () -> settlements.search(new ReportedPaymentFilter(
                today.minusDays(2), today.plusDays(1), null, null, Ids.newId(), null, null, null), PageQuery.of(0, 50)));
        assertThat(foreignToken.items().content()).isEmpty();
        assertThat(foreignToken.totals()).isEqualTo(randomToken.totals());
        assertThat(asAdmin(() -> settlements.search(new ReportedPaymentFilter(today.minusDays(2), today.plusDays(1),
                null, null, tokenB, null, null, null), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedPaymentItem::paymentId).containsExactly(byToken.id());
        // sem PUBLIC_API_TOKEN_VIEW o filtro por token é ignorado (RC-03): mesma resposta que sem o filtro
        Actor noTokenView = user(Set.of(Permission.PAYMENT_VIEW), CreditorScope.ALL);
        assertThat(as(noTokenView, () -> settlements.search(new ReportedPaymentFilter(today.minusDays(2),
                today.plusDays(1), null, null, tokenB, null, null, null), PageQuery.of(0, 50))).totals())
                .isEqualTo(as(noTokenView, () -> settlements.search(period(), PageQuery.of(0, 50))).totals());

        // aba da carga: o título zerado do A aparece para quem vê o A; o lote é só condição dentro do escopo
        List<ReportedTitleItem> imports = as(onlyA, () -> importSettlements.search(importPeriod(), PageQuery.of(0, 50)))
                .items().content();
        assertThat(imports).extracting(ReportedTitleItem::titleId).containsExactly(title30);
        assertThat(imports.getFirst().situation()).isEqualTo("SETTLED_BY_IMPORT");
        assertThat(imports.getFirst().amount()).isEqualByComparingTo("400.00");
        Actor onlyB = user(Set.of(Permission.PAYMENT_VIEW, Permission.IMPORT_VIEW), CreditorScope.of(List.of(creditorB)));
        assertThat(as(onlyB, () -> importSettlements.search(new ReportedTitleFilter(today.minusDays(2),
                today.plusDays(1), null, batchA, null), PageQuery.of(0, 50))).totals().count()).isZero();
        assertThat(as(onlyB, () -> importSettlements.search(new ReportedTitleFilter(today.minusDays(2),
                today.plusDays(1), null, Ids.newId(), null), PageQuery.of(0, 50))).totals().count()).isZero();
        // sem IMPORT_VIEW o filtro por lote é ignorado (RC-03): o lote aleatório não esvazia a página
        assertThat(as(onlyA, () -> importSettlements.search(new ReportedTitleFilter(today.minusDays(2),
                today.plusDays(1), null, Ids.newId(), null), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedTitleItem::titleId).containsExactly(title30);

        // outra assessoria não vê nada desta
        Set<UUID> firstTenant = Set.of(toB.id(), toA.id(), byToken.id());
        UUID firstTitle = title30;
        provision("baixas-outra");
        assertThat(asAdmin(() -> settlements.search(period(), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedPaymentItem::paymentId).doesNotContainAnyElementsOf(firstTenant);
        assertThat(asAdmin(() -> importSettlements.search(importPeriod(), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedTitleItem::titleId).doesNotContain(firstTitle);
    }

    private static Class<? extends Throwable> refusal(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e.getClass();
        }
        throw new AssertionError("leitura fora do escopo não foi recusada");
    }

    @Test
    void eachFieldOnlyWithItsPermissionAndAnonymizedDebtorLeavesNoTrace() throws Exception {
        provision("baixas-minimo");
        UUID tokenB = Ids.newId();
        PaymentView byToken = asIntegration(tokenB, creditorB, () -> payments.registerExternal(direct(creditorB, titleB,
                "50.00", "CREDITOR_DIRECT", "MIN-1"), pdf(), null));

        Actor bare = user(Set.of(Permission.PAYMENT_VIEW), CreditorScope.ALL);
        ReportedPaymentItem minimal = as(bare, () -> settlements.search(period(), PageQuery.of(0, 50))).items().content()
                .getFirst();
        assertThat(minimal.debtorName()).isNull();
        assertThat(minimal.titleIds()).isEmpty();
        assertThat(minimal.titleCount()).isEqualTo(1);
        assertThat(minimal.token()).isNull();
        assertThat(minimal.evidenceUnverified()).isTrue();

        Actor full = user(Set.of(Permission.PAYMENT_VIEW, Permission.DEBTOR_VIEW, Permission.PUBLIC_API_TOKEN_VIEW),
                CreditorScope.ALL);
        ReportedPaymentItem complete = as(full, () -> settlements.search(period(), PageQuery.of(0, 50))).items().content()
                .getFirst();
        assertThat(complete.debtorName()).isEqualTo("Maria Devedora");
        assertThat(complete.titleIds()).containsExactly(titleB);
        // o token não é de um cadastro do publicapi aqui: "integração não identificada"
        assertThat(complete.token()).isNotNull();
        assertThat(complete.token().id()).isEqualTo(byToken.informedBy());
        assertThat(complete.token().found()).isFalse();

        // titular anonimizado: nenhum pedaço do nome nem da referência informada sai na resposta
        UUID[] other = debtorWithTitle(creditorB, portfolioB, 77, "Joana Original Ficticia", "REF-JOANA", "90.00");
        PaymentView paidOff = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, other[0],
                new BigDecimal("90.00"), paidAt, "PIX", "CREDITOR_DIRECT", "COMPROVANTE-JOANA", "Joana pagou na loja",
                new TargetInput("TITLES", null, List.of(other[1]), null)), pdf(), null));
        assertThat(paidOff.status()).isEqualTo("CONFIRMED");
        system(() -> {
            debtorUseCases.anonymize(other[0], "Solicitação do titular");
            return null;
        });
        awaitTrue("comprovante do titular anonimizado removido", () -> count(
                "select count(*) from payment.payment where id = ? and evidence_key is null", paidOff.id()) == 1);
        String body = mvc().perform(get("/api/v1/payment/creditor-reported")
                        .param("from", today.minusDays(2).toString()).param("to", today.plusDays(1).toString())
                        .with(authentication(ActorAuthentication.of(full))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Joana").doesNotContain("JOANA").doesNotContain("Original");
        ReportedPaymentItem anonymized = as(full, () -> settlements.search(period(), PageQuery.of(0, 50))).items().content()
                .stream().filter(i -> i.paymentId().equals(paidOff.id())).findFirst().orElseThrow();
        assertThat(anonymized.debtorAnonymized()).isTrue();
        assertThat(anonymized.debtorName()).isEqualTo("Titular anonimizado");
        assertThat(anonymized.evidenceAvailable()).isFalse();
        assertThat(anonymized.evidenceUnverified()).isFalse();
    }

    @Test
    void creditorAndIntegrationActorsAreRefusedAndFiltersAreStrict() throws Exception {
        provision("baixas-filtro");
        MockMvc mvc = mvc();
        Actor creditorPerson = new Actor(Actor.ActorType.CREDITOR, Ids.newId(), tenant, Ids.newId(), "CREDOR",
                EnumSet.copyOf(Permission.CREDITOR_ONLY), CreditorScope.of(List.of(creditorA)));
        Actor integration = new Actor(Actor.ActorType.INTEGRATION, Ids.newId(), tenant, null, "integration:x",
                EnumSet.of(Permission.PAYMENT_VIEW), CreditorScope.of(List.of(creditorA)));
        for (String path : List.of("/api/v1/payment/creditor-reported", "/api/v1/titles/creditor-reported")) {
            for (Actor actor : List.of(creditorPerson, integration)) {
                mvc.perform(get(path).param("from", today.toString()).param("to", today.toString())
                        .with(authentication(ActorAuthentication.of(actor)))).andExpect(status().isForbidden());
            }
        }
        assertThatThrownBy(() -> asAdmin(() -> settlements.search(new ReportedPaymentFilter(null, today, null, null,
                null, null, null, null), PageQuery.of(0, 20)))).hasFieldOrPropertyWithValue("code",
                "creditor_reported.period_required");
        assertThatThrownBy(() -> asAdmin(() -> settlements.search(new ReportedPaymentFilter(today.minusDays(367),
                today, null, null, null, null, null, null), PageQuery.of(0, 20)))).hasFieldOrPropertyWithValue("code",
                "creditor_reported.period_invalid");
        assertThatThrownBy(() -> asAdmin(() -> importSettlements.search(new ReportedTitleFilter(today,
                today.minusDays(1), null, null, null), PageQuery.of(0, 20)))).hasFieldOrPropertyWithValue("code",
                "creditor_reported.period_invalid");
        assertThatThrownBy(() -> asAdmin(() -> settlements.search(period(), PageQuery.of(0, 101))))
                .hasFieldOrPropertyWithValue("code", "creditor_reported.page_size_invalid");
        assertThatThrownBy(() -> asAdmin(() -> settlements.search(new ReportedPaymentFilter(today, today, null, null,
                null, new BigDecimal("10"), new BigDecimal("5"), null), PageQuery.of(0, 20))))
                .hasFieldOrPropertyWithValue("code", "creditor_reported.amount_range_invalid");
        assertThatThrownBy(() -> asAdmin(() -> settlements.search(new ReportedPaymentFilter(today, today, null,
                "integration", null, null, null, null), PageQuery.of(0, 20))))
                .hasFieldOrPropertyWithValue("code", "payment.channel_invalid");
        assertThatThrownBy(() -> asAdmin(() -> importSettlements.search(new ReportedTitleFilter(today, today, null,
                null, "PAGO"), PageQuery.of(0, 20)))).hasFieldOrPropertyWithValue("code", "title.situation_invalid");
        mvc.perform(get("/api/v1/payment/creditor-reported").param("from", today.toString())
                        .param("to", today.toString()).param("sort", "creditorId,asc")
                        .with(authentication(ActorAuthentication.of(user(Set.of(Permission.PAYMENT_VIEW),
                                CreditorScope.ALL)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.invalid_sort"));
    }

    /**
     * Decisão do responsável (26/11/2026, RS-100): pagamento da integração com token revogado ou não encontrado só é
     * aprovado com a confirmação explícita, e a auditoria da aprovação grava o estado do token e a confirmação. A
     * situação de revisão do caso diz o canal, sem o token (RS-99).
     */
    @Test
    void inactiveTokenNeedsExplicitConfirmationAndReviewStatusShowsOnlyTheChannel() {
        provision("baixas-token");
        UUID token = Ids.newId();
        PaymentView informed = asIntegration(token, creditorB, () -> payments.registerExternal(direct(creditorB, titleB,
                "600.00", "CREDITOR_DIRECT", "TK-1"), pdf(), null));
        assertThat(informed.status()).isEqualTo("INFORMED");

        Actor agreementOnly = user(Set.of(Permission.AGREEMENT_VIEW), CreditorScope.ALL);
        PaymentReviewStatusUseCases.ReviewItem item = as(agreementOnly, () -> reviewStatus.forDebtor(creditorB, debtor))
                .pending().getFirst();
        assertThat(item.channel()).isEqualTo("INTEGRATION");
        assertThat(item.informedBy()).isNull();

        assertThatThrownBy(() -> asFinance1(() -> payments.approve(informed.id(), "Conferido", false)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "payment.integration_token_inactive");
        assertThat(asFinance1(() -> payments.get(informed.id())).status()).isEqualTo("INFORMED");
        // recusar não exige confirmação; aqui aprovamos com ela
        PaymentView approved = asFinance1(() -> payments.approve(informed.id(), "Conferido com o credor", true));
        assertThat(approved.status()).isEqualTo("CONFIRMED");
        assertThat(count("""
                select count(*) from shared.audit_event
                 where action = 'payment.approved' and entity_id = ?
                   and metadata ->> 'tokenRevokedAtDecision' = 'true' and metadata ->> 'inactiveTokenConfirmed' = 'true'
                   and metadata ->> 'tokenState' = 'NOT_FOUND'""", informed.id())).isEqualTo(1);
    }

    /** RS-98: o lote da carga só pelo servidor, só em SETTLED, preservado no reprocessamento e limpo ao reabrir. */
    @Test
    void importSettlementIsRecordedOnlyBySettlingImportAndClearedWhenTheTitleReopens() {
        provision("baixas-carga");
        PaymentView partial = asOperator(() -> payments.registerExternal(direct(creditorB, titleB, "400.00",
                "AGENCY_ACCOUNT", "CG-1"), pdf(), null));
        assertThat(partial.status()).isEqualTo("CONFIRMED");
        UUID batch = Ids.newId();
        TitleRecord reduced = new TitleRecord(creditorB, portfolioB, debtor, "B-1", null, 1, today.minusDays(20),
                new BigDecimal("400.00"), null, null);
        system(() -> ingestion.upsertTitle(reduced, batch));
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("SETTLED");
        assertThat(count("select count(*) from debt.debt_title where id = ? and settled_by_import_batch_id = ?",
                titleB, batch)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'debt_title.settled_by_import' "
                + "and entity_id = ?", titleB)).isEqualTo(1);
        ReportedTitleItem row = asAdmin(() -> importSettlements.search(importPeriod(), PageQuery.of(0, 50))).items()
                .content().stream().filter(i -> i.titleId().equals(titleB)).findFirst().orElseThrow();
        assertThat(row.importBatchId()).isEqualTo(batch);
        assertThat(row.amount()).isEqualByComparingTo("600.00");
        assertThat(row.titleReference()).isEqualTo("B-1/1");
        // sem IMPORT_VIEW, sem o lote; sem DEBTOR_VIEW/CASE_VIEW, sem a referência
        ReportedTitleItem bare = as(user(Set.of(Permission.PAYMENT_VIEW), CreditorScope.ALL),
                () -> importSettlements.search(importPeriod(), PageQuery.of(0, 50))).items().content().getFirst();
        assertThat(bare.importBatchId()).isNull();
        assertThat(bare.titleReference()).isNull();
        assertThat(bare.debtorName()).isNull();

        // reprocessamento (outro lote) não sobrescreve: o título quitado não muda pela carga
        system(() -> ingestion.upsertTitle(reduced, Ids.newId()));
        assertThat(count("select count(*) from debt.debt_title where id = ? and settled_by_import_batch_id = ?",
                titleB, batch)).isEqualTo(1);

        // o estorno reabre o título: o lote deixa de valer, com auditoria
        asFinance1(() -> payments.reverse(partial.id(), new ReverseCommand("BANK_RETURN", "Devolvido pelo banco")));
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("OPEN");
        assertThat(count("select count(*) from debt.debt_title where id = ? and settled_by_import_batch_id is null "
                + "and settled_by_import_at is null", titleB)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and before_state::text like '%settledByImportBatchId%'", titleB)).isPositive();
        assertThat(asAdmin(() -> importSettlements.search(importPeriod(), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedTitleItem::titleId).doesNotContain(titleB);

        // suspensão estruturada pela carga; a suspensão comum com o mesmo texto não entra (o texto nunca é lido)
        UUID reportBatch = Ids.newId();
        system(() -> debtTitles.suspendReportedByCreditor(title60, "PAID", reportBatch,
                "Credor informou pagamento; aguardando conciliação (carga 1)"));
        system(() -> debtTitles.suspend(title30, "Credor informou cancelamento; aguardando confirmação (carga 0)", null));
        List<ReportedTitleItem> suspended = asAdmin(() -> importSettlements.search(importPeriod(),
                PageQuery.of(0, 50))).items().content();
        assertThat(suspended).filteredOn(i -> i.titleId().equals(title60)).singleElement().satisfies(i -> {
            assertThat(i.situation()).isEqualTo("REPORTED_PAID");
            assertThat(i.importBatchId()).isEqualTo(reportBatch);
        });
        assertThat(suspended).extracting(ReportedTitleItem::titleId).doesNotContain(title30);
        // uma única auditoria da suspensão pela carga, com o lote (RC-05)
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action like 'debt_title.%' "
                + "and (coalesce(after_state::text, '') || coalesce(metadata::text, '')) like ?", title60, "%" + reportBatch + "%")).isEqualTo(1);
        assertThat(asAdmin(() -> importSettlements.search(new ReportedTitleFilter(today.minusDays(2),
                today.plusDays(1), null, reportBatch, null), PageQuery.of(0, 50))).items().content())
                .extracting(ReportedTitleItem::titleId).containsExactly(title60);
        // a reativação limpa o motivo codificado e o lote
        system(() -> debtTitles.reactivate(title60, null));
        assertThat(count("select count(*) from debt.debt_title where id = ? and suspension_reason_code is null "
                + "and suspension_import_batch_id is null", title60)).isEqualTo(1);
        // só o processamento da carga (ator SYSTEM, com a permissão) grava a suspensão estruturada (SEG-03)
        assertThatThrownBy(() -> asAdmin(() -> debtTitles.suspendReportedByCreditor(title60, "PAID", Ids.newId(),
                "Credor informou pagamento; aguardando conciliação (carga 2)")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperator(() -> debtTitles.suspendReportedByCreditor(title60, "PAID", Ids.newId(),
                "Credor informou pagamento; aguardando conciliação (carga 2)")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> system(() -> debtTitles.suspendReportedByCreditor(title60, "ACTIVE", Ids.newId(),
                "x"))).isInstanceOf(BusinessRuleException.class);
    }
}
