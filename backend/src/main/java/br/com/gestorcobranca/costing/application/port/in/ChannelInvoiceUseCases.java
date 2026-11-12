package br.com.gestorcobranca.costing.application.port.in;

import br.com.gestorcobranca.costing.domain.model.ChannelItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Faturas de canal e conciliação (desenho §3.5 e §13, {@code /api/v1/costing/invoices}): a gestão informa, por mês, a
 * fatura de cada item (telefonia, WhatsApp, SMS, e-mail, IA — em R$); a diferença para a estimativa é rateada na
 * proporção do custo estimado do item e o painel mostra a base "conciliado com a fatura". As faturas são da assessoria
 * inteira: ler exige {@code COST_VIEW} ou {@code COST_MANAGE} e escrever exige {@code COST_MANAGE}, as duas com acesso
 * a todos os credores. Informar, substituir ou excluir refaz o ajuste do mês na hora (com outra apuração em andamento,
 * 409 {@code costing.refresh_running}); toda alteração fica na auditoria.
 */
public interface ChannelInvoiceUseCases {

    /** Faturas dos meses (inclusive; nulos = os últimos 24 meses), do mês mais recente ao mais antigo. */
    List<InvoiceView> invoices(YearMonth from, YearMonth to);

    /**
     * Estimativa de cada item em cada mês (inclusive; nulos = os últimos 24 meses), na assessoria inteira, com a fatura
     * do item no mês quando houver — para comparar ao informar a fatura. Um item por mês × item (zero sem uso).
     */
    List<ItemEstimateView> estimates(YearMonth from, YearMonth to);

    /** Informa a fatura do mês × item; se já existe, substitui (auditado). Refaz o ajuste do mês na hora. */
    InvoiceView save(SaveInvoiceCommand command);

    /** Exclui a fatura (motivo opcional, até 500 caracteres, na auditoria) e refaz o ajuste do mês na hora. */
    void delete(UUID invoiceId, String reason);

    /**
     * @param amount   valor da fatura em R$ (zero ou mais, 2 casas); a fatura de IA em dólar entra convertida
     * @param supplier fornecedor ou descrição (opcional, até 120)
     * @param note     observação (opcional, até 500)
     */
    record SaveInvoiceCommand(YearMonth month, ChannelItem item, BigDecimal amount, String supplier, String note) {
    }

    /**
     * @param month      mês no formato {@code AAAA-MM}
     * @param estimated  estimativa do item no mês na assessoria inteira (2 casas)
     * @param adjustment fatura − estimativa (o que o ajuste rateia)
     */
    record InvoiceView(UUID id, String month, ChannelItem item, BigDecimal amount, String supplier, String note,
                       BigDecimal estimated, BigDecimal adjustment, Instant updatedAt) {
    }

    /**
     * @param month     mês no formato {@code AAAA-MM}
     * @param estimated custo estimado do item no mês (2 casas)
     * @param quantity  uso do item no mês (minutos, conversas/mensagens ou dólares de IA)
     * @param unit      unidade da quantidade: {@code MINUTES}, {@code UNITS} ou {@code USD}
     * @param invoiceId fatura do mês × item (nula sem fatura)
     * @param invoiced  valor da fatura (nulo sem fatura)
     */
    record ItemEstimateView(String month, ChannelItem item, BigDecimal estimated, BigDecimal quantity, String unit,
                            UUID invoiceId, BigDecimal invoiced) {
    }
}
