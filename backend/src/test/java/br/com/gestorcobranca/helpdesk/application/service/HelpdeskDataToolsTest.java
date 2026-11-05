package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.domain.service.PersonalData;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook.Kind;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Minimização do que as ferramentas do chat devolvem ao modelo (texto livre de outras pessoas). */
class HelpdeskDataToolsTest {

    private static final String FREE_TEXT = "Filha Maria Souza atende no 11 98765-4321, devedor em tratamento de câncer";

    private final CaseUseCases cases = mock(CaseUseCases.class);
    private final AgreementUseCases agreements = mock(AgreementUseCases.class);
    private final ImportBatchUseCases imports = mock(ImportBatchUseCases.class);
    private final KnowledgeBase knowledge = mock(KnowledgeBase.class);
    private final HelpdeskDataTools tools = new HelpdeskDataTools(mock(DebtorUseCases.class), cases, agreements,
            mock(PaymentUseCases.class), imports, mock(SettlementStatementUseCases.class), mock(StatementUseCases.class),
            mock(PendingWorkUseCases.class), mock(CreditorUseCases.class), knowledge, Clock.systemUTC());
    private final Actor actor = new Actor(Actor.ActorType.USER, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Ana", Set.of(), CreditorScope.ALL);

    private TurnState state() {
        when(knowledge.screen(any())).thenReturn(Optional.empty());
        return new TurnState(actor, UUID.randomUUID(), null, ReferenceBook.empty(),
                PersonalData.withPlaceholders(List.of()), null);
    }

    @Test
    void caseReasonWrittenByAPersonGoesToTheModelOnlyAsRecorded() {
        TurnState state = state();
        UUID debtorId = UUID.randomUUID();
        String debtorRef = state.references.refFor(Kind.DEBTOR, debtorId);
        CaseView c = mock(CaseView.class);
        when(c.id()).thenReturn(UUID.randomUUID());
        when(c.creditorId()).thenReturn(UUID.randomUUID());
        when(c.status()).thenReturn("SUSPENDED");
        when(c.statusReason()).thenReturn(FREE_TEXT);
        when(cases.listByDebtor(debtorId)).thenReturn(List.of(c));

        Map<String, Object> forModel = tools.debtorCases(debtorRef, state).forModel();

        Map<?, ?> first = (Map<?, ?>) ((List<?>) forModel.get("casos")).getFirst();
        assertThat(first.get("motivoRegistrado")).isEqualTo(Boolean.TRUE);
        assertThat(first.containsKey("motivoSituacao")).isFalse();
        assertThat(forModel.toString()).doesNotContain("Maria").doesNotContain("98765").doesNotContain("câncer");
    }

    @Test
    void caseWithoutReasonDoesNotTellTheModelThereIsOne() {
        TurnState state = state();
        UUID debtorId = UUID.randomUUID();
        String debtorRef = state.references.refFor(Kind.DEBTOR, debtorId);
        CaseView c = mock(CaseView.class);
        when(c.id()).thenReturn(UUID.randomUUID());
        when(c.creditorId()).thenReturn(UUID.randomUUID());
        when(c.statusReason()).thenReturn("  ");
        when(cases.listByDebtor(debtorId)).thenReturn(List.of(c));

        Map<?, ?> first = (Map<?, ?>) ((List<?>) tools.debtorCases(debtorRef, state).forModel().get("casos")).getFirst();

        assertThat(first.containsKey("motivoRegistrado")).isFalse();
    }

    @Test
    void agreementReasonGoesToTheModelOnlyAsRecorded() {
        TurnState state = state();
        UUID debtorId = UUID.randomUUID();
        String debtorRef = state.references.refFor(Kind.DEBTOR, debtorId);
        AgreementView a = mock(AgreementView.class);
        when(a.id()).thenReturn(UUID.randomUUID());
        when(a.creditorId()).thenReturn(UUID.randomUUID());
        when(a.debtorId()).thenReturn(debtorId);
        when(a.statusReason()).thenReturn(FREE_TEXT);
        when(a.installments()).thenReturn(List.of());
        when(agreements.search(any(), any())).thenReturn(new PageResponse<>(List.of(a), 0, 5, 1, 1));

        Map<String, Object> forModel = tools.agreements(debtorRef, null, state).forModel();

        Map<?, ?> first = (Map<?, ?>) ((List<?>) forModel.get("acordos")).getFirst();
        assertThat(first.get("motivoRegistrado")).isEqualTo(Boolean.TRUE);
        assertThat(forModel.toString()).doesNotContain("Maria").doesNotContain("98765");
    }

    @Test
    void importReasonIsMaskedAndShort() {
        TurnState state = state();
        UUID batchId = UUID.randomUUID();
        String batchRef = state.references.refFor(Kind.IMPORT, batchId);
        ImportBatchView b = mock(ImportBatchView.class);
        when(b.id()).thenReturn(batchId);
        when(b.statusReason()).thenReturn("Cancelado a pedido de fulano@credor.com.br, CPF 123.456.789-09 " + "x".repeat(300));
        when(imports.get(batchId)).thenReturn(b);

        Map<String, Object> forModel = tools.imports(batchRef, state).forModel();

        String reason = (String) ((Map<?, ?>) ((List<?>) forModel.get("importacoes")).getFirst()).get("motivoSituacao");
        assertThat(reason).contains("[e-mail]").contains("[documento]")
                .doesNotContain("fulano@credor.com.br").doesNotContain("123.456.789-09");
        assertThat(reason).hasSize(HelpdeskDataTools.MAX_NOTE_LENGTH).endsWith("…");
    }
}
