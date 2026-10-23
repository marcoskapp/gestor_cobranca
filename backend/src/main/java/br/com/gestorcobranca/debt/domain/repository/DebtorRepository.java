package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DebtorRepository {

    Debtor save(Debtor debtor);

    Optional<Debtor> findById(UUID id);

    Optional<Debtor> findByDocumentHash(String documentHash);

    List<Debtor> findAllByIdIn(Collection<UUID> ids);

    /**
     * Devedores que têm ao menos um título nos credores informados (e, se informados, no
     * credor/carteira do filtro).
     *
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição (inclui devedores sem títulos)
     * @param text               trecho do nome (opcional)
     * @param documentHash       blind index do documento (opcional; usado quando o texto é um CPF/CNPJ)
     */
    /**
     * Devedores ativos sem títulos em cobrança cuja última movimentação é anterior a
     * {@code closedBefore}, com id maior que {@code afterId}, em ordem de id.
     */
    List<InactiveDebtorCandidate> findInactive(Instant closedBefore, UUID afterId, int limit);

    Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                        String documentHash, Pageable pageable);
}
