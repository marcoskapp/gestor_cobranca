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
5. **Feedback imediato**: estados otimistas (preferências), notificação (toast) para conclusão, barra de progresso em recarga, esqueleto na primeira carga.
6. **Prevenção de erro** em ações financeiras/irreversíveis: `ConfirmDialog` com resumo do impacto (`details`), aviso (`warning`), motivo obrigatório (`reason`) e digitação de confirmação (`confirmationText`); foco inicial em "Cancelar".
7. **Teclado na estação de cobrança**: `KeyboardShortcuts` central; atalhos não disparam em campos de texto (exceto `mod+k`); lista viva na ajuda.
8. **Densidade ajustável**: confortável (padrão) e compacta; compacta só com ponteiro fino (`pointer: fine`).
9. **Conforto visual em uso prolongado**: neutros frios de baixo contraste para superfícies, texto com contraste alto, tema escuro com barra superior neutra (sem azul saturado), movimento mínimo.

## 3. Acessibilidade (WCAG 2.2 AA)

| Critério | Implementação |
| --- | --- |
| 1.4.3 / 1.4.11 contraste | Cores do Bootstrap parametrizadas com contraste comentado em `styles/bootstrap/_variables*.scss`: texto ≥ 4,5:1 (tons de estado ≥ 7:1) e borda de controles ≥ 3:1 nos dois modos (§11); foco visível na cor primária |
| 1.4.1 uso de cor | `app-status-chip` sempre com texto **e** ícone por tom; valores com sinal explícito (`+`/`−`) além da cor |
| 2.4.7 / 2.4.11 foco visível e não encoberto | `:focus-visible` global; o convite do onboarding fica no canto e não recebe foco automático; o balão do tour recebe foco e devolve ao elemento de origem |
| 2.5.8 tamanho do alvo | Botões de ícone ≥ 28 px na densidade compacta; densidade compacta desativada em telas de toque |
| 2.1.1 teclado | Tour (← → Esc), paleta (padrão combobox/listbox com `aria-activedescendant`), menus `ngbDropdown` (setas, Home/End, Esc), diálogos `NgbModal` (foco preso e devolvido), typeahead (combobox), atalhos |
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
| Erro | `app-empty-state tone="error"` com ação "Tentar novamente"; erros de servidor via notificação (interceptor) |
| Sucesso | Notificação de sucesso (`NotificationService.success`, toast do ng-bootstrap, 5 s, pausa com ponteiro/foco, dispensável) |

## 7. Identidade visual (tokens)

Desde a onda 1 da migração (decisões 220+), a interface usa **Bootstrap 5.3 + ng-bootstrap** e a identidade é definida pela **parametrização oficial do Bootstrap** (§11). Arquivos:

- `frontend/src/styles/bootstrap/` — variáveis documentadas do Bootstrap (claro e escuro), mapas, utilitários, ajustes de modo escuro e a ordem de importação.
- `frontend/src/styles/_tokens.scss` — tokens `--app-*` como **aliases** das variáveis `--bs-*` (as telas e componentes os usam); cores próprias (barra superior, gráficos, borda de controles) por modo.
- `frontend/src/styles/_density.scss` — densidade compacta.
- `frontend/src/styles.scss` — classes globais da aplicação (layout de página, estados, onboarding).

- **Cor**: primária `#3068b5` (componentes) e navy institucional `#0f2f57`/`#123f73` (barra superior, textos de ênfase); tons `success|warning|danger|info|neutral` = cores "subtle/emphasis" do Bootstrap (`--app-<tom>-bg/fg`); superfícies `--bs-body-bg` (cartões, campos) sobre `--bs-tertiary-bg` (página).
- **Tipografia**: Inter Variable (OFL) self-hosted via `@fontsource-variable/inter` — sem CDN; peso base **300** (fina e delicada), títulos 400, ênfases moderadas (500/600); 14 px no texto, 13 px em tabelas; `tabular-nums` no corpo.
- **Forma**: raios generosos — 12 px (botões, campos, menus), 16 px (cartões, alertas), 20 px (diálogos), pílula (selos, busca). **Elevação**: utilitários `.shadow-sm`/`.shadow` (sombras suaves parametrizadas). **Movimento**: transições do Bootstrap, reduzidas com `prefers-reduced-motion`.
- **Densidade**: `html[data-density=compact]` ajusta as CSS custom properties documentadas dos componentes (`--bs-btn-*`, `--bs-dropdown-*`…) e os componentes compartilhados escolhem `.table-sm`/`.form-control-sm` via `DensityService`; ignorada sem `pointer: fine`.
- **Tema**: `ThemeService` aplica `data-bs-theme="light|dark"` (modo efetivo; "seguir o sistema" resolve `prefers-color-scheme` e acompanha mudanças) e `data-theme` (preferência fixada, usada pelo `color-scheme` e pelo tema Material transitório); `main.ts` aplica a preferência guardada antes do bootstrap (a CSP impede script inline).

Componentes compartilhados (`frontend/src/app/shared/`): ver §12.

Shell (`core/layout/`): cabeçalho com contexto (assessoria + escopo de credores), busca rápida (`CommandPaletteService`, `Ctrl/⌘ + K`, `NgbModal`), alternância de tema, ajuda e menu do usuário (`ngbDropdown`, com tema e densidade); menu lateral por permissão (pílulas do Bootstrap) recolhível em trilho (`navigationCollapsed` persistido; `Alt + M`) e em painel `NgbOffcanvas` abaixo de 960 px; `app-breadcrumbs` (breadcrumb do Bootstrap; `BreadcrumbService.setCurrentLabel` para nome do registro).

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
- Cronômetro de atendimento e "posso contatar agora?" (decisão do porteiro do compliance por canal, com o próximo horário permitido — a tela não calcula janelas; `collection.md` §8.1) visíveis, sem animação chamativa.

**Negociação**
- Simulação lado a lado (à vista × parcelado) com `app-amount` e memória de cálculo sob demanda.
- Indicar o nível da escada de concessões e o limite da alçada antes do envio; acima da alçada, o botão muda para "Pedir aprovação" (não esconder a opção).
- Formalizar/cancelar acordo e estornar pagamento: `ConfirmDialog` com `details` (devedor mascarado, valor, parcelas, vencimentos), `reason` e, para irreversíveis, `confirmationText`.

**Conversas (omnichannel e assistente)**
- Linha do tempo única por devedor com canal por ícone + texto; mensagens do assistente identificadas visualmente e por texto ("Assistente").
- Composer com modelos, contagem de caracteres por canal e bloqueio explicado quando o porteiro de contato negar (motivo legível, não código).
- Nova mensagem anunciada por `aria-live` discreto; sem som por padrão.

## 11. Bootstrap: parametrização

Decisão do responsável: *"A aplicação deve utilizar o Bootstrap (getbootstrap.com) na versão 5.3 como framework para desenvolvimento das telas. Ajuste os parâmetros do Bootstrap para que o resultado sejam componentes arredondados com fonte mais fina e delicada, tudo usando os padrões de parametrização do Bootstrap para facilitar futuras migrações para novas versões do framework."* Escolha confirmada: substituir o Angular Material por **Bootstrap 5.3 + ng-bootstrap** em todas as telas, em ondas.

### 11.1 Versões e pacotes

| Pacote | Versão | Motivo |
| --- | --- | --- |
| `bootstrap` | **5.3.8** (fixa) | Última estável da linha 5.3. Só o Sass é usado — **o JavaScript do Bootstrap não é carregado** (o ng-bootstrap reimplementa os componentes em Angular). Sem jQuery, sem CDN. |
| `@ng-bootstrap/ng-bootstrap` | **21.0.0** (fixa) | Linha compatível com Angular 22 (peer `@angular/core ^22.0.0`, conferido no pacote). MIT. |
| `@popperjs/core` | **2.11.8** (fixa) | Exigido pelo ng-bootstrap (posicionamento de menus, tooltips, typeahead, datepicker). |
| `@angular/localize` | `^22.2.3` | Peer do ng-bootstrap (`$localize`); permite traduzir os rótulos acessíveis do ng-bootstrap em tempo de execução (`core/config/ngb-translations.ts`, carregado em `main.ts`). Polyfill `@angular/localize/init` em `angular.json`. |
| `bootstrap-icons` | **1.13.1** (fixa) | Ícones SVG MIT, servidos pela aplicação em sprite próprio (§12.1). |

### 11.2 Estrutura (ordem de "Customize → Sass" da documentação)

`frontend/src/styles/bootstrap/_index.scss` importa, nesta ordem: `functions` → **`_variables.scss`** e **`_variables-dark.scss`** (sobrescritas) → `variables` → `variables-dark` → **`_maps.scss`** → `maps` → `mixins` → `utilities` → **`_utilities.scss`** → `root`/`reboot`/`type`/`containers`/`grid` → componentes usados → **`_color-modes.scss`** → `helpers` → `utilities/api`.

Regras:

1. **Só variáveis documentadas.** Os arquivos `_variables*.scss` contêm apenas atribuições a variáveis que existem com `!default` no Bootstrap instalado. O script `npm run bootstrap:check` (rodado antes de `build` e `test`) falha se alguma variável não existir — é o alarme de que uma nova versão renomeou ou removeu algo.
2. **Nenhum seletor interno do Bootstrap é sobrescrito**, salvo as exceções da §11.6. Ajustes de componente usam as CSS custom properties que o próprio Bootstrap expõe (`--bs-btn-*`, `--bs-nav-*`, `--bs-modal-*`, `--bs-card-*`…), aplicadas em classes da aplicação (`.app-topbar__btn`, `.app-sidenav__list`, `.app-palette-window`), como mostra a documentação ("Buttons → Variables", "Navs → Variables").
3. **Mapas e utilitários pelas APIs oficiais**: `map-remove($theme-colors, …)` e `$utilities` com `map-merge` (`responsive: false`).
4. **Modo escuro nativo** (`$color-mode-type: data`, `[data-bs-theme="dark"]`): cores do escuro em `_variables-dark.scss`; o único ajuste além de variáveis usa os mixins documentados `color-mode()` e `button-outline-variant()` (§11.5).
5. **Importação seletiva**: ficam de fora `carousel`, `images`, `navbar`, `popover`, `forms/floating-labels` e `forms/form-range`. Para usar um deles numa tela, acrescente o `@import` em `_index.scss` — nunca copie CSS do Bootstrap.

### 11.3 Variáveis alteradas e por quê

| Grupo | Variáveis (valor) | Por quê |
| --- | --- | --- |
| Opções globais | `$prefix: bs-` (valor padrão, declarado para as sobrescritas poderem usar `var(--bs-…)`), `$enable-shadows: false`, `$enable-gradients: false`, `$enable-caret: false`, `$enable-validation-icons: false`, `$enable-reduced-motion: true`, `$enable-smooth-scroll: false`, `$enable-negative-margins: false`, `$enable-dark-mode: true`, `$color-mode-type: data`, `$enable-deprecation-messages: false` | Superfícies planas e leves (elevação só onde pedida, com `.shadow-sm`/`.shadow`); sem seta automática em menus (botões só de ícone ficam limpos; o menu do usuário mostra um chevron explícito); sem ícone de validação embutido (a mensagem em texto comunica e o SVG embutido não acompanha o modo escuro); respeito a `prefers-reduced-motion`; rolagem instantânea para quem usa teclado; modo escuro por atributo controlado pelo `ThemeService` (permite "seguir o sistema" e fixar claro/escuro). |
| Cores do tema | `$primary: #3068b5`, `$secondary: #5b6675`, `$success: #1e7b3f`, `$info: #1f6fb5`, `$warning: #e8a317`, `$danger: #b3261e`, `$min-contrast-ratio: 4.5` | Azul institucional um tom acima do navy `#1d4e89` para servir aos **dois** modos sem sobrescrever componentes: texto branco 5,57:1 e 3,08:1 contra a superfície escura (contorno de caixas de seleção, WCAG 1.4.11). Demais tons com texto branco ≥ 5,2:1; `warning` usa texto preto (escolhido pelo `color-contrast` do Bootstrap). |
| Tons "subtle/emphasis" (claro) | `$<cor>-text-emphasis`, `$<cor>-bg-subtle`, `$<cor>-border-subtle` para primary, secondary, success, info, warning, danger (valores em `_variables.scss`) | São exatamente os tokens semânticos da identidade anterior (`--app-<tom>-bg/fg`), com contraste ≥ 7:1 — viram a base de alertas, selos de status (`app-status-chip`), destaque do menu e do item ativo. |
| Corpo e superfícies | `$body-color: #1b2430` (15,6:1), `$body-bg: #fff` (superfície), `$body-secondary-bg: #eceff3`, `$body-tertiary-bg: #f6f8fb` (página, `.bg-body-tertiary` no `<body>`), `$body-emphasis-color`, `$box-shadow*` (sombras suaves), `$border-color: #dde2e9`, `$border-color-translucent`, `$link-color: $primary`, `$link-decoration: none`, `$link-hover-decoration: underline` | Neutros frios de baixo contraste nas superfícies e texto de alto contraste para turnos longos; cartões brancos sobre fundo levemente acinzentado; links sem sublinhado em repouso (interface densa), sublinhados ao passar o ponteiro. |
| Tipografia | `$font-family-sans-serif` (Inter Variable self-hosted), `$font-family-monospace`, `$font-size-base: .875rem`, `$font-size-sm: .8125rem`, `$font-size-lg: 1rem`, `$font-weight-light: 300`, `$font-weight-normal: 400`, `$font-weight-medium: 500`, `$font-weight-semibold: 500`, `$font-weight-bold: 600`, **`$font-weight-base: 300`**, `$line-height-base: 1.5`, `$h1…h6-font-size` (22/19/16/15/14/13 px), **`$headings-font-weight: 400`**, `$headings-line-height: 1.25`, `$headings-margin-bottom`, `$display-font-weight: 300`, `$lead-font-weight: 300`, `$small-font-size`, `$dt-font-weight`, `$legend-font-weight` | **Fonte fina e delicada**: corpo em 300, títulos em 400 e ênfases moderadas (500/600) da Inter Variable (OFL, já servida pela aplicação — nenhuma fonte nova nem CDN). Escala de títulos de aplicação operacional (h1 = título de página de 22 px), não de site. `$letter-spacing` não existe no Bootstrap 5.3 — o espaçamento sutil fica só no título da página (`.app-page-header__title`). |
| Arredondamento | `$border-radius: .75rem`, `-sm: .5rem`, `-lg: 1rem`, `-xl: 1.25rem`, `-xxl: 1.75rem`, `-pill`; `$btn-border-radius*`, `$input-border-radius*`, `$card-border-radius` (lg), `$modal-content-border-radius` (xl), `$dropdown-border-radius`, `$toast-border-radius` (lg), `$alert-border-radius` (lg), `$list-group-border-radius` (lg), `$accordion-border-radius` (lg), `$popover-border-radius` (lg), `$tooltip-border-radius` (sm), `$badge-border-radius` (pílula), `$progress-border-radius` (pílula), `$pagination-border-radius`, `$nav-pills-border-radius`, `$form-check-input-border-radius: .375em` | **Componentes arredondados**: 12 px em botões e campos, 16 px em cartões, 20 px em diálogos, pílula em selos. Todos referenciam as CSS custom properties `--bs-border-radius*`, então a densidade ou um tema futuro ajusta tudo num lugar. |
| Foco | `$focus-ring-width: .1875rem`, `$focus-ring-opacity: .45` | Anel de foco do Bootstrap mais visível (WCAG 2.4.7); elementos fora do Bootstrap usam o contorno global `:focus-visible` (`--app-focus-ring`). |
| Botões e campos | `$input-btn-padding-y/x` e `-sm`, `$btn-font-weight: 400`, `$btn-padding-x: 1rem`, `$input-border-color` e `$form-check-input-border` = `var(--app-control-border)`, `$form-label-margin-bottom`, `$form-label-font-weight: 400`, `$form-text-margin-top`, `$input-placeholder-color` | Altura de 37 px (confortável); botões em peso 400 (delicados). **Borda de controles ≥ 3:1** contra a superfície nos dois modos (`#8a93a0` claro, `#6b7480` escuro — o padrão do Bootstrap, `#dee2e6`, tem 1,3:1 e reprovaria o WCAG 1.4.11); o valor por modo vem do token `--app-control-border`. |
| Tabelas | `$table-color: var(--bs-body-color)`, `$table-th-font-weight: 500`, `$table-cell-padding-*` (e `-sm`), `$table-cell-vertical-align: middle`, `$table-border-color`, `$table-hover-bg-factor: .04` | Texto da tabela na cor do corpo (o padrão é a cor de ênfase, preta); células com respiro de 10 px (6 px na compacta com `.table-sm`); hover discreto. |
| Cartões, diálogos, menus, avisos | `$card-spacer-*`, `$card-cap-bg`, `$card-border-color`; `$modal-inner-padding`, `$modal-header-padding-*`, `$modal-header/footer-border-width: 0`, `$modal-sm/md/lg` (400/560/800 px), `$modal-backdrop-opacity`; `$dropdown-padding-*`, `$dropdown-item-padding-*`, `$dropdown-border-color`, `$dropdown-link-active-*`; `$nav-pills-link-active-*`, `$nav-link-padding-*`; `$tooltip-*`; `$toast-*`; `$badge-*`; `$alert-padding-*`; `$pagination-padding-*`, `$pagination-font-size`; `$list-group-item-padding-*`; `$offcanvas-*`; `$breadcrumb-*` (separador `›`); `$placeholder-opacity-*`; `$progress-height`; `$spinner-border-width-sm`; `$kbd-*` | Diálogos sem linhas divisórias e com a largura padrão anterior (560 px); item ativo de menus e do menu lateral em tom suave (`primary-bg-subtle` + `primary-text-emphasis`) em vez do azul sólido; selos e paginação compactos; teclas (`kbd`) claras em vez do preto padrão; barra de progresso fina. |
| Modo escuro (`_variables-dark.scss`) | `$<cor>-text-emphasis-dark`, `$<cor>-bg-subtle-dark`, `$<cor>-border-subtle-dark`, `$body-color-dark: #e6edf3`, `$body-bg-dark: #171c23`, `$body-secondary-bg-dark`, `$body-tertiary-bg-dark: #0f1217`, `$body-emphasis-color-dark`, `$border-color-dark`, `$border-color-translucent-dark`, `$link-color-dark: #a7c8ff`, `$link-hover-color-dark`, `$form-valid/invalid-(border-)color-dark` | Os mesmos tokens escuros da identidade anterior (textos ≥ 7:1, superfícies de baixo brilho); links em azul claro (10:1). |
| Mapas (`_maps.scss`) | `$theme-colors: map-remove($theme-colors, 'light', 'dark')` | A aplicação usa modos de cor, não variantes claras/escuras de componente; remove `.btn-light`, `.text-bg-dark`, `.table-light` etc. e reduz o CSS. |
| Utilitários (`_utilities.scss`) | `responsive: false` para `float`, `object-fit`, `flex-grow/shrink`, `align-content/self`, `order`, margens e paddings direcionais (`mx/my/mt/me/ms`, `px/py/pt/pe/pb/ps`), `row-gap`, `column-gap` | Os utilitários de leiaute responsivo mais usados (`d-*`, `flex-*`, `justify/align-items`, `gap`, `m`, `mb`, `p`, `text-*`) mantêm os sufixos de breakpoint; os demais continuam existindo sem sufixo. Reduz ~35 kB do CSS inicial. |

Contraste conferido (WCAG 2.2 AA): texto secundário 6,9:1 (claro) e 8,4:1 (escuro); link 5,2:1 sobre o fundo da página; tons de estado 7,1–9,8:1; barra superior 13,4:1 / 14,6:1.

### 11.4 Densidade compacta

`styles/_density.scss` (só com `pointer: fine`): tokens de espaçamento da aplicação e as CSS custom properties documentadas de `.btn`, `.dropdown-menu`, `.nav`, `.list-group`, `.card`, `.modal` e `.pagination`. Tabela e campos não têm variáveis CSS no 5.3: o `app-data-table` usa `.table-sm` e a diretiva `appInput` usa `.form-control-sm`/`.form-select-sm` quando `DensityService.compact()` (atributo `data-density` + ponteiro fino).

### 11.5 Modo escuro e o `ThemeService`

`ThemeService` grava `data-bs-theme` com o modo **efetivo** (no "seguir o sistema" escuta `prefers-color-scheme` e troca na hora) e `data-theme` com a preferência fixada. A barra superior usa `data-bs-theme="dark"` em qualquer modo (fundo institucional); por isso seus tokens (`--app-topbar-*`) são definidos só no `<html>`. Em `_color-modes.scss`, os botões de contorno usam, no escuro, os tons `$theme-colors-text-dark` via `button-outline-variant()` (o texto `#3068b5` teria 3,1:1 sobre a superfície escura).

### 11.6 Exceções documentadas (seletores próprios sobre marcação do Bootstrap/ng-bootstrap)

| Onde | O quê | Por quê |
| --- | --- | --- |
| `app-data-table` (`data-table.scss`) | `.app-table__pagination ::ng-deep .pagination { margin: 0 }` | O `<ul>` do `ngb-pagination` herda a margem inferior de listas do reboot; o componente não expõe classe para isso. |
| `app-data-table` | cabeçalho fixo e em 12 px (`.app-table__head th`) | O Bootstrap não tem variável para o cabeçalho da tabela. |
| `app-breadcrumbs` | no celular, esconde o separador `::before` do primeiro item visível | A trilha mostra só os 2 últimos níveis abaixo de 600 px. |
| `app-entity-picker` | `:focus-within` com o anel de foco no contêiner dos selos | O campo de busca fica dentro de um contêiner com aparência de `.form-control`. |

### 11.7 Atualizar o Bootstrap no futuro

1. Trocar as versões fixas em `package.json` (`bootstrap`, e o `@ng-bootstrap/ng-bootstrap` compatível com o Angular do projeto) e rodar `npm install`.
2. `npm run bootstrap:check` — lista variáveis que deixaram de existir; revisar a tabela da §11.3 contra o `_variables.scss`/`_variables-dark.scss` da nova versão (novos `!default` podem merecer ajuste).
3. Revisar `_index.scss` contra o `bootstrap.scss` da nova versão (módulos novos ou renomeados) e as exceções da §11.6.
4. Conferir os ids de tradução do ng-bootstrap (`core/config/ngb-translations.ts`) e rodar `npm test`, `npm run build` e as capturas claro/escuro.

## 12. Componentes e serviços da fundação (onda 1)

| Peça | Arquivo | Notas |
| --- | --- | --- |
| `app-icon` | `shared/components/icon/` | Bootstrap Icons (MIT) num **sprite SVG próprio** (`public/icons/app-icons.svg`, ~107 kB, baixado sob demanda e em cache) gerado por `npm run icons` a partir de `icon-names.ts`; `<use href>` com `currentColor`; decorativo (`aria-hidden`) ou `label` → `role="img"`. Sem fonte de ícones, sem CDN, sem `innerHTML`. Aceita, na transição, nomes do Material (`material-aliases.ts`, §14). |
| `DialogService` + `DialogRef` + `DIALOG_DATA` | `shared/dialog/` | Substituto do `MatDialog` sobre `NgbModal`: `open(Componente, { data, size, disableClose, role })` → `afterClosed()` (resultado ou `undefined`). Diretivas `appDialogTitle` (liga o `aria-labelledby`) e `appDialogClose` (sem valor = cancelamento). Foco preso, Esc, foco devolvido; `ngbAutofocus` marca o foco inicial. |
| `app-field` + `appInput` | `shared/forms/` | Substituto do `mat-form-field`: rótulo, ajuda (`form-text`), erro (`invalid-feedback` com `errorMessage()`), `is-invalid` + `aria-invalid` + `aria-describedby`; mostra erro após tocar ou submeter; tamanho compacto pela densidade. |
| `app-data-table` | `shared/components/data-table/` | Mesma API (+ `sortable`/`sort`/`sortChange` opcionais): tabela responsiva do Bootstrap, cabeçalho fixo, `ngb-pagination` + quantidade por página + faixa "1 – 20 de 135", esqueleto/vazio/erro. |
| `ConfirmService`/`ConfirmDialog` | `shared/components/confirm-dialog/` | Mesma API; `alertdialog` quando destrutivo, foco inicial em "Cancelar", motivo e palavra de confirmação com `app-field`. |
| `DetailDialog` (`openDetail`) | `shared/components/detail-dialog/` | Ficha somente leitura (lista de definições + JSON), para log de acesso/auditoria nas próximas ondas. |
| `NotificationService` + `app-toasts` | `core/notification/` | Mesma API (`success/info/error`); toasts do ng-bootstrap na raiz; `aria-live` polido/assertivo; erro 10 s, demais 5 s, pausa com ponteiro/foco, sem empilhar repetidos. |
| `app-entity-picker` | `shared/components/entity-picker/` | Mesma API; `ngbTypeahead` (combobox) + selos removíveis; "Nenhum resultado" anunciado. |
| `app-status-chip` | `shared/components/status-chip/` | Badge em pílula com ícone (`TONE_ICON`) nas cores subtle/emphasis. |
| `app-empty-state`, `app-skeleton` (placeholders), `app-page-header`, `app-amount`, `app-document`, `app-password-field`, `app-session-table`, `app-totp-setup` | `shared/components/` | Mesma API, marcação Bootstrap. |
| `DensityService` | `core/preferences/` | Densidade efetiva para componentes (sem depender do estado do usuário). |
| `provideBootstrapDefaults()` (raiz) e `provideBootstrapComponentDefaults()` (rotas do shell e do portal) | `core/config/bootstrap.config.ts` | Raiz: datas `dd/MM/aaaa` (`PtBrDateParserFormatter`) com modelo ISO `yyyy-MM-dd` (`IsoDateAdapter`, `LocalDate` sem fuso) e diálogos roláveis. Rotas: domingo como primeiro dia, menus/tooltips/calendário do campo no `body` (calendário em posição fixa), paginação com primeira/última, autocompletar sem pré-seleção — ver §12.1. |
| Shell, trilha, busca rápida, ajuda, atalhos, novidades, onboarding (convite, balão do tour, primeiros passos), páginas 403/404, moldura e telas de autenticação, página inicial | `core/layout`, `core/onboarding`, `features/auth`, `features/dashboard` | Reescritos; âncoras `data-tour` preservadas. O motor do tour continua no CDK Overlay. |

### 12.1 Bundle inicial e padrões do ng-bootstrap

A migração terminou: o Angular Material, o adaptador de datas do Material, o `date-fns` e a fonte `material-icons` saíram do projeto, junto com o tema Material (`_material-legacy.scss`, `_theme-colors.scss`), os padrões `provideMaterialDefaults()`, a lista `LEGACY_DIALOG_LAUNCHERS` e os aliases de ícones do Material. O `@angular/cdk` continua como biblioteca sem aparência (área de transferência, anúncios para leitor de tela, pontos de quebra e o overlay do tour), recursos que o ng-bootstrap não oferece.

O ng-bootstrap é publicado como um único módulo: configurar um componente na raiz (`provideAppInitializer`) traz o código dele para o bundle inicial. Por isso a raiz só configura o que a moldura inicial usa — adaptador e formatação das datas e diálogos (`provideBootstrapDefaults()`) — e os padrões dos componentes de tela (tooltips, menus, paginação, calendários, autocompletar) são aplicados por `provideBootstrapComponentDefaults()` nos providers das rotas do shell e do portal, que já são carregadas sob demanda. As configurações do ng-bootstrap são singletons da raiz: o inicializador do injetor da rota altera a mesma instância usada pelos diálogos do `DialogService`. A moldura de autenticação (rota inicial) configura o seu único tooltip direto no elemento.

Nomes de ícone literais nos templates são conferidos por `npm run icons:check` (antes do build e dos testes): nome fora do sprite falha.

## 13. Guia de migração (próximas ondas)

### 13.1 Material → Bootstrap/ng-bootstrap

| Angular Material | Bootstrap 5.3 / ng-bootstrap / aplicação |
| --- | --- |
| `mat-form-field` + `matInput` + `mat-label`/`mat-hint`/`mat-error` | `<app-field label hint>` + `<input appInput formControlName>` (erro automático em pt-BR; `[error]` para mensagem própria; `hintEnd` para contador) |
| `mat-select` / `mat-option` | `<select appInput formControlName>` + `<option [ngValue]>` (nativo, acessível); seleção múltipla com busca → `app-entity-picker` |
| `mat-datepicker` (`[matDatepicker]`) | `<input appInput ngbDatepicker #d="ngbDatepicker" formControlName placeholder="dd/mm/aaaa">` + botão `(click)="d.toggle()"` com `app-icon name="calendar-event"` e `aria-label="Abrir calendário"` dentro de `.input-group`; modelo ISO `yyyy-MM-dd` (controle `string \| null`; limites com `[minDate]="toDateStruct(iso)"` de `shared/models/dates`), digitação `dd/MM/aaaa`, nomes de meses em português (locale `pt-BR`); erro `ngbDate` traduzido. Intervalo (`mat-date-range-input`) → dois campos "de"/"até" com limites cruzados |
| `mat-checkbox` | `<div class="form-check"><input class="form-check-input" type="checkbox" id formControlName><label class="form-check-label" for></div>` |
| `mat-radio-group`/`mat-radio-button` | `.form-check` com `type="radio"` dentro de `<fieldset><legend class="form-label">` |
| `mat-slide-toggle` | `.form-check.form-switch` com `role="switch"` |
| `mat-button-toggle-group` | `.btn-group` (`role="group"` + `aria-label`) com `input.btn-check` (`type="radio"`, `formControlName`) + `label.btn.btn-outline-primary` — padrão nativo do Bootstrap |
| `mat-tab-group` | `ngbNav` (`<ul ngbNav #nav="ngbNav" class="nav-tabs">` + `[ngbNavOutlet]`), teclado e ARIA prontos |
| `mat-expansion-panel` / `mat-accordion` | `ngbAccordion` (`ngbAccordionItem`, `ngbAccordionButton`, `ngbAccordionCollapse`) |
| `mat-menu` + `[matMenuTriggerFor]` | `ngbDropdown` + `ngbDropdownToggle` + `ngbDropdownMenu` (`class="shadow app-menu"`) + `ngbDropdownItem` (seta via `app-icon`, sem caret automático) |
| `matTooltip` | `ngbTooltip` (aberto após 300 ms, anexado ao `body`); botão só de ícone continua com `aria-label` |
| `mat-chip-set` / `mat-chip` | `span.badge.rounded-pill` (+ `.btn-close` para remover); estados → `app-status-chip` |
| `mat-stepper` | `ngbNav` com passos numerados e `aria-current="step"`, ou lista `.list-group` (o ng-bootstrap não tem stepper) |
| `mat-progress-bar` | `.progress` + `.progress-bar` (`role="progressbar"`, `aria-valuenow`); indeterminado: `.progress-bar-striped.progress-bar-animated.w-100` |
| `mat-progress-spinner` | `.spinner-border` (`-sm` em botões, `aria-hidden` + texto) |
| `mat-paginator` / `matSort` / `mat-table` | `app-data-table` (`page`, `sort`/`sortChange`, `columns[].sortable`); tabela própria: `.table-responsive` > `table.table.table-hover` |
| `mat-autocomplete` | `ngbTypeahead` (texto livre) ou `app-entity-picker` (ids) |
| `MatDialog` / `MAT_DIALOG_DATA` / `MatDialogRef` / `mat-dialog-*` | `DialogService` / `DIALOG_DATA` / `DialogRef` / `.modal-header` (`h2 appDialogTitle`, `.btn-close appDialogClose`), `.modal-body`, `.modal-footer` (`[appDialogClose]="valor"`) |
| `MatSnackBar` | `NotificationService` |
| `mat-card` | `.card` > `.card-body` (`.card-title`, `.card-subtitle`) ou a classe `.app-card` |
| `mat-list` / `mat-nav-list` | `.list-group` (`.list-group-item-action` para links) |
| `matBadge` | `.badge` (contagem) ou ponto `position-absolute translate-middle p-1 rounded-circle` com texto oculto |
| `mat-button` / `mat-flat-button` / `mat-stroked-button` / `mat-icon-button` | `.btn.btn-link` / `.btn.btn-primary` (`.btn-danger` destrutivo) / `.btn.btn-outline-primary` (`-secondary`) / `.btn` com `app-icon` + `aria-label` (variáveis `--bs-btn-*` para ajustes) |
| `mat-icon` | `app-icon name="…"` (Bootstrap Icons; §14) |
| `mat-divider` | `<hr>` ou `.dropdown-divider` em menus |
| Classes `cdk-visually-hidden` | `visually-hidden` |

### 13.2 Padrões de formulário

- Rótulo sempre visível acima do campo (`app-field`); nada de placeholder no lugar do rótulo (WCAG 3.3.2). Ajuda curta em `hint`; contador em `hintEnd`.
- Validação reativa: o erro aparece depois de tocar o campo ou submeter (`markAllAsTouched()` no submit, padrão já usado). Erros de servidor continuam com `applyServerErrors()` (chave `server`), exibidos pelo `app-field`.
- Grupo de campos: `.app-form-grid` (grade responsiva) ou `row g-3` + `col-md-*`. Ações em `.app-form-actions` (primária à direita).
- Erro de formulário inteiro (ex.: senhas diferentes): `<div class="invalid-feedback d-block" role="alert">`.
- Alerta de tela: `.alert.alert-<tom>` com `role="status"` (informativo) ou `role="alert"` (erro).

### 13.3 Checklist por tela

1. Trocar todos os `Mat*Module`/`mat-*` pelos equivalentes da §13.1; nenhum import de `@angular/material` no arquivo.
2. Ícones: `app-icon` com o nome do Bootstrap Icons (§14); acrescentar nomes novos em `icon-names.ts` e rodar `npm run icons`.
3. Diálogos: `DialogService` e `DIALOG_DATA`/`DialogRef`; foco inicial (`ngbAutofocus`) no campo principal ou em "Cancelar" quando destrutivo; o serviço da tela que estiver em `LEGACY_DIALOG_LAUNCHERS` volta a `providedIn: 'root'`.
4. Estilos da tela: trocar `--mat-sys-*` por `--bs-*` (ex.: `on-surface-variant` → `--bs-secondary-color`, `primary` → `--bs-primary` ou `--bs-primary-text-emphasis` em texto, `surface-container*` → `--bs-secondary-bg`/`--bs-tertiary-bg`, `outline-variant` → `--bs-border-color`, `error` → `--bs-danger-text-emphasis`); preferir utilitários do Bootstrap a CSS novo.
5. Acessibilidade: rótulos/`aria-label` preservados, ordem de foco, contraste (usar `-emphasis` para texto colorido), estados com texto + ícone, `data-tour` preservado.
6. Testes: atualizar seletores (`tr.mat-mdc-row` → `app-data-table tbody tr`, `mat-dialog-actions` → `.modal-footer`), providers (`MatDialogRef` → `DialogRef`), `NgbConfig.animation = false` em testes de diálogo.
7. Capturas claro/escuro, desktop/celular; `npm run build`, `npm test`, `npm run lint`.
8. Atualizar a versão da tela em `onboarding/content/screens.ts` só se o comportamento mudar (troca visual não é novidade para o usuário).

## 14. Mapeamento de ícones (Material → Bootstrap Icons)

Fonte: `shared/components/icon/material-aliases.ts` (usado pelo `app-icon` durante a transição; apagar ao fim da migração). Use o nome da direita nas telas migradas.

| Material | Bootstrap Icons | Material | Bootstrap Icons | Material | Bootstrap Icons |
| --- | --- | --- | --- | --- | --- |
| `abc` | `alphabet` | `folder_open` | `folder2-open` | `playlist_remove` | `list-task` |
| `account_balance` | `bank` | `folder_shared` | `folder-symlink` | `policy` | `shield-check` |
| `account_balance_wallet` | `wallet2` | `format_list_numbered` | `list-ol` | `power_settings_new` | `power` |
| `account_tree` | `diagram-3` | `forum` | `chat-left-text` | `preview` | `eye` |
| `add` | `plus-lg` | `forward_to_inbox` | `forward` | `priority_high` | `exclamation-lg` |
| `add_business` | `shop` | `front_hand` | `hand-index` | `privacy_tip` | `shield-exclamation` |
| `add_card` | `credit-card` | `functions` | `calculator` | `publish` | `upload` |
| `add_task` | `calendar-plus` | `gavel` | `hammer` | `qr_code_2` | `qr-code` |
| `alarm` | `alarm` | `group` | `people` | `queue` | `collection` |
| `alt_route` | `signpost-split` | `group_add` | `person-plus` | `radio` | `broadcast` |
| `apartment` | `buildings` | `group_off` | `person-x` | `radio_button_checked` | `record-circle` |
| `archive` | `archive` | `groups` | `people-fill` | `radio_button_unchecked` | `circle` |
| `arrow_back` | `arrow-left` | `handshake` | `person-hearts` | `rate_review` | `chat-square-text` |
| `arrow_downward` | `arrow-down` | `headset_mic` | `headset` | `receipt_long` | `receipt` |
| `arrow_forward` | `arrow-right` | `hearing` | `ear` | `refresh` | `arrow-clockwise` |
| `arrow_upward` | `arrow-up` | `help` | `question-circle` | `remove` | `dash-lg` |
| `article` | `file-earmark-text` | `help_outline` | `question-circle` | `remove_circle_outline` | `dash-circle` |
| `assignment` | `clipboard` | `history` | `clock-history` | `replay` | `arrow-counterclockwise` |
| `assignment_ind` | `person-badge` | `home` | `house` | `report` | `exclamation-octagon` |
| `assignment_return` | `arrow-return-left` | `hourglass_bottom` | `hourglass-bottom` | `request_page` | `file-earmark-ruled` |
| `assignment_turned_in` | `clipboard-check` | `hourglass_empty` | `hourglass` | `request_quote` | `file-earmark-text` |
| `assistant` | `stars` | `hourglass_top` | `hourglass-top` | `restore` | `arrow-counterclockwise` |
| `attach_file` | `paperclip` | `how_to_reg` | `person-check` | `route` | `signpost-2` |
| `auto_awesome` | `stars` | `image` | `image` | `rule` | `list-check` |
| `auto_delete` | `trash3` | `inbox` | `inbox` | `save` | `floppy` |
| `auto_fix_high` | `magic` | `info` | `info-circle` | `savings` | `piggy-bank` |
| `autorenew` | `arrow-repeat` | `input` | `box-arrow-in-right` | `schedule` | `clock` |
| `badge` | `person-vcard` | `insights` | `graph-up` | `science` | `eyedropper` |
| `balance` | `bank2` | `key` | `key` | `score` | `speedometer2` |
| `bar_chart` | `bar-chart` | `keyboard` | `keyboard` | `search` | `search` |
| `block` | `slash-circle` | `label` | `tag` | `search_off` | `search` |
| `bolt` | `lightning-charge` | `language` | `globe` | `send` | `send` |
| `brightness_auto` | `circle-half` | `light` | `lightbulb` | `settings` | `gear` |
| `business` | `building` | `light_mode` | `sun` | `settings_input_antenna` | `broadcast-pin` |
| `calculate` | `calculator` | `lightbulb` | `lightbulb` | `share` | `share` |
| `call` | `telephone` | `link` | `link-45deg` | `shield` | `shield` |
| `call_end` | `telephone-x` | `link_off` | `link` | `skip_next` | `skip-forward` |
| `call_split` | `signpost-split` | `list` | `list-ul` | `smart_toy` | `robot` |
| `campaign` | `megaphone` | `list_alt` | `card-list` | `sms` | `chat-dots` |
| `cancel` | `x-circle` | `local_post_office` | `mailbox` | `source` | `folder` |
| `cases` | `briefcase` | `lock` | `lock` | `space_dashboard` | `grid-1x2` |
| `category` | `tags` | `lock_clock` | `shield-lock` | `stairs` | `bar-chart-steps` |
| `chat` | `chat` | `lock_open` | `unlock` | `star` | `star` |
| `check` | `check-lg` | `lock_person` | `person-lock` | `start` | `skip-start` |
| `check_circle` | `check-circle` | `login` | `box-arrow-in-right` | `stop_circle` | `stop-circle` |
| `checklist` | `list-check` | `logout` | `box-arrow-right` | `storefront` | `shop-window` |
| `chevron_left` | `chevron-left` | `mail` | `envelope` | `subject` | `text-left` |
| `chevron_right` | `chevron-right` | `mark_chat_read` | `chat-square-dots` | `support_agent` | `headset` |
| `close` | `x-lg` | `menu` | `list` | `swap_horiz` | `arrow-left-right` |
| `cloud_off` | `cloud-slash` | `menu_open` | `text-indent-right` | `sync` | `arrow-repeat` |
| `code` | `code-slash` | `message` | `chat-left` | `sync_lock` | `shield-lock` |
| `commit` | `git` | `money` | `cash` | `tab` | `window-stack` |
| `compare_arrows` | `arrow-left-right` | `monitor_heart` | `activity` | `table_chart` | `table` |
| `contact_phone` | `person-lines-fill` | `more` | `three-dots` | `table_rows` | `table` |
| `content_copy` | `copy` | `more_vert` | `three-dots-vertical` | `table_view` | `table` |
| `create_new_folder` | `folder-plus` | `move_to_inbox` | `inbox` | `task` | `file-earmark-check` |
| `dark_mode` | `moon-stars` | `new_releases` | `patch-exclamation` | `task_alt` | `check2-circle` |
| `delete` | `trash3` | `note` | `sticky` | `thumb_up` | `hand-thumbs-up` |
| `density_medium` | `distribute-vertical` | `notes` | `journal-text` | `timeline` | `graph-up-arrow` |
| `density_small` | `list` | `open_in_new` | `box-arrow-up-right` | `timer` | `stopwatch` |
| `description` | `file-earmark-text` | `paid` | `cash-coin` | `timer_off` | `stopwatch` |
| `details` | `info-square` | `palette` | `palette` | `tips_and_updates` | `lightbulb` |
| `devices` | `laptop` | `pan_tool` | `hand-index` | `title` | `type` |
| `do_not_disturb_on` | `dash-circle` | `password` | `asterisk` | `today` | `calendar-day` |
| `domain` | `building` | `pattern` | `grid-3x3` | `toggle_off` | `toggle-off` |
| `domain_add` | `building-add` | `pause` | `pause` | `toggle_on` | `toggle-on` |
| `done` | `check-lg` | `pause_circle` | `pause-circle` | `token` | `coin` |
| `download` | `download` | `payment` | `credit-card` | `tour` | `signpost` |
| `drag_handle` | `grip-horizontal` | `payments` | `cash-stack` | `trending_down` | `graph-down-arrow` |
| `drive_file_move` | `folder-symlink` | `pending` | `three-dots` | `trending_up` | `graph-up-arrow` |
| `edit` | `pencil` | `pending_actions` | `hourglass-split` | `tune` | `sliders` |
| `edit_note` | `pencil-square` | `percent` | `percent` | `undo` | `arrow-counterclockwise` |
| `email` | `envelope` | `person` | `person` | `update` | `arrow-clockwise` |
| `error` | `exclamation-circle` | `person_add` | `person-plus` | `upload` | `upload` |
| `error_outline` | `exclamation-circle` | `person_check` | `person-check` | `upload_file` | `file-earmark-arrow-up` |
| `event` | `calendar-event` | `person_off` | `person-slash` | `verified` | `patch-check` |
| `event_available` | `calendar-check` | `person_search` | `person-bounding-box` | `verified_user` | `shield-check` |
| `event_busy` | `calendar-x` | `phone` | `telephone` | `view_column` | `layout-three-columns` |
| `event_note` | `calendar-week` | `phone_disabled` | `telephone-x` | `view_list` | `view-list` |
| `expand_less` | `chevron-up` | `phone_forwarded` | `telephone-forward` | `visibility` | `eye` |
| `expand_more` | `chevron-down` | `phonelink_erase` | `phone` | `visibility_off` | `eye-slash` |
| `extension` | `puzzle` | `phonelink_lock` | `phone-vibrate` | `warning` | `exclamation-triangle` |
| `fact_check` | `card-checklist` | `picture_as_pdf` | `file-earmark-pdf` | `warning_amber` | `exclamation-triangle` |
| `filter_alt` | `funnel` | `pix` | `qr-code` | `waving_hand` | `hand-index-thumb` |
| `filter_alt_off` | `funnel` | `play_arrow` | `play` | `webhook` | `plug` |
| `flag` | `flag` | `play_circle` | `play-circle` | `window` | `window` |
| `folder_off` | `folder-x` | `playlist_add` | `list-check` |  |  |

## 15. Plano das ondas seguintes

Telas por feature (rótulo das rotas) e estimativa em dias de desenvolvimento (1 dia ≈ 6 h efetivas), calculada pelo volume de componentes Material, diálogos, telas e testes de cada feature. Inclui testes e capturas.

| Feature | Telas | Componentes / diálogos / usos de `mat-*` | Estimativa |
| --- | --- | --- | --- |
| account | Meu perfil; Trocar senha; Verificação em duas etapas; Minhas sessões | 4 / 0 / 15 | 0,5 d |
| admin | Usuários; Usuário; Perfis (form com permissões bloqueadas "fora do seu perfil", aviso e "Marcar todas que posso conceder"/"Desmarcar" — **preservar**); Equipes; Unidades | 11 / 6 / 137 | 3 d |
| analytics | Painel operacional; Recuperação; Conversão; Produtividade; Confiabilidade | 12 / 0 / 44 | 1,5 d |
| assistant | Políticas do assistente; Política; Revisão; Turno; Qualidade | 7 / 1 / 221 | 3,5 d |
| campaign | Campanhas; Segmentos; Novo segmento; Segmento; Testes A/B; Campanha; Régua; Execução; Testes A/B da campanha | 15 / 2 / 438 | 7,5 d |
| collection | Estação de cobrança; Casos; Caso; Agenda de retornos; Filas; Tabulações; Parâmetros da operação; Contestações | 17 / 7 / 431 | 7,5 d |
| communication | Caixa de entrada; Canais; Templates; Conversa | 8 / 3 / 205 | 3,5 d |
| compliance | Restrições de contato; Política de contato; Feriados; Bases legais; Solicitações de titulares; Solicitação; Retenção de dados | 15 / 6 / 322 | 6 d |
| debt | Devedores; Ficha do devedor; Títulos; Título | 14 / 3 / 166 | 3 d |
| governance | Log de acesso; Trilha de auditoria (usar `DetailDialog`) | 4 / 2 / 68 | 1,5 d |
| ingestion | Importação de carteiras; Layouts; Nova importação; Conferência (wizard); Lote | 11 / 1 / 192 | 3,5 d |
| legal | Painel jurídico; Casos jurídicos; Caso jurídico; Regras de elegibilidade; Escritórios parceiros; Parâmetros | 9 / 2 / 223 | 4 d |
| negotiation | Simulador; Propostas; Proposta; Aprovações; Acordos; Acordo; Políticas; Política; Índices de correção; Alçadas | 14 / 5 / 397 | 7,5 d |
| organization | Configurações da assessoria; Credores; Credor; Carteiras | 9 / 5 / 186 | 3,5 d |
| payment | Pagamentos; Instruções; Instrução; Aprovação; Sobras e realocação; Conciliação; Extrato; Divergências; Contas Pix; Parâmetros; Pagamento | 24 / 8 / 368 | 7,5 d |
| platform | Assessorias | 2 / 1 / 30 | 0,5 d |
| portal-admin | Portal de autonegociação; Pedidos do portal; Configuração do portal | 3 / 0 / 44 | 1 d |
| settlement | Regras de comissão; Versão das regras; Razão de comissão; Demonstrativos; Demonstrativo; Repasses | 13 / 5 / 285 | 5 d |
| telephony | Campanhas de discagem; Campanha; Chamadas e gravações; Operadores e ramais | 10 / 5 / 196 | 3,5 d |
| portal (devedor) | Entrar; Entrar pelo link; Aviso de privacidade; Minhas dívidas; Condições; Meus acordos; Meu acordo; Falar com atendente (layout próprio, marca do credor, mobile first) | 10 / 0 / 94 | 2,5 d |
| Limpeza final (**concluída**) | Remover `@angular/material`, `@angular/material-date-fns-adapter`, `material-icons`, `date-fns` (se sem uso), `_material-legacy.scss`, `_theme-colors.scss`, `material.config.ts`, `provideLocale` legado, `material-aliases.ts`, `TONE_ICONS`, `LEGACY_DIALOG_LAUNCHERS`; revisar orçamento do bundle | — | 1 d |

Total ≈ 77 dias. Sugestão de frentes paralelas (sem arquivos em comum entre frentes, exceto os serviços compartilhados listados):

| Frente | Features | Estimativa |
| --- | --- | --- |
| A — Operação de cobrança | collection, debt, compliance | ≈ 16,5 d |
| B — Negociação e pagamentos | negotiation, payment (inclui `PaymentLauncher`) | ≈ 15 d |
| C — Relacionamento | campaign, communication, telephony (inclui `TelephonyLauncher`) | ≈ 14,5 d |
| D — Retaguarda | settlement, legal (inclui `FormDialogService`), ingestion, portal do devedor | ≈ 15 d |
| E — Gestão e plataforma | admin, organization, account, governance, platform, portal-admin, assistant, analytics | ≈ 15 d |
| Final | limpeza do Material (depois de todas as frentes) | 1 d |

`RestrictionActions` (compliance) é usado também pela ficha do devedor (debt) — ambos na frente A.
