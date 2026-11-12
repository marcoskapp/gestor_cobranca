# Desenho — Distribuição priorizada por desempenho do negociador (proposta de 12/11/2026)

Pedido do responsável em 12/11/2026: uma rotina de distribuição automática e parametrizável de carga para as equipes,
que entregue primeiro os casos de maior potencial aos negociadores mais eficientes. A classificação combina o
**quartil de qualidade** (nota da monitoria) com o **quartil de eficiência** (potencial de reversão do operador e/ou
atingimento de meta), dentro do sistema de filas que já existe.

Status: **fase 1 aprovada** em 12/11/2026 com as respostas do §10. Decisões a partir da 1264.

## 1. O que existe hoje

| Peça | Onde | Situação |
| --- | --- | --- |
| Filas com critérios (credor, carteira, atraso, saldo, score, vulnerável, contato novo, PF/PJ), prioridade e ordem (`HIGHEST_SCORE`, `HIGHEST_BALANCE`…) | `collection.queue` (collection.md §3.1) | pronto |
| "Próximo caso" (o operador puxa da fila das suas equipes) | `CaseWorkService.nextCase` | pronto, sem noção de desempenho |
| Distribuição em lote `ROUND_ROBIN` / `LEAST_LOADED` com capacidade por operador | `CaseDistributor` (função pura), `POST /queues/{id}/distribute` | **manual** (o supervisor dispara); operador sem peso nem nível |
| Redistribuição | `POST /redistributions` (sempre menor carga) | pronto |
| Distribuição automática | só nas contestações (`dispute_settings.distribution_mode`) | não existe para casos |
| **Qualidade** por operador | `incentive.quality_evaluation` → `qaScoreAverage` (0–10) por período | calculado na consulta, sem agregado |
| **Potencial de reversão** por operador (realizado ÷ esperado pelo score dos casos que recebeu) | `incentive.expected_recovery_fact` → `expectedRecoveryRatio` (scoring §7, remuneracao-variavel §2.5) | pronto, só para safras encerradas |
| **Atingimento de meta %** | `incentive.goal_target` + cálculo do extrato (`StatementCalculator.goal`) | só dentro do extrato/prévia; não há indicador |
| Potencial do caso | `collection_case.score` (propensão 0–1000) e saldo elegível | pronto |

Limite de arquitetura: o `collection` não pode depender do `incentive` (ModularityTest). A classificação chega ao
`collection` por **evento** e fica numa projeção própria.

## 2. Visão geral

```
incentive (classifica)                         collection (distribui)
  qualidade (monitoria)  ─┐                      projeção operator_tier (só o nível)
  potencial de reversão  ─┼─► nível A/B/C/D ──►  estratégia "Por nível de desempenho"
  atingimento de meta   ─┘   (evento, com      ─  casos ordenados por valor esperado
  parâmetros + revisão humana     revisão)        (score × saldo) em faixas
                                                 ─  rotina automática por fila (horários)
                                                 ─  "Próximo caso" respeita o nível
```

## 3. Classificação do negociador (módulo `incentive`)

### 3.1 Quartis

Para cada operador ativo com `CASE_WORK`, na janela de apuração (padrão: últimos 90 dias, ❓):

* **Quartil de qualidade (Q1–Q4)**: posição da nota média da monitoria entre os operadores da assessoria (ou da
  equipe, ❓ §10-Q5). Mínimo de avaliações para entrar no ranking (padrão: 5); falha crítica no período ou nota abaixo
  do portão da monitoria (padrão 7,0, o mesmo do plano de comissão) → no máximo Q2.
* **Quartil de eficiência (Q1–Q4)** ❓ §10-Q1: índice ponderado de
  * **potencial de reversão** = realizado ÷ esperado (já ajusta pela dificuldade da carteira recebida — o indicador
    mais justo da remuneração variável);
  * **atingimento de meta %** (meta individual de recuperado no período; novo indicador calculado pela mesma regra do
    extrato).
  Mínimo de casos na safra para entrar (padrão: 30).

Quartil 4 = melhor 25%. Empates e grupos com menos de 4 pessoas: posição por percentil (sem quartil vazio forçado).

### 3.2 Nível de desempenho

Matriz qualidade × eficiência, parametrizável (padrão abaixo), resultando em quatro níveis:

| | Eficiência Q4 | Q3 | Q2 | Q1 |
| --- | --- | --- | --- | --- |
| **Qualidade Q4** | A | A | B | C |
| **Q3** | A | B | B | C |
| **Q2** | B | C | C | D |
| **Q1** | C | C | D | D |

A qualidade pesa tanto quanto a eficiência: quem converte muito com conduta ruim não fica no topo (CDC e LGPD na
cobrança; critério de sucesso = acordo cumprido).

* **Sem dados suficientes** (operador novo, poucas avaliações ou safra) → nível padrão parametrizável (padrão: **B**,
  para não punir quem está começando).
* A classificação **não usa** dado sensível nem atributo pessoal: só monitoria e resultados.

### 3.3 Revisão humana antes de valer

Decisão automática que muda a carga das pessoas passa por revisão humana (premissa de conformidade):

1. A rotina apura a **classificação proposta** (semanal ou mensal, ❓) e avisa os supervisores em "Minhas pendências".
2. O supervisor (da equipe) ou o gestor revê: vê os quartis, a matriz e os números de cada pessoa (no escopo
   `PERFORMANCE_VIEW_TEAM/ALL`) e pode **ajustar o nível com motivo** (ex.: férias, troca de carteira, licença).
3. Ao **publicar**, os níveis passam a valer e o `incentive` publica `NegotiatorTierChanged(userId, tier, validFrom)`
   — só o nível, nunca a nota, o quartil ou o valor recuperado.
4. Sem publicação no prazo (parametrizável, padrão 3 dias úteis), vale a classificação anterior.

Tudo com `AuditEntry` (proposta, ajuste com motivo, publicação).

## 4. Distribuição (módulo `collection`)

### 4.1 Projeção e potencial do caso

* `collection.negotiator_tier(user_id, tier, valid_from)` — projeção do evento. Só o nível.
* **Valor esperado do caso** = probabilidade do score × saldo elegível (sem score → saldo × probabilidade média da
  carteira). Faixas de potencial (alta, média, baixa) por tercis dos casos disponíveis na fila no momento.

### 4.2 Estratégia nova `TIERED` ("Por nível de desempenho")

Para filas com essa estratégia, o planejador (`CaseDistributor`, função pura):

1. Ordena os casos disponíveis por valor esperado (maior primeiro).
2. Entrega a faixa alta preferencialmente aos níveis A, depois B; a média a B e C; a baixa a C e D — sempre
   respeitando a capacidade (`max_cases_per_user`) e o escopo de credores. Quando um nível esgota a capacidade, o caso
   desce ao nível seguinte (nunca fica sem dono por causa do nível).
3. **Capacidade por nível** ❓ §10-Q2: opcional, multiplicador (padrão A 100%, B 100%, C 100%, D 100%; o gestor
   pode usar, ex., A 120% e D 80%).
4. **Fatia de desenvolvimento** (padrão 15%): parte dos casos de alto potencial vai por rodízio a todos os níveis,
   para que todos tenham chance de melhorar o potencial de reversão — sem ela a classificação se autoalimenta (quem
   recebe os melhores casos tende a continuar no topo).
5. Cada atribuição grava o motivo no histórico (`case_assignment`): `DISTRIBUTION` + nível + faixa de potencial —
   explicável e auditável.

**"Próximo caso"** numa fila `TIERED`: o operador recebe primeiro os casos da faixa do seu nível; sem casos ali, os da
faixa seguinte. A fatia de desenvolvimento vale também aqui.

### 4.3 Rotina automática por fila ❓ §10-Q3

Configuração da fila: `auto_distribution` (ligado/desligado), horários (ex.: 08:00 e 13:00, no fuso da assessoria,
só em dias úteis) e limite por rodada. Job com ShedLock, por fila, usando a mesma regra do "Distribuir" manual
(que continua existindo). Considera só operadores **disponíveis** (sessão ativa ou presença na telefonia, ❓) para não
entregar casos a quem está ausente; a carga que já existe conta para a capacidade.

### 4.4 Compliance

* Fila de **atenção especial** (vulneráveis) continua com prioridade e equipe próprias; o nível não a substitui. Opção:
  exigir nível mínimo (ex.: B) e qualidade Q3+ para essa fila.
* Escopo por credor, elegibilidade e "sem próxima ação futura" continuam valendo.
* O nível não altera política de contato, alçada ou oferta: só quem recebe o caso.

## 5. Telas

* **Organização › Classificação de desempenho** (gestor): parâmetros (janela, pesos, mínimos, matriz, nível padrão,
  periodicidade, prazo de revisão, fatia de desenvolvimento, capacidade por nível).
* **Classificação da equipe** (supervisor, gestor): proposta da rodada com quartis, nível, números e ajuste com motivo;
  "Publicar".
* **Filas**: estratégia "Por nível de desempenho" e a aba "Distribuição automática"; o diálogo "Distribuir" mostra a
  prévia por nível (quantos casos de cada faixa irão a cada nível) antes de confirmar.
* **Meu desempenho** (operador) ❓ §10-Q4: o próprio nível e o que o compõe (quartis, não a posição exata nem os
  outros operadores).

## 6. Permissões

* `TIER_MANAGE` (nova): parâmetros da classificação — GESTOR, ADMIN.
* `TIER_REVIEW` (nova): revisar e publicar a classificação no seu escopo de pessoas — SUPERVISOR (equipe), GESTOR
  (todos).
* Configurar a fila e a rotina: `QUEUE_MANAGE` (já existe). O nível de cada pessoa só aparece para quem tem
  `PERFORMANCE_VIEW_TEAM/ALL` no escopo; na prévia do "Distribuir" sem essa permissão, só as quantidades por nível.

## 7. Privacidade e trabalho

* Classificação é dado pessoal do empregado (LGPD art. 20: decisão automatizada que afeta interesses → direito a
  explicação e revisão): por isso a revisão humana antes de valer, a explicação no "Meu desempenho" e a trilha.
* Nenhum valor individual (nota, recuperado, posição) sai do `incentive`; o `collection` conhece só o nível.
* Retenção da classificação: a mesma dos fatos de desempenho do incentive.

## 8. Indicadores para acompanhar

* Recuperado ÷ esperado por nível (a classificação funciona se A > B > C > D sem piorar a quebra).
* Quebra precoce e reclamações por nível (o topo não pode crescer à custa de conduta).
* Mobilidade entre níveis por rodada (classificação estática demais indica autoalimentação).

## 9. Fases

1. **Fase 1:** classificação (quartis, matriz, revisão e publicação), projeção no collection, estratégia `TIERED` no
   "Distribuir" e no "Próximo caso", rotina automática por horário, telas e indicadores do §8 no painel de
   produtividade.
2. **Fase 2:** disponibilidade pela presença da telefonia, níveis mínimos por fila, simulação ("e se") da
   classificação e da distribuição antes de publicar.

## 10. Decisões do responsável (12/11/2026)

* **Q1 — eficiência:** só o **potencial de reversão** (realizado ÷ esperado). Sem atingimento de meta na fase 1.
* **Q2 — efeito do nível:** **prioridade e capacidade**: níveis altos recebem primeiro os casos de maior valor
  esperado **e** mais casos (multiplicador de capacidade por nível), mantendo a **fatia de desenvolvimento de 15%**
  em rodízio para a classificação não se autoalimentar. Padrão do multiplicador: A 120%, B 110%, C 100%, D 80%
  (parametrizável; nunca abaixo de 50% nem acima de 200%).
* **Q3 — disparo:** **rotina automática por horário** em cada fila, além do "Distribuir" manual e do "Próximo caso".
* **Q4 — transparência:** o operador **vê o próprio nível** e o que o compõe (quartis e base), sem ranking dos colegas.
* **Q5 — escopo:** quartis calculados na **assessoria inteira**.

## 11. Contrato da fase 1

### 11.1 Evento (`shared.events.incentive`)

```java
/** Nível de desempenho publicado (só o nível; nunca nota, quartil ou valores). tier nulo = sem nível (volta ao padrão). */
public record NegotiatorTierChanged(UUID eventId, UUID tenantId, Instant occurredAt, UUID userId, String tier,
                                    LocalDate validFrom, UUID publicationId) implements IntegrationEvent { }
```
Um evento por pessoa a cada publicação (mesmo sem mudança de nível, para a projeção ficar idempotente pela
`publicationId`). Siga o padrão dos demais eventos de `shared.events` (interface, serialização, `eventId`).

### 11.2 `incentive` (classificação)

* Tabelas (V18xx, RLS, checks): `incentive.tier_settings` (janela em dias 30–365 padrão 90; mínimo de avaliações
  padrão 5; mínimo de casos na safra padrão 30; portão de qualidade padrão 7,0; matriz 4×4 de níveis em jsonb com o
  padrão do §3.2; nível padrão sem dados `B`; periodicidade `WEEKLY|MONTHLY` padrão `MONTHLY`; prazo de revisão em
  dias úteis padrão 3), `incentive.tier_round` (rodada: período, situação `PROPOSED|PUBLISHED|EXPIRED`, criada,
  publicada por/em), `incentive.tier_assignment` (rodada × pessoa: quartil de qualidade, quartil de eficiência,
  amostras, nível proposto, nível final, ajustado por, motivo do ajuste).
* Rotina (ShedLock) cria a rodada na periodicidade, avisa supervisores em "Minhas pendências"; publicação manual
  (`TIER_REVIEW`) ou expiração (vale a anterior). Publicação emite os eventos.
* REST `/api/v1/incentive/tiers`: `GET/PUT /settings` (`TIER_MANAGE`), `GET /rounds`, `GET /rounds/{id}` (pessoas no
  escopo de quem vê: `PERFORMANCE_VIEW_TEAM/ALL`), `POST /rounds` (apurar agora, `TIER_MANAGE`), `PUT
  /rounds/{id}/people/{userId}` (ajuste com motivo, `TIER_REVIEW`), `POST /rounds/{id}/publish` (`TIER_REVIEW`; o
  supervisor publica só a sua equipe? — não: publicação é da rodada inteira, por quem tem escopo total; o supervisor
  ajusta a sua equipe), `GET /me` (o próprio nível e composição, `PERFORMANCE_VIEW_OWN`).

### 11.3 `collection` (distribuição)

* Projeção `collection.negotiator_tier(user_id, tier, valid_from, publication_id)` do evento.
* `collection.queue`: estratégia nova `TIERED`; colunas `auto_distribution boolean`, `auto_distribution_times`
  (lista de horários `HH:mm`), `auto_distribution_limit`; parâmetros da assessoria em `collection.settings`:
  `tier_capacity` (jsonb `{A:120,B:110,C:100,D:80}`), `tier_development_share` (padrão 15), `tier_default` (padrão B,
  o mesmo da classificação).
* `CaseDistributor`: ramo `TIERED` (§4.2); `Member` ganha `tier`; casos com valor esperado. Prévia sem gravar
  (`POST /queues/{id}/distribute/preview`).
* "Próximo caso" em fila `TIERED` (§4.2).
* Job `collection.auto-distribution` (ShedLock, a cada minuto verifica filas com horário vencido no fuso de negócio,
  dias úteis), uma rodada por horário por dia.
* Motivo gravado no histórico: `case_assignment.mode = DISTRIBUTION` + colunas novas `tier` e `potential_band`
  (`HIGH|MEDIUM|LOW|DEVELOPMENT`).

## 12. Implementação da fase 1 — classificação (12/11/2026)

Parte do `incentive` (§3, §11.1 e §11.2). A distribuição no `collection` (§4, §11.3) é outra entrega.

### 12.1 Mapa

| Peça | Onde |
| --- | --- |
| Evento `NegotiatorTierChanged` (record exatamente como no §11.1) | `shared/events/incentive/NegotiatorTierChanged.java`; teste `NegotiatorTierChangedCompatibilityTest` |
| Tabelas `tier_settings`, `tier_round`, `tier_assignment` (RLS forçada, checks, sem `delete` para a aplicação) | `db/migration/incentive/V1892__incentive_negotiator_tier.sql` |
| Regras puras: quartis, portão, mínimos, matriz, rodada, ajuste, agenda | `incentive/domain/model/tier/*`, `domain/service/TierClassifier`, `TierSchedule`; testes `TierClassifierTest`, `TierRulesTest` |
| Casos de uso | `application/port/in/TierUseCases` (REST) e `TierScheduling` (rotina); `application/service/TierService` |
| Insumos | `application/port/out/TierSampleReader` → `JdbcTierSampleReader` (`quality_evaluation` por `evaluated_on`, `expected_recovery_fact` por `reference_date`) |
| Persistência | `domain/repository/TierRepository` → `JdbcTierRepository` |
| Rotina | `IncentiveJobs.tierRounds` (ShedLock `incentive.tier-rounds`, `app.incentive.tier-cron`, padrão 05:40 no fuso de operação) |
| Pendência | `IncentivePendingWork` → `incentive.tier_review` (pendencias.md) |
| REST | `web/TierController` (`/api/v1/incentive/tiers`) |
| Telas | `frontend/src/app/features/incentive/tiers/` (`tier-page`, `tier-matrix`, `tier-adjust-dialog`, `my-tier-card`); rota `/remuneracao/classificacao`; cartão no "Meu desempenho" |
| Teste de integração | `NegotiatorTierIT` |

Versão da migration: `V1892` segue a última da faixa do módulo (`V1891`); uma versão menor (ex.: `V1807`) rodaria
fora de ordem nos ambientes já migrados.

### 12.2 Regras como implementadas

* **Quem é classificado:** usuários **ativos** (situação `ACTIVE`) com `CASE_WORK` no momento da apuração — inclui
  supervisores e gestores com `CASE_WORK`; quem aguarda ativação fica de fora (vale o padrão no `collection`).
* **Janela:** `windowDays` dias corridos terminando **ontem** (fuso de operação). Cada rodada guarda uma cópia dos
  parâmetros (`tier_round.settings`), para a explicação não mudar se os parâmetros mudarem.
* **Qualidade:** nota média das avaliações de monitoria na janela; entra no ranking quem tem `minEvaluations`.
  **Portão:** alguma avaliação com falha crítica na janela **ou** média abaixo de `qualityGate` → quartil limitado a
  Q2 (o ranking usa a média sem o limite; o limite vale depois).
* **Eficiência:** Σ realizado ÷ Σ esperado das safras de score com data de referência na janela (4 casas); entra
  quem tem `minCases` casos nas safras e esperado maior que zero.
* **Quartis:** posição percentil pelo meio do posto, `p = (abaixo + iguais ÷ 2) ÷ n`; Q1 se `p < 25%`, Q2 se
  `< 50%`, Q3 se `< 75%`, senão Q4. Empatados ficam no mesmo quartil; não há quartil vazio ou cheio forçado (uma
  pessoa sozinha, ou todos empatados, ficam em Q3; duas pessoas, Q2 e Q4). Só quem tem a amostra mínima entra no
  ranking daquela dimensão.
* **Nível:** célula da matriz; faltando qualquer um dos quartis, o nível padrão (o portão não se aplica a quem está
  sem amostra).
* **Revisão:** prazo = data da apuração + `reviewBusinessDays` dias úteis (calendário de feriados do compliance);
  o prazo é o último dia em que se ajusta e publica. Ajuste exige `TIER_REVIEW`, pessoa no escopo de desempenho
  (equipes que supervisiona ou todos) e motivo de 10 a 2000 caracteres; ninguém ajusta o próprio nível. Voltar ao
  nível proposto também é ajuste (com motivo). O ajuste lê a rodada com `select ... for share` antes de conferir que
  está em revisão (a publicação e a expiração, que atualizam a rodada, esperam o ajuste terminar), e o gatilho
  `trg_tier_assignment_round_open` (V1892) recusa no banco qualquer `UPDATE` de `tier_assignment` com a rodada fora de
  `PROPOSED`.
* **Publicação:** `TIER_REVIEW` + `PERFORMANCE_VIEW_ALL`, da rodada inteira, até o prazo. Emite um
  `NegotiatorTierChanged` (nível final, `validFrom` = data da publicação, `publicationId` = id da rodada) por pessoa
  da rodada que **ainda** está ativa com `CASE_WORK`, e um com `tier` nulo para quem estava na publicação anterior e
  não recebeu nível agora (desativado ou sem `CASE_WORK`). Quem entrou depois da apuração não recebe evento.
* **Rotina diária:** duas etapas em transações separadas (`TierScheduling#expireOverdueRound` e
  `#openScheduledRound`, chamadas uma a uma por `IncentiveJobs.tierRounds`): expira a rodada em revisão com o prazo
  vencido (`EXPIRED`; nada é publicado, vale a anterior) e abre a rodada **devida no período** — hoje ≥ início do
  período (mensal: 1º dia útil do mês; semanal: segunda-feira) e nenhuma rodada automática criada desde o início do
  período (meia-noite no fuso de operação, `TierRepository#scheduledRoundSince`) — se não houver outra em revisão.
  Uma execução perdida no dia de início é recuperada na próxima do mesmo período; a rodada manual não conta. Num
  ambiente novo, a primeira execução já abre a rodada do período corrente.
* **"Apurar agora"** (`TIER_MANAGE`) é recusado (`tier_round.open_exists`, 409) enquanto houver rodada em revisão.
* **Privacidade:** eventos levam só o nível; logs de negócio (`tier.round_*`) e métrica (`incentive.tier.rounds`)
  só contagens; nenhuma nota, quartil ou índice em log técnico. A trilha de auditoria registra parâmetros
  (`tier_settings.updated`), apuração (`tier_round.proposed`), ajuste (`tier_assignment.adjusted`, com o nível antes e
  depois e o motivo), publicação (`tier_round.published`) e expiração (`tier_round.expired`). A consulta da rodada
  gera log de acesso (`COMPENSATION_DATA_VIEWED`, recurso `tier_round`). Retenção: a mesma dos fatos de desempenho
  (sem expurgo automático).

### 12.3 API (`/api/v1/incentive/tiers`)

| Método e caminho | Permissão | Observação |
| --- | --- | --- |
| `GET/PUT /settings` | `TIER_MANAGE` | sem parâmetros gravados, devolve os padrões (`customized=false`); `PUT` valida a matriz (16 células, A–D) |
| `GET /rounds` | `TIER_REVIEW` ou `TIER_MANAGE`, escopo equipe/todos | até 24 rodadas; contagens por nível das pessoas no escopo (as pessoas de todas as rodadas numa consulta só) |
| `GET /rounds/{id}` | idem | pessoas no escopo com quartis, base (avaliações, média, casos, índice), proposto, final, ajuste; `canAdjust`/`canPublish` |
| `POST /rounds` | `TIER_MANAGE` | "Apurar agora" |
| `PUT /rounds/{id}/people/{userId}` | `TIER_REVIEW` + pessoa no escopo | `{tier, reason}` |
| `POST /rounds/{id}/publish` | `TIER_REVIEW` + `PERFORMANCE_VIEW_ALL` | emite os eventos |
| `GET /me` | `PERFORMANCE_VIEW_OWN` | nível vigente (`NONE`, `CLASSIFIED`, `NOT_CLASSIFIED`), quartis, falta de amostra (contagens e mínimos), se foi ajustado e a matriz — sem posição, média, índice ou colegas |

### 12.4 Telas

* **Classificação de desempenho** (`/remuneracao/classificacao`, menu "Remuneração variável", `TIER_REVIEW` ou
  `TIER_MANAGE`): aba "Rodadas" com a rodada na URL (`?rodada=`), resumo (janela, situação, prazo, contagens,
  parâmetros), pessoas com quartis, base, proposto com a explicação e final com o ajuste; "Ajustar" (diálogo com
  motivo), "Apurar agora" (`TIER_MANAGE`) e "Publicar" (confirmação forte `PUBLICAR`, só para quem publica). Aba
  "Parâmetros" (`TIER_MANAGE`, `?aba=parametros`) com a matriz 4 × 4 editável (um seletor por célula, rótulos de
  linha e coluna para leitor de tela) e "Voltar ao padrão". A aba "Rodadas" (e a consulta de rodadas) só aparece com
  escopo de desempenho de equipe ou de todos (`PERFORMANCE_VIEW_TEAM`/`_ALL` ou `COMMISSION_STATEMENT_MANAGE`/
  `_APPROVE`, a mesma regra do servidor); quem tem só `TIER_MANAGE` abre direto em "Parâmetros".
* **Meu desempenho:** cartão "Seu nível de distribuição" (para quem tem `CASE_WORK` e `PERFORMANCE_VIEW_OWN`) com o nível, a explicação dos
  quartis, o que falta de amostra, se houve ajuste e a matriz com a própria célula destacada.
* Indicadores com "i": `incentive.tiers.quality-quartile`, `incentive.tiers.efficiency-quartile`,
  `incentive.tiers.level`. Tour `incentive.tiers`, manual, glossário (nível de desempenho, quartil) e novidade 31.

### 12.5 Decisões tomadas na implementação (para revisão do responsável)

1. Quartil pela posição percentil do meio do posto (alternativas: `ntile`, que força quartis cheios e separa
   empatados; posto ÷ (n − 1), indefinido com uma pessoa).
2. Falha crítica = alguma avaliação de monitoria com falha crítica na janela; ocorrências de conduta confirmadas
   não entram no portão (alternativa: violação grave de compliance também limitar a Q2).
3. Falta de amostra em qualquer dimensão → nível padrão, mesmo com a outra completa (alternativa: usar só a dimensão
   disponível).
4. Classificados só os usuários ativos com `CASE_WORK` (inclui supervisão e gestão com `CASE_WORK`).
5. "Apurar agora" com rodada em revisão é recusado (alternativa: reapurar a rodada aberta mantendo os ajustes).
6. Publicação recusada depois do prazo, mesmo antes de a rotina expirar a rodada.
7. Semanal sempre às segundas, mesmo feriado (alternativa: 1º dia útil da semana).
8. Evento com `tier` nulo só para quem estava na publicação anterior; quem entrou depois da apuração fica sem evento.
9. Rota `/remuneracao/classificacao` (sugestão era `/desempenho/classificacao`): todas as telas do `incentive` ficam
   sob `/remuneracao`.
10. Pendência para todos com `TIER_REVIEW` enquanto houver rodada em revisão (sem filtrar supervisor sem pessoas na
    rodada).
11. O negociador vê que o nível foi ajustado, mas não o motivo (pode conter dado pessoal, ex.: licença); o motivo
    fica na trilha e com a supervisão.
12. Números (média e índice) aparecem só para quem revê; o `/me` mostra quartis e as contagens da base.

### 12.6 Pendências

* Consumo do evento e distribuição por nível no `collection` (outra entrega, §11.3).
* Indicadores do §8 por nível no painel de produtividade.
* Dados fictícios de classificação no sandbox (seed) e conferência pelo `audit.sh`.

## 13. Implementação da fase 1 — distribuição (módulo `collection`)

Detalhe operacional em [collection.md](collection.md) §3.3.2 e §3.3.3; eventos e métricas em
[logs-e-metricas.md](../logs-e-metricas.md) (`queue.distributed`, `collection.distribution.*`,
`collection.next_case.tiered`, `collection.auto_distribution`).

### 13.1 O que foi entregue

| Peça | Onde |
| --- | --- |
| Evento `NegotiatorTierChanged` (§11.1, exatamente o contrato) | `shared.events.incentive` |
| Consumidor `collection.negotiator-tier` → projeção `collection.negotiator_tier` (uma linha imutável por pessoa e publicação; idempotente pela `publicationId` além do `eventId`; nível nulo = sem nível) | `NegotiatorTierProjection`, `V0612` |
| Estratégia `TIERED`, distribuição automática na fila, parâmetros na `collection.settings`, `tier`/`potential_band` no `case_assignment` | `V0612`, `CollectionQueue`, `CollectionSettings`, `TierPolicy`, `CaseAssignment` |
| Planejador puro (ramo `TIERED`, faixas, capacidade por nível, desenvolvimento, nível padrão, ordem das faixas do "próximo caso") | `CaseDistributor` (testes `TieredDistributionTest`) |
| "Distribuir", prévia e rodada automática com o mesmo plano | `QueueDistribution`; `POST /queues/{id}/distribute/preview` |
| "Próximo caso" por faixa do nível, com `SKIP LOCKED` | `CaseWorkService#nextCase`, `JdbcCaseSelector#lockNextByPotential` |
| Job `collection.auto-distribution` (ShedLock, a cada minuto, fuso de operação, dias úteis) | `CollectionJobs`, `AutoDistribution` |
| Parâmetros por nível: `GET/PUT /api/v1/collection/settings/tier-distribution` | `SettingsService`; tela Cobrança › Parâmetros da operação |
| Telas: estratégia "Por nível de desempenho" e seção "Distribuição automática" no cadastro da fila; prévia por nível e faixa no "Distribuir"; resumo da rodada automática na lista de filas | `features/collection/queues`, `features/collection/settings/tier-settings.ts` |
| IT ponta a ponta (projeção, distribuir, prévia com e sem visão de desempenho, próximo caso, desenvolvimento, rodada automática em dia útil e não no sábado, histórico) | `TieredDistributionIT` |

### 13.2 Regras como implementadas

* **Valor esperado** = probabilidade × saldo elegível; probabilidade = `score ÷ 1000` (o scoring grava o score como
  probabilidade × 1000, scoring.md §4); sem score, a **probabilidade média dos casos disponíveis da fila** que têm score
  (o §4.1 dizia "da carteira": a fila é o conjunto que está sendo distribuído); nenhum com score → 1 (ordena pelo saldo).
* **Faixas**: tercis (`ntile(3)`) dos disponíveis da **fila inteira**, mesmo quando o "Distribuir" tem limite — a rodada
  com limite leva os de maior valor esperado e as faixas não mudam de significado conforme o limite.
* **Preferência por faixa**: alta A→B→C→D; média B→C→A→D; baixa C→D→B→A (esgotado o preferido, o nível mais próximo
  com capacidade; empate de distância sobe). Dentro do nível, menor carga.
* **Capacidade**: `max_cases_per_user` × multiplicador do nível, para baixo, mínimo 1. A estratégia `TIERED` **exige**
  `max_cases_per_user` (sem limite, o nível A levaria toda a faixa alta e o multiplicador não teria efeito).
* **Fatia de desenvolvimento (distribuir)**: `round(casos altos × X%)` casos altos, espalhados de forma uniforme e
  determinística pela faixa (nem só os melhores, nem só os piores), por rodízio entre todos os membros (mesmo
  `round_robin_cursor`), gravados como `DEVELOPMENT`.
* **Fatia de desenvolvimento (próximo caso)**: em X% das chamadas (sorteio uniforme 0–99 por chamada) o operador C ou D
  recebe primeiro a faixa alta; A e B já recebem a alta primeiro. Sem estado entre chamadas — simples e auditável pelo
  histórico (`DEVELOPMENT`), com a proporção conferível na métrica `collection.next_case.tiered{development}`.
* **Ordem das faixas no próximo caso**: A/B alta, média, baixa; C média, baixa, alta; D baixa, média, alta. Dentro da
  faixa, retornos da fila vencidos e depois o maior valor esperado.
* **Rodada automática**: uma por horário por dia (`auto_distribution_last_run_at`); horários perdidos geram uma rodada;
  criar a fila ou mudar a configuração automática não dispara horários do dia já passados; dias úteis = segunda a sexta
  fora dos feriados do calendário do compliance; fila `PULL` recusa (`queue.auto_distribution_pull`); limite 1–1000;
  até 6 horários. A carga existente conta para a capacidade.
* **Concorrência**: o "Distribuir" e a rodada automática leem a fila com bloqueio pessimista (`for update`) até o fim
  da transação — uma rodada por vez por fila; a prévia não bloqueia. Os casos continuam com `SKIP LOCKED`.
* **Prévia**: quantidades por nível (`byTier` e o nível em `byTierBand`) só para quem vê o nível de **todos** os
  membros da fila; os demais recebem os totais por faixa (nível nulo) e a tela mostra a tabela "por faixa".
* **Faixa `DEVELOPMENT` mascarada**: no histórico de atribuição, quem não vê o nível de quem recebeu vê `HIGH`; o log
  de negócio `case.assigned` leva sempre `HIGH`. O banco guarda `DEVELOPMENT`.

### 13.3 Decisões tomadas na implementação (para revisão do responsável)

| Decisão | Alternativas | Motivo |
| --- | --- | --- |
| Parâmetros por nível alteráveis com `QUEUE_MANAGE` + acesso a todos os credores; leitura com `CASE_ASSIGN` ou `QUEUE_MANAGE` | `CASE_ASSIGN`; `TIER_MANAGE` (nova, do incentive) | é configuração da distribuição da assessoria, como os demais parâmetros da cobrança (`QUEUE_MANAGE`, decisão 12 do collection.md); quem distribui precisa ler para entender a prévia. O §5 cita esses parâmetros na tela de classificação: se o responsável preferir `TIER_MANAGE`, basta trocar a anotação do `PUT` |
| Parâmetros na tela Parâmetros da operação (Cobrança) | diálogo na tela de filas | já é a tela de parâmetros da cobrança, com a mesma permissão; a fila mostra só o que é da fila |
| Projeção com histórico (linha por publicação) e nível corrente pela vigência | uma linha por pessoa sobrescrita; apagar a linha com nível nulo | entregas fora de ordem e `validFrom` futuro não trocam o nível antes da hora; "sem nível" vira uma linha com nível nulo em vez de remoção (o efeito é o mesmo: vale o padrão) |
| Nível fora de A–D no evento: ignorado com log técnico e métrica (`negotiator_tier_invalid`) | falhar o consumo | reprocessar não corrige um contrato quebrado; falhar deixaria a publicação pendente para sempre |
| Nível da pessoa no histórico de atribuição e na prévia só para quem pode vê-lo (`PERFORMANCE_VIEW_ALL`; `_TEAM` nas equipes que supervisiona e o próprio; `_OWN` o próprio); a capacidade efetiva também é ocultada (revela o nível) | mostrar a todos que veem o caso | nível é dado pessoal do empregado (§6–7); a faixa de potencial é do caso e fica visível |
| Evento de negócio `case.assigned` leva só `potentialBand` — com `DEVELOPMENT` registrado como `HIGH`; `queue.distributed` leva agregados por faixa e por nível | nível da pessoa no log; `DEVELOPMENT` no log | minimização: o nível fica no histórico de atribuição (auditável), não em logs; no "Próximo caso" só C e D recebem a vez de desenvolvimento, então `DEVELOPMENT` revelaria o nível (decisão 1286) |
| No histórico de atribuição, sem visão do nível de quem recebeu, `DEVELOPMENT` aparece como `HIGH`; o banco mantém `DEVELOPMENT` | mostrar `DEVELOPMENT` a todos; não gravar a marca | a marca explica a atribuição a quem pode ver o nível e não revela o nível aos demais (decisão 1286) |
| Prévia por nível (`byTier`, nível em `byTierBand`) só com visão do nível de todos os membros da fila; senão totais por faixa | agregados por nível para todos | com uma ou duas pessoas por nível, a contagem por nível somada ao planejado de cada um revela o nível (decisão 1287) |
| Fila com bloqueio pessimista no "Distribuir" e na rodada automática; prévia sem bloqueio | advisory lock; versão otimista com nova tentativa | duas rodadas simultâneas leriam as mesmas cargas e o mesmo cursor e passariam da capacidade; o bloqueio da linha da fila é simples e curto (decisão 1288) |
| Dois níveis padrão (classificação e distribuição) com aviso na dica e atalho | um só nível padrão lido do incentive | o collection não lê parâmetros do incentive (só o evento); unificar exigiria contrato novo — fica o aviso (decisão 1292) |
| `TIERED` exige capacidade por operador na fila | permitir sem capacidade | sem limite o multiplicador não faz sentido e o primeiro A levaria toda a faixa alta |
| Faixas sobre a fila inteira (não só sobre o lote do "Distribuir") | tercis do lote selecionado | mesmo significado de "alto/médio/baixo" na prévia, no distribuir, no automático e no próximo caso |
| Média sem B nem C sobe ao A antes de descer ao D (baixa sem C e D sobe ao B e depois ao A) | sempre descer | "nunca deixa caso sem dono por causa do nível"; entre níveis à mesma distância, o de cima |

### 13.4 Pendências

* **Disponibilidade do operador** (sessão ativa ou presença na telefonia, §4.3): a rodada automática considera todos os
  membros ativos com `CASE_WORK`, como o "Distribuir" manual — fase 2 (depende de consulta nova no identity/telephony).
* **Retenção** de `collection.negotiator_tier`: tabela sem `UPDATE` (gatilho) e com `DELETE` liberado; o expurgo deve
  seguir a retenção dos fatos de desempenho do incentive (a definir junto com o incentive).
* **Fila de atenção especial com nível mínimo** (§4.4) e **simulação "e se"** ficam para a fase 2.
* **Desempenho do "próximo caso"** em filas `TIERED` muito grandes: as faixas são recalculadas (janela `ntile`) a cada
  chamada sobre os disponíveis da fila; se o p95 de `collection.next_case.duration{outcome=queue}` subir, guardar os
  cortes de tercil por fila por alguns minutos.
* O histórico de atribuição do caso ainda não tem tela no frontend (a API já traz `tier`/`potentialBand`).
* `validate constraint` de `ck_case_assignment_tier` e `ck_case_assignment_potential_band` (criados `not valid` na
  V0612) numa migration posterior, rodada fora do pico.

## 14. Pendências após a revisão (12/11/2026)

* Perfil personalizado com `TIER_REVIEW` sem escopo de equipe ou de todos vê o menu "Classificação de desempenho" e cai num aviso de tela vazia (os perfis padrão não caem nesse caso). Solução prevista: critério combinado no menu e na rota (`TIER_MANAGE` ou `TIER_REVIEW` + escopo) ou recusar esse perfil no editor de perfis.
* `validate constraint` dos checks novos de `collection.case_assignment` (criados `not valid`) numa migration posterior, fora do pico.

