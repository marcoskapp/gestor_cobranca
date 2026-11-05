package br.com.gestorcobranca.collection.domain.model;

/**
 * Resposta ao devedor sobre o resultado da contestação: pendente após a resolução, enviada (por canal permitido
 * ou registrada por outro meio) ou não exigida (contestação aberta pelo credor ou por órgão/jurídico, desistência,
 * contestações anteriores ao fluxo).
 */
public enum DebtorNoticeStatus {
    PENDING,
    SENT,
    NOT_REQUIRED
}
