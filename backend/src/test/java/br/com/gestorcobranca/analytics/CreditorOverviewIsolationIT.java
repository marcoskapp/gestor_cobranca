package br.com.gestorcobranca.analytics;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.application.port.in.creditor.CreditorIndicatorsQueries;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.Overview;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases.PortfolioShare;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.support.JsonContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Indicadores do Início do portal do credor (fatia 7): cada credor vê só os próprios números — dois credores na mesma
 * assessoria e um em outra, com valores distintos (111,11 / 222,22 / 333,33) —, "Outros" sem carteira de outro credor,
 * parte paga direto ao credor, acordos em dia (quebra suspensa conta como em dia), saldo em cobrança, recusa da
 * assessoria e o contrato da resposta por snapshot.
 */
class CreditorOverviewIsolationIT extends AnalyticsTestSupport {

    @Autowired
    AnalyticsRefreshUseCases refresh;
    @Autowired
    CreditorIndicatorsQueries indicators;
    @Autowired
    CreditorHomeUseCases home;
    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;

    @Test
    void cadaCredorVeSoOsPropriosNumerosNaMesmaAssessoriaENaOutra() throws Exception {
        LocalDate today = today();
        LocalDate previousMonthSameDay = today.minusMonths(1);

        // ---- outra assessoria: credor C com 333,33
        provisionTenant("ovc");
        UUID tenantC = tenant;
        UUID managerC = manager;
        UUID[] c = new UUID[2];
        run(() -> {
            Facts f = new Facts();
            c[0] = f.creditor("Credor Outra Assessoria", "C");
            c[1] = f.portfolio(c[0], "PC");
            receipt(c[0], c[1], "333.33", today, "AGENCY");
        });
        as(managerC, DefaultRoles.GESTOR, () -> refresh.refreshNow());
        UUID creditorC = c[0];

        // ---- assessoria do teste: credores A (seis carteiras) e B
        provisionTenant("ovw");
        UUID[] ids = new UUID[9];
        run(() -> {
            Facts f = new Facts();
            UUID a = f.creditor("Banco Alfa", "A");
            UUID b = f.creditor("Loja Beta", "B");
            ids[0] = a;
            ids[1] = b;
            String[] values = {"50.00", "20.00", "15.00", "10.00", "9.00", "7.11"};
            UUID[] portfolios = new UUID[6];
            for (int i = 0; i < 6; i++) {
                portfolios[i] = f.portfolio(a, "PA" + (i + 1));
                // a sexta carteira foi paga direto ao credor (confirmada pela assessoria)
                receipt(a, portfolios[i], values[i], today, i == 5 ? "CREDITOR" : "AGENCY");
            }
            ids[2] = portfolios[5];
            UUID pb = f.portfolio(b, "PB1");
            ids[3] = pb;
            receipt(b, pb, "222.22", today, "AGENCY");
            // mês anterior, no mesmo trecho: 40,00 (variação)
            receipt(a, portfolios[0], "40.00", previousMonthSameDay, "AGENCY");

            // saldo em cobrança: A 1.000 (+ 250 suspenso), B 2.000; título quitado fica fora
            UUID d1 = f.debtor();
            UUID d2 = f.debtor();
            f.title(a, portfolios[0], d1, today.minusDays(40), "OPEN", "1000.00");
            f.title(a, portfolios[1], d1, today.minusDays(10), "SUSPENDED", "250.00");
            f.title(a, portfolios[1], d1, today.minusDays(10), "SETTLED", "0");
            f.title(b, pb, d2, today.minusDays(90), "OPEN", "2000.00");

            // acordos de A: atrasado (fora), em dia (dentro), atrasado com a quebra suspensa (dentro), quitado (não é ativo)
            UUID caseA = f.collectionCase(d1, a, portfolios[0], "IN_NEGOTIATION", true, "1000.00", "1000.00", "USER",
                    null, operatorX, today.minusDays(40));
            Instant formalized = Instant.now().minusSeconds(86_400 * 60L);
            UUID late = f.agreement(a, d1, portfolios[0], caseA, "HUMAN", "USER", operatorX, "PHONE", formalized,
                    "300.00", 1, "IN_PAYMENT", null, null);
            f.installment(late, 1, today.minusDays(10), "300.00", "OPEN", null);
            UUID onTrack = f.agreement(a, d1, portfolios[0], caseA, "HUMAN", "USER", operatorX, "PHONE", formalized,
                    "600.00", 2, "FORMALIZED", null, null);
            f.installment(onTrack, 1, today.minusDays(20), "300.00", "PAID", Instant.now().minusSeconds(86_400 * 20L));
            f.installment(onTrack, 2, today.plusDays(10), "300.00", "OPEN", null);
            UUID suspended = f.agreement(a, d1, portfolios[0], caseA, "HUMAN", "USER", operatorX, "PHONE", formalized,
                    "200.00", 1, "IN_PAYMENT", null, null);
            f.installment(suspended, 1, today.minusDays(3), "200.00", "PARTIALLY_PAID", null);
            breakSuspension(suspended, a, today.minusDays(1));
            UUID settled = f.agreement(a, d1, portfolios[0], caseA, "HUMAN", "USER", operatorX, "PHONE", formalized,
                    "100.00", 1, "SETTLED", Instant.now().minusSeconds(86_400 * 5L), null);
            f.installment(settled, 1, today.minusDays(30), "100.00", "PAID", Instant.now().minusSeconds(86_400 * 30L));
            // B: um acordo em dia
            UUID caseB = f.collectionCase(d2, b, pb, "IN_NEGOTIATION", true, "2000.00", "2000.00", "USER", null,
                    operatorY, today.minusDays(90));
            UUID agB = f.agreement(b, d2, pb, caseB, "HUMAN", "USER", operatorY, "PHONE", formalized, "500.00", 1,
                    "IN_PAYMENT", null, null);
            f.installment(agB, 1, today.plusDays(5), "500.00", "OPEN", null);
        });
        as(manager, DefaultRoles.GESTOR, () -> refresh.refreshNow());
        UUID creditorA = ids[0];
        UUID creditorB = ids[1];

        // ---- credor A
        Overview a = asCreditor(tenant, creditorA, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> home.overview("THIS_MONTH"));
        assertThat(a.unavailable()).doesNotContain("INDICATORS");
        assertThat(a.recovery().recovered()).isEqualByComparingTo("111.11");
        assertThat(a.recovery().paidToCreditor()).isEqualByComparingTo("7.11");
        assertThat(a.recovery().previousRecovered()).isEqualByComparingTo("40.00");
        assertThat(a.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(a.to()).isEqualTo(today);
        // 5 carteiras + "Outros" (7,11 da sexta carteira de A — nada de B)
        assertThat(a.byPortfolio()).hasSize(6);
        assertThat(a.byPortfolio().subList(0, 5)).extracting(PortfolioShare::recovered).extracting(BigDecimal::toPlainString)
                .containsExactly("50.00", "20.00", "15.00", "10.00", "9.00");
        assertThat(a.byPortfolio().subList(0, 5)).allSatisfy(p -> assertThat(p.name()).startsWith("PA"));
        PortfolioShare others = a.byPortfolio().getLast();
        assertThat(others.others()).isTrue();
        assertThat(others.portfolioId()).isNull();
        assertThat(others.recovered()).isEqualByComparingTo("7.11");
        assertThat(a.byPortfolio()).noneMatch(p -> ids[3].equals(p.portfolioId()));
        // série fixa de 6 meses, do mais antigo ao corrente
        assertThat(a.monthly()).hasSize(6);
        assertThat(a.monthly().getLast().month()).isEqualTo(YearMonth.from(today).toString());
        assertThat(a.monthly().getLast().recovered()).isEqualByComparingTo("111.11");
        assertThat(a.monthly().getLast().paidToCreditor()).isEqualByComparingTo("7.11");
        assertThat(a.monthly().get(4).recovered()).isEqualByComparingTo("40.00");
        assertThat(a.monthly().getFirst().recovered()).isEqualByComparingTo("0.00");
        // saldo em cobrança: títulos em aberto e suspensos com saldo
        assertThat(a.balance().amount()).isEqualByComparingTo("1250.00");
        assertThat(a.balance().titles()).isEqualTo(2);
        assertThat(a.balance().referenceDate()).isEqualTo(today);
        // acordos ativos: 3 (o quitado fica fora); em dia: o sem atraso e o com a quebra suspensa → 2/3, em alerta
        assertThat(a.agreements().active()).isEqualTo(3);
        assertThat(a.agreements().onTrack()).isEqualTo(2);
        assertThat(a.agreements().ratio()).isEqualByComparingTo("0.6667");
        assertThat(a.agreements().alert()).isTrue();
        assertThat(a.dataUntil()).isNotNull();
        assertThat(a.generatedAt()).isNotNull();

        // ---- credor B na mesma assessoria: só os dele
        Overview b = asCreditor(tenant, creditorB, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> home.overview("THIS_MONTH"));
        assertThat(b.recovery().recovered()).isEqualByComparingTo("222.22");
        assertThat(b.recovery().paidToCreditor()).isEqualByComparingTo("0.00");
        assertThat(b.byPortfolio()).singleElement().satisfies(p -> {
            assertThat(p.portfolioId()).isEqualTo(ids[3]);
            assertThat(p.recovered()).isEqualByComparingTo("222.22");
        });
        assertThat(b.balance().amount()).isEqualByComparingTo("2000.00");
        assertThat(b.agreements().onTrack()).isEqualTo(1);
        assertThat(b.agreements().active()).isEqualTo(1);
        assertThat(b.agreements().alert()).isFalse();

        // ---- credor da outra assessoria
        Overview other = asCreditor(tenantC, creditorC, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> home.overview("THIS_MONTH"));
        assertThat(other.recovery().recovered()).isEqualByComparingTo("333.33");
        assertThat(other.byPortfolio()).singleElement().satisfies(p -> assertThat(p.portfolioId()).isEqualTo(c[1]));
        // credor A procurado na assessoria errada (ator forjado): nada
        Overview forged = asCreditor(tenantC, creditorA, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> home.overview("THIS_MONTH"));
        assertThat(forged.recovery().recovered()).isEqualByComparingTo("0.00");
        assertThat(forged.byPortfolio()).isEmpty();

        // cache curto, depois da permissão: a segunda leitura vem do cache; sem permissão, recusa (nunca do cache)
        UUID person = Ids.newId();
        assertThat(asCreditor(tenant, creditorA, person, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW),
                () -> indicators.indicators("THIS_MONTH")).cached()).isTrue();
        assertThatThrownBy(() -> asCreditor(tenant, creditorA, Set.of(Permission.CREDITOR_PAYMENT_REPORT),
                () -> home.overview("THIS_MONTH"))).isInstanceOf(ScopeAccessDeniedException.class);
        // a porta do credor não atende a assessoria (nem com todas as permissões dela)
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> indicators.indicators("THIS_MONTH")))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // ---- HTTP: no-store, período fora da lista → 400, contrato por snapshot e nada do credor B
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ActorAuthentication auth = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), creditorA,
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW)));
        MvcResult result = mvc.perform(get("/api/creditor/v1/overview").param("period", "THIS_MONTH")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("222.22").doesNotContain(ids[3].toString()).doesNotContain(creditorB.toString())
                .doesNotContain(creditorA.toString());
        JsonNode node = json.readTree(body);
        JsonContract.assertMatches(node, "creditor-portal/overview.txt");
        mvc.perform(get("/api/creditor/v1/overview").param("period", "LAST_10_YEARS").with(authentication(auth)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/creditor/v1/overview").param("creditorId", creditorB.toString())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("222.22"));
    }

    // ------------------------------------------------------------------ apoio

    private void receipt(UUID creditorId, UUID portfolioId, String amount, LocalDate on, String receivedBy) {
        Instant now = Instant.now();
        jdbc.update("""
                insert into settlement.receipt (id, tenant_id, version, created_at, updated_at, kind, allocation_id,
                    payment_id, creditor_id, portfolio_id, agreement_id, amount, honorarium_amount, days_past_due,
                    payment_type, received_by, renegotiation, paid_on, competence_date, detail, rating_status)
                values (?, ?, 0, ?, ?, 'PAYMENT', ?, ?, ?, ?, null, ?, 0, 10, 'INSTALLMENTS', ?, false, ?, ?,
                        '{}'::jsonb, 'PENDING_RULES')""",
                Ids.newId(), tenant, ts(now), ts(now), Ids.newId(), Ids.newId(), creditorId, portfolioId,
                new BigDecimal(amount), receivedBy, on, on);
    }

    /** Suspensão da quebra em vigor (pagamento informado em análise), na parcela 1 do acordo. */
    private void breakSuspension(UUID agreementId, UUID creditorId, LocalDate startedOn) {
        UUID installment = jdbc.queryForObject(
                "select id from negotiation.agreement_installment where agreement_id = ? and number = 1", UUID.class,
                agreementId);
        Instant now = Instant.now();
        jdbc.update("""
                insert into negotiation.agreement_break_suspension (id, tenant_id, version, created_at, updated_at,
                    agreement_id, creditor_id, trigger_installment_id, status, started_on, started_at, suspended_until,
                    pending_payments, pending_amount)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1, 200.00)""",
                Ids.newId(), tenant, ts(now), ts(now), agreementId, creditorId, installment, startedOn, ts(now),
                startedOn.plusDays(10));
    }

    private <T> T asCreditor(UUID tenantId, UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        return asCreditor(tenantId, creditorId, Ids.newId(), permissions, action);
    }

    private <T> T asCreditor(UUID tenantId, UUID creditorId, UUID person, Set<Permission> permissions,
                             Supplier<T> action) {
        return tenantExecution.callAs(Actor.creditor(tenantId, person, Ids.newId(), creditorId, permissions), action);
    }
}
