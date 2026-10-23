package br.com.gestorcobranca.settlement.application.port.in;

import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Regras de comissionamento da assessoria versionadas por contrato de serviço (F17, RB08):
 * rascunho → publicação com vigência (quem editou não publica) → versão imutável. Alteração exige
 * {@code COMMISSION_MANAGE}; consulta e simulação, {@code COMMISSION_VIEW} ou {@code COMMISSION_MANAGE}.
 * Escopo por credor do contrato.
 */
public interface CommissionRuleUseCases {

    /** Novo rascunho do contrato (um por vez), versão = última + 1. */
    RuleSetView createDraft(RuleSetCommand command);

    RuleSetView updateDraft(UUID ruleSetId, RuleSetCommand command);

    /**
     * Publica a partir de {@code effectiveFrom}: dentro da vigência do contrato, depois do início da
     * versão anterior (que termina na véspera) e depois do último recebimento já apurado no contrato.
     * Recebimentos aguardando regra passam a ser apurados.
     */
    RuleSetView publish(UUID ruleSetId, LocalDate effectiveFrom);

    RuleSetView discard(UUID ruleSetId);

    RuleSetView get(UUID ruleSetId);

    /** Versões do credor (e do contrato, se informado), mais recentes primeiro. */
    List<RuleSetView> list(UUID creditorId, UUID contractId);

    /** Calcula um recebimento hipotético com as regras de uma versão (ou as informadas). Não grava nada. */
    CalculationView simulate(SimulateCommand command);

    record RuleSetCommand(UUID contractId, CommissionRules rules, String notes) {
    }

    /** @param status DRAFT, PUBLISHED ou DISCARDED */
    record RuleSetView(UUID id, UUID creditorId, UUID contractId, int ruleVersion, String status,
                       LocalDate effectiveFrom, LocalDate effectiveTo, CommissionRules rules, String notes,
                       UUID rulesEditedBy, Instant rulesEditedAt, UUID publishedBy, Instant publishedAt, long version) {
    }

    /**
     * @param ruleSetId   versão a simular; nulo para simular {@code rules}
     * @param paymentType LUMP_SUM ou INSTALLMENTS
     * @param receivedBy  AGENCY ou CREDITOR
     */
    record SimulateCommand(UUID ruleSetId, CommissionRules rules, BigDecimal amount, BigDecimal honorarium,
                           UUID portfolioId, Integer daysPastDue, String paymentType, String receivedBy,
                           boolean renegotiation, boolean firstReceiptOfAgreement) {
    }

    record MemoryLineView(String step, String description, BigDecimal base, BigDecimal rate, BigDecimal amount) {
    }

    /** Memória de cálculo de um recebimento (ou de um estorno, com valores negativos). */
    record CalculationView(String ruleCode, BigDecimal rate, BigDecimal recovered, BigDecimal agencyHonorarium,
                           BigDecimal creditorHonorarium, BigDecimal commissionBase, BigDecimal commission,
                           BigDecimal fixedFee, BigDecimal transferEffect, List<MemoryLineView> memory) {
    }
}
