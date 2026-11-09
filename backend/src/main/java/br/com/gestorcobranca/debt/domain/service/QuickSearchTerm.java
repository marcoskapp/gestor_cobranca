package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Termo da busca rápida de devedor (Ctrl+K), já classificado. Regras (revisão de produto 11/2026, item 1.3):
 * <ul>
 *   <li>só dígitos e separadores ({@code . - / ( ) + espaço}) com CPF ou CNPJ válido → documento completo;</li>
 *   <li>só dígitos e separadores que formam um telefone do Brasil (DDD + número) → telefone completo;</li>
 *   <li>sem espaço, de 3 a 80 caracteres e que não seja documento nem telefone → código do título no credor
 *       (exato);</li>
 *   <li>com letra e pelo menos {@value #MIN_NAME_LENGTH} letras ou dígitos → trecho do nome.</li>
 * </ul>
 * Um CPF válido de 11 dígitos também pode ser um celular: o documento tem precedência e o telefone só é buscado
 * quando o documento não encontra ninguém visível ao ator (decidido no serviço). Documento ou telefone incompleto
 * nunca é buscado como documento, telefone ou nome (nunca por trecho de dado pessoal); sem espaço, ele só vira busca
 * <em>exata</em> pelo código do título no credor (ex.: {@code 071.340} acha o título cujo código é exatamente esse).
 */
public final class QuickSearchTerm {

    /** Mínimo de letras ou dígitos para buscar por trecho do nome. */
    public static final int MIN_NAME_LENGTH = 3;
    /** Máximo aceito para o termo (o código do título tem até 80). */
    public static final int MAX_LENGTH = 80;

    private static final Pattern NUMERIC = Pattern.compile("[\\d.\\-/()+\\s]+");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private final String text;
    private final Document document;
    private final ContactValue phone;
    private final String titleCode;
    private final String name;

    private QuickSearchTerm(String text, Document document, ContactValue phone, String titleCode, String name) {
        this.text = text;
        this.document = document;
        this.phone = phone;
        this.titleCode = titleCode;
        this.name = name;
    }

    public static QuickSearchTerm of(String raw) {
        String text = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (text.length() > MAX_LENGTH) {
            throw new BusinessRuleException("debtor.quick_search_too_long",
                    "Busca com no máximo %d caracteres".formatted(MAX_LENGTH));
        }
        if (text.isEmpty()) {
            return new QuickSearchTerm(text, null, null, null, null);
        }
        Document document = null;
        ContactValue phone = null;
        if (NUMERIC.matcher(text).matches()) {
            String digits = text.replaceAll("\\D", "");
            if (digits.length() == 11 && Document.isValid(Document.DocumentType.CPF, digits)
                    || digits.length() == 14 && Document.isValid(Document.DocumentType.CNPJ, digits)) {
                document = Document.parse(digits);
            }
            phone = parsePhone(text);
        }
        // documento e telefone completos não viram código de título (o texto não é repetido em outra consulta)
        String titleCode = document == null && phone == null && !text.contains(" ") && text.length() >= MIN_NAME_LENGTH
                ? text : null;
        String name = HAS_LETTER.matcher(text).find() && significantLength(text) >= MIN_NAME_LENGTH ? text : null;
        return new QuickSearchTerm(text, document, phone, titleCode, name);
    }

    private static ContactValue parsePhone(String text) {
        try {
            return ContactValue.parse(ContactType.PHONE, text);
        } catch (BusinessRuleException e) {
            return null;
        }
    }

    private static int significantLength(String text) {
        String plain = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return plain.replaceAll("[^\\p{L}\\d]", "").length();
    }

    /** Nada a buscar (vazio, curto demais ou documento/telefone incompleto). */
    public boolean isEmpty() {
        return document == null && phone == null && titleCode == null && name == null;
    }

    /** O termo traz dado pessoal completo (documento ou telefone): a busca vai para o log de acesso. */
    public boolean isPersonalData() {
        return document != null || phone != null;
    }

    public Optional<Document> document() {
        return Optional.ofNullable(document);
    }

    public Optional<ContactValue> phone() {
        return Optional.ofNullable(phone);
    }

    public Optional<String> titleCode() {
        return Optional.ofNullable(titleCode);
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** Nunca contém o texto digitado (pode ser documento ou telefone). */
    @Override
    public String toString() {
        return "QuickSearchTerm[document=" + (document != null) + ", phone=" + (phone != null) + ", titleCode="
                + (titleCode != null) + ", name=" + (name != null) + ", length=" + text.length() + "]";
    }
}
