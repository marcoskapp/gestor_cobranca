package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DebtorRepository {

    Debtor save(Debtor debtor);

    Optional<Debtor> findById(UUID id);

    Optional<Debtor> findByDocumentHash(String documentHash);

    /**
     * Devedores que têm ao menos um título nos credores informados (e, se informados, no
     * credor/carteira do filtro).
     *
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição (inclui devedores sem títulos)
     * @param text               trecho do nome (opcional)
     * @param documentHash       blind index do documento (opcional; usado quando o texto é um CPF/CNPJ)
     */
    Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                        String documentHash, Pageable pageable);
}
