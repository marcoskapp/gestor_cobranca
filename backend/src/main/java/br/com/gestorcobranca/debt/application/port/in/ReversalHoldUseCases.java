package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Retenção da cobrança após o estorno de um pagamento recebido pelo credor ("Estorno em conferência", H07 parte 3).
 * Decisões do responsável (25/11 e 26/11/2026): o estorno não retoma a cobrança sozinho; enquanto houver retenção
 * pendente do par devedor–credor, nenhum contato de cobrança do credor com o devedor em qualquer canal (porteiro do
 * compliance), nenhuma oferta, acordo ou instrução de pagamento, e o portal não mostra os títulos retidos. Uma pessoa
 * com {@code PAYMENT_REVIEW}, que não estornou nem informou o pagamento, libera com justificativa e confirmação.
 * <p>
 * A criação fica em {@code debt :: reversal-hold} ({@code ReversalHoldPlacement}, só o payment). Consultas internas
 * ({@link #isHeld}, {@link #heldTitleIds}, {@link #heldAmong}, {@link #breakExemptInstallmentIds},
 * {@link #pendingInstallmentIds}, {@link #createdForAllocation}) não conferem permissão: são chamadas
 * por outros casos de uso que já autorizaram o ator, devolvem só ids e booleanos e respeitam a RLS do tenant corrente.
 * Nenhuma delas é exposta à pessoa do credor, à integração do credor nem ao assistente.
 */
public interface ReversalHoldUseCases {

    /** Período máximo da consulta da aba, em dias. */
    int MAX_PERIOD_DAYS = 366;

    /** Tamanho máximo da página. */
    int MAX_PAGE_SIZE = 100;


    /** Há retenção pendente do credor para o devedor (devedor principal ou coobrigado ativo de título retido). */
    boolean isHeld(UUID debtorId, UUID creditorId);

    /** Títulos do devedor principal com retenção pendente do credor (portal, elegibilidade). */
    Set<UUID> heldTitleIds(UUID creditorId, UUID debtorId);

    /** Títulos com retenção pendente entre os informados (instrução de pagamento). */
    Set<UUID> heldAmong(Collection<UUID> titleIds);

    /** Parcelas do acordo com retenção pendente (instrução de pagamento da parcela). */
    Set<UUID> pendingInstallmentIds(UUID agreementId);

    /**
     * Parcelas do acordo que não contam para a quebra (decisão do responsável 3): retenção pendente, ou liberada há
     * menos de {@code graceDays} dias (carência normal a partir da liberação).
     */
    Set<UUID> breakExemptInstallmentIds(UUID agreementId, LocalDate today, int graceDays);

    /** A alocação estornada gerou retenção (pagamento recebido pelo credor) — o score não a trata como comportamento. */
    boolean createdForAllocation(UUID allocationId);


    /** Aba "Estornos em conferência" ({@code PAYMENT_VIEW}; ator do credor, integração e assistente recusados). */
    PageResponse<HoldView> search(HoldFilter filter, PageQuery page);

    /** Retenção por id ({@code PAYMENT_VIEW}); fora do escopo, de outra assessoria ou inexistente: 404 igual. */
    HoldView get(UUID holdId);

    /** Retenções de um pagamento (ficha do pagamento estornado; {@code PAYMENT_VIEW}). */
    List<HoldView> forPayment(UUID paymentId);

    /** Títulos retidos do devedor visíveis ao ator (selo "Estorno em conferência"; {@code DEBTOR_VIEW} ou {@code CASE_VIEW}). */
    List<HeldTitle> heldTitlesOfDebtor(UUID debtorId);

    /**
     * Libera a retenção ({@code PAYMENT_REVIEW}; nunca quem estornou nem quem informou; ator do credor, integração e
     * assistente recusados): 404 fora do escopo antes de qualquer regra; 409 versão, situação ou sem confirmação; 422
     * sem motivo/justificativa ou por segregação.
     */
    HoldView release(UUID holdId, ReleaseCommand command);


    /**
     * @param from      primeiro dia do estorno (fuso de operação); obrigatório fora de {@code PENDING}
     * @param to        último dia (até {@value #MAX_PERIOD_DAYS} dias depois de {@code from}); obrigatório fora de
     *                  {@code PENDING}
     * @param state     PENDING (padrão, nulo; sem período — todas as pendentes), RELEASED ou ENDED
     * @param paymentId só as retenções deste pagamento (condição dentro do escopo)
     */
    record HoldFilter(LocalDate from, LocalDate to, UUID creditorId, String state, UUID paymentId) {

        public HoldFilter(LocalDate from, LocalDate to, UUID creditorId, String state) {
            this(from, to, creditorId, state, null);
        }
    }

    /**
     * @param reasonCode            CREDITOR_CONFIRMED_NOT_RECEIVED, PAYMENT_ON_WRONG_TITLE ou OTHER
     * @param note                  justificativa (obrigatória, até 500 caracteres; sem dados pessoais do devedor)
     * @param confirmedWithCreditor "Conferi com o credor que este valor está em aberto"
     */
    record ReleaseCommand(long expectedVersion, String reasonCode, String note, boolean confirmedWithCreditor) {
    }

    /**
     * Sem a justificativa do estorno, documento, contato ou comprovante. A justificativa da liberação nunca sai do
     * servidor (só o motivo codificado).
     *
     * @param debtorName        nome de exibição (nulo sem {@code DEBTOR_VIEW}); anonimizado = nome substituto
     * @param agreementIds      acordos afetados (vazio sem {@code AGREEMENT_VIEW})
     * @param titleIds          títulos afetados (vazio sem {@code DEBTOR_VIEW} e sem {@code CASE_VIEW})
     * @param placedByType      USER ou SYSTEM (devolução informada pelo provedor)
     * @param placedBy          quem estornou (nulo quando o sistema)
     * @param canRelease        o ator tem {@code PAYMENT_REVIEW}, não estornou nem informou e a retenção está pendente
     * @param reversalsInWindow retenções do mesmo credor nos últimos 90 dias
     */
    record HoldView(UUID id, long version, UUID creditorId, UUID debtorId, String debtorName, boolean debtorAnonymized,
                    UUID paymentId, UUID reversalId, List<UUID> agreementIds, List<UUID> titleIds, int titleCount,
                    int installmentCount, String reversalReason, String state, Instant placedAt, String placedByType,
                    UUID placedBy, Instant releasedAt, UUID releasedBy, String releaseReason, Instant endedAt,
                    boolean canRelease, long reversalsInWindow) {

        public HoldView {
            titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
            agreementIds = agreementIds == null ? List.of() : List.copyOf(agreementIds);
        }
    }

    /** Título retido (selo) e a retenção que o segura; {@code paymentId} só com {@code PAYMENT_VIEW}. */
    record HeldTitle(UUID titleId, UUID holdId, UUID paymentId, Instant placedAt) {
    }
}
