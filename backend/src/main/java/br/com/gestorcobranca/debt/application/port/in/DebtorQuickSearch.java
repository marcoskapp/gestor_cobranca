package br.com.gestorcobranca.debt.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Busca rápida de devedores (Ctrl+K — revisão de produto 11/2026, item 1.3): por trecho do nome (mínimo de 3
 * caracteres), CPF/CNPJ completo, telefone completo ou código do título no credor (exato). Sem paginação: devolve no
 * máximo {@value #MAX_RESULTS} devedores visíveis ao ator (escopo por credor; os fora do escopo são omitidos sem
 * revelar existência), cada um com o documento mascarado e só os credores do escopo.
 * <p>
 * Exige {@code DEBTOR_VIEW}. Busca por documento ou telefone completo registra {@code PERSONAL_DATA_SEARCHED} no log
 * de acesso com o critério, a quantidade e os ids devolvidos — nunca o termo. O termo também não vai para log técnico,
 * evento ou métrica.
 */
public interface DebtorQuickSearch {

    int MAX_RESULTS = 10;

    QuickSearchResult quickSearch(String term);

    /**
     * @param criteria critérios aplicados ({@code NAME}, {@code DOCUMENT}, {@code PHONE}, {@code TITLE_CODE}); vazio
     *                 quando o termo é curto ou incompleto (nada buscado)
     * @param truncated havia mais devedores que o limite
     */
    record QuickSearchResult(List<String> criteria, List<DebtorHit> debtors, boolean truncated) {

        public QuickSearchResult {
            criteria = List.copyOf(criteria);
            debtors = List.copyOf(debtors);
        }
    }

    /**
     * @param matchedBy   critério que encontrou o devedor
     * @param creditorIds credores do escopo do ator em que o devedor tem títulos
     */
    record DebtorHit(UUID id, String name, String maskedDocument, String personType, String status, String matchedBy,
                     List<UUID> creditorIds) {

        public DebtorHit {
            creditorIds = List.copyOf(creditorIds);
        }
    }
}
