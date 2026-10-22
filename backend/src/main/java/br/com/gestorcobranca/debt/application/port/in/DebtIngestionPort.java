package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <strong>Porta interna</strong> (sem endpoint web) para o módulo ingestion gravar devedores e
 * títulos de uma carga (F04). Projetada para ser chamada linha a linha, cada chamada na sua
 * própria transação: cada método é autocontido, idempotente e seguro para reprocessamento —
 * reimportar o mesmo arquivo não duplica devedores, contatos, títulos, lançamentos ou saldos.
 * <p>
 * Problemas que não impedem a gravação da linha (contato inválido, título bloqueado para
 * alteração) voltam em {@link UpsertResult#warnings()} como códigos estáveis; violações que
 * impedem a gravação lançam exceção de domínio com código estável.
 */
public interface DebtIngestionPort {

    /** Casa pelo documento; contatos novos do registro são acrescentados (origem IMPORT). */
    UpsertResult upsertDebtor(DebtorRecord record);

    /**
     * Casa por (credor, identificador externo). Na criação lança a abertura do principal e abre
     * a titularidade; na atualização altera vencimento/dados e corrige o principal por
     * lançamento de ajuste, exceto em títulos quitados, cancelados ou devolvidos (aviso
     * {@code title.locked_status}). O saldo informado é registrado para conciliação.
     * Valida que a carteira pertence ao credor e aceita cargas.
     */
    UpsertResult upsertTitle(TitleRecord record, UUID importBatchId);

    /** Publica {@code PortfolioLoaded} ao fim do lote, para a abertura/atualização de casos. */
    void completeImport(ImportSummary summary);

    enum Outcome { CREATED, UPDATED, UNCHANGED }

    /** @param warnings códigos de aviso (ex.: {@code contact.phone_invalid}, {@code title.locked_status}) */
    record UpsertResult(UUID id, Outcome outcome, List<String> warnings) {

        public UpsertResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    record DebtorRecord(String document, String name, LocalDate birthDate, List<ContactRecord> contacts) {

        public DebtorRecord {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
        }
    }

    /**
     * @param type  PHONE, EMAIL ou ADDRESS
     * @param label MOBILE, HOME, WORK ou OTHER (opcional)
     */
    record ContactRecord(String type, String value, String label, boolean whatsapp) {
    }

    /**
     * @param contract             contrato de origem (opcional)
     * @param reportedBalance      saldo informado pelo credor (opcional)
     * @param balanceReferenceDate data-base do saldo informado (obrigatória com o saldo)
     */
    record TitleRecord(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, ContractRecord contract,
                       Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount,
                       BigDecimal reportedBalance, LocalDate balanceReferenceDate) {
    }

    record ContractRecord(String externalId, String product, LocalDate signedAt, BigDecimal originalAmount) {
    }

    record ImportSummary(UUID importBatchId, UUID creditorId, UUID portfolioId, int titlesCreated, int titlesUpdated,
                         int debtorsAffected) {
    }
}
