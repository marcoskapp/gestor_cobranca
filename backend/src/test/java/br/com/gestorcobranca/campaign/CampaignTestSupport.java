package br.com.gestorcobranca.campaign;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.CreateSegmentCommand;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.CommunicationTestSupport;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cenário dos testes de integração do módulo: o mesmo do communication (assessoria, credores A e B,
 * operadores, política de contato sem restrição de horário, conta WhatsApp sandbox) e template
 * {@code COBRANCA} publicado na assessoria. O motor roda sob demanda ({@link #runEngine()}): jobs do
 * campaign e do communication e os consumidores de eventos, até não haver trabalho pronto.
 */
public abstract class CampaignTestSupport extends CommunicationTestSupport {

    protected static final Map<String, String> VARIABLES = Map.of("nome", "DEBTOR_FIRST_NAME", "valor", "OPEN_BALANCE");

    @Autowired
    protected SegmentUseCases segments;
    @Autowired
    protected CampaignUseCases campaigns;
    @Autowired
    protected CampaignExecutionUseCases execution;
    @Autowired
    protected ExperimentUseCases experiments;

    protected void provisionCampaigns(String prefix) {
        provision(prefix);
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
    }

    /** Devedor com celular próprio (WhatsApp): devedores distintos não compartilham conversa. */
    protected UUID debtorWithPhone(String cpf, int index) {
        String phone = "(11) 9%04d-%04d".formatted(1000 + index, 2000 + index);
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, "Devedor " + cpf.substring(0, 3),
                LocalDate.of(1985, 3, 15), List.of(new ContactRecord("PHONE", phone, "MOBILE", true)))).id());
    }

    /** Caso com um título vencido em {@code dueDate}. */
    protected CaseView loadCase(UUID creditorId, UUID portfolioId, UUID debtorId, String principal, LocalDate dueDate) {
        UUID batch = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId,
                    "T-" + UUID.randomUUID().toString().substring(0, 8), null, 1, dueDate, new BigDecimal(principal),
                    null, null), batch);
            ingestion.completeImport(new ImportSummary(batch, creditorId, portfolioId, 1, 0, 1));
            return null;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        JobDrain.drain(applicationContext, jdbcTemplate, "collection.");
        CaseView kase = system(() -> cases.findActive(debtorId, portfolioId)).orElseThrow(() -> new AssertionError("sem caso"));
        awaitAudience(kase.id());
        return kase;
    }

    /** O read model de audiência recebeu o caso (consumo do CaseStatusChanged). */
    protected void awaitAudience(UUID caseId) {
        awaitTrue("caso no read model de audiência",
                () -> count("select count(*) from campaign.audience_case where case_id = ?", caseId) == 1);
        awaitIdle("campaign.application");
    }

    protected UUID segment(UUID creditorId, String code, SegmentCriteria criteria) {
        return asManager(() -> segments.create(new CreateSegmentCommand(creditorId, code, "Segmento " + code, null,
                criteria))).id();
    }

    protected static CampaignSettings settings(UUID segmentId, String group, int priority) {
        return new CampaignSettings("Campanha", null, segmentId, null, priority, group, null, 7, null, 24, true);
    }

    /** Campanha criada, com régua publicada e ativada. */
    protected CampaignView activeCampaign(String code, UUID creditorId, CampaignSettings settings,
                                          CadenceDefinition cadence) {
        CampaignView view = asManager(() -> campaigns.create(new CreateCampaignCommand(code, creditorId, null, settings)));
        asManager(() -> campaigns.saveDraft(view.id(), cadence));
        asManager(() -> campaigns.publishDraft(view.id()));
        asManager(() -> execution.activate(view.id()));
        return view;
    }

    protected static CadenceStep whatsapp(String code, int waitMinutes, ReplyTransition onReply, String target) {
        return new CadenceStep(code, "Mensagem " + code, waitMinutes, null, null,
                new StepAction(ActionType.SEND_MESSAGE, "WHATSAPP", "COBRANCA", VARIABLES, null, null, null, null, null),
                onReply, target);
    }

    protected static CadenceStep callbackTask(String code, int waitMinutes) {
        return new CadenceStep(code, "Retorno " + code, waitMinutes, null, null,
                new StepAction(ActionType.CREATE_TASK, null, null, null, "CALLBACK", 120, "Ligar após a régua", null,
                        null), null, null);
    }

    protected static CadenceDefinition cadence(ReplyTransition defaultOnReply, CadenceStep... steps) {
        return new CadenceDefinition(defaultOnReply, null, null, List.of(steps));
    }

    /** Executa jobs do campaign e do communication e espera os consumidores, até estabilizar. */
    protected void runEngine() {
        for (int round = 0; round < 10; round++) {
            JobDrain.drain(applicationContext, jdbcTemplate, "campaign.");
            JobDrain.drain(applicationContext, jdbcTemplate, "communication.");
            JobDrain.drain(applicationContext, jdbcTemplate, "collection.");
            awaitIdle("campaign.application");
            awaitIdle("communication");
            awaitIdle("collection");
            Long ready = jdbcTemplate.queryForObject("""
                    select count(*) from shared.processing_job
                     where tenant_id = ? and (type like 'campaign.%' or type like 'communication.%' or type like 'collection.%')
                       and (status = 'RUNNING' or (status = 'PENDING' and run_at <= now()))
                    """, Long.class, tenant);
            if (ready != null && ready == 0) {
                return;
            }
        }
    }

    /** Antecipa a próxima etapa agendada das inscrições da campanha (simula a passagem do tempo). */
    protected void fastForward(UUID campaignId) {
        system(() -> {
            jdbcTemplate.update("""
                    update shared.processing_job set run_at = now()
                     where type = 'campaign.step' and status = 'PENDING'
                       and payload ->> 'enrollmentId' in (select id::text from campaign.enrollment where campaign_id = ?)
                    """, campaignId);
            jdbcTemplate.update("""
                    update campaign.enrollment set next_run_at = now(), step_due_at = now()
                     where campaign_id = ? and status = 'ACTIVE'
                    """, campaignId);
            return null;
        });
    }

    protected EnrollmentView enrollmentOf(UUID campaignId, UUID caseId) {
        return asManager(() -> execution.byCase(caseId)).stream().filter(e -> e.campaignId().equals(campaignId))
                .max(java.util.Comparator.comparing(EnrollmentView::enrolledAt))
                .orElseThrow(() -> new AssertionError("sem inscrição"));
    }

    protected List<EnrollmentView> enrollmentsOf(UUID caseId) {
        return asManager(() -> execution.byCase(caseId));
    }
}
