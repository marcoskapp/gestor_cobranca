package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.domain.Document;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Referências do titular anonimizado que ficam no debt depois da anonimização (SEG-20261124-D01h, decisões 1468 e 1470): os
 * títulos são conservados (LGPD art. 16 I e II) com o identificador do credor, sem nada que identifique a pessoa. Usado
 * para achar os arquivos de carga com o titular, recusar a linha que o traz de volta e montar o aviso ao credor
 * (art. 18 §6) — sempre só ids e referências, nunca nome, documento ou contato.
 */
public interface AnonymizedSubjectQueries {

    /** Teto de documentos por consulta de titular eliminado a pedido (o mesmo da SPI do compliance). */
    int MAX_ERASED_SUBJECT_DOCUMENTS = 5_000;

    /**
     * Títulos do devedor anonimizado, com a referência do credor já mascarada se tiver formato de documento. Vazio se o
     * devedor não existe ou não está anonimizado. Exige {@code DATA_SUBJECT_REQUEST_MANAGE} e escopo de todos os
     * credores (o sistema tem os dois).
     */
    List<TitleReference> referencesOf(UUID debtorId);

    /**
     * Contratos de origem em que o devedor anonimizado era coobrigado (fiador, avalista…), ativos ou encerrados, com a
     * referência do credor já mascarada: o credor do contrato tem o dado do titular e precisa ser avisado (revisão da
     * decisão 1480, decisão 1605). Mesmas exigências de {@link #referencesOf}.
     */
    List<ContractReference> coObligorContractsOf(UUID debtorId);

    /** Contrato do credor em que o titular era coobrigado (sem o identificador cru). */
    record ContractReference(UUID creditorId, UUID portfolioId, String displayReference) {
    }

    /**
     * Dentre os identificadores informados, os que pertencem, no credor, a títulos ou contratos de origem de devedor
     * anonimizado — a mesma regra da recusa por linha na carga (decisão 1470), para recusar em lote antes de criar
     * devedor. Exige {@code IMPORT_EXECUTE} e acesso ao credor. O chamador manda no máximo um bloco da carga.
     */
    AnonymizedKeys anonymizedKeys(UUID creditorId, Collection<String> titleExternalIds,
                                  Collection<String> contractExternalIds);

    /**
     * Devedores anonimizados donos destes identificadores (títulos ou contratos) no credor — para o lote que recusou a
     * linha na carga apontar para o aviso ao credor (decisão 1496). Só ids. Exige {@code IMPORT_EXECUTE} e o credor.
     */
    Set<UUID> anonymizedDebtorsOf(UUID creditorId, Collection<String> titleExternalIds,
                                  Collection<String> contractExternalIds);

    /**
     * Dentre os documentos das linhas de um bloco da carga (devedor e coobrigados), os de titular eliminado a pedido
     * (decisão 1471), para recusar a linha antes de gravar. A tentativa de retorno fica registrada no compliance. Exige
     * {@code IMPORT_EXECUTE} e acesso ao credor; no máximo {@code ErasedSubjects.MAX_DOCUMENTS} documentos.
     *
     * @param linesByDocument documento e quantas linhas do bloco o trazem
     */
    Set<Document> erasedSubjectsAmong(UUID creditorId, UUID batchId, Map<Document, Integer> linesByDocument);

    /** Identificadores do credor que pertencem a devedor anonimizado. */
    record AnonymizedKeys(Set<String> titles, Set<String> contracts) {
        public AnonymizedKeys {
            titles = titles == null ? Set.of() : Set.copyOf(titles);
            contracts = contracts == null ? Set.of() : Set.copyOf(contracts);
        }

        public boolean isEmpty() {
            return titles.isEmpty() && contracts.isEmpty();
        }
    }

    /**
     * Título do credor conservado depois da anonimização do titular.
     *
     * @param titleKey          chave HMAC (64 hex) do identificador no credor ({@code ExternalIdDigest#key}, título) —
     *                          só para casar com as linhas das cargas ({@code import_batch_row.title_key}); o
     *                          identificador cru nunca sai do debt (decisão 1476). Não exibir nem registrar em log
     *                          (o {@code toString} a omite)
     * @param displayReference  o identificador para exibição, com CPF/CNPJ mascarado (a forma guardada na troca)
     * @param installmentNumber parcela (nula se não houver)
     */
    record TitleReference(UUID creditorId, UUID portfolioId, String titleKey, String displayReference,
                          Integer installmentNumber) {

        @Override
        public String toString() {
            return "TitleReference[creditorId=" + creditorId + ", portfolioId=" + portfolioId + ", displayReference="
                    + displayReference + ", installmentNumber=" + installmentNumber + "]";
        }
    }
}
