package br.com.gestorcobranca.publicapi.domain.model;

import java.util.Collection;

/**
 * Quantidades faturáveis de um ciclo, todas inteiras: requisições cobráveis, MB recebidos, MB enviados e registros.
 * <p>
 * <b>Tráfego (decisão 607):</b> a unidade de cobrança é o MB binário (1 MB = 1.048.576 bytes). Não se bilheta em
 * bytes: o tráfego cobrável (só das requisições 2xx/3xx) é somado no ciclo <i>por token e por direção</i> e arredondado
 * para cima para MB inteiros — havendo qualquer tráfego, no mínimo 1 MB. O arredondamento não é por requisição (que
 * inflaria o valor em integrações com muitas chamadas pequenas) nem só no total do credor (o detalhamento por token não
 * fecharia com o total); a quantidade do credor é a soma dos MB de cada token.
 */
public record BillableUsage(long requests, long mbIn, long mbOut, long records) {

    public static final long BYTES_PER_MB = 1_048_576L;

    public static final BillableUsage ZERO = new BillableUsage(0, 0, 0, 0);

    /** MB faturáveis de um total de bytes: arredondado para cima (1 byte → 1 MB; 1.048.577 bytes → 2 MB). */
    public static long megabytes(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return (bytes - 1) / BYTES_PER_MB + 1;
    }

    /** Quantidades faturáveis de um token no ciclo. */
    public static BillableUsage ofToken(UsageTotals totals) {
        return new BillableUsage(totals.billableRequests(), megabytes(totals.billableBytesIn()),
                megabytes(totals.billableBytesOut()), totals.records());
    }

    /** Quantidades do credor: soma das quantidades de cada token (MB já arredondados por token e direção). */
    public static BillableUsage sumOfTokens(Collection<UsageTotals> perToken) {
        return perToken.stream().map(BillableUsage::ofToken).reduce(ZERO, BillableUsage::plus);
    }

    public BillableUsage plus(BillableUsage o) {
        return new BillableUsage(Math.addExact(requests, o.requests), Math.addExact(mbIn, o.mbIn),
                Math.addExact(mbOut, o.mbOut), Math.addExact(records, o.records));
    }
}
