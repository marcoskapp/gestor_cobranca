package br.com.gestorcobranca.shared.domain;

/** Pré-condições de domínio expressivas, lançando {@link BusinessRuleException}. */
public final class Guard {

    private Guard() {
    }

    public static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new BusinessRuleException(code, message);
        }
    }

    public static <T> T requireNonNull(T value, String code, String message) {
        if (value == null) {
            throw new BusinessRuleException(code, message);
        }
        return value;
    }

    public static void requireState(boolean condition, String code, String message) {
        if (!condition) {
            throw new ConflictException(code, message);
        }
    }
}
