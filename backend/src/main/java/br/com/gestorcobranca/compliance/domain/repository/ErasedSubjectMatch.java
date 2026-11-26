package br.com.gestorcobranca.compliance.domain.repository;

import java.util.UUID;

/** Pedido de eliminação executado que reconheceu o índice do titular (decisão 1471). */
public record ErasedSubjectMatch(UUID requestId, String subjectHash) {
}
