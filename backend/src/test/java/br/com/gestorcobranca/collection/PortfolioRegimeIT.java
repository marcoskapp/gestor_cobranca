package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases.RegisterMarkerCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regime da carteira e vulnerabilidade por tipo de pessoa (cobrança B2B §6 e §8): o regime é auditado e só muda com
 * motivo; superendividamento é recusado quando todas as carteiras do devedor são empresariais (os outros marcadores de
 * pessoa física continuam); empresa recebe só "outra situação".
 */
class PortfolioRegimeIT extends CollectionTestSupport {

    @Autowired
    VulnerabilityUseCases vulnerability;

    UUID business;

    @BeforeEach
    void setUp() {
        provision("regime");
        business = draftPortfolio(creditorA, "EMP-01");
        system(() -> portfolios.changeStatus(business, "ACTIVE", "Início da operação", null));
    }

    private RegisterMarkerCommand marker(UUID debtorId, String category) {
        return new RegisterMarkerCommand(debtorId, category, "LEGAL_OBLIGATION", "DEBTOR_DECLARATION",
                "SUPER_INDEBTED".equals(category) ? new BigDecimal("150.00") : null);
    }

    @Test
    void regimeChangeIsAuditedAndNeedsAReason() {
        assertThat(system(() -> portfolios.get(business)).regime()).isEqualTo("CONSUMER");
        assertThatThrownBy(() -> asManager(() -> portfolios.changeRegime(business, "BUSINESS", " ", null)))
                .isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("portfolio.regime_reason_required");
        // operador restrito ao credor B não enxerga a carteira do credor A
        assertThatThrownBy(() -> as(operatorZ, br.com.gestorcobranca.identity.domain.model.DefaultRoles.GESTOR,
                br.com.gestorcobranca.shared.security.CreditorScope.of(java.util.Set.of(creditorB)),
                () -> portfolios.changeRegime(business, "BUSINESS", "Contrato entre empresas", null)))
                .isInstanceOf(ResourceNotFoundException.class);

        PortfolioView changed = asManager(() -> portfolios.changeRegime(business, "BUSINESS", "Contrato entre empresas",
                null));
        assertThat(changed.regime()).isEqualTo("BUSINESS");
        assertThatThrownBy(() -> asManager(() -> portfolios.changeRegime(business, "BUSINESS", "de novo", null)))
                .isInstanceOf(ConflictException.class);
        assertThat(count("select count(*) from shared.audit_event where action = 'portfolio.regime_changed' "
                + "and entity_id = ?", business)).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'portfolio.regime_changed' "
                + "and portfolio_id = ?", business)).isEqualTo(1);
        // proteção nunca retirada por inferência: carteira desconhecida ou lista vazia contam como consumo
        assertThat(system(() -> portfolios.consumerProtectionApplies(List.of()))).isTrue();
        assertThat(system(() -> portfolios.consumerProtectionApplies(List.of(business)))).isFalse();
        assertThat(system(() -> portfolios.consumerProtectionApplies(List.of(business, UUID.randomUUID())))).isTrue();
        assertThat(system(() -> portfolios.consumerProtectionApplies(List.of(business, portfolioA)))).isTrue();
    }

    @Test
    void superIndebtednessNeedsAConsumerPortfolioAndOtherPersonMarkersStay() {
        system(() -> portfolios.changeRegime(business, "BUSINESS", "Contrato entre empresas", null));
        UUID person = debtor("52998224725");
        load(creditorA, business, person, "800.00");

        assertThat(asOperator(operatorX, () -> vulnerability.ofDebtor(person)).availableCategories())
                .doesNotContain("SUPER_INDEBTED").contains("ELDERLY", "HEALTH", "LOW_LITERACY", "OTHER");
        assertThatThrownBy(() -> asOperator(operatorX, () -> vulnerability.register(marker(person, "SUPER_INDEBTED"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("vulnerability.not_applicable_to_business_portfolio");
        // o regime não retira os demais marcadores de pessoa física
        assertThat(asOperator(operatorX, () -> vulnerability.register(marker(person, "ELDERLY"))).status())
                .isEqualTo("ACTIVE");

        // com uma carteira de consumo (outro credor), o superendividamento volta a valer
        load(creditorB, portfolioB, person, "200.00");
        assertThat(asOperator(operatorX, () -> vulnerability.ofDebtor(person)).availableCategories())
                .contains("SUPER_INDEBTED");
        assertThat(asOperator(operatorX, () -> vulnerability.register(marker(person, "SUPER_INDEBTED"))).status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void companyReceivesOnlyTheOtherCategory() {
        UUID company = system(() -> ingestion.upsertDebtor(new DebtorRecord("12.345.678/0001-95", "Empresa Fictícia Ltda",
                null, List.of(new ContactRecord("PHONE", "(11) 91234-5678", "MOBILE", true)))).id());
        load(creditorA, portfolioA, company, "900.00");
        assertThat(asOperator(operatorX, () -> vulnerability.ofDebtor(company)).availableCategories())
                .containsExactly("OTHER");
        for (String category : List.of("SUPER_INDEBTED", "ELDERLY", "HEALTH", "LOW_LITERACY")) {
            assertThatThrownBy(() -> asOperator(operatorX, () -> vulnerability.register(marker(company, category))))
                    .isInstanceOf(BusinessRuleException.class).extracting("code")
                    .isEqualTo("vulnerability.not_applicable_to_company");
        }
        assertThat(asOperator(operatorX, () -> vulnerability.register(marker(company, "OTHER"))).status())
                .isEqualTo("ACTIVE");
    }
}
