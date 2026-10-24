# Score de propensão à recuperação

O score estima, para cada caso de cobrança, a **chance de o devedor fazer um pagamento confirmado nos próximos N dias**
(padrão 30). Serve para a operação decidir **por onde começar**: quais casos o negociador recebe primeiro e qual público
uma campanha alcança. Desenho técnico: [docs/tecnico/desenho/scoring.md](../tecnico/desenho/scoring.md).

## 1. Para que serve — e para que não serve

| Uso permitido | Uso proibido |
| --- | --- |
| Ordem de entrega do "próximo caso" em filas configuradas com ordem "maior score" | Negar ou adiar indefinidamente atendimento a quem procura a assessoria |
| Critério de faixa de score de uma fila (ex.: fila de alta propensão) | Definir desconto, parcelas, entrada ou qualquer condição de negociação |
| Filtro de faixa de score num segmento de campanha | Decidir envio ao jurídico, negativação ou qualquer medida contra o devedor |
| Indicador "recuperado × esperado" do negociador (desconta a qualidade da carteira recebida) | Ser exibido ao devedor como avaliação de crédito |

O módulo de negociação **não consome o score**: as condições continuam vindo só da política de cobrança e da alçada.
Todo caso continua na fila e é atendido; o score muda a ordem, não o direito ao atendimento.

## 2. Como funciona

* Todo dia (madrugada) o sistema recalcula o score de todos os casos ativos; também recalcula após um contato efetivo,
  promessa, acordo ou pagamento, e quando o operador pede.
* **Faixas**: A (maior propensão), B, C, D e E. Padrão: A ≥ 40%, B ≥ 25%, C ≥ 15%, D ≥ 7,5%, E abaixo.
* Casos no jurídico, encerrados ou de devedor anonimizado não recebem score.

## 3. Modelos

1. **Scorecard por pontos** (padrão inicial, quando não há histórico): regras transparentes por faixa, definidas por
   especialistas (§5). A assessoria pode criar outros scorecards.
2. **Modelo estatístico** (regressão logística) treinado **no histórico da própria assessoria**, somente quando há
   histórico confiável: ao menos **500 casos com desfecho conhecido e 50 casos que pagaram** (configurável). Abaixo
   disso o treino não acontece e a assessoria continua no scorecard.
3. O modelo novo é validado em **período posterior ao do treino** (validação fora do tempo) e comparado com o modelo em
   uso nas mesmas observações (AUC/Gini, KS, calibração por faixa, lift por decil). Só modelos aprovados viram
   **desafiantes**.
4. **Troca só manual**: um gestor com `SCORING_MANAGE` — diferente de quem pediu o treino ou criou o scorecard — promove
   o desafiante a **campeão** com justificativa. A decisão fica registrada com a comparação que foi vista.
5. Enquanto não é promovido, o desafiante é calculado "em sombra" nos retratos semanais, para medir o desempenho real
   antes da troca.

## 4. Dados usados (lista fechada)

Somente dados da **dívida e do relacionamento de cobrança**:

| Dado | |
| --- | --- |
| Dias de atraso do título mais antigo, saldo em aberto, número de títulos, idade do caso | posição |
| Tentativas de contato e contatos efetivos (90 dias), dias desde o último contato efetivo | contato |
| Canais em que o devedor respondeu (90 dias) | resposta |
| Promessas cumpridas e quebradas | compromissos |
| Acordos anteriores e acordos quebrados com o credor | relacionamento |
| Pagamentos confirmados (365 dias) e dias desde o último pagamento, líquidos de estornos | pagamento |
| Acordo em vigor, promessa pendente | situação |
| Taxa histórica de pagamento da carteira | carteira/credor |

**Nunca usados** (nem como aproximação): nome, documento, data de nascimento ou idade, gênero, estado civil, raça/cor/
etnia, religião, opinião política, filiação sindical, saúde, deficiência, dados genéticos ou biométricos, endereço, CEP,
cidade, UF, DDD ou qualquer localização, renda presumida, profissão, tipo de pessoa, domínio de e-mail ou operadora,
conteúdo de mensagens e observações, dados de terceiros (coobrigados, familiares). A lista de dados é fixa no sistema:
incluir um dado novo exige mudança de código, revisão e atualização deste documento.

## 5. Scorecard padrão

500 pontos equivalem a 10% de chance; **cada 20 pontos dobram a chance** (padrão de mercado "pontos para dobrar").

| Dado | Pontos |
| --- | --- |
| Atraso | até 30 dias +30 · 31–90 +15 · 91–180 0 · 181–360 −15 · mais de 360 −30 |
| Saldo | até R$ 500 +10 · até R$ 1 mil +5 · até R$ 5 mil 0 · até R$ 20 mil −5 · acima −10 |
| Contatos efetivos (90 dias) | nenhum −10 · 1–2 +10 · 3 ou mais +15 |
| Último contato efetivo | até 7 dias +20 · até 30 +10 · até 90 0 · mais −5 · nunca −10 |
| Respondeu em algum canal (90 dias) | +10 |
| Promessa cumprida | +20 |
| Promessas quebradas | 1–2 −10 · 3 ou mais −20 |
| Acordo anterior com o credor | +5 · algum quebrado −15 |
| Pagamento nos últimos 365 dias | +20 · último em até 30 dias +15 · até 90 dias +5 |
| Acordo em vigor | +40 |
| Promessa pendente | +20 |

## 6. Explicação e direito de revisão (LGPD)

* **Base legal**: o score só funciona depois que a assessoria registra a base legal do tratamento — legítimo interesse
  (art. 7º IX, com a referência da avaliação de legítimo interesse) ou proteção do crédito (art. 7º X). Sem esse
  registro nenhum dado é coletado para o score.
* **Explicação**: cada score mostra os fatores que mais pesaram ("contatos efetivos: 0 — reduz a propensão") e a
  contribuição de cada dado, para o operador e para responder a um titular.
* **Revisão** (art. 20): a pedido do titular, do credor ou por verificação interna, um gestor recalcula o score com os
  dados atuais; o sistema registra o score anterior, o novo, o modelo e a referência da solicitação.
* Na anonimização do devedor, os dados do score que o identificam são apagados.

## 7. Acompanhamento

* **Estabilidade** (PSI): a cada retrato semanal o sistema compara a população atual com a do treino; mudanças fortes
  geram alerta para reavaliar o modelo.
* **Desempenho real** por safra: depois dos N dias, mede quem de fato pagou em cada faixa e a AUC realizada do campeão e
  do desafiante.
* **Recuperado × esperado** por credor, carteira e negociador: valor recuperado ÷ valor que o score esperava para os
  casos recebidos — o indicador mais justo para comparar negociadores com carteiras diferentes (painel de desempenho).
