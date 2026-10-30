package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.service.PasswordPolicy;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DemoCatalogTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Test
    void twoTenantsWithThreeCreditorsAndFourPortfoliosEach() {
        assertThat(DemoCatalog.tenants()).hasSize(2);
        DemoCatalog.tenants().forEach(t -> {
            assertThat(t.creditors()).hasSize(3);
            t.creditors().forEach(c -> assertThat(c.portfolios()).hasSize(4));
        });
    }

    @Test
    void everyDefaultRoleHasADemoUserInEveryTenant() {
        Set<String> roles = DefaultRoles.forTenant().stream().map(DefaultRoles.Definition::code).collect(Collectors.toSet());
        for (TenantSpec tenant : DemoCatalog.tenants()) {
            assertThat(tenant.people().stream().map(Person::roleCode).collect(Collectors.toSet())).containsAll(roles);
            assertThat(tenant.people().stream().map(p -> p.email(tenant)).distinct()).hasSize(tenant.people().size());
        }
    }

    @Test
    void demoPasswordSatisfiesThePasswordPolicyForEveryUser() {
        PasswordPolicy policy = new PasswordPolicy(12);
        for (TenantSpec tenant : DemoCatalog.tenants()) {
            for (Person person : tenant.people()) {
                assertThatCode(() -> policy.validate("Sandbox@2026!", person.email(tenant), person.name()))
                        .doesNotThrowAnyException();
            }
        }
        assertThatCode(() -> policy.validate("Sandbox@2026!", "admin@plataforma.exemplo.test",
                "Administrador da Plataforma (sandbox)")).doesNotThrowAnyException();
    }

    @Test
    void generatedDataIsDeterministicRelativeToTodayAndShareDebtorsAcrossCreditors() {
        TenantSpec tenant = DemoCatalog.tenants().getFirst();
        DemoPortfolioData first = DemoPortfolioData.generate(tenant, TODAY);
        DemoPortfolioData later = DemoPortfolioData.generate(tenant, TODAY.plusDays(10));
        assertThat(first.byPortfolio()).hasSize(12);
        first.byPortfolio().forEach((code, debts) -> {
            List<Debt> other = later.debts(code);
            assertThat(other).hasSameSizeAs(debts);
            for (int i = 0; i < debts.size(); i++) {
                assertThat(other.get(i).debtor().document()).isEqualTo(debts.get(i).debtor().document());
                assertThat(other.get(i).contract().titles().getFirst().dueDate())
                        .isEqualTo(debts.get(i).contract().titles().getFirst().dueDate().plusDays(10));
            }
            // todo título em carteira tem o primeiro vencimento no passado (caso de cobrança aberto)
            debts.forEach(d -> assertThat(d.contract().titles().getFirst().dueDate()).isBefore(TODAY));
        });
        Map<String, Set<String>> creditorsByDocument = new java.util.HashMap<>();
        first.byPortfolio().forEach((code, debts) -> debts.forEach(d -> creditorsByDocument
                .computeIfAbsent(d.debtor().document(), k -> new HashSet<>()).add(code.substring(0, 3))));
        assertThat(creditorsByDocument.values().stream().filter(s -> s.size() > 1).count())
                .isEqualTo(DemoPortfolioData.SHARED_DEBTORS);
    }

    @Test
    void scenarioDebtorsHaveTwoTitlesAboveThePaymentReviewThreshold() {
        for (TenantSpec tenant : DemoCatalog.tenants()) {
            DemoPortfolioData data = DemoPortfolioData.generate(tenant, TODAY);
            tenant.creditors().forEach(c -> {
                List<Debt> debts = data.debts(c.portfolios().getFirst().code(c));
                for (int i = 0; i < DemoPortfolioData.SCENARIO_DEBTORS; i++) {
                    assertThat(debts.get(i).contract().titles()).hasSize(2)
                            .allSatisfy(t -> assertThat(t.principal()).isBetween(new java.math.BigDecimal("900"),
                                    new java.math.BigDecimal("2400.99")));
                }
            });
        }
    }
}
