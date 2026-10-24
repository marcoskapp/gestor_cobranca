package br.com.gestorcobranca.assistant.domain.service;

import java.util.regex.Pattern;

/**
 * Minimização do texto do devedor antes de enviá-lo ao provedor de IA: e-mail, documento (CPF/CNPJ),
 * telefone e sequências numéricas longas (contas, códigos) são substituídos por marcadores. Enquanto
 * a identidade não foi confirmada, também datas e grupos de dígitos (a resposta à confirmação —
 * dígitos do CPF e nascimento — nunca sai do servidor). Valores em reais são preservados.
 */
public final class PersonalDataRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?55[\\s-]?)?\\(?\\b\\d{2}\\)?[\\s-]?9?\\d{4}[\\s-]?\\d{4}\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?\\b");
    private static final Pattern SHORT_DIGITS = Pattern.compile("(?<![\\d,.])\\b\\d{3,5}\\b(?![,.]\\d)");

    private PersonalDataRedactor() {
    }

    /**
     * @param identityPending a mensagem pode conter a resposta da confirmação de identidade
     */
    public static String redact(String text, boolean identityPending) {
        if (text == null) {
            return "";
        }
        String out = EMAIL.matcher(text).replaceAll("[e-mail omitido]");
        out = CNPJ.matcher(out).replaceAll("[documento omitido]");
        out = CPF.matcher(out).replaceAll("[documento omitido]");
        out = PHONE.matcher(out).replaceAll("[telefone omitido]");
        out = LONG_DIGITS.matcher(out).replaceAll("[número omitido]");
        if (identityPending) {
            out = DATE.matcher(out).replaceAll("[data omitida]");
            out = SHORT_DIGITS.matcher(out).replaceAll("[dígitos omitidos]");
        }
        return out;
    }

    /** O texto contém dado pessoal identificável (documento, e-mail ou telefone). */
    public static boolean containsPersonalData(String text) {
        return text != null && (EMAIL.matcher(text).find() || CPF.matcher(text).find() || CNPJ.matcher(text).find()
                || PHONE.matcher(text).find());
    }
}
