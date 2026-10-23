package br.com.gestorcobranca.compliance.domain.model;

/** Categoria de dado sujeita a retenção e descarte. */
public enum RetentionCategory {
    /** Devedor sem títulos em cobrança (OPEN/SUSPENDED) há N meses: anonimização. */
    DEBTOR_INACTIVE(RetentionAction.ANONYMIZE, true),
    /** Arquivos de carga importados. Política declarada para o módulo ingestion. */
    IMPORT_FILES(RetentionAction.DELETE_FILE, false),
    /** Conteúdo de mensagens. Política declarada para o módulo communication. */
    MESSAGE_CONTENT(RetentionAction.DELETE_FILE, false),
    /** Gravações de chamadas. Política declarada para o módulo telephony. */
    CALL_RECORDINGS(RetentionAction.DELETE_FILE, false);

    private final RetentionAction action;
    private final boolean executableHere;

    RetentionCategory(RetentionAction action, boolean executableHere) {
        this.action = action;
        this.executableHere = executableHere;
    }

    /** Ação de descarte aplicável à categoria. */
    public RetentionAction action() {
        return action;
    }

    /** Executada pelo módulo compliance; as demais são consultadas pelos módulos donos dos dados. */
    public boolean isExecutableHere() {
        return executableHere;
    }
}
