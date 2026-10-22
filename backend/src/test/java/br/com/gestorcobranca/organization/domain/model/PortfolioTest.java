package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioTest {

    private Portfolio portfolio() {
        return new Portfolio(UUID.randomUUID(), UUID.randomUUID(), "cart-01", "Carteira", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void followsLifecycleTransitions() {
        Portfolio p = portfolio();
        assertThat(p.getCode()).isEqualTo("CART-01");
        assertThat(p.transitionTo(PortfolioStatus.ACTIVE, null)).isEqualTo(PortfolioStatus.DRAFT);
        p.transitionTo(PortfolioStatus.SUSPENDED, "pedido do credor");
        p.transitionTo(PortfolioStatus.ACTIVE, null);
        p.transitionTo(PortfolioStatus.RETURNED, "fim de contrato");
        assertThatThrownBy(() -> p.transitionTo(PortfolioStatus.ACTIVE, null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void suspensionAndReturnRequireReason() {
        Portfolio p = portfolio();
        p.transitionTo(PortfolioStatus.ACTIVE, null);
        assertThatThrownBy(() -> p.transitionTo(PortfolioStatus.SUSPENDED, " ")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void operableOnlyWhenActiveAndWithinValidity() {
        Portfolio p = portfolio();
        assertThat(p.isOperableOn(LocalDate.of(2026, 10, 15))).isFalse();
        p.transitionTo(PortfolioStatus.ACTIVE, null);
        assertThat(p.isOperableOn(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(p.isOperableOn(LocalDate.of(2027, 1, 1))).isFalse();
    }
}
