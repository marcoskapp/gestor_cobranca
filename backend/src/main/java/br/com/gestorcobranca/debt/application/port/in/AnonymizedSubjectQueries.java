package br.com.gestorcobranca.debt.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Referências do titular anonimizado que ficam no debt depois da anonimização (SEG-20261124-D01h, decisões 1468 e 1470): os
 * títulos são conservados (LGPD art. 16 I e II) com o identificador do credor, sem nada que identifique a pessoa. Usado
 * para achar os arquivos de carga com o titular, recusar a linha que o traz de volta e montar o aviso ao credor
 * (art. 18 §6) — sempre só ids e referências, nunca nome, documento ou contato.
 */
public interface AnonymizedSubjectQueries {

    /**
     * Títulos do devedor anonimizado, com a referência do credor já mascarada se tiver formato de documento. Vazio se o
     * devedor não existe ou não está anonimizado. Exige {@code DATA_SUBJECT_REQUEST_MANAGE} e escopo de todos os
     * credores (o sistema tem os dois).
     */
    List<TitleReference> referencesOf(UUID debtorId);

    /**
     * Dentre os identificadores informados, os que pertencem, no credor, a títulos ou contratos de origem de devedor
     * anonimizado — a mesma regra da recusa por linha na carga (decisão 1470), para recusar em lote antes de criar
     * devedor. Exige {@code IMPORT_EXECUTE} e acesso ao credor. O chamador manda no máximo um bloco da carga.
     */
    AnonymizedKeys anonymizedKeys(UUID creditorId, Collection<String> titleExternalIds,
                                  Collection<String> contractExternalIds);

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
     * @param externalId        identificador do título no credor, como está no debt — só para casar com as cargas;
     *                          pode ter o CPF do titular embutido: nunca gravar, exibir nem registrar em log fora do
     *                          debt (use {@code displayReference}; o {@code toString} o omite)
     * @param displayReference  o mesmo identificador para exibição, com CPF/CNPJ mascarado
     * @param installmentNumber parcela (nula se não houver)
     */
    record TitleReference(UUID creditorId, UUID portfolioId, String externalId, String displayReference,
                          Integer installmentNumber) {

        @Override
        public String toString() {
            return "TitleReference[creditorId=" + creditorId + ", portfolioId=" + portfolioId + ", displayReference="
                    + displayReference + ", installmentNumber=" + installmentNumber + "]";
        }
    }
}
