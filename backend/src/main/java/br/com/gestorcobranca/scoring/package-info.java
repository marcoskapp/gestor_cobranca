/**
 * Score de propensão à recuperação e priorização (F23) — {@code docs/tecnico/desenho/scoring.md} e
 * {@code docs/negocio/score-propensao.md}.
 * <p>
 * Features por caso calculadas a partir de fatos recebidos por eventos de integração e da posição
 * do caso no {@code collection}, materializadas em snapshots por data de referência (sem dado
 * pessoal direto); modelos versionados (scorecard por pontos e regressão logística regularizada
 * treinada no histórico da assessoria), validação fora do tempo, promoção manual campeão ×
 * desafiante; escoragem em lote diário e sob demanda, faixas A–E e evento {@code CaseScoreChanged};
 * monitoramento de estabilidade (PSI) e desempenho realizado por safra; recuperado × esperado
 * publicado para o {@code incentive}; explicação de cada score (contribuição por feature).
 * <p>
 * Uso exclusivo para <b>priorização operacional</b>: o score nunca nega atendimento nem muda
 * condição de negociação (o {@code negotiation} não o consome).
 * <p>
 * Dependências declaradas: {@code collection} (posição dos casos) e {@code debt} (carteira dos
 * títulos de um pagamento sem carteira informada); demais fatos por eventos de
 * {@code shared.events}. O grafo também permite {@code payment} e {@code organization}, mas não são
 * necessários: os fatos de pagamento chegam por {@code PaymentConfirmed}/{@code PaymentReversed}.
 * {@code compliance}: prazos de retenção de fatos e snapshots por assessoria ({@code RetentionUseCases#retentionFor});
 * implementa {@code compliance :: spi} ({@code RetentionDefaults}: os prazos padrão do sistema).
 */
@ApplicationModule(displayName = "Scoring",
        allowedDependencies = {"shared", "collection :: usecases", "debt :: usecases", "compliance :: usecases",
                "compliance :: spi"})
package br.com.gestorcobranca.scoring;

import org.springframework.modulith.ApplicationModule;
