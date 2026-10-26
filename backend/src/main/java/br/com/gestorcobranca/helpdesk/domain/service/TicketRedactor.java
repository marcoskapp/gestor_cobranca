package br.com.gestorcobranca.helpdesk.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máscara determinística do texto que vai para a issue do GitHub (repositório público até a migração): aplicada
 * pelo servidor ao título e ao resumo escritos pela IA, sem depender dela. Dados de devedor nunca vão:
 * <ol>
 *   <li>e-mail, CPF/CNPJ, telefone e CEP ({@link PersonalData});</li>
 *   <li>valores em reais e números com casas decimais → {@code [valor]};</li>
 *   <li>sequências de 5 dígitos ou mais (contrato, título, protocolo, conta) → {@code [número]};</li>
 *   <li>endereço (rua, avenida, travessa… até o número) → {@code [endereço]};</li>
 *   <li>nomes conhecidos da conversa (devedores exibidos nos cartões) → {@code [nome]};</li>
 *   <li>nomes próprios: sequência de duas ou mais palavras com inicial maiúscula das quais ao menos duas não estão no
 *       vocabulário do produto (manual, glossário) nem são palavras comuns → {@code [nome]} — "Estação de Cobrança"
 *       fica, "Maria Souza" sai.</li>
 * </ol>
 * Conservadora de propósito: na dúvida, mascara. O e-mail de quem abriu o chamado é acrescentado depois pelo
 * servidor, fora deste filtro (decisão do responsável).
 */
public final class TicketRedactor {

    private static final Pattern MONEY = Pattern.compile(
            "(?i)(R\\$\\s*-?\\s*\\d[\\d.\\s]*(,\\d{1,2})?|(?<![\\w.,])\\d{1,3}(\\.\\d{3})+(,\\d{2})?(?![\\w.,])"
                    + "|(?<![\\w.,])\\d+,\\d{2}(?![\\w,]))");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\w)\\d[\\d.\\-/]{3,}\\d(?!\\w)");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?iu)\\b(rua|r\\.|avenida|av\\.|travessa|tv\\.|alameda|al\\.|rodovia|estrada|praça|praca|largo|"
                    + "quadra|qd\\.|lote|bairro|condomínio|condominio)\\s+[\\p{L}\\d .ºª°,-]{2,80}?"
                    + "(?=$|[\\n;)]|\\.(\\s|$)|,\\s*(n[ºo°]?\\.?\\s*)?\\d+\\b|\\s+n[ºo°]?\\.?\\s*\\d+)"
                    + "(,?\\s*(n[ºo°]?\\.?\\s*)?\\d+[a-zA-Z]?)?");
    private static final Pattern CAPITALIZED_RUN = Pattern.compile(
            "\\b\\p{Lu}[\\p{L}'’-]+(?:\\s+(?:(?:de|da|do|das|dos|e|d')\\s+)?\\p{Lu}[\\p{L}'’-]+)+");
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'’-]*");

    /** Palavras comuns que podem iniciar frase ou nome de tela (artigos, preposições, verbos frequentes no suporte). */
    private static final Set<String> COMMON = Set.of("a", "o", "as", "os", "um", "uma", "na", "no", "nas", "nos", "em",
            "de", "da", "do", "das", "dos", "e", "ou", "para", "por", "com", "sem", "ao", "aos", "à", "às", "quando",
            "como", "onde", "qual", "quais", "que", "ele", "ela", "eles", "elas", "eu", "nós", "você", "vocês", "este",
            "esta", "esse", "essa", "isso", "isto", "aquele", "aquela", "não", "sim", "também", "mas", "porém", "após",
            "antes", "depois", "hoje", "ontem", "amanhã", "usuário", "usuária", "erro", "tela", "botão", "menu",
            "sistema", "chat", "suporte", "ia", "abrir", "ver", "tentar", "tentei", "cliquei", "aparece",
            "apareceu", "mensagem", "aviso", "problema", "dúvida", "pedido", "segunda", "terça", "quarta", "quinta",
            "sexta", "sábado", "domingo", "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
            "setembro", "outubro", "novembro", "dezembro", "pix", "boleto", "whatsapp", "sms", "cpf", "cnpj");

    private static final Set<String> COMMON_FOLDED = COMMON.stream().map(TicketRedactor::fold)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Set<String> vocabulary;

    /** @param vocabulary palavras do produto (minúsculas), ex.: títulos de telas, módulos e termos do glossário */
    public TicketRedactor(Collection<String> vocabulary) {
        Set<String> v = new HashSet<>();
        for (String w : vocabulary) {
            v.add(fold(w));
        }
        this.vocabulary = Set.copyOf(v);
    }

    /**
     * @param text       texto escrito pela IA
     * @param knownNames nomes de devedores exibidos na conversa (mascarados também por palavra)
     */
    public String redact(String text, Collection<String> knownNames) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String r = PersonalData.mask(text);
        r = ADDRESS.matcher(r).replaceAll("[endereço]");
        r = MONEY.matcher(r).replaceAll("[valor]");
        r = LONG_NUMBER.matcher(r).replaceAll("[número]");
        r = knownNames(r, knownNames);
        return properNames(r);
    }

    private String knownNames(String text, Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return text;
        }
        List<String> parts = new ArrayList<>();
        String r0 = text;
        for (String name : names) {
            if (name == null) {
                continue;
            }
            if (name.strip().length() >= 3) {
                // o nome inteiro primeiro (inclusive com números, ex.: razão social)
                r0 = Pattern.compile("(?iu)(?<![\\p{L}\\d])" + Pattern.quote(name.strip()) + "(?![\\p{L}\\d])")
                        .matcher(r0).replaceAll("[nome]");
            }
            Matcher w = WORD.matcher(name);
            while (w.find()) {
                String word = w.group();
                if (word.length() >= 3 && !COMMON_FOLDED.contains(fold(word))) {
                    parts.add(Pattern.quote(word));
                }
            }
        }
        if (parts.isEmpty()) {
            return r0;
        }
        // cada parte do nome (sobrenome sozinho também identifica)
        Pattern p = Pattern.compile("(?iu)(?<!\\p{L})(" + String.join("|", parts) + ")(?!\\p{L})");
        String r = p.matcher(r0).replaceAll("[nome]");
        return r.replaceAll("\\[nome](\\s+\\[nome])+", "[nome]");
    }

    private String properNames(String text) {
        Matcher m = CAPITALIZED_RUN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(unknownWords(run) >= 2 ? "[nome]" : run));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private int unknownWords(String run) {
        int unknown = 0;
        Matcher w = WORD.matcher(run);
        while (w.find()) {
            String word = fold(w.group());
            if (word.length() > 1 && !COMMON_FOLDED.contains(word) && !vocabulary.contains(word)) {
                unknown++;
            }
        }
        return unknown;
    }

    /** Minúsculas sem acento, para comparar com o vocabulário. */
    static String fold(String word) {
        String n = Normalizer.normalize(word.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
