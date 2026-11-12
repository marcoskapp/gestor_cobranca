package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>Uso interno de outros módulos (collection); não exposto via web.</strong> Atributos do devedor usados como
 * critério de fila e de segmento, sem dado pessoal: tipo de pessoa (docs/tecnico/desenho/cobranca-b2b.md §9) e fim da
 * novidade dos contatos recebidos na carga (docs/tecnico/desenho/contatos-recebidos.md §4). Lido na abertura do caso;
 * depois, a novidade chega pelo evento {@code DebtorContactNoveltyChanged}.
 */
public interface DebtorSegmentationQueries {

    /** Limite de devedores por consulta. */
    int MAX_DEBTORS = 1000;

    /**
     * Atributos dos devedores informados (até {@value #MAX_DEBTORS}); ids inexistentes ou fora do escopo do ator são
     * omitidos.
     */
    Map<UUID, DebtorSegmentation> segmentation(Collection<UUID> debtorIds);

    /**
     * @param personType      INDIVIDUAL ou COMPANY
     * @param newContactUntil fim da novidade dos contatos novos ainda sem tentativa (nulo = nenhum)
     */
    record DebtorSegmentation(UUID debtorId, String personType, Instant newContactUntil) {
    }
}
