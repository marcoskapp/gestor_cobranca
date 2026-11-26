package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ErasedSubjectReturnRepository {

    ErasedSubjectReturn save(ErasedSubjectReturn attempt);

    <S extends ErasedSubjectReturn> List<S> saveAll(Iterable<S> attempts);

    /** Tentativas do pedido, da mais recente para a mais antiga. */
    List<ErasedSubjectReturn> findByRequestIdOrderByAttemptedAtDesc(UUID requestId);

    /** Tentativas ainda sem revisão do pedido (para revisar todas de uma vez). */
    List<ErasedSubjectReturn> findByRequestIdAndOutcomeIsNull(UUID requestId);

    long countByOutcomeIsNull();

    /** Pedidos com tentativa ainda sem revisão, dentre os informados. */
    List<UUID> findRequestIdsPendingReviewIn(Collection<UUID> requestIds);
}
