package br.com.gestorcobranca.ingestion.domain.model;

/** O que o bloqueio fez com o lote. */
public enum BlockEffect {
    /** Estava na fila ou aguardando formato: cancelado na hora. */
    CANCELLED_NOW,
    /** Estava em validação ou importação: para ao fim do bloco corrente (cancelamento do sistema). */
    STOP_REQUESTED,
    /** Já estava encerrado: só fica bloqueado. */
    ALREADY_FINISHED,
    /**
     * O próprio processamento recusou linha de titular anonimizado (decisão 1496): as demais linhas seguem e o arquivo
     * sai no encerramento.
     */
    FOUND_IN_LOAD
}
