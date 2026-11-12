package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.ContactStatus;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.InactiveDebtorCandidate;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DebtorJpaRepository extends JpaRepository<Debtor, UUID>, JpaSpecificationExecutor<Debtor>, DebtorRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Debtor d where d.id = :id")
    Optional<Debtor> findByIdForUpdate(@Param("id") UUID id);

    @Override
    Optional<Debtor> findByDocumentHash(String documentHash);

    @Override
    List<Debtor> findAllByIdIn(Collection<UUID> ids);

    /** Id zero: menor UUID possível, início da paginação por chave. */
    UUID FIRST_ID = new UUID(0L, 0L);

    @Override
    default List<InactiveDebtorCandidate> findInactive(Instant closedBefore, UUID afterId, int limit) {
        return findInactiveCandidates(closedBefore, afterId == null ? FIRST_ID : afterId, Limit.of(limit));
    }

    /**
     * Um único agrupamento por devedor: a última movimentação é a maior data de alteração dos
     * títulos (ou o cadastro, sem títulos), e nenhum título pode estar em cobrança.
     */
    @Query("""
            select new br.com.gestorcobranca.debt.domain.repository.InactiveDebtorCandidate(
                       d.id, coalesce(max(t.updatedAt), d.createdAt))
              from Debtor d left join DebtTitle t on t.debtorId = d.id
             where d.status = br.com.gestorcobranca.debt.domain.model.DebtorStatus.ACTIVE
               and d.id > :afterId
             group by d.id, d.createdAt
            having coalesce(max(t.updatedAt), d.createdAt) < :closedBefore
               and sum(case when t.status in (br.com.gestorcobranca.debt.domain.model.TitleStatus.OPEN,
                                              br.com.gestorcobranca.debt.domain.model.TitleStatus.SUSPENDED)
                            then 1 else 0 end) = 0
             order by d.id
            """)
    List<InactiveDebtorCandidate> findInactiveCandidates(Instant closedBefore, UUID afterId, Limit limit);

    /**
     * O escopo por credor é aplicado por subconsultas ({@code exists}), sem duplicar devedores com
     * vários títulos e sem expor devedores só de credores fora do escopo. O devedor entra por
     * título próprio ou por participação vigente como coobrigado de contrato do credor (com
     * filtro de carteira, um título do contrato deve estar na carteira).
     */
    @Override
    default Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                                String documentHash, Instant newContactAt, UUID newContactBatchId, Pageable pageable) {
        Specification<Debtor> spec = scope(allowedCreditorIds, creditorId, portfolioId);
        if (newContactAt != null) {
            // contato novo ainda sem tentativa, na janela e utilizável (mesma regra de DebtorContact#isNoveltyPending)
            spec = spec.and((root, query, cb) -> {
                Subquery<UUID> pending = query.subquery(UUID.class);
                Root<DebtorContact> contact = pending.from(DebtorContact.class);
                pending.select(contact.get("id")).where(
                        cb.equal(contact.get("debtorId"), root.get("id")),
                        cb.isTrue(contact.get("receivedAfterRegistration")),
                        cb.greaterThan(contact.get("noveltyUntil"), newContactAt),
                        cb.isNull(contact.get("firstAttemptAt")),
                        cb.notEqual(contact.get("status"), ContactStatus.INVALID));
                return cb.exists(pending);
            });
        }
        if (newContactBatchId != null) {
            spec = spec.and((root, query, cb) -> {
                Subquery<UUID> received = query.subquery(UUID.class);
                Root<DebtorContact> contact = received.from(DebtorContact.class);
                received.select(contact.get("id")).where(
                        cb.equal(contact.get("debtorId"), root.get("id")),
                        cb.equal(contact.get("firstImportBatchId"), newContactBatchId),
                        cb.isTrue(contact.get("receivedAfterRegistration")));
                return cb.exists(received);
            });
        }
        if (documentHash != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("documentHash"), documentHash));
        } else if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), like));
        }
        return findAll(spec, pageable);
    }

    /** Ids das unidades da mesma raiz do CNPJ ({@code ix_debtor_document_root}), sem escopo — uso interno (insolvência). */
    @Override
    @Query("select d.id from Debtor d where d.documentRootHash = :documentRootHash")
    List<UUID> findIdsByDocumentRootHash(String documentRootHash);

    /** Mesma raiz do CNPJ ({@code ix_debtor_document_root}), sem o devedor de referência, no escopo do ator. */
    @Override
    default Page<Debtor> searchByDocumentRoot(Collection<UUID> allowedCreditorIds, String documentRootHash,
                                              UUID excludeDebtorId, Pageable pageable) {
        Specification<Debtor> spec = scope(allowedCreditorIds, null, null)
                .and((root, query, cb) -> cb.and(cb.equal(root.get("documentRootHash"), documentRootHash),
                        cb.notEqual(root.get("id"), excludeDebtorId)));
        return findAll(spec, pageable);
    }

    @Override
    default List<Debtor> findCompaniesWithoutDocumentRoot(int limit) {
        return findByPersonTypeAndStatusAndDocumentRootHashIsNullOrderByIdAsc(
                br.com.gestorcobranca.debt.domain.model.PersonType.COMPANY,
                br.com.gestorcobranca.debt.domain.model.DebtorStatus.ACTIVE, Limit.of(limit));
    }

    List<Debtor> findByPersonTypeAndStatusAndDocumentRootHashIsNullOrderByIdAsc(
            br.com.gestorcobranca.debt.domain.model.PersonType personType,
            br.com.gestorcobranca.debt.domain.model.DebtorStatus status, Limit limit);

    private static Specification<Debtor> scope(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId) {
        Specification<Debtor> spec = Specification.unrestricted();
        if (allowedCreditorIds != null || creditorId != null || portfolioId != null) {
            spec = spec.and((root, query, cb) -> {
                if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
                    return cb.disjunction();
                }
                Subquery<UUID> titles = query.subquery(UUID.class);
                Root<DebtTitle> title = titles.from(DebtTitle.class);
                titles.select(title.get("id"));
                var predicate = cb.equal(title.get("debtorId"), root.get("id"));
                if (allowedCreditorIds != null) {
                    predicate = cb.and(predicate, title.get("creditorId").in(allowedCreditorIds));
                }
                if (creditorId != null) {
                    predicate = cb.and(predicate, cb.equal(title.get("creditorId"), creditorId));
                }
                if (portfolioId != null) {
                    predicate = cb.and(predicate, cb.equal(title.get("portfolioId"), portfolioId));
                }
                titles.where(predicate);

                Subquery<UUID> parties = query.subquery(UUID.class);
                Root<ObligationParty> party = parties.from(ObligationParty.class);
                parties.select(party.get("id"));
                var partyPredicate = cb.and(cb.equal(party.get("debtorId"), root.get("id")), cb.isNull(party.get("endedOn")));
                if (allowedCreditorIds != null) {
                    partyPredicate = cb.and(partyPredicate, party.get("creditorId").in(allowedCreditorIds));
                }
                if (creditorId != null) {
                    partyPredicate = cb.and(partyPredicate, cb.equal(party.get("creditorId"), creditorId));
                }
                if (portfolioId != null) {
                    Subquery<UUID> contractTitles = query.subquery(UUID.class);
                    Root<DebtTitle> contractTitle = contractTitles.from(DebtTitle.class);
                    contractTitles.select(contractTitle.get("id")).where(
                            cb.equal(contractTitle.get("originContractId"), party.get("originContractId")),
                            cb.equal(contractTitle.get("portfolioId"), portfolioId));
                    partyPredicate = cb.and(partyPredicate, cb.exists(contractTitles));
                }
                parties.where(partyPredicate);
                return cb.or(cb.exists(titles), cb.exists(parties));
            });
        }
        return spec;
    }
}
