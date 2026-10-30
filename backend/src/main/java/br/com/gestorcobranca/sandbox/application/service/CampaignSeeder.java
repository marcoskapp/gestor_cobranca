package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignFilter;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.CreateSegmentCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.SegmentFilter;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.SendWindow;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.campaign.domain.model.StepCondition;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Segmentos e réguas de cobrança do 1º credor, montados pela área de estratégia: régua de atraso inicial (abertura no
 * WhatsApp → retomada sem resposta → tarefa de ligação) e régua de lembrete de parcela de acordo, ambas ativas. As
 * mensagens saem pelo simulador e respeitam a janela e os limites da política de contato (fora do horário ficam
 * adiadas, como na operação).
 */
@SandboxSeedComponent
class CampaignSeeder {

    private static final SendWindow BUSINESS_HOURS = new SendWindow(LocalTime.of(9, 0), LocalTime.of(19, 0),
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                    DayOfWeek.SATURDAY));

    private final SegmentUseCases segments;
    private final CampaignUseCases campaigns;
    private final CampaignExecutionUseCases execution;
    private final SeedRuntime rt;

    CampaignSeeder(SegmentUseCases segments, CampaignUseCases campaigns, CampaignExecutionUseCases execution,
                   SeedRuntime rt) {
        this.segments = segments;
        this.campaigns = campaigns;
        this.execution = execution;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        UUID creditorId = seed.creditorId(0);
        String prefix = seed.creditor(0).prefix();
        rt.optional("régua de atraso inicial", () -> {
            UUID segment = segment(seed, creditorId, "ATRASO_INICIAL", "Atraso de 15 a 90 dias, sem contato efetivo",
                    new SegmentCriteria(Set.of(seed.portfolioId(0, 0)), 15, 90, null, null, Set.of("OPEN"), true,
                            "WHATSAPP", null, null, false, null, null, null, null, null, null, null, null, null, null));
            campaign(seed, "REGUA_" + prefix + "_ATRASO", new CampaignSettings("Régua de atraso inicial",
                    "Abertura sem expor a dívida, retomada e tarefa de ligação (sandbox)", segment,
                    "EXTRAJUDICIAL_COLLECTION", 10, "COBRANCA", null, 7, null, 24, true),
                    new CadenceDefinition(ReplyTransition.STOP, null, null, List.of(
                            new CadenceStep("ABERTURA", "Abertura no WhatsApp", 0, BUSINESS_HOURS, null,
                                    message("COBRANCA_ABERTURA", Map.of("nome", "DEBTOR_FIRST_NAME")),
                                    ReplyTransition.MOVE_TO_QUEUE, null),
                            new CadenceStep("RETOMADA", "Retomada sem resposta", 3 * 24 * 60, BUSINESS_HOURS,
                                    new StepCondition(null, true, false, false),
                                    message("COBRANCA_RETOMADA", Map.of("nome", "DEBTOR_FIRST_NAME")),
                                    ReplyTransition.MOVE_TO_QUEUE, null),
                            new CadenceStep("LIGACAO", "Tarefa de ligação", 4 * 24 * 60, null,
                                    new StepCondition(null, true, true, false),
                                    new StepAction(ActionType.CREATE_TASK, null, null, null, "CALLBACK", 240,
                                            "Ligar: régua sem resposta no WhatsApp", null, null),
                                    null, null))));
        });
        rt.optional("régua de lembrete de parcela", () -> {
            UUID segment = segment(seed, creditorId, "LEMBRETE_PARCELA", "Acordo em vigor com parcela vencendo em 3 dias",
                    new SegmentCriteria(null, null, null, null, null, null, null, "WHATSAPP", null, null, null, null, null,
                            null, true, null, null, null, null, 3, 3));
            campaign(seed, "REGUA_" + prefix + "_LEMBRETE", new CampaignSettings("Lembrete de parcela do acordo",
                    "Lembrete três dias antes do vencimento (sandbox)", segment, "AGREEMENT_FOLLOW_UP", 20,
                    null, 20, 7, null, 24, true),
                    new CadenceDefinition(ReplyTransition.STOP, null, null, List.of(
                            new CadenceStep("LEMBRETE", "Lembrete de parcela", 0, BUSINESS_HOURS, null,
                                    message("ACORDO_LEMBRETE_PARCELA", Map.of("nome", "DEBTOR_FIRST_NAME",
                                            "credor", "CREDITOR_NAME", "valor", "NEXT_INSTALLMENT_AMOUNT",
                                            "vencimento", "NEXT_INSTALLMENT_DUE_DATE")),
                                    ReplyTransition.MOVE_TO_QUEUE, null))));
        });
    }

    private UUID segment(TenantSeed seed, UUID creditorId, String code, String name, SegmentCriteria criteria) {
        Actor estrategia = seed.actor("estrategia");
        return rt.as(estrategia, () -> segments.search(new SegmentFilter(creditorId, null), PageQuery.of(0, 50)))
                .content().stream().filter(s -> code.equals(s.code())).map(SegmentUseCases.SegmentView::id).findFirst()
                .orElseGet(() -> rt.as(estrategia, () -> segments.create(new CreateSegmentCommand(creditorId, code, name,
                        "Segmento de demonstração (sandbox)", criteria))).id());
    }

    private void campaign(TenantSeed seed, String code, CampaignSettings settings, CadenceDefinition cadence) {
        Actor estrategia = seed.actor("estrategia");
        UUID creditorId = seed.creditorId(0);
        Optional<CampaignView> existing = rt.as(estrategia, () -> campaigns.search(new CampaignFilter(creditorId, null),
                PageQuery.of(0, 50))).content().stream().filter(c -> code.equals(c.code())).findFirst();
        CampaignView campaign = existing.orElseGet(() -> rt.as(estrategia, () -> campaigns.create(
                new CreateCampaignCommand(code, creditorId, null, settings))));
        if (campaign.publishedCadenceId() == null) {
            if (!campaign.hasDraft()) {
                rt.as(estrategia, () -> campaigns.saveDraft(campaign.id(), cadence));
            }
            rt.as(estrategia, () -> campaigns.publishDraft(campaign.id()));
        }
        if ("DRAFT".equals(rt.as(estrategia, () -> campaigns.get(campaign.id())).status())) {
            rt.as(estrategia, () -> execution.activate(campaign.id()));
        }
    }

    private static StepAction message(String template, Map<String, String> variables) {
        return new StepAction(ActionType.SEND_MESSAGE, "WHATSAPP", template, variables, null, null, null, null, null);
    }
}
