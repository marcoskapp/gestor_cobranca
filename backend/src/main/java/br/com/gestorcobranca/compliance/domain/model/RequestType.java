package br.com.gestorcobranca.compliance.domain.model;

/** Direitos do titular (LGPD art. 18) atendidos por uma solicitação. */
public enum RequestType {
    /** I — confirmação da existência de tratamento. */
    CONFIRMATION(false),
    /** II — acesso aos dados. */
    ACCESS(true),
    /** III — correção de dados incompletos, inexatos ou desatualizados. */
    CORRECTION(true),
    /** IV/VI — anonimização ou eliminação. */
    ANONYMIZATION(true),
    /** V — portabilidade. */
    PORTABILITY(true),
    /** VII — informação sobre compartilhamento. */
    INFORMATION_SHARING(false),
    /** Art. 18 §2º — oposição ao tratamento. */
    OPPOSITION(true),
    /** Art. 20 — revisão de decisão automatizada. */
    REVIEW_AUTOMATED_DECISION(false);

    private final boolean requiresDebtor;

    RequestType(boolean requiresDebtor) {
        this.requiresDebtor = requiresDebtor;
    }

    /** O atendimento exige o titular localizado na base (devedor identificado). */
    public boolean requiresDebtor() {
        return requiresDebtor;
    }

    /** Tem ação automática no sistema (exportação, anonimização, restrição). */
    public boolean isExecutable() {
        return this == ACCESS || this == PORTABILITY || this == ANONYMIZATION || this == OPPOSITION;
    }

    /** Exige arquivo de resultado antes da conclusão. */
    public boolean producesFile() {
        return this == ACCESS || this == PORTABILITY;
    }
}
