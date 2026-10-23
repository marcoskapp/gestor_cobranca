package br.com.gestorcobranca.negotiation.application.port.in;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Tabela manual de índices de correção monetária do tenant. Consulta com {@code POLICY_VIEW};
 * manutenção com {@code POLICY_MANAGE} e escopo de todos os credores (o índice serve a todas as
 * políticas da assessoria).
 */
public interface MonetaryIndexUseCases {

    IndexView create(IndexCommand command);

    IndexView update(String code, IndexCommand command);

    List<IndexView> list();

    /** Índice com todas as variações cadastradas. */
    IndexView get(String code);

    /** Cria ou corrige variações mensais (auditadas); competências não informadas ficam como estão. */
    IndexView putRates(String code, List<RateInput> rates);

    /** @param code ignorado em {@code update} */
    record IndexCommand(String code, String name, String source) {
    }

    /** @param ratePercent variação no mês (%, com sinal) */
    record RateInput(YearMonth competence, BigDecimal ratePercent) {
    }

    record RateView(YearMonth competence, BigDecimal ratePercent) {
    }

    /** @param rates vazio na listagem */
    record IndexView(UUID id, String code, String name, String source, YearMonth lastCompetence, List<RateView> rates) {
    }
}
