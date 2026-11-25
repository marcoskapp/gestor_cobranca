package br.com.gestorcobranca.shared.security;

/** Ação protegida pela nova verificação do MFA da pessoa do credor (vai para o log de acesso como {@link #code()}). */
public enum StepUpPurpose {
    STATEMENT_ACKNOWLEDGE("statement_acknowledge"),
    PAYMENT_REPORT("payment_report");

    private final String code;

    StepUpPurpose(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
