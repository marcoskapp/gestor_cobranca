package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AllowedFacts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardrails de saída: toda resposta do assistente passa aqui antes de ir ao communication (que ainda
 * aplica o porteiro de contato). Recusa:
 * <ul>
 *   <li>valor em reais, percentual, data ou número de parcelas que não veio das ferramentas
 *       ({@link AllowedFacts}) — "a IA conversa, o motor decide";</li>
 *   <li>promessa fora da política (garantias, perdão da dívida, condição "especial");</li>
 *   <li>ameaça, constrangimento ou exposição; linguagem abusiva;</li>
 *   <li>conselho jurídico;</li>
 *   <li>menção a negativação sem autorização da política;</li>
 *   <li>passar-se por humano (transparência);</li>
 *   <li>dado pessoal (documento, e-mail, telefone) — inclusive de terceiros;</li>
 *   <li>temas proibidos pela política; resposta vazia ou longa demais.</li>
 * </ul>
 * Puro e determinístico; os códigos devolvidos vão para a trilha do turno e para a métrica
 * {@code assistant.guardrails}.
 */
public final class ResponseGuard {

    public static final int MAX_LENGTH = 1000;

    private static final Pattern MONEY = Pattern.compile(
            "R\\$\\s*(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)|(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})*,\\d{2})(?![\\d])");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:,\\d{1,2})?)\\s?(?:%|por cento)");
    private static final Pattern DATE = Pattern.compile("(?<![\\d])(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?![\\d])");
    private static final Pattern INSTALLMENTS = Pattern.compile(
            "(?<![\\d,.])(\\d{1,3})\\s?(?:x(?![a-z])|vezes|parcelas?|pagamentos?|prestac)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Pattern> FORBIDDEN = Map.of(
            "THREAT", words("(vamos|vou|iremos|ira) (te )?(processar|executar|penhorar|bloquear)", "prisao", "pres[oa]",
                    "policia", "cadeia", "oficial de justica", "busca e apreensao", "ultimo aviso", "consequencias graves",
                    "voce vai se arrepender"),
            "ABUSIVE_LANGUAGE", words("idiota", "imbecil", "burr[oa]", "caloteir[oa]", "vagabund[oa]", "safad[oa]",
                    "mentiros[oa]", "irresponsavel", "vergonhoso"),
            "EMBARRASSMENT", words("(seus|sua|seu) (vizinhos|familia|familiares|empregador|chefe|colegas) (vao|vai|irao|ira) saber",
                    "vamos (avisar|contar|ligar para) (sua|seu|seus) (familia|empregador|chefe|vizinhos)", "vergonha"),
            "LEGAL_ADVICE", words("(recomendo|aconselho|sugiro) (que voce )?(procure|contrate|entre|acione)",
                    "voce tem direito", "seu direito e", "prescri\\w*", "nao precisa pagar", "a lei (diz|garante|permite)",
                    "entre na justica", "procure um advogado"),
            "PROMISE_OUT_OF_POLICY", words("garant(o|imos|ido)", "prometo", "perdoa(r|mos|da)? (a|sua) divida",
                    "divida (sera )?perdoada", "limpar (o )?seu nome", "condicao especial", "so hoje", "ultima chance",
                    "desconto (total|de 100)"),
            "HUMAN_IMPERSONATION", words("sou (um|uma) (humano|humana|pessoa|atendente)", "nao sou (um|uma) (robo|assistente)",
                    "sou de carne e osso"),
            "CREDIT_BUREAU", words("serasa", "spc", "negativa\\w*", "nome sujo", "protest\\w*", "cadastro de inadimplentes"));

    /** Resultado: vazio = aprovado. */
    public record Result(List<String> violations) {

        public Result {
            violations = List.copyOf(violations);
        }

        public boolean approved() {
            return violations.isEmpty();
        }
    }

    /**
     * @param forbiddenTopics          termos proibidos pela política
     * @param allowCreditBureauMention a política autoriza mencionar negativação
     */
    public record Config(List<String> forbiddenTopics, boolean allowCreditBureauMention) {
    }

    private ResponseGuard() {
    }

    public static Result check(String text, AllowedFacts facts, Config config) {
        Set<String> violations = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new Result(List.of("EMPTY"));
        }
        if (text.length() > MAX_LENGTH) {
            violations.add("TOO_LONG");
        }
        AllowedFacts f = facts == null ? AllowedFacts.EMPTY : facts;
        Matcher money = MONEY.matcher(text);
        List<int[]> moneySpans = new ArrayList<>();
        while (money.find()) {
            String raw = money.group(1) != null ? money.group(1) : money.group(2);
            moneySpans.add(new int[]{money.start(), money.end()});
            if (!f.allowsAmount(parse(raw))) {
                violations.add("VALUE_NOT_FROM_ENGINE");
            }
        }
        Matcher pct = PERCENT.matcher(text);
        while (pct.find()) {
            if (!f.allowsPercent(parse(pct.group(1)))) {
                violations.add("PERCENT_NOT_FROM_ENGINE");
            }
        }
        Matcher date = DATE.matcher(text);
        while (date.find()) {
            int day = Integer.parseInt(date.group(1));
            int month = Integer.parseInt(date.group(2));
            Integer year = date.group(3) == null ? null : year(date.group(3));
            if (!f.allowsDate(day, month, year)) {
                violations.add("DATE_NOT_FROM_ENGINE");
            }
        }
        Matcher inst = INSTALLMENTS.matcher(text);
        while (inst.find()) {
            int start = inst.start();
            boolean insideMoney = moneySpans.stream().anyMatch(s -> start >= s[0] && start < s[1]);
            if (!insideMoney && !f.allowsInstallments(Integer.parseInt(inst.group(1)))) {
                violations.add("INSTALLMENTS_NOT_FROM_ENGINE");
            }
        }
        String normalized = TextNormalizer.normalize(text);
        FORBIDDEN.forEach((code, pattern) -> {
            if (pattern.matcher(normalized).find()
                    && !(code.equals("CREDIT_BUREAU") && config != null && config.allowCreditBureauMention())) {
                violations.add(code);
            }
        });
        if (PersonalDataRedactor.containsPersonalData(text)) {
            violations.add("PERSONAL_DATA");
        }
        if (config != null && config.forbiddenTopics() != null) {
            for (String topic : config.forbiddenTopics()) {
                if (words(Pattern.quote(TextNormalizer.normalize(topic))).matcher(normalized).find()) {
                    violations.add("FORBIDDEN_TOPIC");
                }
            }
        }
        List<String> ordered = new ArrayList<>(violations);
        java.util.Collections.sort(ordered);
        return new Result(ordered);
    }

    private static BigDecimal parse(String br) {
        return new BigDecimal(br.replace(".", "").replace(',', '.'));
    }

    private static int year(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private static Pattern words(String... alternatives) {
        return Pattern.compile("(?<![a-z0-9])(?:" + String.join("|", alternatives) + ")(?![a-z0-9])");
    }
}
