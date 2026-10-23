package br.com.gestorcobranca.incentive.domain.model;

/**
 * Faixa de atraso (dimensão de segmentação dos indicadores e dos planos, §2.5). Catálogo fixo
 * para que fatos, metas e multiplicadores sejam comparáveis entre planos e versões.
 */
public enum DelayBucket {
    D0_30(0, 30),
    D31_90(31, 90),
    D91_180(91, 180),
    D181_360(181, 360),
    D361_PLUS(361, Integer.MAX_VALUE),
    /** Atraso não informado pelo produtor do fato. */
    UNKNOWN(-1, -1);

    private final int fromDays;
    private final int toDays;

    DelayBucket(int fromDays, int toDays) {
        this.fromDays = fromDays;
        this.toDays = toDays;
    }

    public static DelayBucket of(Integer daysPastDue) {
        if (daysPastDue == null || daysPastDue < 0) {
            return UNKNOWN;
        }
        for (DelayBucket bucket : values()) {
            if (bucket != UNKNOWN && daysPastDue >= bucket.fromDays && daysPastDue <= bucket.toDays) {
                return bucket;
            }
        }
        return UNKNOWN;
    }
}
