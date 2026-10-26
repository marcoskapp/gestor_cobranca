package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort;
import br.com.gestorcobranca.campaign.application.service.AgreementInstallments.Lookup;
import br.com.gestorcobranca.campaign.application.service.AgreementInstallments.NextInstallment;
import br.com.gestorcobranca.campaign.domain.CampaignFixtures;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.SkipReason;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.DispatchCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.IssueLinkCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.LinkIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Etapas de mensagem com as origens de parcela do acordo e link do portal: sem acordo/parcela/portal a
 * etapa é pulada com motivo (nada é enviado com valor vazio); o link é gerado imediatamente antes do
 * envio e vinculado à mensagem (o portal revoga se a mensagem falhar).
 */
class ActionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-26T13:00:00Z");
    private static final String URL = "https://cobranca.example.com/portal/credor-a/entrar#AbCdEfGh";

    private final ContactGate gate = mock(ContactGate.class);
    private final MessageUseCases messages = mock(MessageUseCases.class);
    private final DebtorUseCases debtors = mock(DebtorUseCases.class);
    private final CreditorUseCases creditors = mock(CreditorUseCases.class);
    private final AgreementInstallments installments = mock(AgreementInstallments.class);
    private final PortalLinkUseCases portalLinks = mock(PortalLinkUseCases.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CampaignOfferPort> offers = mock(ObjectProvider.class);
    private ActionExecutor executor;
    private Enrollment enrollment;
    private AudienceSnapshot snapshot;

    @BeforeEach
    void setUp() {
        executor = new ActionExecutor(gate, messages, mock(TaskUseCases.class), mock(CaseWorkUseCases.class),
                new VariableResolver(debtors, creditors), offers, installments, portalLinks);
        snapshot = CampaignFixtures.snapshot("AGREEMENT", true, "300.00", LocalDate.of(2026, 9, 1));
        enrollment = Enrollment.start(new Enrollment.Origin(UUID.randomUUID(), UUID.randomUUID(), snapshot.caseId(),
                snapshot.debtorId(), snapshot.creditorId(), snapshot.portfolioId(), null, 100, null, null),
                step(Map.of()), NOW);
        when(gate.evaluate(any())).thenReturn(new ContactDecision(true, List.of(), null, null));
        when(debtors.get(snapshot.debtorId())).thenReturn(new DebtorView(snapshot.debtorId(), "PF", "Maria da Silva",
                "***", null, "ACTIVE", null, NOW, null));
        when(creditors.get(snapshot.creditorId())).thenReturn(new CreditorView(snapshot.creditorId(), "Banco A S.A.",
                "Banco A", "00000000000191", null, null, "ACTIVE"));
    }

    private static CadenceStep step(Map<String, String> variables) {
        return new CadenceStep("LEMBRETE", "Lembrete", 0, null, null,
                new StepAction(ActionType.SEND_MESSAGE, "WHATSAPP", "ACORDO_LEMBRETE_PARCELA", variables, null, null,
                        null, null, null), null, null);
    }

    private static MessageView message(String status, String failureCode) {
        return new MessageView(UUID.randomUUID(), UUID.randomUUID(), "WHATSAPP", "OUTBOUND", "CAMPAIGN", null, null, null,
                null, false, status, NOW, failureCode, null, 0, null, NOW, null, null, null, null, null, NOW);
    }

    private ActionExecutor.Result run(Map<String, String> variables) {
        return executor.execute(enrollment, step(variables), snapshot, "campaign:x:LEMBRETE:1", NOW);
    }

    @Test
    void installmentReminderSendsAmountAndDueDateOfTheNextOpenInstallment() {
        when(installments.next(snapshot.caseId(), snapshot.debtorId(), snapshot.creditorId(), snapshot.portfolioId()))
                .thenReturn(Lookup.of(new NextInstallment(UUID.randomUUID(), UUID.randomUUID(), 2,
                        LocalDate.of(2026, 11, 5), new BigDecimal("150.5"))));
        when(messages.send(any())).thenReturn(message("QUEUED", null));

        ActionExecutor.Result result = run(Map.of("nome", "DEBTOR_FIRST_NAME", "credor", "CREDITOR_NAME",
                "valor", "NEXT_INSTALLMENT_AMOUNT", "vencimento", "NEXT_INSTALLMENT_DUE_DATE"));

        assertThat(result).isInstanceOf(ActionExecutor.Executed.class);
        ArgumentCaptor<SendMessageCommand> sent = ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(messages).send(sent.capture());
        assertThat(sent.getValue().variables()).containsExactlyInAnyOrderEntriesOf(Map.of("nome", "Maria",
                "credor", "Banco A", "valor", "150.50", "vencimento", "2026-11-05"));
        verifyNoInteractions(portalLinks);
    }

    @Test
    void withoutActiveAgreementOrOpenInstallmentTheStepIsSkippedAndNothingIsSent() {
        when(installments.next(any(), any(), any(), any())).thenReturn(Lookup.unavailable(SkipReason.NO_ACTIVE_AGREEMENT));
        Map<String, String> vars = Map.of("valor", "NEXT_INSTALLMENT_AMOUNT");
        assertThat(run(vars)).isEqualTo(new ActionExecutor.Skipped(SkipReason.NO_ACTIVE_AGREEMENT, "no_active_agreement",
                null, false));

        when(installments.next(any(), any(), any(), any())).thenReturn(Lookup.unavailable(SkipReason.NO_OPEN_INSTALLMENT));
        assertThat(run(vars)).isInstanceOfSatisfying(ActionExecutor.Skipped.class,
                s -> assertThat(s.reason()).isEqualTo(SkipReason.NO_OPEN_INSTALLMENT));
        verify(messages, never()).send(any());
    }

    @Test
    void portalLinkIsIssuedRightBeforeSendingAndLinkedToTheMessage() {
        UUID linkId = UUID.randomUUID();
        when(portalLinks.issue(any())).thenReturn(new LinkIssue(null, linkId, URL, 48, NOW.plusSeconds(48 * 3600)));
        MessageView queued = message("QUEUED", null);
        when(messages.send(any())).thenReturn(queued);

        ActionExecutor.Result result = run(Map.of("nome", "DEBTOR_FIRST_NAME", "link", "PORTAL_LINK",
                "horas", "PORTAL_LINK_HOURS"));

        assertThat(result).isInstanceOf(ActionExecutor.Executed.class);
        ArgumentCaptor<IssueLinkCommand> issued = ArgumentCaptor.forClass(IssueLinkCommand.class);
        verify(portalLinks).issue(issued.capture());
        assertThat(issued.getValue()).isEqualTo(new IssueLinkCommand(snapshot.debtorId(), snapshot.creditorId(),
                snapshot.portfolioId(), "WHATSAPP", "CAMPAIGN", "campaign-enrollment:" + enrollment.getId()));
        ArgumentCaptor<SendMessageCommand> sent = ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(messages).send(sent.capture());
        assertThat(sent.getValue().variables()).containsEntry("link", URL).containsEntry("horas", "48");
        verify(portalLinks).recordDispatch(new DispatchCommand(linkId, queued.id(), "QUEUED", "CAMPAIGN",
                "campaign-enrollment:" + enrollment.getId()));
    }

    @Test
    void deniedMessageStillRecordsTheDispatchSoThePortalRevokesTheLink() {
        UUID linkId = UUID.randomUUID();
        when(portalLinks.issue(any())).thenReturn(new LinkIssue(null, linkId, URL, 24, NOW));
        MessageView denied = message("FAILED", "CONTACT_DENIED");
        when(messages.send(any())).thenReturn(denied);

        assertThat(run(Map.of("link", "PORTAL_LINK"))).isInstanceOfSatisfying(ActionExecutor.Skipped.class,
                s -> assertThat(s.reason()).isEqualTo(SkipReason.CONTACT_DENIED));
        verify(portalLinks).recordDispatch(new DispatchCommand(linkId, denied.id(), "FAILED", "CAMPAIGN",
                "campaign-enrollment:" + enrollment.getId()));
    }

    @Test
    void withoutPortalForTheCaseTheStepIsSkippedWithThePortalReason() {
        when(portalLinks.issue(any())).thenReturn(LinkIssue.unavailable(LinkIssue.PORTFOLIO_NOT_IN_PORTAL));
        assertThat(run(Map.of("link", "PORTAL_LINK"))).isEqualTo(new ActionExecutor.Skipped(SkipReason.PORTAL_UNAVAILABLE,
                "PORTFOLIO_NOT_IN_PORTAL", null, false));
        verify(messages, never()).send(any());
        verify(portalLinks, never()).recordDispatch(any());
    }

    @Test
    void installmentIsCheckedBeforeTheLinkIsIssued() {
        when(installments.next(any(), any(), any(), any())).thenReturn(Lookup.unavailable(SkipReason.NO_ACTIVE_AGREEMENT));
        run(Map.of("valor", "NEXT_INSTALLMENT_AMOUNT", "link", "PORTAL_LINK"));
        verifyNoInteractions(portalLinks);
    }

    @Test
    void linkIssueNeverExposesTheUrlInToString() {
        assertThat(new LinkIssue(null, UUID.randomUUID(), URL, 48, NOW).toString()).doesNotContain("AbCdEfGh")
                .contains("url=***");
    }
}
