package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.ModelRouting;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.service.ModelTierSelector.Reason;
import br.com.gestorcobranca.assistant.domain.service.ModelTierSelector.Selection;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Nível de modelo por etapa: negociação, proposta, aceite e Pix no avançado; o resto no padrão. */
class ModelTierSelectorTest {

    private static final JsonCodec JSON = new JsonCodec(JsonMapper.builder().build());
    private static final PolicySettings BY_STAGE = PolicySettings.defaults(AssistantMode.AUTONOMOUS);

    private static PolicySettings advancedOnly() {
        PolicySettings d = BY_STAGE;
        return new PolicySettings(d.mode(), d.channels(), d.serviceHours(), d.authorityProfileId(), d.promptCode(),
                d.promptVersion(), d.tone(), d.maxTurns(), d.maxMisunderstandings(), d.minConfidence(),
                d.requireIdentityVerification(), d.maxIdentityAttempts(), d.allowedTopics(), d.forbiddenTopics(),
                d.extraTriggers(), d.requiredProfiles(), d.identificationMessage(), d.handoffMessage(), d.fallbackMessage(),
                d.approvalMessage(), d.limits(), d.reviewSampleRate(), d.allowCreditBureauMention(), d.pixInConversation(),
                ModelRouting.ADVANCED_ONLY).normalized();
    }

    private static SessionMemory withOffer() {
        return SessionMemory.EMPTY.withOffers(UUID.randomUUID(), List.of(new SessionMemory.OfferMemo("O1", UUID.randomUUID(),
                0, 1, new BigDecimal("800.00"), null, null, LocalDate.of(2026, 11, 5), BigDecimal.ZERO, List.of(), true,
                true)));
    }

    @Test
    void generalConversationIdentificationAndIntentStayStandard() {
        assertThat(ModelTierSelector.select(BY_STAGE, null, SessionMemory.EMPTY))
                .isEqualTo(new Selection(ModelTier.STANDARD, Reason.DEFAULT));
        for (String intent : List.of("GREETING", "IDENTITY", "BALANCE", "QUESTION", "PROMISE", "CALLBACK", "OTHER")) {
            assertThat(ModelTierSelector.select(BY_STAGE, intent, SessionMemory.EMPTY).tier()).as(intent)
                    .isEqualTo(ModelTier.STANDARD);
        }
        assertThat(BY_STAGE.modelRouting()).isEqualTo(ModelRouting.BY_STAGE);
    }

    @Test
    void negotiationAcceptanceAndPixGoAdvanced() {
        for (String intent : List.of("NEGOTIATE", "ACCEPT", "PAYMENT")) {
            assertThat(ModelTierSelector.select(BY_STAGE, intent, SessionMemory.EMPTY))
                    .isEqualTo(new Selection(ModelTier.ADVANCED, Reason.INTENT));
        }
        // antes de o modelo registrar a intenção do turno, vale a do turno anterior
        assertThat(ModelTierSelector.select(BY_STAGE, null, SessionMemory.EMPTY.withIntent("NEGOTIATE")).tier())
                .isEqualTo(ModelTier.ADVANCED);
        // depois de registrada, a do turno manda
        assertThat(ModelTierSelector.select(BY_STAGE, "GREETING", SessionMemory.EMPTY.withIntent("NEGOTIATE")).tier())
                .isEqualTo(ModelTier.STANDARD);
    }

    @Test
    void onceTheEngineReturnedOffersProposalOrInstallmentsTheStageStaysAdvanced() {
        assertThat(ModelTierSelector.select(BY_STAGE, "GREETING", withOffer()))
                .isEqualTo(new Selection(ModelTier.ADVANCED, Reason.STAGE));
        SessionMemory proposal = SessionMemory.EMPTY.withProposal(new SessionMemory.ProposalMemo("P1", UUID.randomUUID(),
                "DRAFT", 1, new BigDecimal("800.00"), null));
        assertThat(ModelTierSelector.select(BY_STAGE, "QUESTION", proposal).tier()).isEqualTo(ModelTier.ADVANCED);
        SessionMemory installments = SessionMemory.EMPTY.withInstallments(List.of(new SessionMemory.InstallmentMemo("I1",
                UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.of(2026, 11, 5), new BigDecimal("100.00"), "OPEN",
                "HUMAN")));
        assertThat(ModelTierSelector.select(BY_STAGE, "QUESTION", installments).tier()).isEqualTo(ModelTier.ADVANCED);
        // nova etapa (memória zerada): volta ao padrão
        assertThat(ModelTierSelector.select(BY_STAGE, "GREETING", withOffer().reset()).tier()).isEqualTo(ModelTier.STANDARD);
    }

    @Test
    void policyCanForceAdvancedEverywhere() {
        assertThat(ModelTierSelector.select(advancedOnly(), "GREETING", SessionMemory.EMPTY))
                .isEqualTo(new Selection(ModelTier.ADVANCED, Reason.POLICY));
    }

    @Test
    void policiesStoredBeforeTheOptionReadAsByStage() {
        String current = JSON.toJson(BY_STAGE);
        assertThat(current).contains("\"modelRouting\":\"BY_STAGE\"");
        // jsonb gravado antes da opção: sem o campo
        PolicySettings stored = JSON.fromJson(current.replace(",\"modelRouting\":\"BY_STAGE\"", ""), PolicySettings.class);
        assertThat(stored.modelRouting()).isNull();
        assertThat(stored.routing()).isEqualTo(ModelRouting.BY_STAGE);
        assertThat(ModelTierSelector.select(stored, null, SessionMemory.EMPTY).tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(stored.normalized().modelRouting()).isEqualTo(ModelRouting.BY_STAGE);
        assertThat(JSON.fromJson(JSON.toJson(advancedOnly()), PolicySettings.class).routing())
                .isEqualTo(ModelRouting.ADVANCED_ONLY);
    }
}
