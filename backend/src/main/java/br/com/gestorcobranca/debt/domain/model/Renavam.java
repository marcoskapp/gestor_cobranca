package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Objects;

/**
 * Código RENAVAM do veículo: 11 dígitos, o último verificador. Códigos antigos de 9 dígitos são
 * completados com zeros à esquerda, como faz o próprio sistema do Denatran.
 * <p>
 * Dígito verificador: os 10 primeiros dígitos são multiplicados pelos pesos 3, 2, 9, 8, 7, 6, 5,
 * 4, 3, 2; o dígito é {@code (soma × 10) mod 11}, valendo 0 quando o resto é 10.
 */
public record Renavam(String value) {

    private static final int LENGTH = 11;
    private static final int[] WEIGHTS = {3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public Renavam {
        Objects.requireNonNull(value, "value");
        Guard.require(isValid(value), "collateral.renavam_invalid", "RENAVAM inválido");
    }

    public static Renavam parse(String raw) {
        Guard.require(raw != null && !raw.isBlank(), "collateral.renavam_invalid", "RENAVAM obrigatório");
        String digits = raw.replaceAll("[\\s.-]", "");
        Guard.require(digits.matches("\\d{9,11}"), "collateral.renavam_invalid", "RENAVAM deve ter 9 a 11 dígitos");
        return new Renavam("0".repeat(LENGTH - digits.length()) + digits);
    }

    static boolean isValid(String digits) {
        if (digits.length() != LENGTH || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (digits.charAt(i) - '0') * WEIGHTS[i];
        }
        int digit = (sum * 10) % 11;
        return (digit == 10 ? 0 : digit) == digits.charAt(LENGTH - 1) - '0';
    }
}
