package br.com.gestorcobranca.pendingwork.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Pendências do usuário da sessão (sempre o próprio; não há consulta de outra pessoa). */
public interface PendingWorkUseCases {

    /**
     * Agrega as fontes que se aplicam às permissões do usuário. Fontes que falham ou passam do limite
     * de tempo aparecem em {@code unavailable}; as demais são devolvidas normalmente.
     */
    PendingWorkSummary mine();

    /**
     * @param generatedAt instante da consulta
     * @param total       soma das contagens dos itens
     * @param overdue     itens vencidos (soma)
     * @param dueToday    itens que vencem hoje (soma)
     * @param items       pendências por tipo: vencidas primeiro, depois as de hoje, depois as demais
     * @param unavailable fontes que não responderam
     */
    record PendingWorkSummary(Instant generatedAt, long total, long overdue, long dueToday, List<ItemView> items,
                              List<UnavailableSource> unavailable) {
    }

    /**
     * @param type        código estável ({@code <modulo>.<pendencia>})
     * @param source      fonte (módulo dono)
     * @param area        nome da área (ex.: "Pagamentos")
     * @param severity    OVERDUE, DUE_TODAY ou NORMAL
     * @param oldestAt    desde quando o item mais antigo espera
     * @param nextDueDate prazo mais próximo (inclusive vencido), quando a pendência tem prazo
     * @param route       rota do frontend; com um único item, a do próprio item
     * @param queryParams filtro da tela de destino
     */
    record ItemView(String type, String source, String area, String label, String hint, long count, long overdue,
                    long dueToday, String severity, Instant oldestAt, LocalDate nextDueDate, String route,
                    Map<String, String> queryParams) {
    }

    /** @param reason TIMEOUT, ERROR ou BUSY (executor saturado) */
    record UnavailableSource(String source, String area, String reason) {
    }
}
