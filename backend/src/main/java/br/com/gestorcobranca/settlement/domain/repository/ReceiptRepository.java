package br.com.gestorcobranca.settlement.domain.repository;

import br.com.gestorcobranca.settlement.domain.model.RatingStatus;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReceiptKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository {

    Receipt save(Receipt receipt);

    Optional<Receipt> findById(UUID id);

    List<Receipt> findByAllocationIdAndKind(UUID allocationId, ReceiptKind kind);

    List<Receipt> findByReversesReceiptId(UUID receiptId);

    List<Receipt> findByCreditorIdAndRatingStatus(UUID creditorId, RatingStatus status);

    List<Receipt> findByRatingStatus(RatingStatus status);

    /** Ainda não prestados, com competência até a data (candidatos de um demonstrativo). */
    List<Receipt> findByCreditorIdAndStatementIdIsNullAndCompetenceDateLessThanEqual(UUID creditorId, LocalDate date);

    List<Receipt> findByStatementId(UUID statementId);

    Page<Receipt> findByStatementId(UUID statementId, Pageable pageable);

    List<Receipt> findByAgreementId(UUID agreementId);

    /** Data de pagamento do último recebimento já apurado no contrato (limite de publicação de regras). */
    Optional<LocalDate> lastRatedCompetence(UUID contractId);

    /**
     * @param creditorIds credores visíveis; {@code null} = todos
     */
    Page<Receipt> search(Collection<UUID> creditorIds, UUID creditorId, UUID agreementId, LocalDate from, LocalDate to,
                         RatingStatus ratingStatus, Boolean inStatement, Pageable pageable);
}
