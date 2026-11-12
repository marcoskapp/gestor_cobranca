package br.com.gestorcobranca.compliance.domain.model;

import static br.com.gestorcobranca.compliance.domain.model.RetentionUnit.DAYS;
import static br.com.gestorcobranca.compliance.domain.model.RetentionUnit.MONTHS;

/**
 * Categoria de dado sujeita a retenção e descarte, com a unidade e os limites do prazo.
 * <p>
 * Categorias com {@linkplain #hasSystemDefault() padrão do sistema} sempre descartam: enquanto a assessoria não
 * configura, vale o prazo padrão informado pelo módulo dono (porta {@code RetentionDefaults}); a assessoria pode
 * ajustar o prazo dentro dos limites, mas não desligar o descarte (minimização, LGPD art. 6º III). As demais nascem
 * sem política e nada é descartado até a assessoria configurar e habilitar.
 */
public enum RetentionCategory {
    /** Devedor sem títulos em cobrança (OPEN/SUSPENDED) há N meses: anonimização. */
    DEBTOR_INACTIVE(RetentionAction.ANONYMIZE, true, MONTHS, 1, 1_200, false),
    /** Arquivos de carga importados. Política declarada para o módulo ingestion. */
    IMPORT_FILES(RetentionAction.DELETE_FILE, false, MONTHS, 1, 1_200, false),
    /** Conteúdo de mensagens. Política declarada para o módulo communication (e o assistant). */
    MESSAGE_CONTENT(RetentionAction.DELETE_FILE, false, MONTHS, 1, 1_200, false),
    /** Gravações de chamadas. Política declarada para o módulo telephony. */
    CALL_RECORDINGS(RetentionAction.DELETE_FILE, false, MONTHS, 1, 1_200, false),
    /**
     * Desafios de acesso ao portal vencidos (HMAC do documento digitado, inclusive de quem não é devedor), contados do
     * vencimento. Aplicada pelo portal.
     */
    PORTAL_ACCESS_CHALLENGES(RetentionAction.DELETE_RECORD, false, DAYS, 1, 365, true),
    /**
     * Contadores do bloqueio progressivo do portal (HMAC do documento e do IP) sem uso, contados do último uso;
     * bloqueio vigente nunca é apagado. Mínimo de 7 dias: menos que isso zera a escalada do bloqueio de quem insiste.
     */
    PORTAL_ACCESS_THROTTLES(RetentionAction.DELETE_RECORD, false, DAYS, 7, 365, true),
    /**
     * IP e agente do navegador das sessões encerradas do portal, contados do encerramento (a sessão continua, sem
     * eles, no funil e na trilha). Mínimo de 30 dias para apuração de incidentes de segurança.
     */
    PORTAL_SESSION_CLIENT_DATA(RetentionAction.CLEAR_FIELDS, false, DAYS, 30, 1_825, true),
    /**
     * Fatos dos casos usados nas features do score (contatos, promessas, acordos, pagamentos), contados da data do
     * fato. Mínimo de 730 dias: a janela de fatos das features ({@code app.scoring.fact-lookback-days}).
     */
    SCORING_CASE_FACTS(RetentionAction.DELETE_RECORD, false, DAYS, 730, 3_650, true),
    /**
     * Snapshots de features com o desfecho já apurado (treino e monitoramento do modelo), contados da data de
     * referência. Mínimo de 365 dias: a janela de treino padrão.
     */
    SCORING_FEATURE_SNAPSHOTS(RetentionAction.DELETE_RECORD, false, DAYS, 365, 3_650, true),
    /**
     * Fatos da inteligência de contato do score (tentativas, respostas e leituras por contato, sem o valor do contato),
     * contados da data do fato; o perfil por contato é recalculado com os fatos que ficam. Mesmos limites dos fatos dos
     * casos.
     */
    SCORING_CONTACT_FACTS(RetentionAction.DELETE_RECORD, false, DAYS, 730, 3_650, true);

    private final RetentionAction action;
    private final boolean executableHere;
    private final RetentionUnit unit;
    private final int minAmount;
    private final int maxAmount;
    private final boolean systemDefault;

    RetentionCategory(RetentionAction action, boolean executableHere, RetentionUnit unit, int minAmount, int maxAmount,
                      boolean systemDefault) {
        this.action = action;
        this.executableHere = executableHere;
        this.unit = unit;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.systemDefault = systemDefault;
    }

    /** Ação de descarte aplicável à categoria. */
    public RetentionAction action() {
        return action;
    }

    /** Executada pelo módulo compliance; as demais são consultadas pelos módulos donos dos dados. */
    public boolean isExecutableHere() {
        return executableHere;
    }

    public RetentionUnit unit() {
        return unit;
    }

    public int minAmount() {
        return minAmount;
    }

    public int maxAmount() {
        return maxAmount;
    }

    /** O módulo dono informa um prazo padrão e o descarte nunca fica desligado. */
    public boolean hasSystemDefault() {
        return systemDefault;
    }

    public boolean accepts(int amount) {
        return amount >= minAmount && amount <= maxAmount;
    }
}
