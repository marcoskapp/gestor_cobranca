package br.com.gestorcobranca.debt.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recuperação judicial, recuperação extrajudicial e falência do devedor pessoa jurídica (Lei 11.101/2005; cobrança
 * B2B §7). Registro, convolação e encerramento manuais, com motivo e auditoria, exigem {@code DEBTOR_MANAGE} (web) e
 * escopo sobre <b>todos</b> os credores do devedor: a mudança suspende ou retoma a cobrança em todos eles. A consulta
 * exige o devedor visível ao ator. Cada mudança publica {@code DebtorInsolvencyChanged}; o collection marca os casos do
 * devedor como inelegíveis ({@code DEBTOR_INSOLVENCY}) enquanto houver processo ativo.
 * <p>
 * Acordo em vigor não é cancelado e interação registrada não é apagada (decisão humana: a dívida pode estar no plano).
 */
public interface InsolvencyUseCases {

    /**
     * Registra o processo. Só devedor PJ ({@code insolvency.debtor_not_company}, 422); no máximo um ativo por devedor
     * ({@code insolvency.already_active}, 409).
     */
    ProceedingView register(RegisterProceedingCommand command);

    /** Convolação da recuperação judicial em falência (art. 73). */
    ProceedingView convertToBankruptcy(UUID proceedingId, LocalDate convertedOn, String reason);

    /** Encerra o processo; a elegibilidade dos casos do devedor é reavaliada. */
    ProceedingView end(UUID proceedingId, LocalDate endedOn, String reason);

    /** Processos do devedor (ativo e encerrados), o mais recente primeiro. */
    DebtorInsolvency ofDebtor(UUID debtorId);

    /**
     * Para a elegibilidade do collection: o devedor tem processo ativo? Só o booleano, sem escopo (chamado por ator de
     * sistema na reavaliação dos casos).
     */
    boolean isUnderInsolvency(UUID debtorId);

    /**
     * @param kind       JUDICIAL_RECOVERY, EXTRAJUDICIAL_RECOVERY ou BANKRUPTCY
     * @param caseNumber número CNJ, com ou sem máscara
     * @param filedOn    data do pedido (distribuição), no ano do número CNJ
     * @param note       origem da informação ou motivo do registro (obrigatório, até 500); não registre dado pessoal
     */
    record RegisterProceedingCommand(UUID debtorId, String kind, String caseNumber, String court, LocalDate filedOn,
                                     String note) {
    }

    /**
     * @param caseNumber    número CNJ formatado ({@code NNNNNNN-DD.AAAA.J.TR.OOOO}) — dado público do processo
     * @param since         início do efeito atual (decretação da falência, quando convolada; senão o pedido)
     * @param convertedFrom tipo anterior, quando convolada
     * @param status        ACTIVE ou ENDED
     */
    record ProceedingView(UUID id, UUID debtorId, String kind, String caseNumber, String court, LocalDate filedOn,
                          LocalDate since, String convertedFrom, LocalDate convertedOn, String status,
                          LocalDate endedOn, String endReason, String note) {
    }

    /**
     * @param company   o devedor é pessoa jurídica (só PJ recebe registro)
     * @param active    processo em curso (nulo = nenhum)
     * @param history   todos os processos da empresa (matriz e filiais), o mais recente primeiro
     * @param canManage o ator pode registrar, convolar e encerrar ({@code DEBTOR_MANAGE} e escopo sobre todos os
     *                  credores de todas as unidades da empresa)
     */
    record DebtorInsolvency(UUID debtorId, boolean company, ProceedingView active, List<ProceedingView> history,
                            boolean canManage) {
    }
}
