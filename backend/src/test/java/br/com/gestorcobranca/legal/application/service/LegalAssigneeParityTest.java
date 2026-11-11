package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases.Purpose;
import br.com.gestorcobranca.legal.domain.model.LawFirmMember;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paridade entre a lista de atribuíveis do jurídico e a gravação: para cada campo, quem a lista mostra é exatamente
 * quem {@link LegalAssigneeService#requireEligible} aceita (decisão 686 — antes, o prazo e o vínculo ao escritório
 * aceitavam usuário sem {@code LEGAL_MANAGE}).
 */
class LegalAssigneeParityTest {

    private static final UUID CREDITOR = UUID.randomUUID();
    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID OTHER_FIRM = UUID.randomUUID();

    private final UserDirectory users = mock(UserDirectory.class);
    private final LawFirmMemberRepository members = mock(LawFirmMemberRepository.class);
    private final LegalAccess access = mock(LegalAccess.class);
    private final LegalAssigneeService service = new LegalAssigneeService(users, members, access, mock(AccessScope.class),
            mock(AccessLogger.class));
    private final LegalMatter matter = mock(LegalMatter.class);

    private final UserSummary lawyer = summary("Jurídico", true, Set.of("LEGAL_MANAGE"), true);
    private final UserSummary partner = summary("Parceiro", true, Set.of("LEGAL_MANAGE"), false);
    private final UserSummary colleague = summary("Colega", true, Set.of("LEGAL_MANAGE"), false);
    private final UserSummary outsider = summary("Outro escritório", true, Set.of("LEGAL_MANAGE"), true);
    /** Sem LEGAL_MANAGE: antes aceito como responsável de prazo e como advogado de escritório. */
    private final UserSummary operator = summary("Operador", true, Set.of("CASE_WORK"), true);
    private final UserSummary inactive = summary("Inativo", false, Set.of("LEGAL_MANAGE"), true);
    private final List<UserSummary> all = List.of(lawyer, partner, colleague, outsider, operator, inactive);

    private static UserSummary summary(String name, boolean active, Set<String> permissions, boolean allCreditors) {
        return new UserSummary(UUID.randomUUID(), name, active, false, allCreditors,
                allCreditors ? Set.of() : Set.of(CREDITOR), permissions, Set.of());
    }

    @BeforeEach
    void setUp() {
        UUID matterId = UUID.randomUUID();
        when(matter.getId()).thenReturn(matterId);
        when(matter.getCreditorId()).thenReturn(CREDITOR);
        when(access.visible(matterId)).thenReturn(matter);
        when(access.actor()).thenReturn(new Actor(Actor.ActorType.USER, lawyer.id(), UUID.randomUUID(),
                UUID.randomUUID(), "JURIDICO", Set.of(), CreditorScope.ALL));
        when(access.scope()).thenReturn(LegalAccess.ActorScope.INTERNAL);
        List<LawFirmMember> firmMembers = List.of(member(FIRM, partner), member(FIRM, colleague),
                member(OTHER_FIRM, outsider));
        when(members.findByActiveTrue()).thenReturn(firmMembers);
        when(members.findByUserIdAndActiveTrue(any())).thenReturn(Optional.empty());
        firmMembers.forEach(m -> when(members.findByUserIdAndActiveTrue(m.getUserId())).thenReturn(Optional.of(m)));
        // a busca parte de quem tem LEGAL_MANAGE; aqui todos, para provar que o predicado decide sozinho
        when(users.activeUsersWithPermission("LEGAL_MANAGE")).thenReturn(all);
        all.forEach(u -> when(users.find(u.id())).thenReturn(Optional.of(u)));
    }

    private static LawFirmMember member(UUID firm, UserSummary user) {
        return new LawFirmMember(firm, user.id(), Instant.now());
    }

    private List<UUID> listed(Purpose purpose) {
        return service.search(purpose, purpose == Purpose.FIRM_MEMBER ? null : matter.getId(), null, null,
                new PageQuery(0, 50)).content().stream().map(AssigneeView::id).toList();
    }

    private boolean saves(Purpose purpose, UUID userId) {
        try {
            service.requireEligible(purpose, purpose == Purpose.FIRM_MEMBER ? null : matter, userId, "x", "x");
            return true;
        } catch (BusinessRuleException e) {
            return false;
        }
    }

    private void assertParity(Purpose purpose) {
        List<UUID> listed = listed(purpose);
        all.forEach(u -> assertThat(saves(purpose, u.id())).as(purpose + " " + u.name()).isEqualTo(listed.contains(u.id())));
    }

    @Test
    void internalActorSeesAndSavesTheSameUsers() {
        assertThat(listed(Purpose.MATTER_RESPONSIBLE)).containsExactly(lawyer.id());
        assertThat(listed(Purpose.DEADLINE_RESPONSIBLE)).containsExactlyInAnyOrder(lawyer.id(), partner.id(),
                colleague.id(), outsider.id());
        assertThat(listed(Purpose.FIRM_MEMBER)).containsExactly(lawyer.id());
        for (Purpose purpose : Purpose.values()) {
            assertParity(purpose);
        }
    }

    @Test
    void partnerLawyerAssignsDeadlinesOnlyToFirmColleagues() {
        when(access.scope()).thenReturn(new LegalAccess.ActorScope(FIRM, true));
        assertThat(listed(Purpose.DEADLINE_RESPONSIBLE)).containsExactlyInAnyOrder(partner.id(), colleague.id());
        assertParity(Purpose.DEADLINE_RESPONSIBLE);
    }
}
