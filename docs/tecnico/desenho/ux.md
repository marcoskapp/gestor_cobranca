# Desenho — UX, identidade visual e onboarding

Escopo: frontend Angular (`frontend/`), tema e componentes compartilhados, shell, onboarding e o estado de interface do usuário no módulo `identity` (migration V0201). Visão de produto em [`docs/negocio/ux-identidade-visual.md`](../../negocio/ux-identidade-visual.md).

Objetivo do responsável: *"uma identidade visual leve e fácil de operar, responsiva e cognitiva"*, com onboarding para novos usuários e para telas novas, alteradas ou ainda não navegadas.

## 1. Personas e contextos de uso

| Persona | Contexto | Necessidades que guiam o desenho |
| --- | --- | --- |
| **Negociador júnior** | Estação de cobrança, turnos de 6–8 h, alto volume, ainda aprendendo regras | Orientação na própria tela (tours curtos, textos de ajuda), prevenção de erro nas condições de acordo, linguagem sem jargão |
| **Negociador sênior** | Mesmo posto, foco em produtividade | Teclado (atalhos, Ctrl+K), densidade compacta, mínimo de cliques, nada que interrompa (convites dispensáveis, "não mostrar dicas") |
| **Supervisor** | Desktop, alterna entre equipe, filas e aprovações | Visão de exceções (status com ícone + texto), filtros persistentes, aprovação com resumo do impacto |
| **Gestor** | Desktop e celular/tablet, consultas rápidas | Painéis legíveis em telas pequenas, números com formatação brasileira, tema claro/escuro |
| **Financeiro** | Desktop, conciliação e prestação de contas | Valores com algarismos tabulares, sinais explícitos em estornos, confirmação forte em ações financeiras |
| **Auditor** | Desktop, investigação | Trilhas legíveis, datas completas, detalhe sob demanda, nenhum dado sensível sem registro |
| **Administrador** | Implantação da assessoria | Checklist de primeiros passos (credor → carteira → usuários → equipes), tours por tela |

O **desktop operacional** é o alvo primário (1366–1920 px). Tablet e celular são atendidos para gestor e, futuramente, para o devedor no portal (layout próprio, fora do shell).

## 2. Princípios cognitivos

1. **Carga cognitiva baixa**: uma ação primária por tela (botão preenchido à direita do cabeçalho), secundárias em contorno/texto; agrupamento por proximidade; textos curtos.
2. **Hierarquia visual**: título 22 px/600 → subtítulo em cor variante → conteúdo 14 px; seções com rótulo em caixa alta 12 px; cor de destaque reservada a ações e estados.
3. **Revelação progressiva**: listas mostram o essencial; detalhe em ficha/diálogo; tours curtos (≤ 6 passos) e por tela, nunca um tour gigante.
4. **Reconhecimento em vez de memorização**: menu filtrado por permissão, trilha de navegação, busca rápida por nome de tela/ação, atalhos exibidos nos próprios botões e na ajuda (`?`).
5. **Feedback imediato**: estados otimistas (preferências), snack-bar para conclusão, barra de progresso em recarga, esqueleto na primeira carga.
6. **Prevenção de erro** em ações financeiras/irreversíveis: `ConfirmDialog` com resumo do impacto (`details`), aviso (`warning`), motivo obrigatório (`reason`) e digitação de confirmação (`confirmationText`); foco inicial em "Cancelar".
7. **Teclado na estação de cobrança**: `KeyboardShortcuts` central; atalhos não disparam em campos de texto (exceto `mod+k`); lista viva na ajuda.
8. **Densidade ajustável**: confortável (padrão) e compacta; compacta só com ponteiro fino (`pointer: fine`).
9. **Conforto visual em uso prolongado**: neutros frios de baixo contraste para superfícies, texto com contraste alto, tema escuro com barra superior neutra (sem azul saturado), movimento mínimo.

## 3. Acessibilidade (WCAG 2.2 AA)

| Critério | Implementação |
| --- | --- |
| 1.4.3 / 1.4.11 contraste | Tokens semânticos com contraste ≥ 7:1 (texto) em claro e escuro (valores comentados em `_tokens.scss`); foco 2 px na cor primária (≥ 3:1) |
| 1.4.1 uso de cor | `app-status-chip` sempre com texto **e** ícone por tom; valores com sinal explícito (`+`/`−`) além da cor |
| 2.4.7 / 2.4.11 foco visível e não encoberto | `:focus-visible` global; o convite do onboarding fica no canto e não recebe foco automático; o balão do tour recebe foco e devolve ao elemento de origem |
| 2.5.8 tamanho do alvo | Botões de ícone ≥ 28 px na densidade compacta; densidade compacta desativada em telas de toque |
| 2.1.1 teclado | Tour (← → Esc), paleta (padrão combobox/listbox com `aria-activedescendant`), menus Material, atalhos |
| 2.2.1 tempo | Convites não somem sozinhos; nada com tempo limite |
| 2.3.3 / `prefers-reduced-motion` | Animações e transições reduzidas a 0,01 ms; esqueleto sem pulsação |
| 4.1.3 mensagens de status | Convites em região `aria-live="polite"`; estados vazio/erro com `role=status/alert`; tabela com `aria-busy` |
| Leitores de tela | Trilha com `aria-current="page"`; itens do menu em trilho com `aria-label`; valores compactos com o valor exato em texto oculto; menus de tema com `menuitemradio`/`aria-checked` |
| Modo de alto contraste | Destaque do tour com `outline: Highlight` em `forced-colors` |

## 4. Responsividade

| Faixa | Comportamento |
| --- | --- |
| ≥ 1280 px | Menu lateral fixo (256 px) ou trilho (72 px), busca com rótulo, contexto completo do tenant |
| 960–1279 px | Busca mais estreita |
| 600–959 px | Menu sobreposto (drawer), busca só com ícone, contexto só com nome da assessoria |
| < 600 px | Marca só com logotipo, sem nome do usuário; filtros e ações em largura total; trilha mostra os 2 últimos níveis; balão do tour fixo na parte de baixo; convites em largura total |

Tabelas largas rolam horizontalmente dentro do cartão (sem quebrar o layout). Telas futuras para gestor no celular devem preferir lista de cartões a tabelas.

## 5. Linguagem e formatação

- pt-BR claro, voz ativa, sem jargão técnico ("Não foi possível carregar os dados" em vez de "Erro 503"); verbos no infinitivo nos botões ("Cadastrar credor").
- Moeda: `app-amount` (`Intl.NumberFormat('pt-BR', BRL)`), algarismos tabulares, espaço não separável após `R$`, formato compacto com valor exato acessível.
- Datas: `appDate` (`dd/MM/yyyy` e `dd/MM/yyyy HH:mm`), `LocalDate` sem conversão de fuso.
- Documentos: `app-document` mascara por padrão (`***.982.247-**`); revelar emite `reveal` para a tela buscar no servidor, que exige `DEBTOR_SENSITIVE_VIEW` e registra `SENSITIVE_DATA_VIEWED`. O componente nunca desmascara um valor que já possui.

## 6. Estados de tela

| Estado | Padrão |
| --- | --- |
| Carregando (primeira vez) | `app-skeleton` com o formato do conteúdo (`table`, `card`, `lines`) e `aria-busy` |
| Recarregando | Barra de progresso fina no topo do cartão, conteúdo anterior mantido |
| Vazio | `app-empty-state` com ícone, mensagem, orientação e ação primária (`appEmptyAction` na `app-data-table`) |
| Erro | `app-empty-state tone="error"` com ação "Tentar novamente"; erros de servidor via snack-bar (interceptor) |
| Sucesso | Snack-bar `app-snack-success` (5 s, dispensável) |

## 7. Identidade visual (tokens)

Arquivos: `frontend/src/styles/_tokens.scss` (CSS custom properties `--app-*`), `frontend/src/styles.scss` (tema Material 3 e classes globais), `frontend/src/styles/_theme-colors.scss` (paletas geradas de `#1D4E89`).

- **Cor**: primária azul institucional; tons semânticos `success|warning|danger|info|neutral` (`--app-<tom>-bg/fg`) via `light-dark()`; superfícies `--app-page-bg`, `--app-surface`, `--app-surface-raised`, `--app-border`.
- **Tipografia**: Inter Variable (OFL) self-hosted via `@fontsource-variable/inter` — sem CDN; a CSP passou a aceitar fontes só de `'self'`. Escala `--app-text-xs…2xl` (12–28 px), `tabular-nums` no corpo.
- **Espaçamento**: base 4 px (`--app-space-1…12`). **Raio**: 4/8/12 px e pílula. **Elevação**: 3 níveis. **Movimento**: 120/200 ms, `cubic-bezier(0.2, 0, 0, 1)`.
- **Densidade**: `html[data-density]` ajusta tokens Material (`mat.theme((density: -3))`, tabela −4) e `--app-*` de espaçamento; ignorada sem `pointer: fine`.
- **Tema**: `html[data-theme=light|dark]` fixa `color-scheme`; ausente = segue o sistema. `ThemeService` aplica; `main.ts` aplica a preferência guardada antes do bootstrap (a CSP impede script inline).

Componentes compartilhados (`frontend/src/app/shared/components/`): `page-header` (âncoras de tour), `empty-state`, `skeleton`, `amount`, `document-display`, `status-chip` (ícone por tom), `confirm-dialog` (impacto/aviso/motivo/digitação), `data-table` (esqueleto, vazio e erro com ação).

Shell (`core/layout/`): cabeçalho com contexto (assessoria + escopo de credores), busca rápida (`CommandPaletteService`, `Ctrl/⌘ + K`), alternância de tema, ajuda, menu do usuário com aparência; menu lateral por permissão recolhível em trilho (`navigationCollapsed` persistido; `Alt + M`); `app-breadcrumbs` (seção → tela → detalhe; `BreadcrumbService.setCurrentLabel` para nome do registro).

## 8. Onboarding

### 8.1 Avaliação

| Opção | Decisão |
| --- | --- |
| Biblioteca de tours (Shepherd.js — AGPL/comercial; Intro.js — AGPL/comercial; Driver.js — MIT) | **Não adotada**. As duas mais conhecidas têm licença incompatível; Driver.js manipula o DOM fora do Angular, não conhece o roteador nem o estado no servidor. O CDK Overlay já está no projeto e entrega posicionamento, foco e teclado. |
| Motor próprio sobre `@angular/cdk/overlay` | **Adotado** (`core/onboarding/tour/`), ~300 linhas, sem dependência nova. |
| Progresso no navegador (`localStorage`) | **Descartado**: não acompanha o usuário entre dispositivos e some ao limpar o navegador. |
| Progresso no servidor (`identity.user_ui_state`) | **Adotado**: só o id do usuário e identificadores técnicos. |

### 8.2 Peças

| Peça | Arquivo | Função |
| --- | --- | --- |
| Definições | `core/onboarding/content/{tours,screens,release-notes,checklists}.ts` | Conteúdo declarativo e versionado |
| `TourEngine` | `tour/tour-engine.service.ts` + `tour-popover.ts` | Balão não modal ancorado em `[data-tour~=…]`, destaque que não captura cliques, passos sem âncora visível omitidos, Esc/Pular = `DISMISSED`, Concluir = `COMPLETED`, navegação encerra sem desfecho |
| `OnboardingService` | `onboarding.service.ts` | Decide convites por navegação; checklist; novidades |
| Convite | `hint/onboarding-hint.ts` | Cartão no canto, `aria-live`, "Agora não", "Não mostrar dicas automáticas" |
| Checklist | `getting-started/getting-started.ts` | "Primeiros passos" na tela inicial; reabrível pela ajuda |
| Novidades | `whats-new/whats-new-dialog.ts` | Central por versão; selo "Novo" na ajuda |
| Estado | `core/preferences/ui-state.store.ts` | Carga no login, alterações otimistas agrupadas (debounce 800 ms) em um PATCH, reenvio em falha, `flush()` antes do logout |

### 8.3 Regras dos convites

1. Só após carregar o estado do servidor (`ready`) e com `hintsEnabled`.
2. **Primeiro acesso** (nenhuma tela registrada): convite para `app.welcome`; as notas de versão anteriores são marcadas como lidas.
3. **Tela não navegada** com `tourId`: convite para o tour da tela se ele não foi registrado na versão atual.
4. **Tela com versão maior que a vista**: cartão "Novidades nesta tela" com os itens de `changes` posteriores à versão vista (e o tour, se houver versão nova).
5. Cada convite aparece no máximo uma vez: a visita é registrada ao abrir a tela; "Agora não" registra `DISMISSED` na versão do tour.
6. Nunca abre um tour sozinho; o usuário pode reabrir qualquer tour pela ajuda ou pela busca rápida.

### 8.4 Contrato para telas novas (ex.: estação de cobrança)

1. Marque elementos com `data-tour="<âncora>"` (vários valores separados por espaço são aceitos). Os componentes compartilhados já expõem `page-header`, `page-actions` e `data-table`; formulários de filtro usam `data-tour="filters"`.
2. Registre a tela em `content/screens.ts` (`key`, `version: 1`, `path`, `tourId`).
3. Escreva o tour em `content/tours.ts` (≤ 6 passos, ≤ 25 palavras por passo).
4. Ao mudar a tela de forma relevante: `version + 1` e um item em `changes` com a mesma versão; mudança grande no tour: `version + 1` no tour.
5. Inclua o destino no `NAVIGATION`: os itens de checklist com essa rota (ex.: `/cobranca/fila`, `/devedores`) passam a aparecer automaticamente para os perfis com permissão.
6. Atalhos da tela: `KeyboardShortcuts.register({ group: 'Estação de cobrança', … })` no construtor e remoção no `DestroyRef`; ações na busca rápida: `CommandPaletteService.register([...])`.

## 9. Estado de interface no servidor (módulo `identity`)

- Tabelas: `identity.user_ui_state` (1 por usuário: `theme`, `density`, `navigation_collapsed`, `hints_enabled`, `checklist_dismissed`) e filhas `user_ui_tour` (versão, desfecho, instante), `user_ui_screen` (versão vista), `user_ui_checklist` (instante de conclusão). RLS habilitado e **forçado**; filhas protegidas pela linha pai (padrão V0200). `on delete cascade` a partir de `app_user`.
- API (`UiStateController`, `ACTOR_USER`): `GET /api/v1/me/ui-state` devolve o estado ou os padrões sem gravar; `PATCH /api/v1/me/ui-state` aplica campos presentes e listas de *upsert* (máx. 50 itens por requisição). Não há id de usuário na URL: sempre o da sessão.
- Domínio (`UserUiState`): identificadores `^[a-z0-9]+(?:[.-][a-z0-9]+)*$` (≤ 64), versões 1–9999, limites de 200 tours / 500 telas / 100 itens; versões nunca regridem (aba desatualizada não "desvê" uma tela).
- Concorrência: `insert … on conflict do nothing` + `select … for update` serializa abas e dispositivos sem erro na criação.
- Logs: desfecho de tour no log técnico (`ui.onboarding.tour_recorded`, com `tourId`) e métrica `ui.onboarding.tours{outcome}` (sem o id, para não deixar a cardinalidade nas mãos do cliente). Não é fato de negócio nem auditoria.
- Testes: `UserUiStateTest`, `UiStateServiceTest`, `UiStateIT` (persistência entre sessões, isolamento entre usuários e assessorias via RLS, validação sem gravação parcial, criação concorrente, acesso anônimo 401).

## 10. Recomendações para as próximas telas

**Estação de cobrança (fila e atendimento)**
- Layout em três zonas fixas: fila/próximo caso (esquerda, recolhível), ficha do devedor no centro (títulos, contatos, histórico em abas), painel de ação à direita (registrar interação, simular, propor). Sem rolagem da página inteira; cada zona rola sozinha.
- Atalhos dedicados (grupo "Estação de cobrança"): próximo caso, registrar interação, agendar retorno, abrir simulação, alternar abas (`Alt + 1…5`). Mostrar o atalho no rótulo/tooltip do botão.
- Densidade compacta como sugestão no primeiro acesso da estação (convite, nunca imposta).
- Status do caso, do título e do acordo sempre com `app-status-chip` (novos domínios em `status.ts`).
- Cronômetro de atendimento e indicador de janela de contato permitida (compliance) visíveis, sem animação chamativa.

**Negociação**
- Simulação lado a lado (à vista × parcelado) com `app-amount` e memória de cálculo sob demanda.
- Indicar o nível da escada de concessões e o limite da alçada antes do envio; acima da alçada, o botão muda para "Pedir aprovação" (não esconder a opção).
- Formalizar/cancelar acordo e estornar pagamento: `ConfirmDialog` com `details` (devedor mascarado, valor, parcelas, vencimentos), `reason` e, para irreversíveis, `confirmationText`.

**Conversas (omnichannel e assistente)**
- Linha do tempo única por devedor com canal por ícone + texto; mensagens do assistente identificadas visualmente e por texto ("Assistente").
- Composer com modelos, contagem de caracteres por canal e bloqueio explicado quando o porteiro de contato negar (motivo legível, não código).
- Nova mensagem anunciada por `aria-live` discreto; sem som por padrão.
