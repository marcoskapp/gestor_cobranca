# Desenho — inteligência de contato (perfil por contato, canal e horário)

Pedido do responsável (11/2026): gerar dado para orientar a negociação. Quer saber qual telefone mais atende, em que
horário, depois de quantas tentativas, se o devedor prefere WhatsApp ou ligação, e depois de quantos contatos o devedor
aceita e **cumpre** o acordo. Fica no módulo `scoring` (mesma finalidade, priorização operacional; mesma base legal e
mesmos fatos). Regra de uso e LGPD: [docs/negocio/score-propensao.md](../../negocio/score-propensao.md), que ganha a
seção "Inteligência de contato".

## 1. Princípios

1. **Só orienta.** O perfil ordena e sugere. Nunca aumenta tentativas além da política de contato nem decide sozinho.
   Uso automático no discador ou nas réguas é **fase 2**, com aprovação do responsável (regra de produto: decisão
   automática passa por revisão humana antes de mudar regra de negócio).
2. **Explicável.** Todo número mostra a base ("6 de 10 ligações atendidas entre 18h e 20h") e a quantidade de
   observações. Pouca observação aparece como "dados insuficientes", nunca como certeza.
3. **Estatística honesta.** Taxas suavizadas pela média da carteira (Beta-Binomial), para que 1 de 1 não vire 100%.
4. **Sem dado pessoal nos agregados.** O perfil guarda ids de contato, contagens e horários; o valor do contato fica no
   `debt`. Sem base legal registrada no score, nada é coletado (mesma regra dos fatos do score).

## 2. Fonte (contrato publicado)

Os eventos `InteractionRecorded`, `CallCompleted` e `MessageStatusChanged` passaram a levar `contactId` (o contato do
devedor usado). Com isso cada tentativa e cada resposta é atribuída a um contato.

| Fato | Origem | Tentativa | Resposta (sucesso) |
| --- | --- | --- | --- |
| Ligação | `CallCompleted` OUTBOUND | sim | `ANSWERED` com conversação > 0 |
| Ligação recebida | `CallCompleted` INBOUND | não | sim (canal preferido) |
| Mensagem enviada | `MessageStatusChanged` OUTBOUND `SENT` | sim | `READ` conta como "lida" (sinal fraco, só exibido) |
| Mensagem recebida | `MessageStatusChanged` INBOUND `RECEIVED` | não | sim, e responde à última tentativa do mesmo canal em até 72 h |
| Interação manual | `InteractionRecorded` OUTBOUND / efetiva | sim | `effectiveContact` |

**Sem contagem em dobro.** Toda ligação e toda mensagem também geram uma interação no caso (origem `TELEPHONY` ou
`COMMUNICATION`). O `InteractionRecorded` passou a levar `source` (origem da interação), e a regra é:

* ligação → só pelo `CallCompleted`; mensagem → só pelo `MessageStatusChanged`;
* `InteractionRecorded` entra só com `source` `MANUAL`, `ASSISTANT` ou `SYSTEM` (registro do operador sem ligação pelo
  sistema, atendimento presencial etc.);
* `source` nulo (evento de versão anterior) é ignorado (`ignored{reason=unknown_source}`).

**Defesa em profundidade.** O `collection` recusa interação com contato de outro devedor
(`interaction.contact_not_of_debtor`); ainda assim, o consumidor confere que o `contactId` pertence ao `debtorId` do
evento antes de gravar (`ignored{reason=contact_mismatch}`). Em `MessageStatusChanged`, `contactId` é o contato em uso
na conversa no momento da mudança de status (mesmo endereço).

## 3. Dados (`scoring`, `V1401__scoring_contact_intelligence.sql`)

| Tabela | Conteúdo |
| --- | --- |
| `scoring.contact_attempt` | fato por tentativa/resposta: `debtor_id`, `creditor_id`, `portfolio_id`, `case_id`, `contact_id` (pode ser nulo), `channel` (`PHONE`, `WHATSAPP`, `SMS`, `EMAIL`, `OTHER`), `direction`, `kind` (`ATTEMPT`, `RESPONSE`), `success`, `local_hour` (0–23, fuso de operação), `local_weekday` (1–7), `occurred_at`, `source_event_id` (único) |
| `scoring.contact_profile` | agregado por (`debtor_id`, `contact_id`, `channel`): tentativas, sucessos, tentativas até o 1º sucesso, último sucesso, contagens por faixa horária (6 faixas de 3 h, 06–24) em `jsonb`, `updated_at` |
| `scoring.agreement_contact_outcome` | por acordo: `case_id`, `agreement_id`, `portfolio_id`, `creditor_id`, tentativas e contatos efetivos **antes** do acordo, canal do último contato efetivo, dias entre o 1º contato efetivo e o acordo, `final_status` (`ACTIVE`, `COMPLETED`, `BROKEN`, `CANCELLED`), `person_type` |

RLS (`shared.enable_tenant_rls`) em todas; `check` em `channel`, `direction`, `kind`, `final_status`, `person_type` e
`local_hour between 0 and 23`, `local_weekday between 1 and 7`.

Retenção: categoria nova `SCORING_CONTACT_FACTS` (mesmos limites e padrão de `SCORING_CASE_FACTS`, informada pela
`ScoringRetentionDefaults`). A categoria também entra no `compliance`: valor novo em `RetentionCategory` e migration
`V0510__compliance_retention_contact_facts.sql` estendendo o `check` de `compliance.retention_policy`. Os agregados são
recalculados a partir dos fatos vigentes no descarte, por assessoria (tenant definido em cada rodada, como os outros
jobs). `DebtorAnonymized`: apaga fatos e perfis do devedor; em `agreement_contact_outcome`, `case_id` e `agreement_id`
passam a nulo (colunas anuláveis; unicidade por acordo só enquanto `agreement_id` existe) e ficam só credor, carteira,
contagens e situação — estatística sem identificação.

## 4. Cálculos (`ContactProfileCalculator`, domínio puro)

* **Taxa de sucesso do contato** = `(sucessos + α) / (tentativas + α + β)`, com α e β da taxa da carteira (≥ 30
  tentativas; senão da assessoria) e força 10 tentativas.
* **Melhor faixa horária**: faixa com maior taxa suavizada e ≥ 3 tentativas; empate pela mais recente.
* **Tentativas até o 1º sucesso**: por contato e mediana da carteira.
* **Canal preferido do devedor**: canal com maior taxa de resposta suavizada, mais peso para resposta iniciada pelo
  devedor (ligação recebida, mensagem recebida). Exige ≥ 2 respostas no total; senão "sem preferência clara".
* **Contatos até o acordo e até o acordo cumprido**: a partir de `agreement_contact_outcome` (o acordo só conta como
  cumprido quando `COMPLETED`; acordo `ACTIVE` fica fora da taxa de cumprimento).
* **Confiança**: `LOW` (< 5 tentativas), `MEDIUM` (5–19), `HIGH` (≥ 20).

Atualização: incremental no consumo do evento (`handleOnce`, transação curta, lock por devedor); reconstrução completa
por devedor sob demanda e no descarte da retenção.

## 5. API (`/api/v1/scoring`)

| Recurso | Permissão | Conteúdo |
| --- | --- | --- |
| `GET /debtors/{debtorId}/contact-profile?caseId=` | `SCORING_VIEW` + devedor no escopo (caso do credor no escopo) | por contato: `contactId`, canal, tentativas, sucessos, taxa suavizada, confiança, melhor faixa, tentativas até o 1º sucesso, último sucesso; canal preferido; explicações em texto; aviso de finalidade |
| `GET /contact-insights?portfolioId=&creditorId=&from=&to=&personType=` | `SCORING_VIEW` (filtrado pelo escopo) | carteira: taxa de atendimento por faixa horária e dia da semana, curva tentativas × 1º atendimento, taxa por canal, **contatos até o acordo e até o acordo cumprido** (distribuição e taxa de cumprimento por faixa de contatos), número novo × antigo ([contatos-recebidos.md](contatos-recebidos.md)), corte PF/PJ |

Fatos de um credor fora do escopo nunca entram na resposta (filtro por `creditor_id`).

## 6. Telas

* **Cartão "Melhor forma de contato"** no painel do caso e na ficha do devedor: canal preferido, os contatos
  ordenados pela taxa com a explicação e o melhor horário. O frontend junta o perfil (por `contactId`) com a lista de
  contatos que já mostra (valor mascarado vem do `debt`), sem nova exposição de dado. Sem dados suficientes: estado
  vazio que diz o que falta ("ainda são 2 tentativas; o perfil aparece a partir de 5").
* **Tela "Inteligência de contato"** em `/score/contato` (menu do score, `SCORING_AREA_VIEW`): filtros na URL
  (carteira, credor, período, tipo de pessoa); mapa de calor dia × faixa horária, curva de tentativas até o atendimento,
  taxa por canal, gráfico de contatos até o acordo cumprido e comparação contato novo × antigo; cada gráfico com tabela
  acessível.

## 7. Logs e métricas

* Métricas: `scoring.contact_facts{kind,channel}`, `scoring.contact_facts.ignored{reason}` (`no_legal_basis`,
  `no_contact`, `duplicate`), `scoring.contact_profile.rebuild`.
* Evento de negócio `contact.profile_viewed` não é necessário (sem dado sensível novo); o acesso segue o log de acesso
  das telas do caso.

## 8. Testes

`ContactProfileCalculatorTest` (suavização, melhor faixa com empate, canal preferido, confiança, fuso),
`ContactIntelligenceIT` (eventos com `contactId` → perfil; sem base legal → nada; anonimização; escopo por credor no
insight; acordo cumprido conta e quebrado não), testes do frontend (cartão, estados, tela).

## 9. Fase 2 (depende de aprovação)

Discador escolhendo número e horário pelo perfil; réguas escolhendo canal e horário; feature de contato no score de
propensão; modelo por tipo de pessoa.

## 10. Decisões (numeradas em `decisoes-autonomas.md` na entrega)

* Perfil no módulo `scoring` (alternativa: módulo novo) — mesma base legal, fatos e retenção.
* Origem da interação no evento e contagem sem duplicidade (ligação e mensagem só pelos eventos próprios).
* Suavização Beta-Binomial com força 10 e mínimo de 30 tentativas para a taxa da carteira.
* Faixas de 3 h no fuso de operação.
* Mensagem lida não conta como sucesso (sinal fraco).
* Uso só como sugestão na fase 1.
