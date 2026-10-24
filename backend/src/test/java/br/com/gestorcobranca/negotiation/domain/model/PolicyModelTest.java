package br.com.gestorcobranca.negotiation.domain.model;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import br.com.gestorcobranca.negotiation.domain.model.authority.StandardAuthorityProfiles;
import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyOverride;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Installments;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ScheduleFrequency;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyStatus;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.negotiation.domain.service.DecimalMath;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private final PolicyRules base = StandardPolicyTerms.marketDefault().base();

    private static PolicyOverride override(String code, UUID portfolio, Integer min, Integer max, int maxInstallments) {
        return new PolicyOverride(code, portfolio, min, max, null, null,
                new Installments(maxInstallments, new BigDecimal("50"), new BigDecimal("10"), null, null,
                        ScheduleFrequency.MONTHLY, null), null, null, null);
    }

    @Test
    void marketTemplateIsValid() {
        PolicyTerms terms = StandardPolicyTerms.marketDefault();
        assertThat(terms.base().topLevel()).isEqualTo(3);
        assertThat(terms.base().maxDiscountFor(1).principal()).isEqualByComparingTo("20");
        assertThat(terms.base().maxDiscountFor(7).interest()).isEqualByComparingTo("30");
    }

    @Test
    void mostSpecificOverrideWins() {
        UUID portfolio = UUID.randomUUID();
        PolicyTerms terms = new PolicyTerms(base, List.of(
                override("ATRASO_LONGO", null, 181, null, 18),
                override("CARTEIRA_X", portfolio, null, null, 10),
                override("CARTEIRA_X_LONGO", portfolio, 181, null, 24))).validate();
        assertThat(terms.resolve(null, 30).overrideCode()).isNull();
        assertThat(terms.resolve(null, 200).overrideCode()).isEqualTo("ATRASO_LONGO");
        assertThat(terms.resolve(portfolio, 30).overrideCode()).isEqualTo("CARTEIRA_X");
        PolicyTerms.Resolved resolved = terms.resolve(portfolio, 365);
        assertThat(resolved.overrideCode()).isEqualTo("CARTEIRA_X_LONGO");
        assertThat(resolved.rules().installments().maxInstallments()).isEqualTo(24);
        // grupos não sobrepostos são herdados
        assertThat(resolved.rules().charges()).isEqualTo(base.charges());
    }

    @Test
    void overlappingBandsAreRejected() {
        assertThatThrownBy(() -> new PolicyTerms(base, List.of(override("FAIXA_A", null, 0, 90, 12),
                override("FAIXA_B", null, 90, 180, 12))).validate())
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("interceptam");
    }

    @Test
    void ladderMustBeContiguousAndAscending() {
        List<ConcessionLevel> gap = List.of(new ConcessionLevel(0, "Zero", ComponentRates.NONE, null),
                new ConcessionLevel(2, "Dois", ComponentRates.FULL, null));
        assertThatThrownBy(() -> new PolicyRules(base.charges(), base.discountTiers(), base.installments(), base.validity(),
                gap, base.breakRule()).validate()).hasMessageContaining("contíguos");
        List<ConcessionLevel> descending = List.of(new ConcessionLevel(0, "Zero", ComponentRates.FULL, null),
                new ConcessionLevel(1, "Um", ComponentRates.NONE, null));
        assertThatThrownBy(() -> new PolicyRules(base.charges(), base.discountTiers(), base.installments(), base.validity(),
                descending, base.breakRule()).validate()).hasMessageContaining("anterior");
    }

    @Test
    void publishedVersionIsImmutableAndSupersededByNext() {
        CollectionPolicy v1 = new CollectionPolicy(UUID.randomUUID(), 1, "Política", null, "{}", 1);
        assertThatThrownBy(() -> v1.publish(TODAY.minusDays(1), TODAY, UUID.randomUUID(), Instant.now()))
                .hasMessageContaining("passado");
        v1.publish(TODAY, TODAY, UUID.randomUUID(), Instant.now());
        assertThat(v1.isEffectiveOn(TODAY)).isTrue();
        assertThatThrownBy(() -> v1.defineTerms("{\"x\":1}", 1)).isInstanceOf(ConflictException.class);
        assertThat(v1.supersede(TODAY.plusDays(10))).isTrue();
        assertThat(v1.getEffectiveTo()).isEqualTo(TODAY.plusDays(9));
        assertThat(v1.isEffectiveOn(TODAY.plusDays(10))).isFalse();
    }

    @Test
    void scheduledVersionSupersededBeforeStartNeverBecomesEffective() {
        CollectionPolicy future = new CollectionPolicy(UUID.randomUUID(), 2, "Futura", null, "{}", 1);
        future.publish(TODAY.plusDays(30), TODAY, UUID.randomUUID(), Instant.now());
        future.supersede(TODAY.plusDays(5));
        assertThat(future.getEffectiveTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(future.isEffectiveOn(TODAY.plusDays(30))).isFalse();
    }

    @Test
    void retireEndsValidityNoEarlierThanYesterday() {
        CollectionPolicy p = new CollectionPolicy(UUID.randomUUID(), 1, "P", null, "{}", 1);
        p.publish(TODAY, TODAY, UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> p.retire(TODAY.minusDays(2), TODAY, UUID.randomUUID(), Instant.now()))
                .isInstanceOf(BusinessRuleException.class);
        p.retire(TODAY.minusDays(1), TODAY, UUID.randomUUID(), Instant.now());
        assertThat(p.getStatus()).isEqualTo(PolicyStatus.RETIRED);
        assertThat(p.isEffectiveOn(TODAY)).isFalse();
    }

    @Test
    void standardAuthorityProfilesAreValidAndAssistantCannotFormalize() {
        List<AuthorityProfile> profiles = StandardAuthorityProfiles.all().stream()
                .map(d -> new AuthorityProfile(d.code(), true, d.parameters())).toList();
        assertThat(profiles).extracting(AuthorityProfile::getCode).containsExactly("JUNIOR", "PLENO", "SENIOR",
                "SUPERVISOR", "GESTOR", "ASSISTENTE_INICIAL", "PORTAL");
        AuthorityProfile assistant = profiles.get(5);
        assertThat(assistant.isCanFormalize()).isFalse();
        assertThat(assistant.limits().maxConcessionLevel()).isEqualTo(1);
        assertThat(profiles.get(4).getMaxAgreementAmount()).isNull();
        // autonegociação: conservador (nível 1, até 6 pagamentos, entrada de 20%, sem renegociação), formaliza sozinho
        AuthorityProfile portal = profiles.getLast();
        assertThat(portal.isCanFormalize()).isTrue();
        assertThat(portal.isCanApprove()).isFalse();
        assertThat(portal.limits().maxConcessionLevel()).isEqualTo(1);
        assertThat(portal.limits().maxInstallments()).isEqualTo(6);
        assertThat(portal.limits().minDownPaymentShare()).isEqualByComparingTo("20");
        assertThat(portal.limits().maxRenegotiations()).isZero();
    }

    @Test
    void profileRestrictedToCreditors() {
        UUID creditor = UUID.randomUUID();
        AuthorityProfile p = new AuthorityProfile("ESPECIAL", false, new AuthorityProfile.Parameters("Especial", null, 2,
                new BigDecimal("80"), 10, null, BigDecimal.ZERO, true, false, 0, List.of(creditor)));
        assertThat(p.appliesTo(creditor)).isTrue();
        assertThat(p.appliesTo(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> p.define(new AuthorityProfile.Parameters("X", null, 2, new BigDecimal("80"), 10, null,
                BigDecimal.ZERO, false, true, 0, List.of()))).hasMessageContaining("formalizar");
    }

    @Test
    void decimalMathRootsAndPowers() {
        assertThat(DecimalMath.pow(new BigDecimal("2"), 1, 2).toPlainString())
                .startsWith("1.414213562373095048801688724209");
        assertThat(DecimalMath.pow(new BigDecimal("1.01"), 45, 30)).isEqualByComparingTo(
                DecimalMath.pow(new BigDecimal("1.01"), 3, 2));
        assertThat(DecimalMath.pow(new BigDecimal("1.02"), -1, 1).multiply(new BigDecimal("1.02"), DecimalMath.MC)
                .subtract(BigDecimal.ONE).abs()).isLessThan(new BigDecimal("1E-30"));
    }
}
