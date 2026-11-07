# Estudo 1 — Fluidez da operação por perfil (jornadas e navegação)

Data: 06/11/2026 · Ambiente: sandbox `aurora` (dados fictícios), viewport 1440×900 · Usuários: operador, supervisor,
gestor, financeiro, analista, jurídico.
Capturas e scripts Playwright ficaram só na máquina local (não versionados); cito apenas o nome do arquivo
(capturas `<perfil>-NN-*.png`; scripts `op*.mjs`,
`sup*.mjs`, `ges1.mjs`, `fin1.mjs`, `jur1.mjs`, `menu.mjs`).
Caminhos de código relativos a `frontend/src/app/`.

Legenda: **[V]** fato verificado no sandbox (clicando) · **[C]** fato verificado no código · **[S]** suposição/inferência.
Severidade pelo impacto em produtividade/erro operacional. Esforço: P (≤ 2 dias), M (≤ 1–2 semanas), G (> 2 semanas).

Registro de efeitos no sandbox: criei 1 proposta, 1 aceite e 1 acordo fictícios para o devedor fictício "Henrique
Prudente Escobar" (Vale Conecta), pelo usuário `operador`. Não aprovei nem estornei nada.

---

## 0. Padrões transversais (valem para todos os perfis)

### 0.1 Estado de lista não sobrevive ao "voltar" — Alta, M
- **[C]** As listas leem filtros da URL **uma vez** (`snapshot.queryParamMap`) e nunca escrevem de volta:
  `features/debt/titles/title-list/title-list.ts:81-92`, `negotiation/proposals/proposal-list.ts:192`,
  `payment/payments/payment-list.ts:174`, `payment/reconciliation/divergence-list.ts:117`,
  `collection/disputes/dispute-list.ts:274`, `communication/inbox/inbox.ts:81-85`. A lista de casos nem lê
  (`collection/cases/case-list.ts`). A página corrente vive só em memória (`shared/data/paged-query.ts:28-31`) e o
  roteador sobe a rolagem para o topo em toda navegação (`app.config.ts:34`, `scrollPositionRestoration: 'top'`).
  Não há `Location.back()` em nenhum lugar do frontend (grep vazio).
- **[V]** Títulos: filtro "31 a 60 dias" → abrir título → Voltar do navegador → filtro vazio e lista recomeçada
  (`operador-26a-titulos-filtrado.png` × `operador-26b-titulos-filtro-perdido.png`).
- **[V]** Casos (supervisor): página 2 → abrir caso → Voltar → página 1 (log de `sup2.mjs`).
- **Exceção boa**: painéis analíticos gravam filtros na URL com `replaceUrl` (`features/analytics/data/url-filter.ts:32`).
- **Recomendação**: generalizar o `url-filter` dos painéis num utilitário `syncFormWithUrl(form, mapping)` +
  `page`/`tamanho` no `pagedQuery` (opcional, via URL) e `withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })`
  com restauração depois do carregamento (ou guardar a rolagem por chave de rota). Aplicar em todas as listas com filtro.
  É o que a persona Supervisor pede em `docs/tecnico/desenho/ux.md` §1 ("filtros persistentes").

### 0.2 Abas não ficam na URL — Média, P
- **[V]** Estação: aba "Negociação" (Alt 6) → abrir acordo → Voltar → aba volta para "Títulos"
  (`operador-07-caso-aba-negociacao.png` × `operador-09-voltou-do-acordo.png`). Código reseta a aba ao abrir caso
  (`collection/station/collection-station.ts:185-192`).
- **[V]** Caso jurídico: aba "Prazos" → "Caso de cobrança" → Voltar → aba "Resumo" (`jur1.mjs`). O painel jurídico até
  *lê* `?aba=prazos` (`legal/dashboard/legal-dashboard.ts:133`), mas clicar na aba não grava
  (`legal/matters/matter-detail.html:57`, `[activeId]="tab()"`).
- **[C]** Ficha do devedor e título usam `ngbNav` sem estado (`debt/debtors/debtor-detail/debtor-detail.html:111`,
  `debt/titles/title-detail/title-detail.html:141`).
- **Recomendação**: `?aba=` gravado com `replaceUrl` em todas as telas com abas (estação, devedor, título, caso
  jurídico, conciliação, demonstrativo, correções de valor).

### 0.3 Trilha de navegação reflete o menu, não o caminho — Média, M
- **[C]** `core/layout/breadcrumbs/breadcrumbs.ts:36-70` monta a trilha pelo item de menu de maior prefixo da URL.
  Consequências **[V]**: vindo da estação, a ficha do devedor mostra "Início › Cobrança › Devedores › Nome" e o menu
  marca "Devedores" (`operador-04-ficha-devedor.png`); a proposta aberta pela fila de Aprovações mostra
  "Negociação › Propostas" e marca "Propostas" (`supervisor-06-proposta-aprovacao.png`); o pagamento mostra
  "Pagamentos › Pagamentos" (`financeiro-08-pagamento.png`).
- Os botões "← Devedores", "← Títulos" do cabeçalho apontam fixo para a lista (`debtor-detail.html:21`,
  `title-detail.html:20`), sem filtros e sem considerar a origem.
- **Recomendação**: (a) link de retorno contextual "← Voltar para <origem>" quando a navegação veio de outra tela do
  app (guardar `origem` no `history.state` ao navegar; cair no link fixo da lista quando não houver); (b) trilha com o
  nó de origem quando houver (ex.: "Estação › Caso Fulano › Ficha do devedor"); (c) remover o segundo nível quando a
  seção e o item têm o mesmo nome ("Pagamentos › Pagamentos").

### 0.4 Sem busca de entidade global — Alta, M
- **[C]** A busca rápida (Ctrl+K) só indexa destinos do menu e ações registradas
  (`core/layout/command-palette/command-palette.service.ts:19-21`, `filterCommands`).
- **[V]** "Mateus" e "071.340" → "Nada encontrado" (`operador-25-busca-nome-devedor.png`).
- **Recomendação**: grupo "Devedores/Casos" na paleta, com busca no servidor por nome, CPF/CNPJ (com máscara e log de
  acesso a dado sensível, como já fazem as consultas), telefone e código do título/contrato; resultado abre a ficha
  do caso ativo (ou a do devedor). Incluir "Recentes" (últimos 10 registros abertos pelo usuário, só em memória ou
  no `user_ui_state`). Mantém a regra de não expor dados em logs.

### 0.5 Ficha de caso para quem não opera é a estação do operador — Média, M
- **[V]** Jurídico (e qualquer perfil sem `CASE_WORK`) ao clicar "Abrir caso" cai em "Estação de cobrança" com coluna
  "Meus casos (0) — A fila de trabalho é dos operadores…", painel direito vazio com só "⋮" e cronômetro rodando
  (`juridico-04-caso-cobranca.png`). Mesmo componente em `/cobranca/casos/:id` (`collection/collection.routes.ts`,
  `loadComponent: station`).
- **[V]** Para o operador, a mesma tela por dois endereços: `/cobranca/fila?caso=` (menu marca "Estação") e
  `/cobranca/casos/:id` (menu marca "Casos", trilha "Cobrança › Casos") — `operador-20-caso-por-id.png`. Todos os
  links "Abrir caso" (acordo, proposta, contestação, conversa, agenda, jurídico) usam o segundo.
- **Recomendação**: modo "consulta" da ficha do caso (sem fila, sem cronômetro, sem painel de registro) para perfis
  sem `CASE_WORK`, com uma faixa "Relacionados" (ver 0.6). Para quem opera, "Abrir caso" deve levar ao mesmo contexto
  da estação (`/cobranca/fila?caso=`) — ou unificar os dois endereços.

### 0.6 Entidades não se ligam nos dois sentidos — Alta, M
Mapa verificado de links entre fichas ([C] grep de `routerLink`; [V] nos casos marcados):

| De \ Para | Devedor | Título | Caso | Proposta | Acordo | Pagamento | Contestação | Jurídico | Conversa |
|---|---|---|---|---|---|---|---|---|---|
| Caso (estação) [V] | ✔ | ✔ | — | ✔ (aba Negociação) | ✔ (aba Negociação) | ✘ | ✘ (lista sem link, `case-sheet-panel.html:264-282`) | ✘ (só "Motivo", `case-sheet-panel.html:36-40`) | aba inline |
| Ficha do devedor [V] | — | ✔ | **✘** | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ |
| Título [V] | ✔ | — | **✘** | ✘ | ✘ | ✘ | ✘ | ✘ | — |
| Proposta [V] | ✘ (só texto) | ✘ | ✔ | — | ✔ | — | — | — | — |
| Acordo [V] | ✘ (só texto, `agreement-detail.html:180-184`) | ✘ | ✔ | ✔ | — | **✘** (tabela "Pagamentos aplicados" sem link, `agreement-detail.html:147-170`) | — | — | — |
| Pagamento [V] | ✘ (texto) | ✔ | ✘ | — | ✔ | — | — | — | — |
| Contestação [C] | ✘ (subtítulo texto, `dispute-page.ts:72`) | ✔ | ✘ | — | — | — | — | — | — |
| Caso jurídico [V] | ✘ | ✘ | ✔ | — | — | — | — | — | — |
| Demonstrativo [C] | — | — | — | — | ✘ | ✘ (recebimentos sem link, `settlement/components/receipt-table.ts:42`) | — | ✔ (custas) | — |
| Lote de importação [C] | ✘ | ✘ (nenhum link para `/titulos` ou `/devedores` em `features/ingestion`) | — | — | — | — | — | — | — |

- **Recomendação**: componente único `app-related-records` (cartão "Relacionados") nas fichas de devedor, título,
  caso, acordo, pagamento, contestação e caso jurídico, alimentado por um endpoint de vínculos por entidade (respeitando
  escopo por credor e permissões — link só aparece se o usuário pode abrir o destino). Prioridade: devedor→caso(s),
  título→caso/acordo/contestação/jurídico, acordo→pagamentos/devedor, caso→contestação/jurídico.

---

## 1. Operador / negociador

### 1.1 Mapa da jornada principal (verificado clicando)
| # | Passo | Tela/rota | Cliques | Observação |
|---|---|---|---|---|
| 1 | Login | `/login` → `/` | — | Início com pendências e "Acesso rápido" (repete o menu, inclusive 5 itens de score) — `operador-home.png` |
| 2 | Pendências | `/pendencias` | 1 | Links diretos para conversa/contestação ✔ (`operador-01-pendencias.png`) |
| 3 | Estação | `/cobranca/fila` | 1 | Três zonas, "Próximo caso Alt N", lista "Meus casos" ✔ (`operador-02-estacao.png`) |
| 4 | Abrir caso | `?caso=<id>` | 1 | Caso na URL ✔; abas com atalhos Alt 1–7 ✔; "Posso contatar agora?" ✔ (`operador-03-caso-aberto.png`) |
| 5 | Ficha do devedor | `/devedores/:id` | 1 | **Sai da estação**; ícone de "abrir fora" mas abre na mesma aba; sem link de volta ao caso (`operador-04-ficha-devedor.png`) |
| 6 | Simular | `/negociacao/simulador?credor&devedor&caso` | 1 | **Sai da estação**; credor/devedor preenchidos ✔, "Voltar ao caso" ✔, mas exige clicar "Simular" de novo (`operador-10-simulador.png`) |
| 7 | Escolher oferta → validar → Propor → confirmar | simulador | 4 | Diálogo com resumo ✔ |
| 8 | Proposta | `/negociacao/propostas/:id` | (auto) | Vai para a proposta, **não volta ao caso** (`simulator.ts:383`) (`operador-14-proposta.png`) |
| 9 | Registrar aceite | diálogo | 3 | Canal do aceite **não vem preenchido** (proposta tinha canal; estação sabe que é telefone) (`operador-15-dialogo-aceite.png`) |
| 10 | Formalizar | diálogo | 3 | Digitar "FORMALIZAR" ✔ (irreversível) → vai ao acordo (`operador-18-acordo-novo.png`) |
| 11 | Pix da parcela | diálogo no acordo | 2+ | Bom: Pix/boleto/Pix com vencimento e canal de envio no mesmo diálogo (`operador-19-pix.png`) |
| 12 | Voltar ao caso | "Abrir caso" | 1 | Cai em `/cobranca/casos/:id` (menu marca "Casos"), **cronômetro zerado** (`operador-20-caso-por-id.png`) |
| 13 | Registrar interação/tabulação | painel direito | 3–5 | — |
| 14 | Próximo caso | Alt N | 1 | ✔ |

Total do fechamento de um acordo com Pix: **~20 cliques e 4 trocas de tela** (estação → simulador → proposta →
acordo → estação), contra o desenho de `ux.md` §10 ("painel de ação à direita: registrar interação, simular, propor").

### 1.2 Atritos
1. **Sair da estação apaga o atendimento em curso** — Alta, M.
   [V] Observação digitada → abrir ficha do devedor → Voltar → campo vazio; cronômetro reiniciado (00:36 → 00:30 após
   o retorno) (`operador-11-anotacao-digitada.png` × `operador-12-apos-voltar-anotacao-perdida.png`).
   [C] O instante de início é sinal do componente, recriado ao voltar (`collection/station/collection-station.ts:176-190`)
   e é **enviado ao servidor** como `startedAt`/`endedAt` da interação (`collection/station/interaction-panel.ts:205-215`):
   a duração registrada do atendimento fica subestimada sempre que o operador simula ou abre a ficha — distorce
   produtividade/remuneração variável [S: depende de como o backend usa a duração].
   Recomendação: estado do atendimento (início, rascunho do registro, aba) num serviço por caso (`providedIn: 'root'`,
   chaveado por `caseId`, limpo ao registrar/devolver/abrir outro caso); simulador e ficha do devedor como **painel
   lateral/gaveta dentro da estação** (o simulador já é componente independente).
2. **Propor → aceitar → formalizar em 3 telas e 10 cliques, sem volta automática ao caso** — Alta, M.
   [V] passos 7–12 acima. Recomendação: depois de "Propor" com o devedor na linha, oferecer no mesmo diálogo
   "Registrar aceite agora" (canal pré-preenchido pelo canal da estação/interação) e, se a alçada permitir,
   "Formalizar"; ao concluir, voltar à estação com um cartão "Acordo formalizado — gerar Pix da 1ª parcela / enviar
   termo". Manter a confirmação digitada para formalizar (regra de irreversível).
3. **Atendimento receptivo sem caminho até o caso** — Alta, M.
   [C] Lista de casos não filtra por devedor/CPF (`collection/cases/case-list.ts:70-115`); a ficha do devedor não
   tem link para casos (`debtor-detail.html`, abas na linha 113-294); a paleta não busca entidades (0.4).
   Quem recebe ligação/visita de um devedor que não está em "Meus casos" precisa adivinhar o caminho. A telefonia
   integrada só pede o caso na tabulação (`telephony/station/tabulation-dialog.ts:69-71`) — não abre o caso ao atender
   [S: sem screen pop].
   Recomendação: busca de devedor/caso na paleta e no topo da estação ("Atender devedor: nome/CPF/telefone"), e
   "Casos (n)" na ficha do devedor com "Abrir na estação".
4. **Ficha do devedor e título são becos sem saída** — Alta, P/M (ver 0.6). [V] `operador-04`, `operador-06`.
5. **Estação perde aba ao voltar** — Média, P (0.2).
6. **Contestação e jurídico citados no caso sem link** — Média, P. [C] `case-sheet-panel.html:36-40, 264-282`.
7. **Menu inchado para o operador** — Média, P. [V] 31 itens (`menu.mjs`): 5 de "Score de propensão" (inclusive
   "Parâmetros do score"), "Políticas de cobrança", "Índices de correção", "Credores", "Carteiras", "Formulários de
   monitoria", "Lançamentos de comissão"… A permissão `SCORING_VIEW` é dada ao operador para ver o score no caso
   (`docs/tecnico/desenho/scoring.md:180`) e a mesma permissão abre os 5 itens de administração do score
   (`core/layout/navigation.ts`, seção "Score de propensão"). O "Acesso rápido" do Início repete tudo
   (`operador-home.png`). Recomendação: separar "ver score do caso" de "consultar modelos/execuções" (permissão nova
   ou exigir `SCORING_MANAGE`/perfil analítico para o menu) — **decisão do PO**; seções do menu recolhíveis e
   favoritos/fixados por usuário; "Acesso rápido" baseado em uso (mais usados), não espelho do menu.
8. Ícone "abrir fora" (`box-arrow-up-right`) em "Ficha do devedor"/"Abrir caso" abre na mesma aba — Baixa, P.
   [V] `operador-03`, `operador-08`. Ou abre em nova aba de verdade (útil para consulta lateral) ou troca o ícone.
9. Simulador aberto pelo caso não simula automaticamente — Baixa, P. [V] `operador-10-simulador.png`.

### 1.3 Agenda, caixa de entrada, contestação
- Agenda: linhas levam ao caso (`collection/agenda/agenda.ts:139,160`) ✔; botão "Estação de cobrança" ✔; período lido
  da URL mas não gravado (0.1). Em `/cobranca/casos/:id`, não em `?caso=` (0.5).
- Caixa de entrada: "Abrir caso" na conversa (`communication/inbox/conversation-panel.html:19`) ✔; aba (Minhas/Fila/
  Todas) não gravada na URL → voltar do caso tende a cair em "Minhas" [S, pelo código `inbox.ts:83`].
- Contestações: lista liga contestação e caso ✔ (`operador-23-contestacoes.png`); a página da contestação não liga
  ao caso nem ao devedor [C].

---

## 2. Supervisor

### 2.1 Mapa
Pendências (`supervisor-01-pendencias.png`: links diretos ✔) → Filas e distribuição (`supervisor-02-filas.png`) →
Aprovações (`supervisor-03-aprovacoes.png`) → Proposta (`supervisor-06-proposta-aprovacao.png`) → Monitoria
(`supervisor-04-monitoria.png`) → Caixa de entrada → Contestações (`?fila=supervisao`).
Menu: 56 itens (`menu.mjs`).

### 2.2 Atritos
1. **Monitoria de qualidade desligada do atendimento** — Alta, M.
   [C] O diálogo de avaliação pede o "Identificador (UUID)" da chamada/conversa/caso/acordo digitado à mão
   (`incentive/quality/evaluation-list.ts:77-86, 140-141`); a lista mostra só 8 caracteres do id, sem link
   (`evaluation-list.ts:252`); não há ação "Avaliar" em Chamadas e gravações, na conversa ou no histórico do caso
   (grep vazio em `telephony/calls`, `collection/station`).
   Recomendação: botão "Avaliar este atendimento" na linha da chamada, na conversa e no histórico do caso, abrindo o
   formulário já com negociador, referência e data; na lista de avaliações, link para a referência.
2. **Aprovação sem "próxima" e sem retorno à fila** — Média, P.
   [V] Proposta aberta da fila de Aprovações: trilha "Negociação › Propostas", menu marca "Propostas", nenhum link para
   "Aprovações"; após aprovar, a tela fica na proposta (`proposal-detail.ts:152-166`). Recomendação: "Aprovar e abrir
   a próxima" / "Reprovar e abrir a próxima" quando a origem é a fila, contador "1 de N", e retorno à fila com filtro.
   O conteúdo da tela de decisão é **bom** ("Por que precisa de aprovação", alçada, memória) — preservar.
3. **Filas sem drill-down** — Média, P. [V] `supervisor-02-filas.png`: a linha da fila não leva aos casos dela; a
   lista de casos já filtra por fila (`case-list.ts:83`), falta o link `/cobranca/casos?fila=<id>` (e a lista passar a
   ler esse parâmetro).
4. Filtros/página das listas de casos, contestações e propostas perdidos ao voltar — Alta (0.1). [V] `sup2.mjs`.
5. Menu com 56 itens, incluindo Telefonia, Campanhas, Assistente de IA, Score, Compliance, Administração — Média
   (mesma recomendação de 1.2.7).

---

## 3. Gestor

### 3.1 Mapa
Início (`gestor-00-home.png`) → Painéis (abas entre painéis ✔, filtros na URL ✔, "Dados até…" ✔) → **(não há
drill-down)** → Casos/Títulos via menu → Correções de valor (`gestor-04-correcoes.png`, liga ao título ✔) → Alçadas
(`gestor-05-alcadas.png`) → Políticas. Menu: 77 itens.

### 3.2 Atritos
1. **Painéis sem drill-down até o registro** — Alta, M.
   [V] Operacional, Recuperação, Produtividade: os únicos links em `main` são as abas dos painéis (`ges1.mjs`;
   `gestor-02-recuperacao.png`). [C] Só `analytics/pages/disputes-panel.ts:129` leva a uma lista filtrada. Contraste:
   o painel jurídico leva às listas por fase e ao prazo do caso (`legal/dashboard/legal-dashboard.ts:64-133`) — é o
   padrão a replicar.
   Recomendação: cada KPI/barra/linha da tabela "Detalhe por…" vira link para a lista correspondente com os mesmos
   filtros (período, credor, carteira, operador, equipe, canal) — exige que as listas aceitem esses parâmetros (casa
   com 0.1). Ex.: "4 recebimentos confirmados" → `/pagamentos?situacao=CONFIRMED&de=&ate=&credor=`; linha por operador
   → `/cobranca/casos?operador=`.
2. Do título para a correção e de volta: correção liga ao título ✔; o título tem aba "Correções de valor" ✔ — bom.
3. Contexto de credor não é global — Média, M [S]. O seletor "Aurora Cobrança · Todos os credores" no topo é só
   informativo (`core/layout/shell/shell.ts:123-133`); cada lista pede o credor de novo e o filtro do painel não
   acompanha a navegação. Recomendação: "credor em foco" opcional na sessão, aplicado como filtro inicial nas
   listas e painéis (sem mudar o escopo de segurança).
4. Menu com 77 itens sem recolher seções — Média (1.2.7).

---

## 4. Financeiro

### 4.1 Mapa
Pendências (`financeiro-00-pendencias.png`: "Pagamentos informados para aprovar", "Demonstrativos para revisar" ✔) →
Conciliação ↔ Divergências (links recíprocos ✔, `financeiro-01/02`) → Extrato → linha → Pagamento
(`statement-detail.ts`/`divergence-list.ts:86` ✔ [C]; sandbox sem extrato importado, não clicado) → Pagamento
(`financeiro-08-pagamento.png`: acordo ✔, instrução ✔, título ✔ [C], **devedor só texto**) → Estornar (no próprio
pagamento ✔) → Demonstrativos → Repasses (repasse ↔ demonstrativo ✔ [C]).

### 4.2 Atritos
1. **Demonstrativo não leva aos recebimentos/pagamentos** — Média, P. [C] `settlement/components/receipt-table.ts:42`
   (sem link). Na prestação de contas, conferir um recebimento exige ir a Pagamentos e procurar de novo.
2. **Acordo → pagamentos aplicados sem link** — Média, P. [V/C] `agreement-detail.html:147-170`. O financeiro que
   investiga uma parcela "paga" não chega ao pagamento/estorno.
3. **Pagamento → devedor/caso sem link** — Baixa, P. [V] `financeiro-08-pagamento.png`.
4. Pagamento não mostra a linha do extrato que o conciliou (só "Conciliado · Por consulta ao PSP") — Baixa, P [V].
5. Filtros das listas de pagamentos/divergências perdidos ao voltar — Alta para quem concilia em lote (0.1).
6. Trilha "Pagamentos › Pagamentos" redundante — Baixa, P.

---

## 5. Analista de carteiras (resumo)
Importação bem amarrada [C]: lista → nova importação/PDF → conferência → lote ("Abrir a importação", "Abrir a prévia",
"Voltar para importações") em `features/ingestion/**`. Falta: do lote para os **títulos/devedores importados**
(nenhum link para `/titulos` ou `/devedores` em `features/ingestion`; a lista de títulos não filtra por lote) — Média, P/M.
Menu enxuto (15 itens) ✔.

## 6. Jurídico (resumo)
Painel jurídico com drill-down por fase e prazos ✔ (`juridico-01-painel.png`). Caso jurídico → caso de cobrança ✔;
volta perde a aba (0.2); títulos e devedor do caso jurídico sem link [C] (`legal/matters/matter-detail.html:77`);
abrir o caso de cobrança cai na estação vazia do operador (0.5, `juridico-04-caso-cobranca.png`); do lado da
cobrança não há link para o caso jurídico (0.6).

---

## 7. O que está bom e deve ser preservado
- **Minhas pendências** com links que já chegam no registro ou na lista filtrada (`/conversas?aba=queue&aguardando=true`,
  `/cobranca/contestacoes?fila=supervisao`, proposta específica) e sino com contador no topo.
- **Estação de cobrança**: três zonas, caso na URL (`?caso=`, Voltar do navegador reabre o caso — [V]), "Próximo caso"
  com Alt N, abas com Alt 1–7, "Posso contatar agora?" por canal, Simular/Agendar/Contestação no painel de ação.
- **Simulador aberto pelo caso** com credor/devedor/caso preenchidos e "Voltar ao caso".
- **Diálogos de confirmação** de aceite/formalização com resumo do impacto e confirmação digitada no irreversível.
- **Tela de decisão da proposta** (por que precisa de aprovação, alçada, memória de cálculo, histórico).
- **Acordo** com Pix/boleto por parcela no próprio cronograma e "Abrir caso/Abrir proposta/Acordo anterior".
- **Painéis** com filtros na URL, abas entre painéis preservando o período e "Dados até…".
- **Painel jurídico** com drill-down (modelo para os painéis analíticos).
- **Conciliação ↔ Divergências ↔ Pagamento**, **Repasse ↔ Demonstrativo**, **Correções de valor → Título**.
- **Importação**: fluxo com links de avanço e retorno em todos os passos.
- Menu filtrado por permissão (nada exibido para depois negar), item ativo mais específico (`activeNavRoute`).

## 8. Backlog sugerido (ordem)
| # | Item | Sev. | Esforço |
|---|---|---|---|
| 1 | Estado do atendimento por caso (rascunho, início, aba) + simulador/ficha como gaveta na estação | Alta | M |
| 2 | Filtros/página/aba na URL em todas as listas + restauração de rolagem | Alta | M |
| 3 | Cartão "Relacionados" (vínculos bidirecionais entre devedor, título, caso, acordo, pagamento, contestação, jurídico) | Alta | M |
| 4 | Busca de devedor/caso na paleta (nome, CPF/CNPJ, telefone, título) + recentes | Alta | M |
| 5 | Fechamento do acordo sem sair da estação (propor → aceite → formalizar → Pix → volta ao caso) | Alta | M |
| 6 | Drill-down dos painéis analíticos para listas filtradas | Alta | M |
| 7 | Monitoria a partir da chamada/conversa/histórico, sem UUID manual | Alta | M |
| 8 | Fila de aprovação com "aprovar e próxima" e retorno à fila | Média | P |
| 9 | Ficha do caso em modo consulta para perfis sem `CASE_WORK`; unificar `/cobranca/fila?caso=` × `/cobranca/casos/:id` | Média | M |
| 10 | Menu por perfil: separar permissão do menu de score (decisão do PO), seções recolhíveis, favoritos | Média | P/M |
| 11 | Retorno contextual ("← Voltar para <origem>") e trilha com a origem | Média | M |
| 12 | Links pontuais: fila→casos, lote→títulos, demonstrativo→pagamento, acordo→pagamento, pagamento→devedor | Média | P |
| 13 | Credor em foco opcional (filtro inicial global) | Média | M |
| 14 | Ícone "abrir fora" coerente; simular automaticamente quando vier do caso; canal do aceite pré-preenchido | Baixa | P |
