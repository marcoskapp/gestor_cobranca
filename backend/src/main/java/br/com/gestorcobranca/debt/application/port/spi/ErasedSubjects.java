package br.com.gestorcobranca.debt.application.port.spi;

import br.com.gestorcobranca.shared.domain.Document;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Titular que teve os dados eliminados a pedido e não pode voltar como devedor nem coobrigado (SEG-20261124-D01h,
 * decisão 1471). Implementada pelo compliance, dono do pedido do titular; o debt consulta na criação do devedor
 * ({@code DebtorRegistry}) e a carga, por bloco, pelo {@code AnonymizedSubjectQueries}. Injeção obrigatória: sem a
 * implementação a aplicação não sobe (a regra nunca some em silêncio).
 * <p>
 * Só devolve documentos que vieram na entrada — nunca o índice, o pedido ou datas. Os documentos trafegam só em memória.
 * Cada documento reconhecido conta como tentativa de retorno, registrada no compliance para revisão humana (manter a
 * recusa ou liberar o retorno), mesmo que a operação do chamador seja desfeita.
 */
public interface ErasedSubjects {

    /** Teto de documentos por consulta (o bloco da carga com devedor e coobrigados). */
    int MAX_DOCUMENTS = br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries.MAX_ERASED_SUBJECT_DOCUMENTS;

    /**
     * @param linesByDocument documentos da entrada e quantas linhas (ou cadastros) cada um traz
     * @return os documentos da entrada que pertencem a titular eliminado a pedido, ainda não liberado
     */
    Set<Document> erasedAmong(Map<Document, Integer> linesByDocument, ReturnAttempt attempt);

    /** De onde veio a tentativa de trazer o titular de volta. */
    enum Source { IMPORT, DEBTOR_FORM, CO_OBLIGOR_FORM }

    /**
     * @param creditorId credor da carga (nulo no cadastro manual, que não é de um credor)
     * @param batchId    lote da carga (só na origem {@link Source#IMPORT})
     */
    record ReturnAttempt(Source source, UUID creditorId, UUID batchId) {
        public ReturnAttempt {
            if (source == null || (source == Source.IMPORT) != (batchId != null)) {
                throw new IllegalArgumentException("origem e lote incoerentes");
            }
        }

        public static ReturnAttempt importBatch(UUID creditorId, UUID batchId) {
            return new ReturnAttempt(Source.IMPORT, creditorId, batchId);
        }
    }
}
