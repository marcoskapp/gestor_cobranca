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
| 1.4.13 conteúdo em hover ou foco | `app-info-hint`: o balão pode receber o ponteiro, fica aberto enquanto ponteiro ou foco estiverem nele e fecha com Esc sem mover o foco (§8.6) |
| 4.1.2 / 1.4.13 ação desabilitada com motivo | `app-disabled-action` (exceção à regra de ocultar ações sem permissão, só por decisão do responsável): botão com `aria-disabled` (continua focável), motivo como `aria-describedby` e num balão ao passar o mouse, ao receber o foco e ao tocar ou clicar; Esc fecha; o clique nunca executa a ação nem chama o servidor |
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

Tabelas largas rolam horizontalmente dentro do cartão (sem quebrar o layout) a partir de 576 px; abaixo disso o `app-data-table` vira lista empilhada (título, metadados, selo e linha clicável — §12). Filtros recolhem num botão "Filtros (n)" abaixo de 768 px (§12.3).

**Leiaute de cartões.** Cartões empilhados no fluxo normal recebem o espaço da regra global `.app-card + .app-card`. Cartões dentro de grade ou flex (lado a lado, coluna principal ou lateral de uma ficha) ficam num contêiner `.app-columns` (grade; a tela define `grid-template-columns`) ou `.app-stack` (coluna): o espaço vem do `gap` e os cartões filhos diretos não recebem a margem — sem ela o segundo cartão da linha desceria e ficaria desalinhado. Não zere a margem tela a tela; a auditoria da interface (`scripts/sandbox/audit.sh`) acusa cartões da mesma linha com topos diferentes (tolerância de 1 px) e o documento rolando além da área de conteúdo.

## 5. Linguagem e formatação

- pt-BR claro, voz ativa, sem jargão técnico ("Não foi possível carregar os dados" em vez de "Erro 503"); verbos no infinitivo nos botões ("Cadastrar credor").
- Moeda: `app-amount` (`Intl.NumberFormat('pt-BR', BRL)`), algarismos tabulares, espaço não separável após `R$`, formato compacto com valor exato acessível.
- Moeda digitada: máscara `appMoney` (centavos pela direita, sem separadores digitados, "R$" no `input-group-text`) — contrato na §13.4.
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

- **Cor**: primária `#3068b5` (componentes) e um navy institucional único, `#0f2f57` (`--app-topbar-bg`: barra superior e painel da marca no login); no escuro a barra usa um navy bem escuro, `#0d1b2e` (texto 14,6:1), distinto da superfície do menu (`#171c23`). `#123f73` é o tom "emphasis" do primário (texto sobre `primary-bg-subtle`), não outro navy. Tons `success|warning|danger|info|neutral` = cores "subtle/emphasis" do Bootstrap (`--app-<tom>-bg/fg`); superfícies `--bs-body-bg` (cartões, campos) sobre `--bs-tertiary-bg` `#f3f5f8` (página; cartão 1,09:1). Gráficos com cinzas frios (`--app-chart-grid` `#e6e9ee`/`#262c34`, `--app-chart-axis` `#c3cad4`/`#3a424d`).
- **Tom dos estados** (`shared/status/status.ts`): `danger` = falha, perda, atraso ou bloqueio; `warning` (amarelo) **só para o que exige atenção** — ação humana pendente (aprovar, revisar, responder) ou risco em curso; `info` = etapa normal em andamento ou aguardando um evento esperado (promessa, aguardando pagamento, fase de processo); `success` = concluído bem; `neutral` = encerrado ou inativo sem juízo. Ex.: "Promessa" e "Aguardando pagamento" são `info`; "Aguardando aprovação" é `warning`.
- **Tipografia** (revisão de produto 2026-11, D4 = proposta A, "leve, mas sólida"): Inter Variable (OFL) self-hosted via `@fontsource-variable/inter` — sem CDN; corpo **400**, títulos **600**, ênfase única **600** (`$font-weight-semibold`, `.fw-semibold`, `--app-weight-semibold`), botões e rótulos de campo **500**, negrito 700 (raro); o traço fino 300 fica só em `display`/`lead`. Escala única `--app-text-*`: 12 · 13 · **14 (corpo)** · 16 (título de cartão) · **20 (título de página, `letter-spacing: -0.015em`)** · 24/28 (valor de indicador); nenhum outro tamanho de texto nas telas (o portal do devedor tem escala própria, base 16 px). Pesos em estilos de componente pelos tokens `--app-weight-*` (aliases das variáveis do Bootstrap), nunca número solto. **Algarismos tabulares só em números** (`.app-numeric`, `td.text-end`, valor de indicador, `time`): no corpo inteiro a Inter troca também o hífen pelo tabular ("E - mail").
- **Forma** (D5): raios 6 px (pequenos, tooltips), **8 px** (botões, campos, menus), **12 px** (cartões, alertas, tabela), 16 px (diálogos), pílula (selos, busca); regra de aninhamento: raio interno = externo − padding (mín. 6 px) — o que fica dentro de um cartão usa `--bs-border-radius` (8). **Elevação**: `.shadow-sm` em duas camadas (`0 1px 2px` + `0 1px 3px`, nítida) e `.shadow` para menus e diálogos. **Movimento**: transições do Bootstrap, reduzidas com `prefers-reduced-motion`.
- **Hierarquia de ação**: por tela ou região, no máximo uma `.btn-primary`; secundárias em `.btn-outline-secondary` (cinza, não o contorno azul); o azul fica para ação. Nas fichas, até duas secundárias e o menu "Mais ações" (§12.2) com a destrutiva por último; sem "← Voltar" (a trilha faz isso). Nomes com link nas tabelas na cor do texto (efeito `.link-body-emphasis`), sublinhados ao passar o ponteiro.
- **Abas**: `.nav-underline` do Bootstrap com a classe `.app-tabs` (inativas em cor secundária, sem ícone, linha de base e rolagem horizontal no celular). Abas não repetem ícones nem usam caixa (`nav-tabs` saiu da aplicação). **Abas que não cabem** vão para "Mais N ▾" com `<li class="nav-item app-tabs-more-item d-none" role="presentation"><app-tabs-more [nav]="nav" /></li>` como último item da faixa (`shared/components/tabs-more`): mede a largura, oculta na ordem as que sobram e as lista no menu; com uma delas aberta, o botão mostra o nome dela e "+N" (reservando espaço para o nome inteiro); escolher no menu aciona o próprio link da aba (estado na URL e carregamento da tela continuam). Toda faixa `ngbNav` com `.app-tabs` usa o componente; a estação tem divisão fixa própria (decisões 986 e 988).
- **Densidade**: `html[data-density=compact]` ajusta as CSS custom properties documentadas dos componentes (`--bs-btn-*`, `--bs-dropdown-*`…) e os componentes compartilhados escolhem `.table-sm`/`.form-control-sm` via `DensityService`; ignorada sem `pointer: fine`.
- **Tema**: `ThemeService` aplica `data-bs-theme="light|dark"` (modo efetivo; "seguir o sistema" resolve `prefers-color-scheme` e acompanha mudanças) e `data-theme` (preferência fixada, usada pelo `color-scheme`); `main.ts` aplica a preferência guardada antes do bootstrap (a CSP impede script inline).

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
| Convites | `hint/onboarding-hint.ts` e `hint/screen-tip.ts` | Boas-vindas: faixa dentro da página inicial (`aria-live`, "Fazer o tour", "Agora não"). Tour novo ou novidades de uma tela de trabalho: ponto no botão de ajuda e destaque no topo do menu de ajuda — nada flutua sobre o conteúdo. "Não mostrar dicas automáticas" fica na ajuda |
| Checklist | `getting-started/getting-started.ts` | "Primeiros passos" na tela inicial; reabrível pela ajuda |
| Novidades | `whats-new/whats-new-dialog.ts` | Central por versão; selo "Novo" na ajuda |
| Estado | `core/preferences/ui-state.store.ts` | Carga no login, alterações otimistas agrupadas (debounce 800 ms) em um PATCH, reenvio em falha, `flush()` antes do logout |
| Manual | `core/onboarding/manual/` (`content/*.ts`, `manual-index.ts`, `manual.service.ts`, `screen-tour.ts`, `screen-manual-panel.ts`) e `features/help/` | Manual rápido por tela ("Sobre esta tela"), passo de objetivo do tour e manual de cada módulo em `/ajuda` (§8.5) |
| Informação contextual | `shared/components/info-hint/` e `core/onboarding/manual/indicators/` (`content/*.ts`, `indicator-index.ts`) | O "i" ao lado de cada número com significado de negócio e o catálogo dos textos (§8.6) |

### 8.3 Regras dos convites

1. Só após carregar o estado do servidor (`ready`) e com `hintsEnabled`.
2. **Tour de boas-vindas** (`app.welcome`) ainda não registrado: convite **só na tela inicial**, dentro da página (nunca por cima do conteúdo), e só quando o checklist "Primeiros passos" visível não traz o item "Conhecer a navegação" (que já oferece o mesmo tour). No primeiro acesso, as notas de versão anteriores são marcadas como lidas.
3. **Tela não navegada** com `tourId`: destaque "Tour rápido" no menu de ajuda (com ponto no botão) se o tour não foi registrado na versão atual.
4. **Tela com versão maior que a vista**: "Novidades nesta tela" no topo do menu de ajuda, com os itens de `changes` posteriores à versão vista (e o tour, se houver versão nova).
5. Cada convite aparece no máximo uma vez: a visita é registrada ao abrir a tela (o destaque some ao trocar de tela); o convite de boas-vindas registra `DISMISSED` já na exibição (revisão de produto de 2026-11, T8: o cartão aparecia em todas as telas até ser dispensado e cobria colunas); "Agora não" também registra `DISMISSED`.
6. Nunca abre um tour sozinho; o usuário pode reabrir qualquer tour pela ajuda ou pela busca rápida.

### 8.4 Contrato para telas novas (ex.: estação de cobrança)

1. Marque elementos com `data-tour="<âncora>"` (vários valores separados por espaço são aceitos). Os componentes compartilhados já expõem `page-header`, `page-actions` e `data-table`; formulários de filtro usam `data-tour="filters"`.
2. Registre a tela em `content/screens.ts` (`key`, `version: 1`, `path`, `tourId`).
3. Escreva o tour em `content/tours.ts` (≤ 6 passos, ≤ 25 palavras por passo).
4. Ao mudar a tela de forma relevante: `version + 1` e um item em `changes` com a mesma versão; mudança grande no tour: `version + 1` no tour.
5. Inclua o destino no `NAVIGATION`: os itens de checklist com essa rota (ex.: `/cobranca/fila`, `/devedores`) passam a aparecer automaticamente para os perfis com permissão.
6. Atalhos da tela: `KeyboardShortcuts.register({ group: 'Estação de cobrança', … })` no construtor e remoção no `DestroyRef`; ações na busca rápida: `CommandPaletteService.register([...])`.
7. **Manual da tela (obrigatório)**: acrescente o `ScreenManual` da tela no arquivo do módulo em `core/onboarding/manual/content/` (`id` = `key` da tela; `objective` de 6 a 25 palavras, que vira o primeiro passo do tour; `purpose`, `profiles`, `inputs`, `outputs`, `related`, `terms`). Prefixo de chave novo → inclua-o em `MODULE_BY_PREFIX` (`manual-index.ts`). O `manual-content.spec.ts` falha se a tela ou a rota do menu ficar sem manual.
8. **Números com significado de negócio (obrigatório)**: todo cartão de indicador, título de gráfico e coluna de tabela com valor calculado leva o "i" (§8.6), com o texto no catálogo de indicadores. O `npm run hints:check` (antes do build e dos testes) recusa `app-kpi-card` sem `info` e id fora do catálogo.

### 8.5 Ajuda contextual e manual do usuário (decisões 410–422)

| Opção avaliada | Decisão |
| --- | --- |
| Painel lateral "Sobre esta tela" (`NgbOffcanvas` à direita, 480 px; largura total no celular) | **Adotado** para o manual rápido: não tira o usuário da tela; foco preso, Esc fecha e devolve o foco (ng-bootstrap); fecha ao navegar |
| Manual dentro do app (`/ajuda`, `/ajuda/:modulo`) | **Adotado** para o "Saiba mais": visão do todo, regras, todas as telas do módulo e glossário; âncora `#<chave da tela>` com foco na seção |
| Link para `docs/` do repositório | **Descartado**: texto técnico para a equipe, fora da autenticação e do escopo, sem filtro por permissão |
| Markdown empacotado / JSON em `assets/` | **Descartados**: parser e sanitização (CSP sem `unsafe-inline`) ou conteúdo sem verificação do compilador; o conteúdo é TypeScript só com dados, exibido por interpolação |

**Peças**

- `manual.models.ts` — `ScreenManual` (por tela), `ModuleManual` (por módulo), `GlossaryTerm`, perfis padrão (`PROFILE_LABELS`) e `MANUAL_SCHEMA_VERSION`.
- `manual-index.ts` — os 16 módulos (`MANUAL_MODULES`: 15 pedidos + "Visão geral"), o módulo de cada tela pelo prefixo da chave (`moduleOfScreen`), carregadores sob demanda com cache (`loadModuleManual`, `loadScreenManual`, `loadGlossary`) e `loadKnowledgeBase()`. Nada do conteúdo entra no bundle inicial: cada arquivo de `content/` é um chunk.
- `screen-tour.ts` — `composeScreenTour`: "tour desta tela" = passo centralizado com o `objective` + passos do tour próprio (na tela inicial, o de boas-vindas). Sem tour próprio: objetivo + passo ancorado em `help` apontando para "Sobre esta tela", tour `transient` (não oferecido em convite e não gravado no servidor). Sem conteúdo (falha de rede), o tour próprio segue como está.
- `manual.service.ts` — resolve telas relacionadas (título do manual da tela de destino, que pode estar em outro chunk) e o link: só para rota sem `:param` presente no `NAVIGATION` com a permissão do usuário, ou rotas abertas (`/`, `/conta/*`, `/ajuda`); sem acesso, o texto aparece sem link.
- `screen-manual-body.ts` — conteúdo do manual rápido (objetivo, para que serve, quem usa, de onde vêm os dados / para onde vai o resultado, telas relacionadas, termos), com títulos de seção em `role=heading` e `aria-level` configurável (3 no painel, 4 no manual do módulo).
- `screen-manual-panel.ts` — o painel; carregando (`app-skeleton`), erro com "Tentar novamente", tela sem manual; rodapé com "Fazer o tour desta tela" e "Saiba mais no manual: <módulo>".
- `features/help/` — central (`help-center.ts`: encaixe dos módulos, cartões por módulo, glossário) e manual do módulo (`module-manual.ts`: sumário das telas, visão geral, encaixe, regras, telas, glossário do módulo).

**Acesso**: menu de ajuda ("Tour desta tela", "Sobre esta tela", "Manual do sistema") e busca rápida (`help.about`, `help.manual`). O manual é visível a todos os autenticados; só os links para telas seguem as permissões.

**Conteúdo**: pt-BR para o usuário final, sem códigos internos nem jargão técnico (o teste recusa HTML, identificadores em caixa alta com sublinhado e termos como "API" ou "endpoint"); coerente com os documentos de negócio e com as regras que o sistema aplica. Ao mudar uma regra de negócio que aparece no manual, atualize o texto no mesmo PR. Prazo, limite ou quantidade que vem da configuração nunca é escrito à mão: use o marcador de parâmetro (§8.7).

**Base do chat de suporte** ([`chat-suporte.md`](chat-suporte.md) §7): `npm run knowledge` exporta o manual (`knowledge-export.ts`: módulos, telas com caminho e acesso pelo menu, glossário, versão) para `backend/src/main/resources/helpdesk/knowledge-base.json`; `knowledge:check` roda antes do build e dos testes e falha com o arquivo desatualizado — **ao mudar o manual, rode `npm run knowledge` e versione o JSON**. O chat (`core/support-chat/`, botão na barra superior, painel `NgbOffcanvas` como "Sobre esta tela") envia só a chave da tela; o texto do manual vem do servidor.

**Testes**: `manual/manual-content.spec.ts` (cobertura de todas as telas e rotas do menu, campos obrigatórios, vínculos, glossário, jargão e o tour com objetivo em todas as telas), `manual/screen-manual-panel.spec.ts` (links por permissão, seções, "Saiba mais", tour), `features/help/help-pages.spec.ts` (central, módulo com âncora e foco, módulo inexistente), além de `onboarding.service.spec.ts` e `tour-engine.service.spec.ts` (objetivo primeiro, tour transitório não gravado).

### 8.6 Informação contextual de indicadores (decisões 700–711)

Pedido do responsável: *"trazer contexto de negócio para cada valor exibido, informando do que se trata aquele valor, para que ele é usado na tomada de decisão… A ideia é o usuário entender o valor sem sair da tela."*

| Opção avaliada | Decisão |
| --- | --- |
| `ngbTooltip` (só texto, abre no hover/foco) | **Descartado**: o conteúdo tem estrutura e link ("Saiba mais"); tooltip não recebe foco nem toque e some ao tirar o ponteiro |
| `ngbPopover` com `triggers="manual"`, anexado ao `body` | **Adotado** (`app-info-hint`): abertura controlada pelo componente (ponteiro, foco do teclado e toque), balão fora de contêineres com rolagem (tabelas, `container-type` dos cartões) |
| Diálogo (`NgbModal`) ou painel lateral | **Descartados**: tiram o usuário do contexto; o painel "Sobre esta tela" continua para a leitura da tela inteira |
| Textos nos templates ou em `i18n` | **Descartados**: o mesmo texto precisa servir ao chat de suporte e ao manual; catálogo declarativo e versionado, como o manual |

**Componente** `app-info-hint` (`shared/components/info-hint/`): `<app-info-hint indicator="analytics.recovery.rate" label="Taxa de recuperação" />`.

| Aspecto | Contrato |
| --- | --- |
| Visual | Botão com o ícone `info-circle` do sprite, cor secundária; realce `primary-bg-subtle` ao passar o mouse ou aberto; balão do popover do Bootstrap (classe `app-info-popover`, até 340 px, texto de 14 px) com título, "O que é", "Como é calculado", "Como usar" e "Saiba mais no manual" (`/ajuda/<módulo>#<tela>`). Tema escuro e densidade compacta pelas variáveis do Bootstrap |
| Abertura | Mouse: abre após 120 ms e fecha 250 ms depois de sair do botão **e** do balão (o ponteiro pode ir até o balão — WCAG 1.4.13). Teclado: o foco abre (o texto é lido pelo `aria-describedby`); Enter/Espaço fixa e leva o foco para dentro do balão (para chegar ao "Saiba mais"). Toque/clique: abre e fixa; segundo toque, toque fora ou Esc fecham. O foco vindo de um toque não abre sozinho (o toque é tratado pelo clique) |
| Fechamento e foco | Esc fecha em qualquer modo (escuta no `document`, com prioridade sobre o Esc de diálogos) e devolve o foco ao botão quando ele estava no balão; Tab para fora do balão fecha e devolve o foco ao botão (o balão fica no `body`, fora da ordem da tela); "Saiba mais" fecha e navega |
| Acessibilidade | `button type=button` com `aria-label="Sobre: <nome>"`, `aria-expanded`, `aria-describedby` (do ng-bootstrap) apontando para o balão (`role=tooltip`); ícone decorativo; alvo de 24 × 24 px (32 × 32 px com `pointer: coarse`) com margens negativas para não aumentar a linha; foco visível; nada depende só de hover |
| Carga | O texto vem do catálogo, carregado na primeira abertura (um chunk por módulo); o componente fica nos chunks das telas. O CSS do popover do Bootstrap entrou no CSS global (`_index.scss`, ~6,5 kB sem compressão, ~1 kB transferido); o JavaScript inicial não mudou |
| Onde | `app-kpi-card` (`info`), `app-bar-chart`/`app-column-chart`/`app-funnel-chart` do analytics e o gráfico do assistente (`info`), colunas do `app-data-table` (`TableColumn.info`) e diretamente nos títulos, cabeçalhos de tabela e rótulos (`<dt>`, `kpi__label`). Nunca dentro de outro botão ou link |

**Catálogo** (`core/onboarding/manual/indicators/`): `IndicatorHelp` = `id` estável (`<prefixo da tela>.<assunto>.<indicador>`; o prefixo define o módulo, pelo mesmo `moduleOfScreen` das telas), `title`, `what` (o que é), `how` (como é calculado, em linguagem de negócio), `use` (como usar na decisão: o que fazer quando sobe ou cai) e `screens` (telas em que aparece; a primeira é a do "Saiba mais"). Um arquivo por módulo em `content/`, só dados. Os textos seguem as definições reais (docs/negocio/indicadores.md, desenho de cada módulo) e o código que calcula cada número.

**Usos do mesmo texto**: o "i" das telas; a seção "Indicadores desta tela" do manual rápido e do manual do módulo; o "tour desta tela" dos painéis (passos nos cartões); a base do chat de suporte (`npm run knowledge` exporta `indicators`; o chat recebe os indicadores da tela atual e consulta `consultar_manual` com `indicador`).

**Contrato para telas novas**
1. Número com significado de negócio → id no catálogo do módulo (`content/<módulo>.ts`) com os quatro textos (6 a 70 palavras cada, frase completa) e as telas.
2. Cartão: `<app-kpi-card label="…" info="<id>">`; gráfico: `info="<id>"`; coluna do `app-data-table`: `{ key, header, info: '<id>' }`; demais lugares: `<app-info-hint indicator="<id>" label="<nome na tela>" />`. Id calculado em tempo de execução: comentário `info-hint: <id>, <id>` ao lado, para a conferência.
3. Rode `npm run knowledge` e versione o JSON do chat.
4. Tour da tela: os passos principais apontam para os cartões (`data-tour="kpi-…"`) e um passo para o "i" (`data-tour="info-hint"`, o primeiro visível), dentro do limite de 6 passos + objetivo; aumente a versão da tela e do tour.

**Verificação**: `hints:check` (todo id usado existe; todo cartão tem `info`; `app-info-hint` com `indicator` e `label`), `indicator-content.spec.ts` (ids, módulo, campos e tamanhos, telas existentes, "Saiba mais" com manual, a mesma regra anti-jargão do manual — `content-rules.ts`), `info-hint.spec.ts` (hover, foco, Enter, Tab, toque, clique fora, Esc, ARIA), `knowledge-export.spec.ts` e `ClasspathKnowledgeBaseTest`/`HelpdeskIT` no backend.

**Inventário** (195 indicadores, 15 módulos do manual): painéis (operacional, recuperação, conversão, produtividade, confiabilidade e a tela inicial), Minhas pendências, score (cartão do caso, monitoramento, modelo, execuções), remuneração variável (desempenho, extratos, prévia, metas, monitoria), prestação de contas (demonstrativo, posição do credor, repasses), pagamentos e conciliação, negociação (simulador, propostas, acordos), importação (validação, totais de controle, alertas, impacto, lote, PDF), campanhas (execução e testes A/B), comunicação (SLA e janela de 24 h), telefonia (comparação, discador, presença), assistente (qualidade), compliance (prazo LGPD), jurídico (painel e prazos) e consumo da integração dos credores.

### 8.7 Parâmetros da configuração no texto (decisões 918–926)

Pedido do responsável: *"Texto fixo no manual: ajustar para ficar dinâmico conforme eventuais mudanças."* Prazos,
limites e quantidades que vêm de `app.*` no backend (janela do limite acumulado da correção de valor, validade da
proposta, suspensão da quebra, tamanho do PDF…) não são mais escritos à mão no manual, no tour, nas novidades, no "i"
dos indicadores nem nos textos de tela: o texto usa um **marcador** resolvido com o valor que o servidor aplica.

| Opção avaliada | Decisão |
| --- | --- |
| Endpoint de cada módulo (ex.: `GET /title-amount-adjustments/settings`, que já traz `cumulativeWindowDays`) | **Descartado**: exige a permissão da tela (o manual é lido por todos os perfis → 403 ou texto errado) e traria dados da assessoria |
| Endpoint único autenticado com só números de configuração, alimentado por um contrato do kernel | **Adotado**: `GET /api/v1/helpdesk/manual-parameters` (`ACTOR_USER`), agregando `shared.manual.ManualParameterSource` de cada módulo dono, como `PendingWorkSource` |
| Marcador = número (`{{debt.cumulativeWindowDays}}`) | **Descartado**: "nos últimos 1 dias", e sem texto neutro possível enquanto carrega ("nos últimos dias"?) |
| Marcador = **frase** do catálogo (`{{debt.cumulativeWindow}}` → "nos últimos 30 dias" · "no último dia" · "no período configurado") | **Adotado**: singular, zero e texto neutro corretos na mesma frase |
| Base do chat com texto neutro | **Descartado**: o chat responderia "o período configurado" sem saber o número |
| Base do chat com marcadores resolvidos no backend, na subida | **Adotado**: mesmo valor da tela; marcador desconhecido ou parâmetro sem valor impedem a subida |

**Peças**

- Backend: `shared.manual.ManualParameterSource` (contrato publicado do kernel: `module()` + `parameters()` com
  `BigDecimal`; só números de configuração da aplicação, iguais para todas as assessorias, nunca dado de assessoria ou
  pessoa); uma implementação por módulo dono (`DebtManualParameters`, `NegotiationManualParameters`,
  `PaymentManualParameters`, `ComplianceManualParameters`, `PendingWorkManualParameters`, `TelephonyManualParameters`,
  `IngestionManualParameters`, `ScoringManualParameters`, `SettlementManualParameters`, em `application/service`);
  agregador `helpdesk.application.service.ManualParameterService` (`ManualParameterUseCases`, chave
  `<modulo>.<nome>`, valida padrão, repetição e sinal na subida); `ManualParametersController` (`ACTOR_USER`,
  `no-store`). O helpdesk não depende dos módulos: recebe as fontes por injeção.
- Unidade exata: as `*Properties` recusam subir com prazo que não cabe na unidade citada (dias inteiros em
  `app.debt.amount-adjustment.*` e `app.payment.review-status.rejected-window`, segundos inteiros em
  `app.telephony.presence-timeout`, MB inteiros em `app.ingestion.pdf.max-file-size`) — o texto nunca mostra número
  arredondado.
- Frontend: `core/onboarding/manual/manual-parameters.ts` (só dados e funções puras: `MANUAL_PARAMETERS` com o
  formato `integer`/`decimal` (casas que o valor tem, de 2 a 4, sem arredondar)/`money` (2 casas), `MANUAL_PHRASES` com `zero`/`one`/`other` (`{n}`)/`neutral`,
  `resolveManualText`, `renderPhrase`, `unknownMarkers`); `ManualParameters` (serviço: lê uma vez por sessão, sob
  demanda, só com usuário autenticado, sem notificação de erro; falha → nova tentativa só depois de 30 s; valor que não
  é número finito vira texto neutro); pipe impuro `manualText` (com memória do último resultado) nos templates do
  manual rápido, manual do módulo, central de ajuda, tour, novidades da tela e "i"; `manualPhrase('<frase>')` para
  textos de tela montados no componente; `manualParameter('<parâmetro>')` (valor ou `null`) para regras de tela
  que dependem da configuração — sem valor, a tela não antecipa e a decisão fica com o servidor (limite do PDF,
  prévia da segunda aprovação do ajuste do demonstrativo).
- Base do chat: `npm run knowledge` exporta os textos com os marcadores mais `parameters` e `phrases` (formato 2); a
  exportação falha com marcador desconhecido (e com ela o `knowledge:check` do build); `ClasspathKnowledgeBase`
  resolve tudo na carga com `helpdesk.domain.service.ManualText` (mesmas regras de formato do frontend); a versão
  efetiva da base é a do JSON + `.` + 8 hex do SHA-256 dos valores usados (decisão 926).

**Onde o marcador pode aparecer**: objetivo, para que serve, entradas, saídas e motivo das relacionadas do manual
rápido; visão geral, encaixe e regras do módulo; definição do glossário; o que é, como é calculado e como usar do
indicador; texto dos passos do tour; itens das novidades das telas. **Não** em títulos, nomes de termos, resumo de
tour, notas de versão nem checklists (não são resolvidos; o teste recusa).

**Enquanto carrega ou se a leitura falhar**: a frase sai no texto neutro ("no período configurado", "pelo prazo
máximo configurado") — nunca um número chutado. Quando o valor chega, o texto atualiza sozinho (sinal).

**Contrato para texto novo que cite configuração**
1. Backend: o módulo dono publica o valor no seu `ManualParameterSource` (chave nova em camelCase, com a unidade no
   nome: `...Days`, `...Seconds`, `...Mb`); se a unidade puder não ser exata, a `*Properties` recusa na subida.
2. Frontend: parâmetro em `MANUAL_PARAMETERS` e frase em `MANUAL_PHRASES` (as quatro formas encaixando na mesma
   frase); texto com `{{<modulo>.<frase>}}`.
3. Atualize `ManualParameterDefaults` (testes do backend) e rode `npm run knowledge`.

**Testes**: `manual-parameters.spec.ts` (resolução, singular/zero, texto neutro sem dígitos, marcador desconhecido e
mal formado, catálogo coerente, todo marcador do conteúdo conhecido e só onde é resolvido, tamanho e jargão com os
padrões e com o texto neutro), `manual-parameters.service.spec.ts` (carga única, neutro → número, falha, valor
inválido, sem sessão), `knowledge-export.spec.ts`; backend `ManualTextTest`, `ManualParameterServiceTest`,
`ManualParametersControllerTest`, `ClasspathKnowledgeBaseTest` (resolução com outros valores, parâmetro sem valor e
marcador desconhecido impedem a subida), testes das `*Properties` e `ManualParametersIT` (todo usuário interno, mesmo
sem permissão, lê os valores aplicados; devedor e integração 403; sem sessão 401; base do chat resolvida).

**Inventário** (15 parâmetros, 16 frases): correção de valor (janela do acumulado, validade da proposta), suspensão da
quebra (prazo máximo e carência), janela dos pagamentos recusados, prazo padrão das solicitações de titulares, "perto
do prazo" das pendências, tempo sem sinal na telefonia, tamanho do PDF, padrões do score (janela de desfecho, carência,
AUC mínima, limites de PSI) e limite da segunda aprovação do ajuste do demonstrativo.

## 9. Estado de interface no servidor (módulo `identity`)

- Tabelas: `identity.user_ui_state` (1 por usuário: `theme`, `density`, `navigation_collapsed`, `hints_enabled`, `checklist_dismissed`) e filhas `user_ui_tour` (versão, desfecho, instante), `user_ui_screen` (versão vista), `user_ui_checklist` (instante de conclusão), `user_ui_nav_section` (seções do menu recolhidas, `V0208`; até 50, chave = `NavSection.key`; o PATCH aceita `navigationSections: [{sectionKey, collapsed}]`). RLS habilitado e **forçado**; filhas protegidas pela linha pai (padrão V0200). `on delete cascade` a partir de `app_user`.
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
- **Revisão de 2026-11 (onda 2, jornada do operador)**: a 1366×768 o registro de interação fica no topo da coluna direita com o botão fixo no rodapé da coluna; "Posso contatar agora?" vira uma linha de chips (o detalhe só para canais não liberados); ações numa barra compacta (Ligar · Mensagem · Simular · ⋯, com Agendar retorno, Ficha do devedor e Contestação no ⋯); abas mais usadas à vista e as demais em "Mais N ▾" (seta e contagem sempre visíveis; com uma aba recolhida aberta, o botão mostra o nome curto dela e "+N", e só esse item encolhe com reticências — decisão 986); indicadores do caso numa faixa de 4 (migram para o `app-kpi-card` compartilhado); tabela de títulos sem cortar o saldo. O atendimento (início, rascunho e aba) é guardado por caso (`collection.md` §8.2), a aba fica em `?aba=`, e simulador e ficha do devedor abrem num painel lateral (`.app-station-panel`), com o acordo fechado sem sair do caso (`negociacao-e-alcadas.md` §10.13).

**Início ("Seu dia", decisão D8)**
- Para operador e supervisor: "Próximo caso" (Alt + N) e as contagens do dia (`collection.md` §8.3), cada número levando à tela de trabalho; depois "Suas pendências", indicadores (quem vê painéis) e os primeiros passos. Sem "Acesso rápido" (o menu já leva a todas as telas). O aviso de MFA (uma linha, dispensável na sessão) e o convite do tour só aparecem quando o checklist não os traz.

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
5. **Importação seletiva**: ficam de fora `carousel`, `images`, `navbar`, `forms/floating-labels` e `forms/form-range` (o `popover` entrou com o `app-info-hint`, §8.6). Para usar um deles numa tela, acrescente o `@import` em `_index.scss` — nunca copie CSS do Bootstrap.

### 11.3 Variáveis alteradas e por quê

| Grupo | Variáveis (valor) | Por quê |
| --- | --- | --- |
| Opções globais | `$prefix: bs-` (valor padrão, declarado para as sobrescritas poderem usar `var(--bs-…)`), `$enable-shadows: false`, `$enable-gradients: false`, `$enable-caret: false`, `$enable-validation-icons: false`, `$enable-reduced-motion: true`, `$enable-smooth-scroll: false`, `$enable-negative-margins: false`, `$enable-dark-mode: true`, `$color-mode-type: data`, `$enable-deprecation-messages: false` | Superfícies planas e leves (elevação só onde pedida, com `.shadow-sm`/`.shadow`); sem seta automática em menus (botões só de ícone ficam limpos; o menu do usuário mostra um chevron explícito); sem ícone de validação embutido (a mensagem em texto comunica e o SVG embutido não acompanha o modo escuro); respeito a `prefers-reduced-motion`; rolagem instantânea para quem usa teclado; modo escuro por atributo controlado pelo `ThemeService` (permite "seguir o sistema" e fixar claro/escuro). |
| Cores do tema | `$primary: #3068b5`, `$secondary: #5b6675`, `$success: #1e7b3f`, `$info: #1f6fb5`, `$warning: #e8a317`, `$danger: #b3261e`, `$min-contrast-ratio: 4.5` | Azul dos componentes, mais claro que o navy institucional `#0f2f57` (barra superior e painel da marca; ver §7), para servir aos **dois** modos sem sobrescrever componentes: texto branco 5,57:1 e 3,08:1 contra a superfície escura (contorno de caixas de seleção, WCAG 1.4.11). Demais tons com texto branco ≥ 5,2:1; `warning` usa texto preto (escolhido pelo `color-contrast` do Bootstrap). |
| Tons "subtle/emphasis" (claro) | `$<cor>-text-emphasis`, `$<cor>-bg-subtle`, `$<cor>-border-subtle` para primary, secondary, success, info, warning, danger (valores em `_variables.scss`) | São exatamente os tokens semânticos da identidade anterior (`--app-<tom>-bg/fg`), com contraste ≥ 7:1 — viram a base de alertas, selos de status (`app-status-chip`), destaque do menu e do item ativo. |
| Corpo e superfícies | `$body-color: #1b2430` (15,6:1), `$body-bg: #fff` (superfície), `$body-secondary-bg: #eceff3`, `$body-tertiary-bg: #f3f5f8` (página, `.bg-body-tertiary` no `<body>`), `$body-emphasis-color`, `$box-shadow`, **`$box-shadow-sm`** (duas camadas: `0 1px 2px` 5% + `0 1px 3px` 6%), `$box-shadow-lg`, `$border-color: #dde2e9`, `$border-color-translucent`, `$link-color: $primary`, `$link-decoration: none`, `$link-hover-decoration: underline` | Neutros frios de baixo contraste nas superfícies e texto de alto contraste para turnos longos; cartões brancos sobre fundo levemente acinzentado (1,09:1, um tom mais firme que o anterior `#f6f8fb`, 1,06:1); sombra pequena nítida, no padrão das ferramentas densas; links sem sublinhado em repouso (interface densa), sublinhados ao passar o ponteiro. |
| Tipografia | `$font-family-sans-serif` (Inter Variable self-hosted), `$font-family-monospace`, `$font-size-base: .875rem`, `$font-size-sm: .8125rem`, `$font-size-lg: 1rem`, `$font-weight-light: 300`, `$font-weight-normal: 400`, `$font-weight-medium: 500`, **`$font-weight-semibold: 600`**, **`$font-weight-bold: 700`**, **`$font-weight-base: 400`**, `$line-height-base: 1.5`, `$h1…h6-font-size` (20/16/14/14/14/12 px), **`$headings-font-weight: $font-weight-semibold` (600)**, `$headings-line-height: 1.25`, `$headings-margin-bottom`, `$display-font-weight: 300`, `$lead-font-weight: 300`, `$small-font-size`, `$dt-font-weight`, `$legend-font-weight` | **Leve, mas sólida** (decisão D4 da revisão de produto 2026-11, proposta A, que substitui o "traço fino" da onda 1): corpo em 400 e títulos em 600 da Inter Variable (OFL, já servida pela aplicação — nenhuma fonte nova nem CDN); o 300 a 13–14 px ficava acinzentado a 1x (Windows/ClearType) e mais fino ainda no escuro. Uma só ênfase (600) para títulos de cartão e valores; o 300 fica nos textos grandes (`display`, `lead`). Escala de títulos de aplicação operacional (h1 = título de página de 20 px), não de site. `$letter-spacing` não existe no Bootstrap 5.3 — o espaçamento negativo sutil fica nos títulos de página e valores de indicador (tokens `--app-tracking-*`). |
| Arredondamento | `$border-radius: .5rem`, `-sm: .375rem`, `-lg: .75rem`, `-xl: 1rem`, `-xxl: 1.5rem`, `-pill`; `$btn-border-radius*`, `$input-border-radius*`, `$card-border-radius` (lg), `$modal-content-border-radius` (xl), `$dropdown-border-radius`, `$toast-border-radius` (lg), `$alert-border-radius` (lg), `$list-group-border-radius` (lg), `$accordion-border-radius` (lg), `$popover-border-radius` (lg), `$tooltip-border-radius` (sm), `$badge-border-radius` (pílula), `$progress-border-radius` (pílula), `$pagination-border-radius`, `$nav-pills-border-radius`, `$form-check-input-border-radius: .375em` | **Componentes arredondados sem virar "bolha"** (D5): 8 px em botões e campos (a 37 px de altura, 12 px ficava quase pílula), 12 px em cartões e na tabela, 16 px em diálogos, 6 px nos pequenos, pílula em selos. Regra de aninhamento: raio interno = externo − padding (mín. 6 px). Todos referenciam as CSS custom properties `--bs-border-radius*`, então a densidade ou um tema futuro ajusta tudo num lugar. |
| Foco | `$focus-ring-width: .1875rem`, `$focus-ring-opacity: .45` | Anel de foco do Bootstrap mais visível (WCAG 2.4.7); elementos fora do Bootstrap usam o contorno global `:focus-visible` (`--app-focus-ring`). |
| Botões e campos | `$input-btn-padding-y/x` e `-sm`, `$btn-font-weight: 500`, `$btn-padding-x: 1rem`, `$input-border-color` e `$form-check-input-border` = `var(--app-control-border)`, `$form-label-margin-bottom`, `$form-label-font-weight: 500`, `$form-text-margin-top`, `$input-placeholder-color` | Altura de 37 px (confortável); botões e rótulos de campo em 500 (D4: um degrau acima do corpo, sem pesar). **Borda de controles ≥ 3:1** contra a superfície nos dois modos (`#8a93a0` claro, `#6b7480` escuro — o padrão do Bootstrap, `#dee2e6`, tem 1,3:1 e reprovaria o WCAG 1.4.11); o valor por modo vem do token `--app-control-border`. |
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
| `.app-tabs` (`styles.scss`) | `.app-tabs .nav-link { white-space: nowrap }` | As cores, o peso e o espaçamento das abas vêm das CSS custom properties documentadas (`--bs-nav-link-color`, `--bs-nav-link-font-weight`, `--bs-nav-underline-*`); só a quebra de linha do rótulo não tem variável — no celular as abas rolam em vez de cortar o texto. |
| `.app-filter-panel` (`styles.scss`) | `.app-filters > * { width: 100% !important }` dentro do painel de filtros | Os filtros em painel lateral (celular) ocupam a largura toda; o `!important` vence a largura fixa que alguns filtros declaram com `!important` (ex.: datas dos painéis). |
| `app-entity-picker` | `:focus-within` com o anel de foco no contêiner dos selos | O campo de busca fica dentro de um contêiner com aparência de `.form-control`. |

### 11.7 Atualizar o Bootstrap no futuro

1. Trocar as versões fixas em `package.json` (`bootstrap`, e o `@ng-bootstrap/ng-bootstrap` compatível com o Angular do projeto) e rodar `npm install`.
2. `npm run bootstrap:check` — lista variáveis que deixaram de existir; revisar a tabela da §11.3 contra o `_variables.scss`/`_variables-dark.scss` da nova versão (novos `!default` podem merecer ajuste).
3. Revisar `_index.scss` contra o `bootstrap.scss` da nova versão (módulos novos ou renomeados) e as exceções da §11.6.
4. Conferir os ids de tradução do ng-bootstrap (`core/config/ngb-translations.ts`) e rodar `npm test`, `npm run build` e as capturas claro/escuro.

## 12. Componentes e serviços da fundação (onda 1)

| Peça | Arquivo | Notas |
| --- | --- | --- |
| `app-icon` | `shared/components/icon/` | Bootstrap Icons (MIT) num **sprite SVG próprio** (`public/icons/app-icons.svg`, ~107 kB, baixado sob demanda e em cache) gerado por `npm run icons` a partir de `icon-names.ts`; `<use href>` com `currentColor`; decorativo (`aria-hidden`) ou `label` → `role="img"`. Sem fonte de ícones, sem CDN, sem `innerHTML`. |
| `DialogService` + `DialogRef` + `DIALOG_DATA` | `shared/dialog/` | Substituto do `MatDialog` sobre `NgbModal`: `open(Componente, { data, size, disableClose, role })` → `afterClosed()` (resultado ou `undefined`). Diretivas `appDialogTitle` (liga o `aria-labelledby`) e `appDialogClose` (sem valor = cancelamento). Foco preso, Esc, foco devolvido; `ngbAutofocus` marca o foco inicial. |
| `app-field` + `appInput` | `shared/forms/` | Substituto do `mat-form-field`: rótulo, ajuda (`form-text`), erro (`invalid-feedback` com `errorMessage()`), `is-invalid` + `aria-invalid` + `aria-describedby`; mostra erro após tocar ou submeter; tamanho compacto pela densidade. |
| `appMoney` (`MoneyInput`) + `div.input-group` (`InputGroupDensity`) | `shared/forms/money-input.ts`, `money.ts`, `input-group.ts` | Máscara monetária por centavos, `ControlValueAccessor` + validador (`[min]`/`[max]`), modelo `number` ou texto decimal, vazio = `null` (§13.4). O `input-group` recebe `.input-group-sm` na densidade compacta. Ambos vêm com `FORM_FIELD`. |
| `app-info-hint` | `shared/components/info-hint/` | Informação contextual de indicador (popover do ng-bootstrap, §8.6). |
| `app-data-table` | `shared/components/data-table/` | Mesma API (+ `sortable`/`sort`/`sortChange`, `info` e `mobile` por coluna, opcionais): tabela responsiva do Bootstrap, cabeçalho fixo, `ngb-pagination` + quantidade por página + faixa "1 – 20 de 135", esqueleto/vazio/erro. **Paginador só com mais de uma página** (e o rodapé inteiro some quando o total não passa da menor quantidade por página). **Celular (< 576 px): lista empilhada** — a primeira coluna é o título, a coluna `status` é o selo à direita, as três seguintes viram metadados com rótulo; `TableColumn.mobile` (`title`, `meta`, `badge`, `hidden`) ajusta a distribuição — as listas principais (devedores, títulos, casos, pagamentos, acordos, propostas, contestações, alertas da revisão, títulos e contatos da ficha do devedor) declaram os papéis. Com o título declarado (`mobile: 'title'`) e um link nele, a linha inteira fica clicável (mesmo recurso do `.stretched-link`); metadados, selo e ações ficam acima dessa camada (texto selecionável, controles alcançáveis). Ordenação no celular: ainda não (frente de listas). Nomes com link nas linhas na cor do texto (azul só para ação). |
| `app-kpi-card` + `.app-kpi-grid` | `shared/components/kpi-card/`, `styles.scss` | **Cartão de indicador único** (substitui as implementações por tela): rótulo em uma linha em *sentence case* (reticências e texto completo no `title`), valor 18–28 px/600 que acompanha a largura do cartão (`cqi`), detalhe 12 px; `info` (catálogo §8.6, exigido pelo `hints:check`) ou `record` (valor do próprio registro ou contagem direta, sem cálculo a explicar), `tone` (`warning`/`danger`), `emphasis` (total de um resumo), `variant="text"` e conteúdo extra com `appKpiExtra` (ex.: medidor). Dentro de `.app-card` vira a forma embutida (fundo terciário, sem borda). Grade `.app-kpi-grid`: `auto-fit` (mín. 180 px), linhas equilibradas para 4/6/7/8/9 cartões (4×1, 3×2, 4×2, 3×3) e 2 colunas no celular. |
| `app-more-actions` | `shared/components/more-actions/` | Menu "Mais ações" (⋯) das fichas, a partir de uma lista `MoreAction` (`label`, `icon`, `hint`, `danger`, `disabled`, `run`): a destrutiva vai por último, separada e em vermelho. Recebe as ações como dados porque o `ngbDropdownMenu` só navega por teclado entre itens do próprio template. Sem ações, não renderiza. Padrão de uso: §12.2. |
| `app-approval-decision` + `approvalQueue()` | `shared/approval/` | Padrão único das filas de aprovação (§18): quadro de decisão (por que precisa de aprovação com quanto passa do limite, quem pediu × quem decide, "Aprovar/Reprovar e abrir a próxima") e navegação na fila filtrada (contador "1 de N", próxima, volta com os filtros da URL). |
| `app-filter-bar` | `shared/components/filter-bar/` | Filtros na linha em telas largas; abaixo de 768 px, botão "Filtros (n)" que abre um `NgbOffcanvas` com os mesmos campos (projetados uma vez, estado no formulário da tela) e "Ver resultados". `activeCount` = filtros aplicados. Padrão: §12.3. |
| `ConfirmService`/`ConfirmDialog` | `shared/components/confirm-dialog/` | Mesma API; `alertdialog` quando destrutivo, foco inicial em "Cancelar", motivo e palavra de confirmação com `app-field`. |
| `DetailDialog` (`openDetail`) | `shared/components/detail-dialog/` | Ficha somente leitura (lista de definições + JSON), para log de acesso/auditoria nas próximas ondas. |
| `NotificationService` + `app-toasts` | `core/notification/` | Mesma API (`success/info/error`); toasts do ng-bootstrap na raiz; `aria-live` polido/assertivo; erro 10 s, demais 5 s, pausa com ponteiro/foco, sem empilhar repetidos. |
| `app-entity-picker` | `shared/components/entity-picker/` | Mesma API; `ngbTypeahead` (combobox) + selos removíveis; "Nenhum resultado" anunciado. |
| `app-status-chip` | `shared/components/status-chip/` | Badge em pílula com ícone (`TONE_ICON`) nas cores subtle/emphasis. |
| `app-empty-state`, `app-skeleton` (placeholders), `app-page-header`, `app-amount`, `app-document`, `app-password-field`, `app-session-table`, `app-totp-setup` | `shared/components/` | Mesma API, marcação Bootstrap. |
| `DensityService` | `core/preferences/` | Densidade efetiva para componentes (sem depender do estado do usuário). |
| `provideBootstrapDefaults()` (raiz) e `provideBootstrapComponentDefaults()` (rotas do shell e do portal) | `core/config/bootstrap.config.ts` e `bootstrap-components.config.ts` | Raiz: datas `dd/MM/aaaa` (`PtBrDateParserFormatter`) com modelo ISO `yyyy-MM-dd` (`IsoDateAdapter`, `LocalDate` sem fuso) e diálogos roláveis. Rotas: domingo como primeiro dia, menus/tooltips/calendário do campo no `body` (calendário em posição fixa), paginação com primeira/última, autocompletar sem pré-seleção — ver §12.1. |
| Shell, trilha, busca rápida, ajuda, atalhos, novidades, onboarding (convite, balão do tour, primeiros passos), páginas 403/404, moldura e telas de autenticação, página inicial | `core/layout`, `core/onboarding`, `features/auth`, `features/dashboard` | Reescritos; âncoras `data-tour` preservadas. O motor do tour continua no CDK Overlay. |

### 12.1 Bundle inicial e padrões do ng-bootstrap

A migração terminou: o Angular Material, o adaptador de datas do Material, o `date-fns` e a fonte `material-icons` saíram do projeto, junto com o tema Material (`_material-legacy.scss`, `_theme-colors.scss`), os padrões `provideMaterialDefaults()`, a lista `LEGACY_DIALOG_LAUNCHERS` e os aliases de ícones do Material. O `@angular/cdk` continua como biblioteca sem aparência (área de transferência, anúncios para leitor de tela, pontos de quebra e o overlay do tour), recursos que o ng-bootstrap não oferece.

O ng-bootstrap é publicado como um único módulo: configurar um componente na raiz (`provideAppInitializer`) traz o código dele para o bundle inicial. Por isso a raiz só configura o que a moldura inicial usa — adaptador e formatação das datas e diálogos (`provideBootstrapDefaults()`) — e os padrões dos componentes de tela (tooltips, menus, paginação, calendários, autocompletar) são aplicados por `provideBootstrapComponentDefaults()` — em arquivo próprio (`bootstrap-components.config.ts`), porque o empacotador separa por arquivo — nos providers das rotas do shell e do portal, que já são carregadas sob demanda. Resultado: bundle inicial de **705 kB** (169 kB transferidos), abaixo dos 725 kB de antes da onda 2. As configurações do ng-bootstrap são singletons da raiz: o inicializador do injetor da rota altera a mesma instância usada pelos diálogos do `DialogService`. A moldura de autenticação (rota inicial) configura o seu único tooltip direto no elemento.

Nomes de ícone literais nos templates são conferidos por `npm run icons:check` (antes do build e dos testes): nome fora do sprite falha.

### 12.2 Padrão de ação das fichas (cabeçalho)

Revisão de produto 2026-11 (V2): as fichas tinham 5–7 contornos azuis do mesmo peso e o "Cancelar" vermelho no meio. O cabeçalho (`app-page-header`) segue:

1. **No máximo uma ação primária** (`.btn-primary`) — a próxima ação natural do estado (Aprovar, Renegociar, Editar dados, Reativar).
2. **Até duas secundárias** em `.btn-outline-secondary` (atalhos e ações frequentes: Ficha do devedor, Corrigir valor, Baixar termo).
3. **O resto em `app-more-actions`** (⋯ "Mais ações"), sempre com a **destrutiva** (cancelar, estornar, inativar) por último, separada e em vermelho; ela continua pedindo confirmação (`ConfirmService`).
4. **Sem "← Voltar" no cabeçalho** para a lista: a trilha (`app-breadcrumbs`) já leva ao nível de cima, **com os filtros, a página e a rolagem de quando o usuário saiu da lista** (§16), e mostra "← Voltar para <origem>" quando ele veio de outra tela. Esse retorno contextual é um `.btn-sm.btn-outline-secondary` na linha da trilha, fora do cabeçalho, nunca concorrendo com a ação primária. Atalhos para **outro** módulo (ex.: o acordo a partir da atribuição) continuam. Estados de erro e retornos das fichas ("Voltar para devedores", "Voltar para planos") usam `history.linkTo('/lista')`, para voltar com os filtros: dívida, importação (lote, conferência, PDF), credor, usuário, política de cobrança, conjunto de regras de comissão, plano e regras do plano, extrato de remuneração, campanha do discador, telas de campanha (campanha, cadência, execução, segmento, teste A/B), política e turno do assistente, token da API pública, extrato da conciliação e modelo do score. Faltam o demonstrativo (ficha da frente D) e a solicitação do titular (compliance, frente G) — mesma troca de uma linha. Tela nova: nunca o link simples para a lista.
5. Ações sem permissão ficam ocultas (o menu inteiro some quando não sobra ação). No celular o rótulo "Mais ações" vira só o ícone (com `aria-label`).
6. Ao escolher um item, o menu fecha e o foco volta ao botão "Mais ações" **antes** de a ação abrir o diálogo (o menu fica no `body`; o diálogo devolveria o foco a um item escondido). Itens que só existem num tamanho de tela são renderizados com `@if`, nunca escondidos por CSS (a navegação por setas do `ngbDropdown` contaria o item invisível) — ex.: "Chat de suporte" no menu do usuário abaixo de 600 px.

Aplicado em: título, devedor, acordo, proposta, pagamento, demonstrativo, credor, usuário, solicitação do titular e caso jurídico; nos demais cabeçalhos, as secundárias passaram a `.btn-outline-secondary`. Ações irreversíveis que ainda estavam soltas no cabeçalho foram para o "Mais ações" (revisão 11/2026): descartar a importação PDF, cancelar a conferência da importação, revogar o token da API pública, cancelar o processamento do extrato (conciliação), descartar o rascunho e aposentar a versão do plano de remuneração, descartar o rascunho das regras de comissão, aposentar a política do assistente e rejeitar o modelo do score. O passo do tour que apontava para o botão aponta para o "Mais ações". Fica pendente o "Cancelar" do lote de importação (ficha da frente D). "Descartar alterações" (edição não salva) e "Desligar carteira" do assistente (reversível) continuam no cabeçalho.

### 12.3 Celular: filtros e listas

- **Filtros** abaixo de 768 px: `app-filter-bar` recolhe os campos num botão "Filtros (n)" e abre um painel lateral; os números e a lista aparecem na primeira tela (nos painéis, os indicadores vêm logo depois do botão). Nas listas, a contagem vem de `activeFilterCount(form, { ignore })` (`shared/data/filter-count.ts`): campos diferentes do padrão da tela (o valor de antes da URL, guardado por `syncFormWithUrl`); `ignore` para campo dependente (o operador escolhido conta junto com "Responsável"; o período personalizado da agenda conta junto com "Período"). Aplicado nos painéis e nas listas com dois filtros ou mais: casos, devedores, títulos, acordos, pagamentos, instruções de pagamento, divergências, razão de comissão, demonstrativos, regras de comissão, lotes de importação, layouts, auditoria, log de acesso, usuários, credores, carteiras, políticas de cobrança, campanhas, segmentos, testes A/B, templates, agenda, chamadas, casos jurídicos, revisão do assistente, extratos e lançamentos da remuneração, tokens e consumo da API pública. Usam o padrão as listas com dois filtros ou mais, inclusive propostas, aprovação de pagamentos, contestações e reclamações (as filas de aprovação com um filtro só ficam sem o botão). Filtros de contexto vindos de outra tela (ex.: devedor e lote na lista de títulos) ficam fora do botão, sempre visíveis (decisão 1070).
- **Listas** abaixo de 576 px: `app-data-table` em lista empilhada (§12); a paginação some quando tudo cabe numa página. Rótulos de indicador podem ocupar 2 linhas.
- **Botão "Filtros"** informa `aria-expanded`; ao alargar a tela com o painel aberto, os filtros voltam para a linha.
- **Cabeçalho** abaixo de 600 px: menu, marca, busca, pendências, ajuda e avatar; o chat de suporte e o tema ficam no menu do usuário.

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
- Valor em reais digitado: sempre `appMoney` dentro de `input-group` com `<span class="input-group-text" aria-hidden="true">R$</span>`; nada de `type="number"`, `inputmode="decimal"` ou "(R$)" no rótulo (§13.4).

### 13.3 Checklist por tela

1. Trocar todos os `Mat*Module`/`mat-*` pelos equivalentes da §13.1; nenhum import de `@angular/material` no arquivo.
2. Ícones: `app-icon` com o nome do Bootstrap Icons (§14); acrescentar nomes novos em `icon-names.ts` e rodar `npm run icons`.
3. Diálogos: `DialogService` e `DIALOG_DATA`/`DialogRef`; foco inicial (`ngbAutofocus`) no campo principal ou em "Cancelar" quando destrutivo; o serviço da tela que estiver em `LEGACY_DIALOG_LAUNCHERS` volta a `providedIn: 'root'`.
4. Estilos da tela: trocar `--mat-sys-*` por `--bs-*` (ex.: `on-surface-variant` → `--bs-secondary-color`, `primary` → `--bs-primary` ou `--bs-primary-text-emphasis` em texto, `surface-container*` → `--bs-secondary-bg`/`--bs-tertiary-bg`, `outline-variant` → `--bs-border-color`, `error` → `--bs-danger-text-emphasis`); preferir utilitários do Bootstrap a CSS novo.
5. Acessibilidade: rótulos/`aria-label` preservados, ordem de foco, contraste (usar `-emphasis` para texto colorido), estados com texto + ícone, `data-tour` preservado.
6. Testes: atualizar seletores (`tr.mat-mdc-row` → `app-data-table tbody tr`, `mat-dialog-actions` → `.modal-footer`), providers (`MatDialogRef` → `DialogRef`), `NgbConfig.animation = false` em testes de diálogo.
7. Capturas claro/escuro, desktop/celular; `npm run build`, `npm test`, `npm run lint`.
8. Atualizar a versão da tela em `onboarding/content/screens.ts` só se o comportamento mudar (troca visual não é novidade para o usuário).

### 13.4 Campos monetários (`appMoney`)

Padrão de mercado (bancos, maquininhas, apps de pagamento): o usuário digita só os algarismos e cada um entra pela direita, como centavos — "1" → 0,01; "12345" → 123,45; "123456789" → 1.234.567,89. Backspace apaga o último algarismo. Ninguém digita vírgula nem ponto, então não há valor ambíguo ("1.234" é mil ou um vírgula dois?).

**Marcação (contrato para telas novas)**

```html
<app-field label="Valor pago" hint="…">
  <div class="input-group">
    <span class="input-group-text" aria-hidden="true">R$</span>
    <input appInput appMoney formControlName="amount" required [min]="0.01" />
  </div>
</app-field>
```

- Rótulo sem "(R$)"; o símbolo fica no `input-group-text` com `aria-hidden` e a diretiva acrescenta "Valor em reais." à descrição do campo (`aria-describedby`, antes da ajuda e do erro). O texto do campo nunca contém "R$": leitor de tela não soletra símbolo a cada tecla, e copiar o valor dá um número.
- **Modelo do controle:** `number` (padrão) ou texto decimal canônico (`moneyValue="string"` → `"1234.56"`, o formato do `BigDecimal`). Internamente o valor é um inteiro de centavos (`bigint`); a conversão para `number` acontece uma vez, dividindo o inteiro por 10ⁿ, sem acumular erro de ponto flutuante. Até 15 algarismos significativos (o que o `number` representa sem perda: R$ 9,99 trilhões com 2 casas). Campo vazio = `null` (nunca 0); apagar até o último algarismo esvazia. Digitar "0" vale 0,00.
- **Validação:** `required` e os validadores do formulário continuam valendo; `[min]`/`[max]` (em reais) são comparados em centavos e geram `moneyMin`/`moneyMax` com mensagem em reais ("O valor mínimo é R$ 0,01."). Limite que depende de outro dado (saldo disponível) pode ficar no `[max]` ou num validador do `FormControl`.
- **Negativo:** só com `allowNegative` (ajustes de extrato e de demonstrativo). "-" no teclado inverte o sinal e "+" volta a positivo; como o teclado numérico do iPhone não tem "-", o campo vem com um botão "+/−" ao lado: `<input … appMoney #sign="appMoney" allowNegative /> <button class="btn btn-outline-secondary" type="button" (click)="sign.toggleSign()" [attr.aria-pressed]="sign.negative" aria-label="Valor negativo (…)">+/−</button>`.
- **Casas:** `[decimals]` (0 a 6, padrão 2). Hoje só o custo máximo por conversa do assistente usa 4 casas (moeda do provedor do modelo, `[currency]="null"`, sem "R$" e sem a descrição em reais).
- **Colar** (Ctrl+V, arrastar, corretor que troca o texto inteiro) interpreta o texto: "1.234,56", "1234.56", "R$ 1.234,56", "1,234.56", "-150,00", "150,00-" e "(150,00)". Com ponto e vírgula, o último é o decimal; só vírgula, uma é decimal; só ponto, "1.234" (exatamente 3 algarismos) é milhar e "10.5"/"1234.56" é decimal; sem separador o número é inteiro ("1234" = 1.234,00). Casas a mais arredondam meio para cima; texto sem número, negativo onde não cabe ou grande demais é ignorado (o valor anterior fica).
- **Teclado e cursor:** `type="text"` + `inputmode="numeric"` (teclado numérico no celular), sem autocompletar/corretor; o cursor fica sempre no fim (setas, Home/End e clique o devolvem ao fim); selecionar o texto (Shift+setas, Ctrl+A, duplo clique) e digitar substitui o valor; selecionar e apagar esvazia. Teclados de composição (IME, alguns Android) que não deixam cancelar o `beforeinput` caem na releitura dos algarismos do texto.
- Funciona com `formControlName`, `[formControl]` e `[(ngModel)]` (células de tabela sem `appInput` recebem a descrição direto no `aria-describedby`).
- Densidade compacta: o `div.input-group` recebe `.input-group-sm` (diretiva `InputGroupDensity`, em `FORM_FIELD`), para o "R$" acompanhar o `.form-control-sm`.

**O que é monetário:** saldo, valor, entrada, parcela, desconto em R$, limites de alçada e de aprovação, metas em R$, faixas de comissão, honorários e custas em R$, limites do Pix, totais da planilha. **Não é:** percentuais, taxas, fatores, pontos, quantidades e prazos — continuam `type="number"` com `step` (sem máscara de percentual; decisão 386).

**Verificação automática:** `npm run money:check` (roda antes do build e dos testes, como o `icons:check`) recusa `appMoney` com `type="number"`/`inputmode`, campo sem `appMoney` com "R$" no rótulo ou no `aria-label`, e campo numérico sem `appMoney` ligado a nome de valor (`amount`, `balance`, `fee`, `cost`, `threshold`, `target`…). Exceção consciente: comentário `not-money: <motivo>` na linha anterior ao campo.

**Campos com a máscara (out/2026)**

| Tela | Campos |
| --- | --- |
| Cobrança — fila (`queue-form`) | Saldo mínimo, saldo máximo |
| Cobrança — estação (`interaction-panel`) e telefonia (`tabulation-dialog`) | Valor da promessa |
| Campanhas — critérios do segmento (`criteria-editor`, faixa `money-range`) | Saldo em aberto mínimo e máximo |
| Negociação — simulador | Entrada (parcelamento pedido e validação de condições) |
| Negociação — alçadas | Valor máximo do acordo |
| Negociação — regras da política (`rules-editor`) | Encargo de valor fixo, parcela mínima, entrada mínima em R$ |
| Pagamentos | Valor pago (pagamento informado), valor do Pix/cobrança, valor realocado, limite para segunda aprovação |
| Repasse (`settlement`) | Valor fixo por acordo, simulação (valor recebido, honorários cobrados), valor da transferência, efeito do ajuste (com sinal) |
| Remuneração variável (`incentive`) | Faixas de comissão ("a partir de"), valor fixo por acordo, meta padrão (métrica "valor recuperado"), meta do período com a unidade "Valor (R$)", teto por período, ajuste de extrato e ajuste na resolução de contestação (com sinal) |
| Jurídico | Outros bens e custas previstas (viabilidade); campos `money` do formulário genérico (orçamento de custas, valor da causa, valores de penhora/levantamento, saldo mínimo das regras de elegibilidade) |
| Assistente virtual | Valor máximo por Pix; custo máximo por conversa (4 casas, moeda do provedor) |
| Importação — conferência de totais | Total na planilha (principal, saldo, valor do contrato) |

O portal do devedor não tem campo de valor digitado: o devedor escolhe entre as ofertas calculadas.

**Valor que pode ser R$ ou %** (meta do período, decisões 387 e 505): a pessoa escolhe a unidade antes de digitar, com botões `btn-check` num grupo rotulado. Com "Valor (R$)", o campo é `appMoney`; com "Percentual (%)", é `type="number"` com "%" no `input-group-text` (`aria-hidden`) e a faixa aceita na ajuda, que é lida como descrição do campo. Use controles separados para cada unidade (só o da escolhida habilitado), e trocar a unidade limpa o valor, sem converter. Quando a regra de negócio só admite uma unidade, ela vem marcada e a outra fica desabilitada, com a explicação ligada ao grupo por `aria-describedby`.

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

## 16. Estado de navegação: listas, abas, trilha e retorno (revisão de produto 11/2026 — J2, J10, K8, T4, T5, T10)

**Problema:** voltar de uma ficha perdia filtros, página, aba e rolagem; a trilha repetia níveis ("Pagamentos ›
Pagamentos") e o "← Lista" voltava sem filtros; o menu era montado pela permissão de leitura, não pela tarefa.

**Estado na URL** (`shared/data/url-state.ts`, `shared/data/paged-query.ts`)

| Peça | Contrato |
| --- | --- |
| `syncFormWithUrl(form, codecs)` | Lê os parâmetros ao abrir (antes da consulta), grava cada mudança dos filtros com `replaceUrl` (filtrar não cria entrada no histórico) e acompanha link externo para a mesma tela. Valor igual ao padrão não vai para a URL; vazio diferente do padrão ("Todas" quando o padrão é "Ativos") fica como `param=` |
| `urlParam.*` | `text`, `id`, `ids`, `choice`, `flag`, `date`, `int`. Valor inválido ou adulterado cai no padrão; o servidor continua validando |
| `pagedQuery(..., { url: true, sortFields })` | `pagina` (1 em diante), `tamanho` e `ordem` (`-coluna` = decrescente) na URL da lista principal; `sortFields` mapeia a coluna da tabela para o campo da lista branca do servidor (`sort=campo,asc|desc`, `SortableFields`) |
| `urlTab(abas, padrão)` | Aba ativa em `?aba=` (`[activeId]="tab.value()" (activeIdChange)="tab.set($event)"`). Tela embutida em outra (ex.: ficha do devedor no painel da estação) leva `appUrlStateEmbedded` no elemento: a aba fica só em memória e não toca o `?aba=` da hospedeira (decisão 985) |
| `UrlStateWriter` | Um por rota ativada: coalesce as gravações da tela (inclusive as dos efeitos do mesmo ciclo) numa navegação só, mantém na fila os parâmetros de uma gravação ainda em curso e descarta a gravação durante a navegação de um link ou do "voltar" e depois que a tela foi deixada |

Nomes de parâmetro em português, curtos (`credor`, `devedor`, `situacao`, `pagina`, `ordem`, `aba`). **Nunca dado
pessoal na URL** (ela fica em histórico, logs de proxy e é compartilhada): devedor vai pelo id escolhido no seletor (o
CPF/CNPJ vai no corpo de `POST /debtors/lookup`). **Regra:** nenhuma busca por pessoa (devedor, usuário, titular) vai
para a URL — nem nome, nem documento, e-mail, telefone ou IP; a busca da lista de devedores, de usuários, de credores
(aceita CPF/CNPJ), de titulares e o IP do log de acesso ficam só no formulário (somem ao voltar). Na URL só ids e
filtros de cadastro sem dado pessoal (situação, período, nome de carteira, código de template).

**Rolagem e caminho percorrido** (`core/layout/breadcrumbs/navigation-history.ts`): o conteúdo rola em `#conteudo`, que o
roteador não conhece (`scrollPositionRestoration: 'disabled'`). O `NavigationHistory` guarda a rolagem de cada entrada
do histórico e, no "voltar", restaura quando a lista já tem altura (as listas carregam depois da navegação), desistindo se
o usuário rolar ou após 4 s; gravação de estado (filtro, página, aba) não mexe na rolagem; tela nova começa no topo.
Também guarda de onde o usuário veio e o último endereço de cada tela (só em memória, só URLs do app, até 200 de cada; esvaziado na troca de usuário). `pagedQuery` também acompanha um link externo para a mesma lista (página e ordem).

**Trilha e retorno** (`breadcrumbs.ts`): seção some quando tem o nome do item; fichas mostram o nome do registro
(`recordLabel(() => …)`); o link da lista na trilha volta com os filtros (e é o "voltar" do navegador quando a lista é a
origem, com rolagem); "← Voltar para <origem>" aparece quando a origem é outra tela (ex.: da estação para a ficha do
devedor). Não há "← Lista" no cabeçalho das fichas (§12.2, item 4); nos estados de erro e retornos, as fichas voltam com
`history.linkTo('/lista')` (lista em §12.2, item 4; faltam o demonstrativo e a solicitação do titular).

**Drill-down dos painéis** (J6): o número do painel leva à lista com a base do indicador pelos mesmos parâmetros de URL
(`credor`, `carteira`, `situacao`, `elegivel`, `atendimento`, `fila`, `pago-de`/`pago-ate`, `tipo`, `aberta-de`/`aberta-ate`);
regras e mapa de indicadores com e sem link em `analytics.md` §7.1.

**Menu por tarefa** (`core/layout/navigation.ts`, `docs/negocio/perfis-de-acesso.md`): `permissions` = quem tem a tarefa;
`alsoRequires` = exigidas todas, além de uma da tarefa (área de consulta + consulta dos dados; rota: `data.allPermissions` no `permissionGuard`); `openedFrom` = quem abre a tela por outro caminho (a rota aceita as duas listas; `navigation-routes.spec.ts` confere e
exige que o `via` seja um item que essas permissões veem); `ownLabel` dá o nome pessoal ("Meu desempenho"). Seções com
`key` estável, recolhíveis (preferência no servidor, §9); a seção da tela atual fica aberta. Ícones só de contorno e um
por item (`navigation.spec.ts`). Os painéis não têm mais abas repetindo o menu: o período escolhido acompanha o usuário
de um painel para outro (`urlFilter`, em memória).

**Para telas novas:** lista principal com `syncFormWithUrl` + `pagedQuery({ url: true })`; colunas ordenáveis só com lista
branca no servidor; abas com `urlTab`; ficha com `recordLabel`; item de menu pela tarefa do perfil.

### 16.1 Índices das ordenações (medição de 08/11/2026, decisões 954 e 1037)

**Como foi medido.** O sandbox tem poucas centenas de registros por assessoria (232 casos: 0,3 ms com seq scan — não
mede nada). As tabelas foram copiadas (estrutura e os registros fictícios do sandbox, lidos sem escrita) para um
PostgreSQL 16 descartável com a mesma configuração (`shared_buffers` 128 MB, `work_mem` 4 MB) e multiplicadas numa
assessoria: 300 mil casos (94% em aberto), 500 mil títulos, 105 mil propostas, 120 mil acordos, 200 mil pagamentos e
18 mil lotes. Cada consulta é a da lista (filtro da assessoria como o RLS aplica, situações "Ativos" nos casos,
`order by <coluna>, id limit 20`), medida com `EXPLAIN (ANALYZE, BUFFERS)` na segunda execução (cache quente), para
quem vê todos os credores — o pior caso, porque os índices existentes começam por credor, carteira, devedor ou situação.

| Lista · ordem | Sem índice | Com (assessoria, coluna, id) |
| --- | --- | --- |
| Casos · padrão (abertura ↓) / saldo ↓ / atraso ↓ / próxima ação ↑ | 42 / 45 / 38 / 72 ms, ~11,9 mil buffers, seq scan + top-N heapsort | 0,1–0,2 ms, 23–33 buffers |
| Títulos · padrão (vencimento ↑) / saldo ↓ / em cobrança, saldo ↓ | 40 / 48 / 80 ms, ~17 mil buffers | 0,1–0,2 ms, 23–34 buffers |
| Propostas · padrão (criação ↓) / valor ↓ | 23 / 23 ms, ~11,7 mil buffers | 0,1–0,2 ms |
| Acordos · padrão (formalização ↓) / valor ↓ | 17 / 20 ms, ~11,6 mil buffers | 0,2 ms |
| Pagamentos · padrão (pagamento ↓) / valor ↓ | 28 / 23 ms, ~11,7 mil buffers | 0,2 ms |
| Lotes · padrão (criação ↓) | 0,2 ms (já usa `ix_import_batch_created`) | — |
| Lotes · linhas lidas ↓ | 14 ms, 4,5 mil buffers (18 mil lotes) | 0,2 ms |
| Contagem da página (casos / títulos) | 22–56 ms / 26–38 ms (seq scan) | não muda — o índice de ordenação não ajuda o `count` |

**Decisão.** Criados 12 índices `(tenant_id, coluna, id)` — a ordem decrescente lê o índice de trás para a frente com
Incremental Sort no desempate, então um índice por coluna serve às duas direções: casos `V0608` (abertura, saldo,
vencimento mais antigo, próxima ação), títulos `V0308` (vencimento, saldo), propostas e acordos `V0755` (criação /
formalização e valor), pagamentos `V0809` (pagamento e valor). Inclui a ordem **padrão** de cada lista, que também
fazia seq scan para quem vê todos os credores. **Não criado:** lotes por linhas lidas (tabela pequena por assessoria,
14 ms em 18 mil lotes; criar quando o volume pedir). Custo de escrita medido nos casos (saldo e próxima ação mudam a
cada pagamento e interação): 20 mil atualizações em 1,2 s sem os dois índices e 1,9 s com eles (~0,03 ms por
atualização) e ~17–19 MB por índice a cada 300 mil casos — aceito pelo ganho de leitura. Nos títulos, o saldo muda a
cada pagamento e correção de valor: o custo de escrita **não foi medido** — estimado igual ao dos casos (~0,03 ms por
atualização; ~28–30 MB por índice a cada 500 mil títulos, medido); medir com volume real e rever se a carga de
pagamentos pesar. Propostas, acordos e pagamentos: valor e data não mudam depois de gravados (sem custo de atualização,
só de inclusão). **Fica em aberto** a
contagem total de cada página (seq scan proporcional à assessoria, ~50% do tempo da lista depois dos índices): avaliar
contagem estimada ou "mais de N" quando o volume real pedir.

## 17. Busca de devedor e caso e vínculos entre telas (revisão de produto 11/2026 — J3, J4, T6; itens 1.3 e 1.4)

**Busca rápida (Ctrl + K)** (`core/layout/command-palette`): além de telas e ações, quem tem `DEBTOR_VIEW` encontra
devedor e caso. O texto digitado com 3+ letras ou dígitos vai, depois de 300 ms sem digitar, para
`POST /api/v1/collection/quick-search` (`QuickSearchApi`) — **no corpo, nunca na URL**; a resposta de um termo antigo é
descartada (e a requisição cancelada). O servidor decide o critério (nome, CPF/CNPJ ou telefone completos, código do
título — [collection.md](collection.md) §8.4) e registra no log de acesso a busca por documento/telefone. A paleta mostra
os grupos "Casos" (devedor · credor; situação · carteira) e "Devedores" (nome; documento mascarado · credores) **depois**
dos destinos do menu, para a seleção do teclado não pular quando a resposta chega; "Buscando…", falha e "há mais
resultados" ficam numa região `role="status"`. Abrir um resultado leva à ficha do caso ou do devedor.
**Recentes** (`RecentRecords`): os últimos 10 devedores/casos abertos pela paleta, mostrados com o campo vazio; só em
memória (sinal) — nunca `localStorage`/`sessionStorage`/servidor, porque rótulo e dica são dado pessoal — e esvaziados
na saída ou troca de usuário. Sem `DEBTOR_VIEW`, a paleta continua só com telas e ações (sem chamada ao servidor).

**Cartão "Relacionados"** (`shared/components/related-records`): `<app-related-records [records]="…" />` recebe os
vínculos que **existem** (`RelatedRecord`: `kind`, `id`, `label`, `hint`, `queryParams`) e mostra só os que o perfil
**pode abrir**: cada `kind` aponta para uma rota em `RELATED_TARGETS` com as mesmas permissões do `permissionGuard`
(o `related-records.spec.ts` resolve a árvore real de rotas e falha se divergir). Ficha e lista do mesmo tipo ficam no
mesmo grupo ("Pagamentos": os últimos e "Ver todos" → `/pagamentos?devedor=`). Sem vínculo visível, nada é renderizado.
A tela não deve buscar dados de um destino que o perfil não abre (`RelatedRecords.canOpen(kind, has)`): evita 403 e
"acesso negado" no log. Os vínculos vêm dos endpoints que cada módulo já expõe, com o escopo por credor do servidor
(`features/debt/data/debtor-links.ts`: casos, acordos, pagamentos, contestações — `GET /collection/disputes?debtorId=` —,
casos jurídicos e conversas do devedor, uma chamada por módulo e nenhuma por caso; falha num módulo esvazia só aquele
grupo).

| Ficha | Relacionados |
| --- | --- |
| Devedor | casos (ativos primeiro; "Ver todos" acima de 5), lista de títulos do devedor, acordos, pagamentos, contestações (dos casos), casos jurídicos, conversas |
| Título | devedor, caso(s) da carteira, acordos que incluem o título, contestação, caso jurídico com o título, lote de importação |
| Acordo | devedor, caso, proposta, acordo anterior/atual da renegociação, pagamentos aplicados, títulos do devedor |
| Pagamento | devedor, acordos e títulos que receberam o pagamento |
| Caso, contestação, caso jurídico | componente pronto; inclusão nas fichas pelas frentes donas (estação/ficha do caso; contestações e jurídico) |

**Ficha do devedor com ações**: "Abrir caso" (primária; um caso em cobrança → link direto; vários → menu por
credor · carteira), "Simular" (simulador com credor, devedor e caso; sem caso ativo, por credor dos títulos em
cobrança), "Editar dados"; link do portal e dossiê em "Mais ações". Aba **Interações** (`CASE_VIEW`): as 50 mais recentes
de todos os casos do devedor no escopo, numa chamada (`GET /collection/debtors/{id}/interactions`); com mais, o aviso
remete ao histórico de cada caso. `?aba=interacoes` sem `CASE_VIEW` volta para Títulos.
**Embutida no painel da estação** (`appUrlStateEmbedded`): sem "Abrir caso" e "Simular" — o operador já está no caso e
simula pelo painel da própria estação; o "Relacionados" continua (ir a outro caso é uma escolha explícita, e o
atendimento fica guardado por caso, [collection.md](collection.md) §8.2).

**Links pontuais**: linha da fila → `/cobranca/casos?fila=` (`CASE_VIEW`); lote de importação → "Títulos importados"
(`/titulos?lote=`, só lote `IMPORT` com títulos criados/atualizados; filtro `lote` na lista de títulos, só id); acordo →
cada pagamento aplicado (data) e o devedor; recebimento do demonstrativo (e do razão de comissão) → pagamento de origem;
pagamento → devedor. Todos só com a permissão da tela de destino.

## 18. Aprovações num padrão único (revisão de produto 11/2026 — J8, T9, telas 15 e 18)

**Problema:** cada fila de aprovação (propostas, pagamentos informados, correções de valor) tinha um leiaute; a decisão não
dizia **quanto** passava da alçada ou do limite, não havia "próxima" nem volta à fila (a trilha marcava "Propostas"), a
lista não tinha desconto nem prazo e a tela mostrava códigos ("alçada JUNIOR").

| Peça | Contrato |
| --- | --- |
| Fila (lista) | Filtros na URL com os mesmos nomes da decisão (`credor`, e `origem` nos pagamentos); colunas úteis (valor, desconto em %, prazo — "vence hoje" —, quem pediu e alçada); "Revisar" (`btn-sm btn-outline-primary`) e a data da linha abrem a decisão com os filtros |
| Decisão (`<fila>/:id`) | Rota própria sob a fila: menu e trilha marcam a fila. `app-approval-decision` no topo: "Voltar para <fila>" com os filtros, contador "<Item> 1 de N", **Por que precisa de aprovação** (critério, pedido, limite de quem pediu, **passa em** valor e %, alçada de quem decide com ✓/✗), motivos sem medida em lista, **Pedido por** × **Decide**, e as ações; detalhes (condições, memória de cálculo, comprovante, componentes) projetados abaixo |
| Ações | "Aprovar e abrir a próxima" (primária) e "Reprovar/Recusar e abrir a próxima"; na última, "… e voltar à fila". A confirmação (motivo, resumo do impacto) continua na tela, pelo `ConfirmService`. Decidido, `approvalQueue().advance()` (Promise) relê a fila filtrada e abre o item que vinha depois (ou o primeiro que sobrar), senão volta à fila; a tela fica ocupada até a navegação terminar. Erro na decisão: a tela recarrega o item |
| Não pode decidir este item | Segregação (pedido próprio) ou perfil que não aprova: as ações somem e o motivo aparece no lugar (`blockedReason`; o servidor recusa de qualquer forma). Alçada insuficiente para as condições: só "Aprovar" some, com o motivo (`approveBlockedReason`) — reprovar continua, como o servidor permite. Sem a permissão de decidir, nem o motivo |
| Fora da fila | A ficha do registro (ex.: `/negociacao/propostas/:id`) mostra o mesmo quadro para quem decide, sem contador e com "Aprovar"/"Reprovar" simples |

O "quanto passa" vem do módulo dono, nunca é calculado na tela: `GET /negotiation/proposals/{id}/approval-assessment`
(`negociacao-e-alcadas.md` §10.14), `GET /payment/payments/{id}/review-assessment` (`pagamentos.md` §5) e
`GET /title-amount-adjustments/{id}/approval` (`docs/negocio/correcao-de-valor.md`). A fila lida pela decisão é a primeira
página de até 200 itens (`APPROVAL_QUEUE_WINDOW`); com mais que isso, o total vem do servidor e a próxima continua
funcionando dentro da janela.

## 19. Formulário de monitoria (revisão de 10/11/2026)

**Problema:** o diálogo "Registrar monitoria" era uma grade de campos soltos: o atendimento vinha preenchido, mas a data
continuava editável; sem atendimento de origem, pedia um identificador UUID que a interface nunca mostra; cada critério
era um campo de texto (nota digitada, com o rótulo do peso e do crítico no meio do texto) e o título "Notas de 0 a 10"
saía no tamanho de título de página; a nota ponderada ficava perdida entre os campos.

| Peça | Contrato |
| --- | --- |
| Cabeçalho | "Registrar monitoria"; com um só formulário ativo, o nome dele fica abaixo do título (sem seletor). Com mais de um, o seletor abre a seção "Critérios". |
| Atendimento avaliado | Vindo de "Avaliar": cartão fixo (ícone do tipo, negociador, tipo · descrição e data). Sem origem: negociador, tipo (padrão "Geral (sem atendimento específico)") e data, mais o aviso com os links para avaliar no próprio atendimento (Chamadas e gravações, Caixa de entrada, conforme a permissão). |
| Critérios | Um bloco por critério (`fieldset` + `legend`): número, descrição, selo "Crítico" (a regra "nota 0 zera a avaliação" fica no tooltip e em texto para leitor de tela) e "Peso N"; escala de 11 botões (`btn-check`, rádio) em grade que encolhe até a largura do celular, com "Não atendeu" e "Excelente" nas pontas (os rádios dizem só "Nota N"; o critério vem do `legend`). Nota 0 em critério crítico pinta o bloco de perigo; ao registrar sem nota, o bloco fica com a borda de erro e "Dê uma nota a este critério." |
| Rodapé | Nota ponderada sempre visível (`aria-live`), com "faltam N critérios" ou "falha em critério crítico"; botões "Cancelar" e "Registrar monitoria". |
| Comentário | Opcional; com falha crítica, o rótulo perde o "(opcional)" e a dica pede a descrição do ocorrido (decisão 1074). |

Pontos de entrada: chamada (linha e player da gravação), conversa, histórico do caso e o painel de desempenho por
negociador (desenho em `remuneracao-variavel.md` §2.4.1). Atendimento que a própria pessoa já avaliou mostra o selo verde
"Avaliado por você · nota X" no lugar do botão (na lista de chamadas, o ícone preenchido), focável, com a dica da data e de
que outro avaliador ainda pode avaliar; o player não oferece "Avaliar" (decisão 1078). Teste: `features/incentive/quality/evaluation-dialog.spec.ts`.
