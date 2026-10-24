package br.com.gestorcobranca.scoring.domain.model;

/**
 * Catálogo fechado das features permitidas no score (lista documentada em
 * {@code docs/negocio/score-propensao.md} §4). Todas descrevem a <b>dívida e o comportamento de
 * pagamento/contato</b> no relacionamento de cobrança; nenhuma é dado pessoal direto nem variável
 * sensível ou proxy dela (raça, religião, gênero, idade, estado civil, localização, CEP, DDD,
 * renda presumida, tipo de pessoa, domínio de e-mail…). Uma feature nova exige mudança de código,
 * revisão e atualização do documento — não há feature configurável por tenant.
 * <p>
 * O valor guardado é o <b>bruto</b> (dias, contagens, R$, 0/1); a transformação abaixo é aplicada
 * só pela regressão logística, antes da padronização.
 */
public enum Feature {

    DAYS_OVERDUE("Dias de atraso do título mais antigo", Transform.LOG1P),
    OPEN_BALANCE("Saldo em aberto (R$)", Transform.LOG1P),
    OPEN_TITLES("Títulos em aberto", Transform.LOG1P),
    CASE_AGE_DAYS("Dias desde a abertura do caso", Transform.LOG1P),
    CONTACT_ATTEMPTS_90D("Tentativas de contato nos últimos 90 dias", Transform.LOG1P),
    EFFECTIVE_CONTACTS_90D("Contatos efetivos (com a pessoa certa) nos últimos 90 dias", Transform.LOG1P),
    DAYS_SINCE_EFFECTIVE_CONTACT("Dias desde o último contato efetivo", Transform.RECENCY),
    RESPONSIVE_CHANNELS_90D("Canais com resposta do devedor nos últimos 90 dias", Transform.IDENTITY),
    PROMISES_KEPT("Promessas cumpridas no caso", Transform.LOG1P),
    PROMISES_BROKEN("Promessas quebradas no caso", Transform.LOG1P),
    PRIOR_AGREEMENTS("Acordos anteriores com o credor", Transform.LOG1P),
    BROKEN_AGREEMENTS("Acordos quebrados com o credor", Transform.LOG1P),
    PARTIAL_PAYMENTS_365D("Pagamentos confirmados no caso nos últimos 365 dias", Transform.LOG1P),
    DAYS_SINCE_LAST_PAYMENT("Dias desde o último pagamento confirmado", Transform.RECENCY),
    ACTIVE_AGREEMENT("Acordo em vigor", Transform.IDENTITY),
    PENDING_PROMISE("Promessa de pagamento pendente", Transform.IDENTITY),
    PORTFOLIO_RECOVERY_RATE("Taxa histórica de pagamento da carteira", Transform.IDENTITY);

    /** Transformação usada pela regressão logística. */
    public enum Transform {
        /** ln(1 + max(0, x)); ausente = 0. */
        LOG1P,
        /** 1 / (1 + dias/30): 1 = hoje, tende a 0 com o tempo; ausente ("nunca") = 0. */
        RECENCY,
        /** valor como está; ausente = média do treino (imputação). */
        IDENTITY
    }

    private final String label;
    private final Transform transform;

    Feature(String label, Transform transform) {
        this.label = label;
        this.transform = transform;
    }

    public String label() {
        return label;
    }

    public Transform transform() {
        return transform;
    }

    /** Valor transformado; {@code null} somente para IDENTITY ausente (imputação pelo modelo). */
    public Double transformed(Double raw) {
        return switch (transform) {
            case LOG1P -> raw == null ? 0.0 : Math.log1p(Math.max(0.0, raw));
            case RECENCY -> raw == null ? 0.0 : 1.0 / (1.0 + Math.max(0.0, raw) / 30.0);
            case IDENTITY -> raw;
        };
    }
}
