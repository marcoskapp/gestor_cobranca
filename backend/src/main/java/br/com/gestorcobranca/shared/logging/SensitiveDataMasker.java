package br.com.gestorcobranca.shared.logging;

import java.util.regex.Pattern;

/**
 * Mascara dados pessoais em textos livres antes de irem para qualquer log. Aplicado
 * automaticamente pelo encoder de logs ({@code MaskingMessageConverter}) e disponível para uso
 * explícito ao montar mensagens de erro vindas de provedores externos.
 */
public final class SensitiveDataMasker {

    private static final Pattern CPF = Pattern.compile("(?<!\\d)(\\d{3})\\.?(\\d{3})\\.?(\\d{3})-?(\\d{2})(?!\\d)");
    private static final Pattern CNPJ = Pattern.compile("(?<!\\d)(\\d{2})\\.?(\\d{3})\\.?(\\d{3})/?(\\d{4})-?(\\d{2})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?55)?\\s?\\(?(\\d{2})\\)?\\s?9?\\d{4}-?(\\d{4})(?!\\d)");

    private SensitiveDataMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = CNPJ.matcher(text).replaceAll("**.$2.$3/$4-**");
        result = CPF.matcher(result).replaceAll("***.$2.$3-**");
        result = EMAIL.matcher(result).replaceAll("$1***@$2");
        result = PHONE.matcher(result).replaceAll("($2) *****-$3");
        return result;
    }

    public static String maskEmail(String email) {
        return email == null ? null : EMAIL.matcher(email).replaceAll("$1***@$2");
    }

    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "*****" + digits.substring(digits.length() - 4);
    }
}
