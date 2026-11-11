package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.paging.NameSearch;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regra única de elegibilidade do responsável por solicitação de titular: ativo, com
 * {@code DATA_SUBJECT_REQUEST_MANAGE} e acesso a todos os credores — a mesma para a lista e para a gravação.
 */
class DataSubjectRequestAssigneesTest {

    private static final String MANAGE = Permission.DATA_SUBJECT_REQUEST_MANAGE.name();

    private static UserSummary user(boolean active, boolean allCreditors, Set<String> permissions) {
        return new UserSummary(UUID.randomUUID(), "Encarregada", active, false, allCreditors,
                allCreditors ? Set.of() : Set.of(UUID.randomUUID()), permissions, Set.of());
    }

    @Test
    void eligibleOnlyWhenActiveWithPermissionAndAllCreditors() {
        assertThat(DataSubjectRequestAssignees.isEligible(user(true, true, Set.of(MANAGE, "USER_VIEW")))).isTrue();
        assertThat(DataSubjectRequestAssignees.isEligible(user(false, true, Set.of(MANAGE)))).isFalse();
        assertThat(DataSubjectRequestAssignees.isEligible(user(true, false, Set.of(MANAGE)))).isFalse();
        assertThat(DataSubjectRequestAssignees.isEligible(user(true, true, Set.of("CASE_WORK")))).isFalse();
        assertThat(DataSubjectRequestAssignees.isEligible(null)).isFalse();
    }

    @Test
    void savingRefusesMissingOrIneligibleUserWithClearMessage() {
        UserDirectory directory = mock(UserDirectory.class);
        DataSubjectRequestAssignees assignees = new DataSubjectRequestAssignees(directory, null, null, null);
        UserSummary eligible = user(true, true, Set.of(MANAGE));
        UserSummary partial = user(true, false, Set.of(MANAGE));
        UUID missing = UUID.randomUUID();
        when(directory.find(eligible.id())).thenReturn(Optional.of(eligible));
        when(directory.find(partial.id())).thenReturn(Optional.of(partial));
        when(directory.find(missing)).thenReturn(Optional.empty());

        assertThatCode(() -> assignees.requireEligible(eligible.id())).doesNotThrowAnyException();
        assertThatThrownBy(() -> assignees.requireEligible(partial.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "dsr.assignee_invalid")
                .hasMessageContaining("acesso a todos os credores");
        assertThatThrownBy(() -> assignees.requireEligible(missing))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "dsr.assignee_invalid");
        assertThatThrownBy(() -> assignees.requireEligible(null))
                .hasFieldOrPropertyWithValue("code", "dsr.assignee_required");
    }

    @Test
    void searchIgnoresCaseAndAccents() {
        assertThat(NameSearch.normalize("  João DA Silva ")).isEqualTo("joao da silva");
        assertThat(NameSearch.normalize(null)).isEmpty();
    }
}
