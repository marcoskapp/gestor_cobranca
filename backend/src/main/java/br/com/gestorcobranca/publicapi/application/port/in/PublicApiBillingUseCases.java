package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelo de faturamento da API pública: extrato por ciclo de consumo do credor, com detalhamento por token e rota.
 * Preços e dia de fechamento do ciclo vêm das condições de cobrança das integrações do contrato do credor (módulo
 * organization, {@code ContractIntegrationPricingUseCases}); este módulo não mantém tabela de preços. Não cobra: deixa
 * o extrato pronto para faturar. Extratos respeitam o escopo por credor.
 * <p>
 * Ciclo: fecha no fim do dia de fechamento do contrato e o próximo começa às 00:00 do dia seguinte (fuso de negócio);
 * meses sem o dia fecham no último dia. Cobrança só de requisições 2xx/3xx; tráfego em MB inteiros (1 MB = 1.048.576
 * bytes, arredondado para cima no total do ciclo por token e direção).
 */
public interface PublicApiBillingUseCases {

    /**
     * Prévia (sem gravar) do próximo ciclo a fechar do credor — o mais antigo ainda sem extrato, encerrado ou em
     * curso —, com o consumo medido até agora e as condições do contrato que se aplicam a ele.
     */
    StatementView preview(UUID creditorId);

    /**
     * Fecha um ciclo do credor e grava o extrato.
     * <ul>
     *   <li>{@code periodStart} nulo: fecha o próximo ciclo a fechar, que precisa ter terminado;</li>
     *   <li>{@code periodStart} de um ciclo já fechado: gera nova revisão (exige {@code reason}; a anterior
     *       permanece), com o mesmo período.</li>
     * </ul>
     */
    StatementView close(UUID creditorId, LocalDate periodStart, String reason);

    /**
     * Fecha, em ordem, todos os ciclos já encerrados do credor que ainda não têm extrato (fechamento automático
     * diário). Devolve quantos extratos foram gravados.
     */
    int closeEndedCycles(UUID creditorId);

    StatementView statement(UUID statementId);

    /** @param month filtra pelo mês do fechamento (fim do ciclo); opcional */
    PageResponse<StatementView> statements(UUID creditorId, YearMonth month, PageQuery page);

    /** Extrato em CSV (linhas e detalhamento); registra {@code DATA_EXPORTED}. */
    void exportStatement(UUID statementId, OutputStream output);

    /**
     * Linha do extrato: quantidades inteiras (requisições, MB, registros; 1 para a mensalidade).
     *
     * @param metric MONTHLY_FEE, REQUESTS, MB_IN, MB_OUT ou RECORDS
     */
    record StatementLine(String metric, long quantity, long included, long billable, BigDecimal unitPrice,
                         BigDecimal amount) {
    }

    /**
     * Detalhamento por token (requisições, requisições cobráveis, MB recebidos/enviados faturáveis — arredondados no
     * token — e registros) ou por rota (requisições, cobráveis e registros: o tráfego é faturado por token).
     */
    record StatementBreakdown(String kind, String key, String label, Map<String, Long> totals) {
    }

    /**
     * @param id              nulo na prévia
     * @param revision        0 na prévia
     * @param periodEnd       dia de fechamento do ciclo (inclusivo); o próximo ciclo começa às 00:00 do dia seguinte
     * @param ended           o ciclo já terminou (pode ser fechado)
     * @param contractNumber  contrato cujas condições foram aplicadas (nulo sem condições)
     * @param pricingVersion  versão das condições do contrato aplicada (nulo sem condições: valor zero)
     * @param billableMbIn    MB recebidos faturáveis (soma dos tokens)
     * @param billableMbOut   MB enviados faturáveis (soma dos tokens)
     * @param usageHash       SHA-256 do consumo usado (verificação de reprodutibilidade)
     */
    record StatementView(UUID id, UUID creditorId, LocalDate periodStart, LocalDate periodEnd, boolean ended,
                         int revision, int usageClosingDay, UUID contractId, String contractNumber, UUID pricingId,
                         Integer pricingVersion, long requests, long billableRequests, long billableMbIn,
                         long billableMbOut, long records, BigDecimal totalAmount, String currency,
                         List<StatementLine> lines, List<StatementBreakdown> breakdown, String usageHash,
                         Instant closedAt, UUID closedBy, String reason) {
    }
}
