package br.com.gestorcobranca.campaign.domain;

import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.StepAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dados de apoio dos testes unitários do módulo. */
public final class CampaignFixtures {

    public static final UUID CREDITOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private CampaignFixtures() {
    }

    public static AudienceSnapshot snapshot(String status, boolean eligible, String balance, LocalDate oldestDue) {
        return new AudienceSnapshot(UUID.randomUUID(), UUID.randomUUID(), CREDITOR, PORTFOLIO, status, null, eligible,
                eligible ? List.of() : List.of("PORTFOLIO_NOT_OPERABLE"), List.of("WHATSAPP", "EMAIL"),
                new BigDecimal(balance), new BigDecimal(balance), oldestDue, "QUEUE", 0, 0, null, null, null, 0, 1,
                Instant.parse("2026-10-23T12:00:00Z"), null);
    }

    public static SegmentCriteria criteria(Integer minDays, Integer maxDays, String minBalance, Set<String> statuses) {
        return new SegmentCriteria(null, minDays, maxDays, minBalance == null ? null : new BigDecimal(minBalance), null,
                statuses, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static CadenceStep message(String code, int wait, ReplyTransition onReply, String target) {
        return new CadenceStep(code, code, wait, null, null,
                new StepAction(ActionType.SEND_MESSAGE, "WHATSAPP", "COBRANCA_1", Map.of("nome", "DEBTOR_FIRST_NAME"),
                        null, null, null, null, null), onReply, target);
    }

    public static CadenceStep task(String code) {
        return new CadenceStep(code, code, 0, null, null,
                new StepAction(ActionType.CREATE_TASK, null, null, null, "CALLBACK", 60, null, null, null), null, null);
    }
}
