package br.com.gestorcobranca.collection.application.port.in;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Busca rápida de devedor e caso (Ctrl+K — revisão de produto 11/2026, item 1.3; atendimento receptivo: quem ligou
 * chega ao caso). Os devedores vêm de {@code debt :: DebtorQuickSearch} (nome com 3+ caracteres, CPF/CNPJ completo,
 * telefone completo ou código do título; escopo por credor, até 10, log de acesso quando o termo é documento ou
 * telefone) e, para quem tem {@code CASE_VIEW}, os casos desses devedores nos credores do escopo (ativos primeiro, até
 * {@value #MAX_CASES}). Sem paginação; o termo nunca vai para a URL, log técnico, evento ou métrica.
 */
public interface QuickSearchQueries {

    int MAX_CASES = 20;

    QuickSearchView search(String term);

    /**
     * @param criteria  critérios aplicados ({@code NAME}, {@code DOCUMENT}, {@code PHONE}, {@code TITLE_CODE}); vazio =
     *                  termo curto ou incompleto (nada buscado)
     * @param creditorNames  nome fantasia dos credores citados (só os do escopo)
     * @param portfolioNames nome das carteiras dos casos
     * @param truncated      havia mais devedores ou casos que o limite (refinar a busca)
     */
    record QuickSearchView(List<String> criteria, List<DebtorResult> debtors, List<CaseResult> cases,
                           Map<UUID, String> creditorNames, Map<UUID, String> portfolioNames, boolean truncated) {

        public QuickSearchView {
            criteria = List.copyOf(criteria);
            debtors = List.copyOf(debtors);
            cases = List.copyOf(cases);
            creditorNames = Map.copyOf(creditorNames);
            portfolioNames = Map.copyOf(portfolioNames);
        }
    }

    /**
     * Só o mínimo para reconhecer a pessoa: nome, documento mascarado, situação e credores do escopo.
     *
     * @param matchedBy critério que encontrou o devedor
     */
    record DebtorResult(UUID id, String name, String maskedDocument, String personType, String status,
                        String matchedBy, List<UUID> creditorIds) {

        public DebtorResult {
            creditorIds = List.copyOf(creditorIds);
        }
    }

    /** @param status situação do caso (OPEN, IN_NEGOTIATION, …, CLOSED) */
    record CaseResult(UUID id, UUID debtorId, UUID creditorId, UUID portfolioId, String status) {
    }
}
