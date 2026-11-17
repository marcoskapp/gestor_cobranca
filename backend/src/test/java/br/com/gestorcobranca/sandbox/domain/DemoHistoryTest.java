package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.identity.domain.service.PasswordPolicy;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Economics;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryCreditor;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistorySpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Trend;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DemoHistoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static HistorySpec aurora() {
        return DemoHistory.of("aurora").orElseThrow();
    }

    @Test
    void onlyAuroraHasHistory() {
        assertThat(DemoHistory.of("aurora")).isPresent();
        assertThat(DemoHistory.of("boreal")).isEmpty();
    }

    @Test
    void aboutThirtyCreditorsAndEightyPortfoliosWithTheMainCatalogIntact() {
        TenantSpec tenant = DemoCatalog.tenants().getFirst();
        HistorySpec history = aurora();
        assertThat(tenant.creditors()).hasSize(3);
        assertThat(tenant.creditors().size() + history.creditors().size()).isEqualTo(30);
        int portfolios = tenant.creditors().stream().mapToInt(c -> c.portfolios().size()).sum() + history.portfolioCount();
        assertThat(portfolios).isEqualTo(80);
        Set<String> prefixes = new HashSet<>();
        tenant.creditors().forEach(c -> prefixes.add(c.prefix()));
        history.creditors().forEach(c -> assertThat(prefixes.add(c.prefix())).as(c.prefix()).isTrue());
        history.creditors().forEach(c -> assertThat(c.legalName()).containsAnyOf("(fictício)", "(fictícia)",
                "(fictícias)"));
    }

    @Test
    void creditorSizesAreUnevenWithALongTailAndEveryProfile() {
        List<Integer> debtors = aurora().creditors().stream()
                .map(c -> c.portfolios().stream().mapToInt(p -> p.spec().debtors()).sum())
                .sorted((a, b) -> b - a).toList();
        int total = debtors.stream().mapToInt(Integer::intValue).sum();
        // três grandes concentram quase metade dos devedores; a cauda tem credores com menos de 10 devedores
        assertThat(debtors.get(0) + debtors.get(1) + debtors.get(2)).isGreaterThan(total * 2 / 5);
        assertThat(debtors.stream().filter(d -> d < 10).count()).isGreaterThanOrEqualTo(10);
        assertThat(aurora().debtorCount()).isBetween(450, 700);
        Set<Economics> economics = aurora().creditors().stream().map(HistoryCreditor::economics)
                .collect(Collectors.toSet());
        assertThat(economics).containsExactlyInAnyOrder(Economics.values());
        Set<Trend> trends = aurora().creditors().stream().map(HistoryCreditor::trend).collect(Collectors.toSet());
        assertThat(trends).containsExactlyInAnyOrder(Trend.values());
        // credores que começam depois (curva da tendência "subindo" a partir do zero)
        assertThat(aurora().creditors().stream().filter(c -> c.startMonth() > 0)).isNotEmpty();
    }

    @Test
    void twentyNegotiatorsInFourTeamsWithEveryCostBand() {
        HistorySpec history = aurora();
        assertThat(history.negotiators()).hasSize(18);
        assertThat(history.teams()).hasSize(4);
        history.teams().forEach(t -> assertThat(history.team(t.code())).as(t.code()).hasSizeGreaterThanOrEqualTo(4));
        assertThat(history.negotiators().stream().map(DemoHistory.Negotiator::band).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("Negociador trainee", "Negociador júnior", "Negociador pleno",
                        "Negociador sênior");
        assertThat(history.negotiators().stream().filter(DemoHistory.Negotiator::caller)).isNotEmpty();
    }

    @Test
    void historyPeopleHaveUniqueEmailsAndTheDemoPasswordIsValid() {
        TenantSpec tenant = DemoCatalog.tenants().getFirst();
        List<Person> people = new java.util.ArrayList<>(tenant.people());
        people.addAll(aurora().people());
        assertThat(people.stream().map(p -> p.email(tenant)).distinct()).hasSize(people.size());
        PasswordPolicy policy = new PasswordPolicy(12);
        for (Person person : aurora().people()) {
            assertThatCode(() -> policy.validate("Sandbox@2026!", person.email(tenant), person.name()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void historyDebtsAreDeterministicRelativeToTheirArrivalAndDoNotChangeTheMainCatalog() {
        TenantSpec tenant = DemoCatalog.tenants().getFirst();
        DemoPortfolioData plain = DemoPortfolioData.generate(tenant, TODAY);
        DemoPortfolioData first = DemoPortfolioData.generate(tenant, TODAY);
        first.addHistory(aurora(), 7, p -> TODAY.minusMonths(12 - p.receivedMonth()));
        DemoPortfolioData second = DemoPortfolioData.generate(tenant, TODAY);
        second.addHistory(aurora(), 7, p -> TODAY.minusMonths(12 - p.receivedMonth()));
        assertThat(first.byPortfolio()).isEqualTo(plain.byPortfolio());
        assertThat(first.historyByPortfolio()).isEqualTo(second.historyByPortfolio());
        assertThat(first.historyByPortfolio()).hasSize(aurora().portfolioCount());
        Set<String> documents = new HashSet<>();
        first.byPortfolio().values().forEach(l -> l.forEach(d -> documents.add(d.debtor().document())));
        first.historyByPortfolio().values().forEach(l -> l.forEach(d ->
                assertThat(documents.add(d.debtor().document())).isTrue()));
        // a carteira recebida há 12 meses chegou com títulos vencidos naquela época
        first.historyByPortfolio().get("MRD-CARTAO").forEach(d -> assertThat(d.contract().titles().getFirst().dueDate())
                .isBefore(TODAY.minusMonths(12)));
        // carteira de empresas gera devedores pessoa jurídica (base da atenção especial por recuperação judicial)
        assertThat(first.historyByPortfolio().get("GSN-INDUSTRIAL")).allMatch(d -> d.debtor().company());
    }
}
