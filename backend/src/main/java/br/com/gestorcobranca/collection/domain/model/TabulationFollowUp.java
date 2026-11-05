package br.com.gestorcobranca.collection.domain.model;

/**
 * Ação automática executada na mesma transação do registro da interação com a tabulação. Garante
 * que o fato relatado pelo operador produza o efeito devido sem depender de um segundo passo
 * manual (decisão 776, {@code docs/tecnico/desenho/collection.md} §4.1).
 */
public enum TabulationFollowUp {
    /** Só registra a interação. */
    NONE,
    /**
     * Abre uma contestação (origem OPERATOR) com os títulos em cobrança do caso, suspendendo a
     * cobrança e o contato até a análise. Tipo: o informado no registro ou o padrão da tabulação.
     */
    OPEN_DISPUTE,
    /**
     * Registra restrição de contato {@code DECEASED} do devedor em todos os canais e credores,
     * até um supervisor rever (anexar a certidão ou revogar).
     */
    REPORT_DECEASED,
    /** Marca como inválido o contato usado na interação (telefone ou e-mail que não é do devedor). */
    INVALIDATE_CONTACT;

    public boolean isNone() {
        return this == NONE;
    }
}
