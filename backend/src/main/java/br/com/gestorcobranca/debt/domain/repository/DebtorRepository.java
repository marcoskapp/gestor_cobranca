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

    /**
     * Devedor com bloqueio de escrita na linha até o fim da transação: serializa o recálculo da novidade dos contatos
     * do mesmo devedor (docs/tecnico/desenho/contatos-recebidos.md §9).
     */
    Optional<Debtor> findByIdForUpdate(UUID id);

    /**
     * Trava a linha do devedor e relê o estado: o registro pode já estar no contexto de persistência, carregado sem
     * trava (a eliminação simultânea do mesmo devedor vê o que a outra gravou).
     */
    void refreshForUpdate(Debtor debtor);

    /**
     * Trava compartilhada da linha do devedor com releitura do estado (decisão 1602): quem cria ou reabre vínculo de
     * dívida (título, coobrigação) espera a eliminação em andamento e vê o devedor já anonimizado; a eliminação, se vier
     * depois, espera e vê o vínculo. Ordem das travas: devedor, depois contrato, depois título. Nunca subir de
     * compartilhada para exclusiva na mesma transação sobre o mesmo devedor sem ter pego a exclusiva antes (deadlock
     * entre duas cargas).
     */
    void refreshForShare(Debtor debtor);

    /** Grava e envia ao banco na hora (a checagem do titular eliminado roda depois do insert, decisão 1601). */
    Debtor saveAndFlush(Debtor debtor);

    /** Ids das unidades da mesma empresa (mesma raiz do CNPJ), sem escopo — uso interno (insolvência). */
    List<UUID> findIdsByDocumentRootHash(String documentRootHash);

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

    default Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                                String documentHash, Pageable pageable) {
        return search(allowedCreditorIds, creditorId, portfolioId, text, documentHash, null, null, pageable);
    }

    /**
     * @param newContactAt      não nulo = só devedores com contato novo recebido na carga ainda sem tentativa, com a
     *                          janela aberta neste instante e utilizável
     * @param newContactBatchId só devedores que receberam contato novo nesta carga
     */
    Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                        String documentHash, Instant newContactAt, UUID newContactBatchId, Pageable pageable);

    /**
     * Outras unidades da mesma empresa (mesma raiz do CNPJ), sem o devedor de referência, com o escopo de
     * {@link #search}.
     */
    Page<Debtor> searchByDocumentRoot(Collection<UUID> allowedCreditorIds, String documentRootHash, UUID excludeDebtorId,
                                      Pageable pageable);

    /** Pessoas jurídicas ativas sem a raiz do CNPJ (cadastros anteriores a ela), em ordem de id. */
    List<Debtor> findCompaniesWithoutDocumentRoot(int limit);
}
