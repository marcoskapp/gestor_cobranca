package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.ExtraTrigger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gatilhos obrigatórios de transferência detectados de forma determinística no texto do devedor,
 * independentes do modelo (docs/negocio/assistente-ia.md §4, camada 3): contestação, alegação de
 * pagamento, fraude, falecimento, vulnerabilidade/superendividamento, menção a advogado, Procon ou
 * processo, reclamação, agressividade, pedido de humano e pedido de descadastro — além dos gatilhos
 * adicionais da política. Os obrigatórios não podem ser desligados. Falso positivo leva a um humano
 * (conservador); falso negativo ainda pode ser apanhado pelo sinal do modelo.
 */
public final class HandoffDetector {

    /** Prioridade de roteamento quando há mais de um gatilho (o primeiro define o perfil exigido). */
    private static final List<HandoffReason> PRIORITY = List.of(HandoffReason.DECEASED, HandoffReason.FRAUD_SUSPECTED,
            HandoffReason.LEGAL_MENTION, HandoffReason.COMPLAINT, HandoffReason.DISPUTE, HandoffReason.PAYMENT_CLAIM,
            HandoffReason.VULNERABILITY, HandoffReason.ABUSIVE, HandoffReason.OPT_OUT, HandoffReason.HUMAN_REQUESTED,
            HandoffReason.POLICY_TRIGGER);

    private static final Map<HandoffReason, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put(HandoffReason.DISPUTE, words("nao reconheco", "desconheco", "nao e minha", "nao e meu",
                "nunca (fiz|contratei|comprei|tive|assinei)", "nao devo", "contest\\w*", "cobranca indevida",
                "divida indevida", "nao fiz (essa|esta|nenhuma) (compra|divida)", "isso nao e meu", "engano"));
        RULES.put(HandoffReason.PAYMENT_CLAIM, words("ja paguei", "ja quitei", "ja (esta|foi|estava) pag[oa]",
                "ja (esta|foi) quitad[oa]", "paguei (essa|esta|a|o) (divida|conta|fatura|boleto)", "tenho o comprovante"));
        RULES.put(HandoffReason.FRAUD_SUSPECTED, words("fraude\\w*", "golpe", "clonad[oa]s?", "roubaram",
                "usaram (meus dados|meu nome|meu cpf|meus documentos)", "falsific\\w*"));
        RULES.put(HandoffReason.DECEASED, words("falec\\w*", "morreu", "morte d[oa]", "obito", "faleceu"));
        RULES.put(HandoffReason.VULNERABILITY, words("desempregad[oa]", "perdi (o|meu) emprego", "sem emprego",
                "doenca", "cancer", "internad[oa]", "hospital\\w*", "tratamento (medico|de saude)", "quimioterapia",
                "superendividad[oa]", "muito endividad[oa]", "nao tenho (como|condicoes de) (comer|sobreviver)",
                "passando fome", "minimo existencial", "depressao", "sou idos[oa]", "deficiencia"));
        RULES.put(HandoffReason.LEGAL_MENTION, words("advogad[oa]s?", "procon", "processo", "processar", "justica",
                "juizado", "judicial", "defensoria", "danos morais", "tribunal", "acao contra"));
        RULES.put(HandoffReason.COMPLAINT, words("reclame aqui", "reclamacao", "reclamar", "ouvidoria",
                "consumidor\\.gov", "banco central", "bacen", "denunciar"));
        RULES.put(HandoffReason.ABUSIVE, words("idiota", "imbecil", "burr[oa]", "otari[oa]", "vagabund[oa]", "lixo",
                "palhac[oa]", "desgracad[oa]", "filh[oa] da puta", "fdp", "porra", "caralho", "merda", "vai se f\\w*",
                "vou te matar", "te matar", "ameac\\w*", "cala a boca"));
        RULES.put(HandoffReason.OPT_OUT, words("nao (quero|desejo) mais receber", "nao quero receber",
                "pare(m)? de (me )?(mandar|enviar|ligar|cobrar)", "nao me (mande|envie|ligue|procure) mais",
                "descadastr\\w*", "remov(a|am|er) (o )?meu (numero|contato|telefone)"));
        RULES.put(HandoffReason.HUMAN_REQUESTED, words("humano", "atendente", "pessoa de verdade", "pessoa real",
                "falar com (alguem|uma pessoa|gente|um gerente|o gerente|um supervisor)", "quero (um|o) gerente",
                "nao quero falar com (robo|maquina|bot)"));
    }

    /** Palavras de descadastro que só valem como mensagem inteira (como no communication). */
    private static final java.util.Set<String> OPT_OUT_WORDS = java.util.Set.of("sair", "parar", "stop", "cancelar",
            "descadastrar");

    private static final Pattern NEGATED_ACCEPTANCE = words("nao aceito", "nao concordo", "nao quero",
            "nao (vou|posso) (aceitar|fechar)", "ainda nao");
    private static final Pattern ACCEPTANCE = words("aceito", "aceitar", "concordo", "fechado", "pode fechar",
            "pode ser", "quero (essa|esse|esta|este) (opcao|proposta|acordo)", "fechar o acordo", "confirmo", "de acordo",
            "sim,? quero", "vamos fechar");

    /** Gatilho encontrado e a regra (expressão) que o acionou. */
    public record Detection(HandoffReason reason, String rule) {
    }

    private HandoffDetector() {
    }

    /** Todos os gatilhos presentes, na ordem de prioridade. */
    public static List<Detection> detect(String text, List<ExtraTrigger> extras) {
        String normalized = TextNormalizer.normalize(text);
        List<Detection> found = new ArrayList<>();
        if (normalized.isEmpty()) {
            return found;
        }
        RULES.forEach((reason, pattern) -> {
            var m = pattern.matcher(normalized);
            if (m.find()) {
                found.add(new Detection(reason, m.group()));
            }
        });
        String bare = normalized.replaceAll("[^a-z ]", "").trim();
        if (OPT_OUT_WORDS.contains(bare) && found.stream().noneMatch(d -> d.reason() == HandoffReason.OPT_OUT)) {
            found.add(new Detection(HandoffReason.OPT_OUT, bare));
        }
        for (ExtraTrigger extra : extras == null ? List.<ExtraTrigger>of() : extras) {
            Pattern p = words(Pattern.quote(TextNormalizer.normalize(extra.keyword())));
            if (p.matcher(normalized).find()) {
                found.add(new Detection(HandoffReason.parse(extra.reasonCode()).orElse(HandoffReason.POLICY_TRIGGER),
                        "policy:" + extra.keyword()));
            }
        }
        found.sort(Comparator.comparingInt(d -> {
            int i = PRIORITY.indexOf(d.reason());
            return i < 0 ? PRIORITY.size() : i;
        }));
        return found;
    }

    /** Gatilho de maior prioridade, se houver. */
    public static Optional<Detection> first(String text, List<ExtraTrigger> extras) {
        return detect(text, extras).stream().findFirst();
    }

    /** O devedor manifesta aceite explícito (e não negado) na mensagem. */
    public static boolean explicitAcceptance(String text) {
        String normalized = TextNormalizer.normalize(text);
        return ACCEPTANCE.matcher(normalized).find() && !NEGATED_ACCEPTANCE.matcher(normalized).find();
    }

    private static Pattern words(String... alternatives) {
        return Pattern.compile("(?<![a-z0-9])(?:" + String.join("|", alternatives) + ")(?![a-z0-9])");
    }
}
