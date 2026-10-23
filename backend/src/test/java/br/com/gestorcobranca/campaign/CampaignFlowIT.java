package br.com.gestorcobranca.campaign;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.CampaignPanel;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentDetail;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.StepExecutionView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.AudienceCount;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.PreviewCommand;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateFilter;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos do motor de campanhas contra PostgreSQL real e o sandbox do communication: régua completa
 * (mensagens pelo porteiro, tarefa no collection, régua esgotada publicada para o jurídico), pausa por
 * pagamento, acordo e restrição de contato com retomada, resposta que redireciona a sequência (RB07),
 * reprocessamento idempotente e contagem prévia de segmento.
 */
@Import(CadenceExhaustedProbe.class)
class CampaignFlowIT extends CampaignTestSupport {

    @Autowired
    CadenceExhaustedProbe probe;

    UUID debtor;
    CaseView kase;
    UUID segmentId;

    @BeforeEach
    void setUp() {
        provisionCampaigns("cmp");
        debtor = debtor("529.982.247-25");
        kase = loadCase(creditorA, portfolioA, debtor, "500.00", LocalDate.of(2026, 3, 10));
        segmentId = segment(creditorA, "ATRASO_30", new SegmentCriteria(null, 30, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void fullCadenceRunsThroughCommunicationAndCollectionAndExhaustionIsPublished() {
        CampaignView campaign = activeCampaign("REGUA_PADRAO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 0, null, null), callbackTask("LIGAR", 0), whatsapp("D2", 2 * 1440, null, null)));

        runEngine();
        EnrollmentView enrollment = enrollmentOf(campaign.id(), kase.id());
        assertThat(enrollment.status()).isEqualTo("ACTIVE");
        assertThat(enrollment.nextStepCode()).isEqualTo("D2");
        assertThat(enrollment.stepsExecuted()).isEqualTo(2);

        // mensagem enviada pelo communication com a execução de origem e a chave idempotente
        List<StepExecutionView> steps = asManager(() -> execution.enrollment(enrollment.id())).steps();
        StepExecutionView d0 = steps.getFirst();
        assertThat(d0.outcome()).isEqualTo("EXECUTED");
        assertThat(d0.messageStatus()).isEqualTo("READ");
        assertThat(count("select count(*) from communication.message where id = ? and campaign_execution_id = ? "
                + "and sender_type = 'CAMPAIGN' and idempotency_key = ?", d0.messageId(), enrollment.id(),
                "campaign:" + enrollment.id() + ":d0:1")).isEqualTo(1);
        assertThat(count("select count(*) from compliance.contact_attempt where origin = 'CAMPAIGN' and reference_id = ?",
                d0.messageId())).isEqualTo(1);
        // tarefa de retorno criada no caso
        assertThat(steps.get(1).taskId()).isNotNull();
        assertThat(count("select count(*) from collection.task where id = ? and case_id = ? and type = 'CALLBACK'",
                steps.get(1).taskId(), kase.id())).isEqualTo(1);

        // espera de 2 dias até a terceira etapa
        fastForward(campaign.id());
        runEngine();
        EnrollmentView ended = enrollmentOf(campaign.id(), kase.id());
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("CADENCE_EXHAUSTED");
        assertThat(ended.stepsExecuted()).isEqualTo(3);
        assertThat(probe.received).filteredOn(e -> e.enrollmentId().equals(ended.id())).singleElement()
                .satisfies(e -> {
                    assertThat(e.campaignPurpose()).isEqualTo("EXTRAJUDICIAL_COLLECTION");
                    assertThat(e.caseId()).isEqualTo(kase.id());
                    assertThat(e.messagesSent()).isEqualTo(2);
                    assertThat(e.stepsExecuted()).isEqualTo(3);
                    assertThat(e.cadenceVersion()).isEqualTo(1);
                });
        assertThat(count("select count(*) from shared.business_event where event_type = 'campaign.step_executed' "
                + "and reference_id = ?", ended.id())).isEqualTo(3);
        assertThat(count("select count(*) from shared.business_event where event_type = 'campaign.cadence_exhausted' "
                + "and reference_id = ?", ended.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'campaign.activated' "
                + "and reference_id = ?", campaign.id())).isEqualTo(1);

        // sem reinscrição: o caso encerrado na régua não volta na próxima varredura (reativação reavalia o segmento)
        asManager(() -> execution.pause(campaign.id(), "Ajuste"));
        asManager(() -> execution.activate(campaign.id()));
        runEngine();
        assertThat(enrollmentsOf(kase.id())).hasSize(1);

        CampaignPanel panel = asManager(() -> execution.panel(campaign.id()));
        assertThat(panel.enrollments()).isEqualTo(1);
        assertThat(panel.steps().executed()).isEqualTo(3);
        assertThat(panel.messages().sent()).isEqualTo(2);
        assertThat(panel.messages().read()).isEqualTo(2);
        assertThat(panel.endedByReason()).containsEntry("CADENCE_EXHAUSTED", 1L);
    }

    @Test
    void segmentPreviewCountsAndTheCampaignSkipsCasesOutsideIt() {
        UUID other = debtor("111.444.777-35");
        CaseView recent = loadCase(creditorA, portfolioA, other, "90.00", LocalDate.now().minusDays(5));
        AudienceCount all = asManager(() -> segments.preview(new PreviewCommand(creditorA, null, SegmentCriteria.any())));
        assertThat(all.cases()).isEqualTo(2);
        assertThat(all.openBalance()).isEqualByComparingTo("590.00");
        AudienceCount overdue = asManager(() -> segments.count(segmentId, null, null));
        assertThat(overdue.cases()).isEqualTo(1);
        assertThat(overdue.debtors()).isEqualTo(1);

        CampaignView campaign = activeCampaign("SO_ATRASADOS", creditorA, settings(segmentId, null, 100),
                cadence(null, whatsapp("D0", 0, null, null)));
        runEngine();
        assertThat(enrollmentsOf(kase.id())).hasSize(1);
        assertThat(enrollmentsOf(recent.id())).isEmpty();
        assertThat(asManager(() -> campaigns.get(campaign.id())).status()).isEqualTo("ACTIVE");
    }

    @Test
    void confirmedPaymentPausesAndAttributesTheRecovery() {
        CampaignView campaign = activeCampaign("PAUSA_PAGAMENTO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D5", 5 * 1440, null, null)));
        runEngine();
        UUID payment = Ids.newId();
        publishAndAwait(new PaymentConfirmed(Ids.newId(), tenant, Instant.now(), payment, debtor, creditorA, portfolioA,
                null, new BigDecimal("120.00"), Instant.now(), "EXTERNAL", "PIX", List.of()), "campaign.payment-confirmed");
        EnrollmentView paused = enrollmentOf(campaign.id(), kase.id());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pauseReason()).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(paused.resumeAt()).isNull();

        // a etapa agendada não roda enquanto pausada
        fastForward(campaign.id());
        runEngine();
        assertThat(asManager(() -> execution.enrollment(paused.id())).steps()).hasSize(1);

        CampaignPanel panel = asManager(() -> execution.panel(campaign.id()));
        assertThat(panel.outcomes().payingEnrollments()).isEqualTo(1);
        assertThat(panel.outcomes().recoveredAmount()).isEqualByComparingTo("120.00");
        assertThat(panel.pausedByReason()).containsEntry("PAYMENT_CONFIRMED", 1L);
        assertThat(count("select count(*) from shared.business_event where event_type = 'campaign.paused' "
                + "and reference_id = ? and attributes ->> 'reason' = 'PAYMENT_CONFIRMED'", paused.id())).isEqualTo(1);

        // retomada manual pela operação
        EnrollmentView resumed = asManager(() -> execution.resumeEnrollment(paused.id()));
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        fastForward(campaign.id());
        runEngine();
        assertThat(enrollmentOf(campaign.id(), kase.id()).endReason()).isEqualTo("CADENCE_COMPLETED");
    }

    @Test
    void formalizedAgreementPausesAndBrokenAgreementResumes() {
        CampaignView campaign = activeCampaign("PAUSA_ACORDO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D5", 5 * 1440, null, null)));
        runEngine();
        UUID agreement = Ids.newId();
        publishAndAwait(new AgreementStatusChanged(Ids.newId(), tenant, Instant.now(), agreement, kase.id(), debtor,
                creditorA, portfolioA, List.of(), null, "FORMALIZED", new BigDecimal("450.00"), null),
                "campaign.agreement-status");
        runEngine();
        EnrollmentView paused = enrollmentOf(campaign.id(), kase.id());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pauseReason()).isEqualTo("AGREEMENT_FORMALIZED");
        assertThat(asManager(() -> execution.panel(campaign.id())).outcomes().agreements()).isEqualTo(1);

        publishAndAwait(new AgreementStatusChanged(Ids.newId(), tenant, Instant.now(), agreement, kase.id(), debtor,
                creditorA, portfolioA, List.of(), "FORMALIZED", "BROKEN", new BigDecimal("450.00"), null),
                "campaign.agreement-status");
        runEngine();
        EnrollmentView resumed = enrollmentOf(campaign.id(), kase.id());
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(count("select broken_agreements from campaign.audience_case where case_id = ?", kase.id()))
                .isEqualTo(1);
    }

    @Test
    void contactRestrictionPausesAndRevocationResumes() {
        CampaignView campaign = activeCampaign("PAUSA_RESTRICAO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D5", 5 * 1440, null, null)));
        runEngine();
        UUID restriction = system(() -> restrictions.create(new CreateRestrictionCommand(debtor, creditorA, "WHATSAPP",
                "CREDITOR_REQUEST", null, null, "Credor pediu", "CREDITOR", null, null)).id());
        awaitIdle("campaign.application");
        EnrollmentView paused = enrollmentOf(campaign.id(), kase.id());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pauseReason()).isEqualTo("CONTACT_RESTRICTED");

        system(() -> restrictions.revoke(restriction, "Liberado"));
        awaitIdle("campaign.application");
        assertThat(enrollmentOf(campaign.id(), kase.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void suspendedOrLegalCasePausesAndResumingTheCaseResumesTheCadence() {
        CampaignView campaign = activeCampaign("PAUSA_CASO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D5", 5 * 1440, null, null)));
        runEngine();
        asSupervisor(() -> cases.suspend(kase.id(), "Pedido do credor"));
        awaitIdle("campaign.application");
        assertThat(enrollmentOf(campaign.id(), kase.id()).pauseReason()).isEqualTo("CASE_SUSPENDED");
        asSupervisor(() -> cases.resume(kase.id(), "Liberado"));
        awaitIdle("campaign.application");
        assertThat(enrollmentOf(campaign.id(), kase.id()).status()).isEqualTo("ACTIVE");

        asSupervisor(() -> cases.sendToLegal(kase.id(), "Ajuizamento"));
        awaitIdle("campaign.application");
        EnrollmentView legal = enrollmentOf(campaign.id(), kase.id());
        assertThat(legal.pauseReason()).isEqualTo("CASE_LEGAL");
        // manual não retoma enquanto o caso está no jurídico
        assertThatThrownBy(() -> asManager(() -> execution.resumeEnrollment(legal.id())))
                .hasMessageContaining("CASE_LEGAL");
    }

    @Test
    void debtorReplyRedirectsTheSequence() {
        CampaignView campaign = activeCampaign("RESPOSTA", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(ReplyTransition.STOP, whatsapp("D0", 0, ReplyTransition.GOTO_STEP, "ATENDER"),
                        whatsapp("D7", 7 * 1440, null, null), callbackTask("ATENDER", 0)));
        runEngine();
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Quero negociar");
            return null;
        });
        runEngine();
        EnrollmentView enrollment = enrollmentOf(campaign.id(), kase.id());
        EnrollmentDetail detail = asManager(() -> execution.enrollment(enrollment.id()));
        assertThat(detail.steps()).extracting(StepExecutionView::stepCode).containsExactly("D0", "ATENDER");
        assertThat(enrollment.repliedAt()).isNotNull();
        assertThat(enrollment.status()).isEqualTo("ENDED");
        assertThat(enrollment.endReason()).isEqualTo("CADENCE_COMPLETED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'campaign.replied' "
                + "and reference_id = ? and attributes ->> 'transition' = 'GOTO_STEP'", enrollment.id())).isEqualTo(1);
        assertThat(asManager(() -> execution.panel(campaign.id())).outcomes().repliedEnrollments()).isEqualTo(1);
    }

    @Test
    void replyCanHandTheCaseToTheAssistant() {
        CampaignView campaign = activeCampaign("PARA_ASSISTENTE", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(ReplyTransition.HAND_TO_ASSISTANT, whatsapp("D0", 0, null, null),
                        whatsapp("D7", 7 * 1440, null, null)));
        runEngine();
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Oi");
            return null;
        });
        runEngine();
        EnrollmentView enrollment = enrollmentOf(campaign.id(), kase.id());
        assertThat(enrollment.status()).isEqualTo("ENDED");
        assertThat(enrollment.endReason()).isEqualTo("HANDED_TO_ASSISTANT");
        assertThat(system(() -> cases.get(kase.id())).ownerType()).isEqualTo("ASSISTANT");
    }

    @Test
    void failedStepIsReprocessedWithoutDuplicatingTheMessage() {
        CampaignView campaign = activeCampaign("REPROCESSO", creditorA, settings(segmentId, "COBRANCA", 100),
                cadence(null, whatsapp("D0", 60, null, null)));
        runEngine();
        // o template sai de vigência antes da etapa: erro de negócio do communication → falha reprocessável
        UUID templateId = asManager(() -> templates.search(new TemplateFilter(null, "COBRANCA", "WHATSAPP", "PUBLISHED"),
                PageQuery.of(0, 10))).content().getFirst().id();
        asManager(() -> templates.retire(templateId));
        fastForward(campaign.id());
        runEngine();
        EnrollmentView failed = enrollmentOf(campaign.id(), kase.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastErrorCode()).isEqualTo("message.template_not_found");
        assertThat(asManager(() -> execution.panel(campaign.id())).failedByReason())
                .containsEntry("message.template_not_found", 1L);

        publishedTemplate(null, "COBRANCA", "WHATSAPP");
        assertThat(asManager(() -> execution.reprocess(campaign.id())).affected()).isEqualTo(1);
        // job duplicado da mesma etapa (ex.: reconciliação concorrente) não repete o envio
        asManager(() -> execution.reprocess(campaign.id()));
        system(() -> {
            jdbcTemplate.update("""
                    insert into shared.processing_job (id, tenant_id, type, payload, status, run_at, attempts, created_at)
                    values (?, ?, 'campaign.step', cast(? as jsonb), 'PENDING', now(), 0, now())
                    """, Ids.newId(), tenant, "{\"enrollmentId\":\"" + failed.id() + "\",\"stepCode\":\"D0\",\"iteration\":1}");
            return null;
        });
        runEngine();
        EnrollmentView done = enrollmentOf(campaign.id(), kase.id());
        assertThat(done.status()).isEqualTo("ENDED");
        assertThat(count("select count(*) from communication.message where campaign_execution_id = ?", done.id()))
                .isEqualTo(1);
        assertThat(count("select count(*) from campaign.step_execution where enrollment_id = ? and outcome = 'EXECUTED'",
                done.id())).isEqualTo(1);
        assertThat(count("select count(*) from campaign.step_execution where enrollment_id = ? and outcome = 'FAILED'",
                done.id())).isEqualTo(1);
    }

    @Test
    void publishingRejectsUnknownTemplatesAndOffersWithoutAdapter() {
        CampaignView campaign = asManager(() -> campaigns.create(new br.com.gestorcobranca.campaign.application.port.in
                .CampaignUseCases.CreateCampaignCommand("INVALIDA", creditorA, null, settings(segmentId, null, 1))));
        asManager(() -> campaigns.saveDraft(campaign.id(), cadence(null, new br.com.gestorcobranca.campaign.domain.model
                .CadenceStep("X", "X", 0, null, null, new br.com.gestorcobranca.campaign.domain.model.StepAction(
                br.com.gestorcobranca.campaign.domain.model.ActionType.SEND_MESSAGE, "WHATSAPP", "INEXISTENTE", null, null,
                null, null, null, null), null, null))));
        assertThatThrownBy(() -> asManager(() -> campaigns.publishDraft(campaign.id())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("INEXISTENTE");
        asManager(() -> campaigns.saveDraft(campaign.id(), cadence(null, new br.com.gestorcobranca.campaign.domain.model
                .CadenceStep("O", "O", 0, null, null, new br.com.gestorcobranca.campaign.domain.model.StepAction(
                br.com.gestorcobranca.campaign.domain.model.ActionType.OFFER, "WHATSAPP", "COBRANCA", VARIABLES, null,
                null, null, null, new br.com.gestorcobranca.campaign.domain.model.OfferSpec(1, 0, 3)), null, null))));
        assertThatThrownBy(() -> asManager(() -> campaigns.publishDraft(campaign.id())))
                .hasMessageContaining("Oferta");
        // variáveis precisam cobrir as do template
        asManager(() -> campaigns.saveDraft(campaign.id(), cadence(null, new br.com.gestorcobranca.campaign.domain.model
                .CadenceStep("V", "V", 0, null, null, new br.com.gestorcobranca.campaign.domain.model.StepAction(
                br.com.gestorcobranca.campaign.domain.model.ActionType.SEND_MESSAGE, "WHATSAPP", "COBRANCA",
                java.util.Map.of("nome", "DEBTOR_FIRST_NAME"), null, null, null, null, null), null, null))));
        assertThatThrownBy(() -> asManager(() -> campaigns.publishDraft(campaign.id())))
                .hasMessageContaining("valor");
        assertThat(Set.of("DRAFT")).contains(asManager(() -> campaigns.get(campaign.id())).status());
    }
}
