package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prazos padrão do sistema da retenção do score ({@code app.scoring.fact-retention-days} e
 * {@code snapshot-retention-days}; os fatos de contato usam o mesmo prazo dos fatos dos casos), informados ao compliance: a tela de Retenção os exibe e a manutenção diária
 * ({@code scoring.retention}) os usa enquanto a assessoria não configura o próprio prazo. A janela de fatos
 * ({@code app.scoring.fact-lookback-days}) vai como mínimo exigido dos fatos: se passar do mínimo da categoria
 * (730 dias), a aplicação não sobe.
 */
@Component
class ScoringRetentionDefaults implements RetentionDefaults {

    /** Fatos dos casos usados nas features (contatos, promessas, acordos, pagamentos). */
    static final String CASE_FACTS = "SCORING_CASE_FACTS";
    /** Snapshots de features com o desfecho apurado (treino e monitoramento do modelo). */
    static final String FEATURE_SNAPSHOTS = "SCORING_FEATURE_SNAPSHOTS";
    /** Fatos da inteligência de contato (tentativas, respostas e leituras por contato). */
    static final String CONTACT_FACTS = "SCORING_CONTACT_FACTS";

    private final ScoringProperties properties;

    ScoringRetentionDefaults(ScoringProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RetentionDefault> retentionDefaults() {
        // a janela de fatos das features é o mínimo que a retenção dos fatos pode ter: com lookback maior que o mínimo da
        // categoria, uma assessoria poderia apagar fatos que o score ainda usa (o compliance recusa a subida)
        return List.of(new RetentionDefault(CASE_FACTS, properties.factRetentionDays(), properties.factLookbackDays()),
                new RetentionDefault(FEATURE_SNAPSHOTS, properties.snapshotRetentionDays()),
                // mesmo padrão dos fatos dos casos (app.scoring.fact-retention-days); o perfil não tem janela mínima
                new RetentionDefault(CONTACT_FACTS, properties.factRetentionDays()));
    }
}
