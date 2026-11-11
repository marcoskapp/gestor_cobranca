package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regra única de elegibilidade do operador na telefonia: ativo e com {@code TELEPHONY_USE} (lista e gravação). */
class AgentCandidatesTest {

    private static final String USE = Permission.TELEPHONY_USE.name();

    private static UserSummary user(boolean active, Set<String> permissions) {
        return new UserSummary(UUID.randomUUID(), "Operadora", active, false, false, Set.of(UUID.randomUUID()), permissions,
                Set.of());
    }

    @Test
    void eligibleOnlyWhenActiveWithTelephonyUse() {
        assertThat(AgentCandidates.isEligible(user(true, Set.of(USE)))).isTrue();
        assertThat(AgentCandidates.isEligible(user(false, Set.of(USE)))).isFalse();
        assertThat(AgentCandidates.isEligible(user(true, Set.of(Permission.TELEPHONY_MANAGE.name())))).isFalse();
        assertThat(AgentCandidates.isEligible(null)).isFalse();
    }

    @Test
    void savingRefusesMissingOrIneligibleUserWithClearMessage() {
        UserDirectory directory = mock(UserDirectory.class);
        AgentCandidates candidates = new AgentCandidates(directory, null, null, null);
        UserSummary eligible = user(true, Set.of(USE));
        UserSummary inactive = user(false, Set.of(USE));
        UUID missing = UUID.randomUUID();
        when(directory.find(eligible.id())).thenReturn(Optional.of(eligible));
        when(directory.find(inactive.id())).thenReturn(Optional.of(inactive));
        when(directory.find(missing)).thenReturn(Optional.empty());

        assertThatCode(() -> candidates.requireEligible(eligible.id())).doesNotThrowAnyException();
        assertThatThrownBy(() -> candidates.requireEligible(inactive.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid")
                .hasMessageContaining("permissão para usar a telefonia");
        assertThatThrownBy(() -> candidates.requireEligible(missing))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid");
    }
}
