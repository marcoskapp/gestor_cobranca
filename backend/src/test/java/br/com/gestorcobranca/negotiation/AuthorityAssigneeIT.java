package br.com.gestorcobranca.negotiation;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleView;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases.AssignableUserView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Atribuição de alçada: a lista do campo "Usuário" mostra exatamente quem a atribuição aceita — habilitado (ativo ou
 * aguardando ativação, para preparar o onboarding), nunca a própria pessoa e, na atribuição por credor, com acesso a
 * ele. {@code NEGOTIATION_PROPOSE} não é exigida (a alçada pode ser preparada antes da permissão).
 */
class AuthorityAssigneeIT extends NegotiationTestSupport {

    private List<UUID> listed(UUID creditorId) {
        return asManager(() -> authorityUseCases.assignableUsers(creditorId, null, null, new PageQuery(0, 50)))
                .content().stream().map(AssignableUserView::id).toList();
    }

    private UUID createUser(String name, String roleCode, boolean allCreditors, Set<UUID> creditorIds) {
        UUID roleId = system(() -> roles.list().stream().filter(r -> r.code().equals(roleCode)).findFirst()
                .map(RoleView::id).orElseThrow());
        return system(() -> userManagement.create(new CreateUserCommand(name, "u-" + UUID.randomUUID().toString()
                .substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors, creditorIds)).id());
    }

    @Test
    void assignableListMatchesWhatAssignmentAccepts() {
        provisionTenant("authassign");
        UUID profile = asManager(() -> authorityUseCases.listProfiles()).stream()
                .filter(p -> p.code().equals("PLENO")).findFirst().orElseThrow().id();
        // só o credor B; e um auditor (sem NEGOTIATION_PROPOSE) aguardando ativação
        UUID onlyB = createUser("Operadora Só B", DefaultRoles.OPERADOR, false, Set.of(creditorB));
        UUID auditor = createUser("Auditor Pendente", DefaultRoles.AUDITOR, true, Set.of());
        UUID disabled = createUser("Operador Desligado", DefaultRoles.OPERADOR, true, Set.of());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'DISABLED', disabled_at = now() where id = ?", disabled));

        // geral: habilitados, sem o próprio gestor e sem o desativado; sem exigir NEGOTIATION_PROPOSE
        assertThat(listed(null)).contains(operator, onlyB, auditor).doesNotContain(manager, disabled);
        // por credor: só quem acessa o credor A
        assertThat(listed(creditorA)).contains(operator, auditor).doesNotContain(onlyB, manager, disabled);
        // busca por nome sem acento e resolução por ids
        assertThat(asManager(() -> authorityUseCases.assignableUsers(creditorB, "operadora so", null,
                new PageQuery(0, 20))).content()).extracting(AssignableUserView::id).containsExactly(onlyB);
        assertThat(asManager(() -> authorityUseCases.assignableUsers(creditorA, null, Set.of(onlyB, operator),
                new PageQuery(0, 20))).content()).extracting(AssignableUserView::id).containsExactly(operator);

        // gravação: mesma regra
        assertThatThrownBy(() -> asManager(() -> authorityUseCases.assign(new AssignCommand("USER", onlyB, creditorA,
                profile)))).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("authority.subject_not_eligible");
        assertThatThrownBy(() -> asManager(() -> authorityUseCases.assign(new AssignCommand("USER", disabled, null,
                profile)))).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("authority.subject_not_eligible");
        asManager(() -> authorityUseCases.assign(new AssignCommand("USER", onlyB, creditorB, profile)));
        asManager(() -> authorityUseCases.assign(new AssignCommand("USER", auditor, creditorA, profile)));
        assertThatThrownBy(() -> asManager(() -> authorityUseCases.assign(new AssignCommand("USER", manager, null,
                profile)))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("própria");

        // escopo do ator: a lista exige o mesmo escopo da atribuição
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, CreditorScope.of(Set.of(creditorA)),
                () -> authorityUseCases.assignableUsers(null, null, null, new PageQuery(0, 20))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(as(manager, DefaultRoles.GESTOR, CreditorScope.of(Set.of(creditorA)),
                () -> authorityUseCases.assignableUsers(creditorA, null, null, new PageQuery(0, 20))).content())
                .isNotEmpty();
        // sem AUTHORITY_MANAGE: recusado
        assertThatThrownBy(() -> asOperator(() -> authorityUseCases.assignableUsers(creditorA, null, null,
                new PageQuery(0, 20)))).isInstanceOf(ScopeAccessDeniedException.class);

        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and details ->> 'resource' = 'authority_assignment'")).isGreaterThanOrEqualTo(4);
        assertThat(AuthorityUseCases.ASSIGNABLE_MAX_PAGE_SIZE).isEqualTo(50);
    }
}
