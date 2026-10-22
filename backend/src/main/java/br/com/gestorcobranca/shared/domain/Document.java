package br.com.gestorcobranca.shared.domain;

import java.util.Objects;

/**
 * Documento de identificação fiscal brasileiro (CPF ou CNPJ), normalizado apenas com dígitos
 * e com dígitos verificadores validados. É a chave principal de identidade de um devedor
 * (RB01): nomes iguais nunca bastam para consolidar pessoas.
 */
public record Document(DocumentType type, String number) {

    public enum DocumentType { CPF, CNPJ }

    public Document {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(number, "number");
        if (!isValid(type, number)) {
            throw new BusinessRuleException("document.invalid", "Documento %s inválido".formatted(type));
        }
    }

    /** Cria a partir de texto livre (com ou sem máscara), inferindo o tipo pelo tamanho. */
    public static Document parse(String raw) {
        if (raw == null) {
            throw new BusinessRuleException("document.required", "Documento obrigatório");
        }
        String digits = raw.replaceAll("\\D", "");
        return switch (digits.length()) {
            case 11 -> new Document(DocumentType.CPF, digits);
            case 14 -> new Document(DocumentType.CNPJ, digits);
            default -> throw new BusinessRuleException("document.invalid", "Documento deve ter 11 (CPF) ou 14 (CNPJ) dígitos");
        };
    }

    public static boolean isValid(DocumentType type, String digits) {
        if (digits == null || !digits.matches("\\d+")) {
            return false;
        }
        return switch (type) {
            case CPF -> validCpf(digits);
            case CNPJ -> validCnpj(digits);
        };
    }

    /** Forma mascarada para exibição e logs: ***.456.789-** / **.345.678/0001-** */
    public String masked() {
        return switch (type) {
            case CPF -> "***." + number.substring(3, 6) + "." + number.substring(6, 9) + "-**";
            case CNPJ -> "**." + number.substring(2, 5) + "." + number.substring(5, 8) + "/" + number.substring(8, 12) + "-**";
        };
    }

    public String formatted() {
        return switch (type) {
            case CPF -> "%s.%s.%s-%s".formatted(number.substring(0, 3), number.substring(3, 6), number.substring(6, 9), number.substring(9));
            case CNPJ -> "%s.%s.%s/%s-%s".formatted(number.substring(0, 2), number.substring(2, 5), number.substring(5, 8), number.substring(8, 12), number.substring(12));
        };
    }

    private static boolean validCpf(String cpf) {
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(cpf, 9, 10) == cpf.charAt(9) - '0'
                && checkDigit(cpf, 10, 11) == cpf.charAt(10) - '0';
    }

    private static int checkDigit(String cpf, int length, int startWeight) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (startWeight - i);
        }
        int mod = (sum * 10) % 11;
        return mod == 10 ? 0 : mod;
    }

    private static boolean validCnpj(String cnpj) {
        if (cnpj.length() != 14 || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return cnpjDigit(cnpj, w1) == cnpj.charAt(12) - '0'
                && cnpjDigit(cnpj, w2) == cnpj.charAt(13) - '0';
    }

    private static int cnpjDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int mod = sum % 11;
        return mod < 2 ? 0 : 11 - mod;
    }

    @Override
    public String toString() {
        // nunca expor o número completo em toString (evita vazamento acidental em logs)
        return type + "(" + masked() + ")";
    }
}
