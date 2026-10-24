package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.IdentityStatus;
import br.com.gestorcobranca.assistant.domain.model.PixCriterion;
import br.com.gestorcobranca.assistant.domain.model.PixDecision;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.PixInConversation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Critérios objetivos para o assistente gerar um Pix na conversa — decisão do responsável: "se a
 * conversa foi amigável e a conciliação com o devedor tranquila, o Pix pode ser gerado pelo assistente
 * IA". Avaliados <strong>no servidor</strong> a partir de fatos do motor, da sessão e do texto do devedor;
 * o modelo só escolhe a parcela por referência opaca e nunca informa valor. Todos os critérios são
 * avaliados (a trilha mostra cada um); a recusa usa o primeiro que falhou, na ordem de {@link PixCriterion}.
 * Puro e determinístico.
 */
public final class PixEligibility {

    /** Origens de acordo formalizado por um humano (a do modo com aprovação). */
    public static final Set<String> HUMAN_ORIGINS = Set.of("HUMAN", "HUMAN_COPILOT", "ASSISTANT_HANDOFF_NEGOTIATED",
            "ASSISTANT_HANDOFF_CONFIRMED");
    public static final Set<String> ACTIVE_AGREEMENT = Set.of("FORMALIZED", "IN_PAYMENT");
    public static final Set<String> OPEN_INSTALLMENT = Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");

    /**
     * Acordo e parcela escolhidos (devolvidos pelo negotiation).
     *
     * @param status            situação do acordo
     * @param origin            origem da atribuição (quem formalizou)
     * @param installmentStatus situação da parcela
     * @param amount            saldo em aberto da parcela (= valor do Pix)
     */
    public record Agreement(String status, String origin, String installmentStatus, BigDecimal amount) {
    }

    /**
     * @param settings        parâmetros da política
     * @param identity        identidade na sessão
     * @param agreement       acordo/parcela (nulo = não encontrado)
     * @param sameParties     acordo do devedor, credor e carteira da conversa
     * @param sessionRisks    motivos de risco registrados na sessão dentro da janela da política e
     *                        gatilhos detectados na mensagem atual
     * @param guardrailInSession guardrail acionado na sessão dentro da janela
     * @param openDispute     contestação aberta ou em análise no caso
     * @param explicitRequest pedido/aceite explícito de pagar agora na mensagem do devedor
     * @param issuedToday     Pix já enviados (ou a enviar) na conversa hoje
     */
    public record Facts(PixInConversation settings, AssistantMode mode, IdentityStatus identity, Agreement agreement,
                        boolean sameParties, Set<String> sessionRisks, boolean guardrailInSession, boolean openDispute,
                        boolean explicitRequest, int issuedToday) {

        public Facts {
            sessionRisks = sessionRisks == null ? Set.of() : Set.copyOf(sessionRisks);
        }
    }

    /** @param detail observação sem dados pessoais (códigos, situação, contagens) */
    public record Check(PixCriterion criterion, boolean passed, String detail) {
    }

    /** @param denial primeiro critério que falhou (vazio = permitido) */
    public record Evaluation(PixDecision decision, List<Check> checks, Optional<PixCriterion> denial) {

        public Evaluation {
            checks = List.copyOf(checks);
        }

        public boolean allowed() {
            return denial.isEmpty();
        }

        public Optional<String> denialCode() {
            return denial.map(PixCriterion::denialCode);
        }
    }

    private PixEligibility() {
    }

    public static Evaluation evaluate(Facts f) {
        boolean drafts = f.mode() != null && f.mode().drafts();
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(PixCriterion.POLICY_ENABLED, f.settings() != null && f.settings().on(), null));
        boolean modeOk = f.mode() != null && (f.mode().autonomous() || drafts);
        checks.add(new Check(PixCriterion.MODE_ALLOWED, modeOk, f.mode() == null ? null
                : drafts ? f.mode().name() + ": só sugestão ao operador" : f.mode().name()));
        boolean identityOk = drafts || f.identity() == IdentityStatus.VERIFIED;
        checks.add(new Check(PixCriterion.IDENTITY_VERIFIED, identityOk,
                drafts ? "OPERATOR_MEDIATED" : f.identity() == null ? null : f.identity().name()));
        Agreement a = f.agreement();
        checks.add(new Check(PixCriterion.AGREEMENT_FORMALIZED, a != null && ACTIVE_AGREEMENT.contains(a.status()),
                a == null ? "não encontrado" : a.status()));
        checks.add(new Check(PixCriterion.SAME_PARTIES, a != null && f.sameParties(), null));
        checks.add(new Check(PixCriterion.INSTALLMENT_OPEN, a != null && OPEN_INSTALLMENT.contains(a.installmentStatus())
                && a.amount() != null && a.amount().signum() > 0, a == null ? null : a.installmentStatus()));
        boolean approval = f.mode() == AssistantMode.AUTONOMOUS_WITH_APPROVAL;
        checks.add(new Check(PixCriterion.HUMAN_FORMALIZED, !approval || (a != null && HUMAN_ORIGINS.contains(a.origin())),
                approval ? (a == null ? null : a.origin()) : "não exigido no modo"));
        Set<String> risks = new TreeSet<>(f.sessionRisks());
        if (f.guardrailInSession()) {
            risks.add("GUARDRAIL");
        }
        checks.add(new Check(PixCriterion.NO_CONVERSATION_RISK, risks.isEmpty(),
                risks.isEmpty() ? null : String.join(",", risks)));
        checks.add(new Check(PixCriterion.NO_OPEN_DISPUTE, !f.openDispute(), null));
        checks.add(new Check(PixCriterion.EXPLICIT_REQUEST, f.explicitRequest(), null));
        BigDecimal max = f.settings() == null ? null : f.settings().maxAmount();
        checks.add(new Check(PixCriterion.AMOUNT_WITHIN_LIMIT, a != null && a.amount() != null && max != null
                && a.amount().compareTo(max) <= 0, max == null ? null : "limite " + max.toPlainString()));
        int perDay = f.settings() == null || f.settings().maxPerConversationPerDay() == null ? 0
                : f.settings().maxPerConversationPerDay();
        checks.add(new Check(PixCriterion.DAILY_LIMIT, f.issuedToday() < perDay, f.issuedToday() + " de " + perDay));
        Optional<PixCriterion> denial = checks.stream().filter(c -> !c.passed()).map(Check::criterion).findFirst();
        PixDecision decision = denial.isPresent() ? PixDecision.DENIED : drafts ? PixDecision.SUGGESTED : PixDecision.ISSUED;
        return new Evaluation(decision, checks, denial);
    }
}
