package br.com.gestorcobranca.assistant;

import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.PolicyView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.CommunicationTestSupport;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cenário dos testes do assistente sobre o do communication: política de cobrança padrão publicada no
 * credor A, devedor (CPF 529…, nascido em 15/03/1985) com caso no credor A, conversa WhatsApp aberta por
 * template do operador X. Mensagens do devedor entram pelo provedor simulado e o turno roda pelo job
 * {@code assistant.turn} com o modelo simulado determinístico (sem rede).
 */
abstract class AssistantTestSupport extends CommunicationTestSupport {

    static final String CPF = "529.982.247-25";
    static final String IDENTITY_ANSWER = "529 e 15/03/1985";

    @Autowired
    AssistantPolicyUseCases assistantPolicies;
    @Autowired
    AssistantTrailUseCases trail;
    @Autowired
    AssistantReviewUseCases reviews;
    @Autowired
    CollectionPolicyUseCases collectionPolicies;
    @Autowired
    AuthorityUseCases authority;
    @Autowired
    ProposalUseCases proposals;

    UUID debtor;
    CaseView kase;
    UUID conversationId;

    void scenario(String prefix, String principal) {
        provision(prefix);
        LocalDate today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        asManager(() -> {
            UUID id = collectionPolicies.createDraft(new CollectionPolicyUseCases.CreatePolicyCommand(creditorA,
                    "Política A", null, StandardPolicyTerms.marketDefault())).id();
            return collectionPolicies.publish(id, today);
        });
        awaitTrue("perfis de alçada semeados",
                () -> count("select count(*) from negotiation.authority_profile where system_profile")
                        == br.com.gestorcobranca.negotiation.domain.model.authority.StandardAuthorityProfiles.all().size());
        debtor = debtor(CPF);
        kase = loadCase(creditorA, portfolioA, debtor, principal);
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
        MessageView first = asOperator(operatorX, () -> messages.send(new SendMessageCommand(null, debtor, creditorA, null,
                "WHATSAPP", null, "COBRANCA", Map.of("nome", "Maria", "valor", principal), null, null, null)));
        drainCommunication();
        conversationId = first.conversationId();
    }

    PolicyView publishPolicy(String code, UUID creditorId, AssistantMode mode, UUID authorityProfileId) {
        PolicySettings base = PolicySettings.defaults(mode);
        PolicySettings settings = new PolicySettings(mode, null, null, authorityProfileId, null, null, null, null, null,
                null, null, null, List.of("negociação", "segunda via"), List.of("sorteio"), null, null, null, null,
                null, null, null, BigDecimal.ONE, null, null);
        assert base.mode() == mode;
        return asAdmin(() -> {
            PolicyView draft = assistantPolicies.createDraft(new CreatePolicyCommand(creditorId, null, code,
                    "Assistente " + code, settings));
            return assistantPolicies.publish(draft.id());
        });
    }

    void handToAssistant() {
        asOperator(operatorX, () -> conversations.returnToAssistant(conversationId, "Assistente conduz"));
    }

    /** Mensagem do devedor → evento → turno do assistente → envio. */
    void inbound(String text) {
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), PHONE_E164, text);
            return null;
        });
        awaitIdle("assistant");
        JobDrain.drain(applicationContext, jdbcTemplate, "assistant.");
        drainCommunication();
        awaitIdle("communication");
    }

    ConversationView conversation() {
        return system(() -> conversations.get(conversationId).conversation());
    }

    /** Mensagens enviadas pelo assistente, da mais antiga para a mais recente. */
    List<MessageView> assistantMessages() {
        return system(() -> messages.listByConversation(conversationId, PageQuery.of(0, 100)).content()).stream()
                .filter(m -> "ASSISTANT".equals(m.senderType()))
                .sorted(Comparator.comparing(MessageView::createdAt)).toList();
    }

    String lastAssistantText() {
        List<MessageView> all = assistantMessages();
        return all.isEmpty() ? null : all.getLast().content();
    }

    /** Resultados dos turnos da conversa (mais antigo primeiro). */
    List<String> outcomes() {
        return system(() -> jdbcTemplate.queryForList("select outcome from assistant.assistant_turn "
                + "where conversation_id = ? order by started_at", String.class, conversationId));
    }

    String lastTurnColumn(String column) {
        return system(() -> jdbcTemplate.queryForObject("select " + column + " from assistant.assistant_turn "
                + "where conversation_id = ? order by started_at desc limit 1", String.class, conversationId));
    }

    UUID assistantProfile(boolean canFormalize) {
        return asAdmin(() -> authority.createProfile(new AuthorityUseCases.ProfileCommand("ASSISTENTE_TESTE",
                "Assistente de teste", null, 1, new BigDecimal("50"), 6, new BigDecimal("3000"), new BigDecimal("10"),
                canFormalize, false, 0, Set.of())).id());
    }
}
