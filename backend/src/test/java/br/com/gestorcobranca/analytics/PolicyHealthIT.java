package br.com.gestorcobranca.analytics;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries.PolicyHealthFilter;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries.PolicyHealthView;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries.SegmentHealth;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saúde da política (decisão 1087) contra PostgreSQL real: safra de acordos por carteira × faixa de atraso, quebra
 * precoce dos maduros, uso do desconto, consultas do portal sem oferta e pedidos "nenhuma condição cabe"; devedor com
 * marcador de vulnerabilidade ativo fora; permissão e escopo por credor.
 */
class PolicyHealthIT extends AnalyticsTestSupport {

    @Autowired
    PolicyHealthQueries health;
    @Autowired
    AnalyticsRefreshUseCases refresh;

    @Test
    void segmentsSuggestionsVulnerabilityAndScope() {
        provisionTenant("ph");
        UUID[] ids = new UUID[3];
        run(() -> {
            Facts f = new Facts();
            UUID creditorA = f.creditor("Banco Alfa", "A");
            UUID creditorB = f.creditor("Loja Beta", "B");
            UUID portfolioA = f.portfolio(creditorA, "PA");
            UUID queue = f.queue("Q1");
            Instant formalized = Instant.now().minus(100, ChronoUnit.DAYS);
            // 12 acordos maduros com mais de 1 ano de atraso, 95% do desconto máximo; 6 quebraram em 30 dias
            for (int i = 0; i < 12; i++) {
                UUID d = f.debtor();
                UUID kase = f.collectionCase(d, creditorA, portfolioA, "OPEN", true, "1000.00", "1000.00", "QUEUE", queue,
                        null, today().minusDays(400));
                UUID ag = f.agreement(creditorA, d, portfolioA, kase, "HUMAN", "USER", operatorX, "PHONE", formalized,
                        "1000.00", 3, i < 6 ? "BROKEN" : "IN_PAYMENT", i < 6 ? formalized.plus(30, ChronoUnit.DAYS) : null,
                        null);
                jdbc.update("update negotiation.agreement set envelope_usage = 95, max_days_overdue = 400 where id = ?", ag);
            }
            // devedor vulnerável: fica fora da análise
            UUID vulnerable = f.debtor();
            UUID kase = f.collectionCase(vulnerable, creditorA, portfolioA, "OPEN", true, "1000.00", "1000.00", "QUEUE",
                    queue, null, today().minusDays(400));
            UUID excluded = f.agreement(creditorA, vulnerable, portfolioA, kase, "HUMAN", "USER", operatorX, "PHONE",
                    formalized, "1000.00", 3, "IN_PAYMENT", null, null);
            jdbc.update("update negotiation.agreement set envelope_usage = 10, max_days_overdue = 400 where id = ?", excluded);
            Instant now = Instant.now();
            jdbc.update("""
                    insert into compliance.vulnerability_marker (id, tenant_id, version, created_at, updated_at, debtor_id,
                        category, legal_basis, source, status, registered_at)
                    values (?, ?, 0, ?, ?, ?, 'SUPER_INDEBTED', 'LEGAL_OBLIGATION', 'DEBTOR_DECLARATION', 'ACTIVE', ?)""",
                    Ids.newId(), tenant, ts(now), ts(now), vulnerable, ts(now));
            // portal: 25 consultas com mais de 1 ano de atraso, 20 sem oferta
            for (int i = 0; i < 25; i++) {
                jdbc.update("""
                        insert into shared.business_event (id, tenant_id, occurred_at, event_type, actor_type, actor_id,
                            creditor_id, portfolio_id, attributes)
                        values (?, ?, ?, 'portal.simulated', 'DEBTOR', ?, ?, ?,
                                jsonb_build_object('offers', ?, 'daysOverdue', 400))""",
                        Ids.newId(), tenant, ts(now.minus(5, ChronoUnit.DAYS)), Ids.newId(), creditorA, portfolioA,
                        i < 20 ? 0 : 3);
            }
            // consulta do portal do devedor vulnerável: também fica fora
            jdbc.update("""
                    insert into shared.business_event (id, tenant_id, occurred_at, event_type, actor_type, actor_id,
                        creditor_id, portfolio_id, debtor_id, attributes)
                    values (?, ?, ?, 'portal.simulated', 'DEBTOR', ?, ?, ?, ?, jsonb_build_object('offers', 0, 'daysOverdue', 400))""",
                    Ids.newId(), tenant, ts(now.minus(5, ChronoUnit.DAYS)), Ids.newId(), creditorA, portfolioA, vulnerable);
            ids[0] = creditorA;
            ids[1] = creditorB;
            ids[2] = portfolioA;
        });
        as(manager, DefaultRoles.GESTOR, () -> refresh.refreshNow());
        UUID creditorA = ids[0];
        UUID creditorB = ids[1];
        UUID portfolioA = ids[2];

        PolicyHealthView view = as(manager, DefaultRoles.GESTOR, () -> health.health(new PolicyHealthFilter(creditorA,
                LocalDate.now().minusDays(180), null, null)));
        SegmentHealth longDelay = view.segments().stream()
                .filter(s -> portfolioA.equals(s.portfolioId()) && "D361_PLUS".equals(s.delayBucket())).findFirst().orElseThrow();
        // o acordo do devedor vulnerável não entra
        assertThat(longDelay.agreements()).isEqualTo(12);
        assertThat(longDelay.matureAgreements()).isEqualTo(12);
        assertThat(longDelay.earlyBreakRate()).isEqualByComparingTo("0.5");
        assertThat(longDelay.averageEnvelopeUsage()).isEqualByComparingTo("95");
        assertThat(longDelay.portalSimulations()).isEqualTo(25);
        assertThat(longDelay.portalWithoutOfferShare()).isEqualByComparingTo("0.8");
        assertThat(view.suggestions()).extracting(PolicyHealthQueries.SuggestionView::kind)
                .contains("HIGH_EARLY_BREAK", "PORTAL_WITHOUT_OFFER");

        // perfil só com REPORT_VIEW (financeiro) analisa; o resultado é o mesmo
        assertThat(as(manager, DefaultRoles.FINANCEIRO, () -> health.health(new PolicyHealthFilter(creditorA, null, null,
                null))).total().portalSimulations()).isEqualTo(25);
        // valor fora da faixa: nenhum acordo
        assertThat(as(manager, DefaultRoles.GESTOR, () -> health.health(new PolicyHealthFilter(creditorA, null, null,
                "V20K_PLUS"))).total().agreements()).isZero();
        // operador (só consulta a política) não vê a análise; gestor sem o credor no escopo também não
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> health.health(new PolicyHealthFilter(creditorA,
                null, null, null)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asScoped(manager, DefaultRoles.GESTOR, Set.of(creditorB), () -> health.health(
                new PolicyHealthFilter(creditorA, null, null, null)))).isInstanceOf(ScopeAccessDeniedException.class);
    }
}
