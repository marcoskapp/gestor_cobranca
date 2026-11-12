package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionPolicyTest {

    @Test
    void unitAndDefaultFollowTheCategory() {
        assertThat(Arrays.stream(RetentionCategory.values()).filter(RetentionCategory::hasSystemDefault))
                .allSatisfy(c -> assertThat(c.unit()).isEqualTo(RetentionUnit.DAYS))
                .containsExactly(RetentionCategory.PORTAL_ACCESS_CHALLENGES, RetentionCategory.PORTAL_ACCESS_THROTTLES,
                        RetentionCategory.PORTAL_SESSION_CLIENT_DATA, RetentionCategory.SCORING_CASE_FACTS,
                        RetentionCategory.SCORING_FEATURE_SNAPSHOTS, RetentionCategory.SCORING_CONTACT_FACTS);
        assertThat(Arrays.stream(RetentionCategory.values()).filter(c -> !c.hasSystemDefault()))
                .allSatisfy(c -> {
                    assertThat(c.unit()).isEqualTo(RetentionUnit.MONTHS);
                    assertThat(c.minAmount()).isEqualTo(1);
                    assertThat(c.maxAmount()).isEqualTo(1_200);
                });
    }

    @Test
    void categoryWithoutDefaultStartsDisabledInMonths() {
        RetentionPolicy policy = new RetentionPolicy(RetentionCategory.IMPORT_FILES, 6);

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getRetentionAmount()).isEqualTo(6);
        assertThat(policy.getRetentionUnit()).isEqualTo(RetentionUnit.MONTHS);
        policy.enable();
        policy.disable();
        assertThat(policy.isEnabled()).isFalse();
        assertThatThrownBy(() -> policy.changeRetention(1_201)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "retention.amount_invalid");
    }

    @Test
    void categoryWithDefaultIsAlwaysEnabledAndRespectsItsLimits() {
        RetentionPolicy policy = new RetentionPolicy(RetentionCategory.PORTAL_ACCESS_THROTTLES, 7);

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getRetentionAmount()).isEqualTo(7);
        assertThat(policy.getRetentionUnit()).isEqualTo(RetentionUnit.DAYS);
        assertThatThrownBy(policy::disable).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "retention.discard_mandatory");
        assertThat(policy.isEnabled()).isTrue();
        assertThatThrownBy(() -> policy.changeRetention(6)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 7 e 365 dias");
        assertThatThrownBy(() -> new RetentionPolicy(RetentionCategory.SCORING_CASE_FACTS, 729))
                .isInstanceOf(BusinessRuleException.class);
        policy.changeRetention(365);
        assertThat(policy.getRetentionAmount()).isEqualTo(365);
    }
}
