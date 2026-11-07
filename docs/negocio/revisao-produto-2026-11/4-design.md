# Estudo 4 — Design da aplicação (identidade visual, design system, consistência)

Data: 06/11/2026 · Escopo: frontend Angular + Bootstrap 5.3.8 + ng-bootstrap + Bootstrap Icons + Inter Variable.
Método: leitura de `styles.scss`, `styles/_tokens.scss`, `_density.scss`, `styles/bootstrap/*`, shell, componentes
compartilhados, 38 SCSS de feature e 177 componentes com `styles:` inline; capturas no sandbox (boreal: gestor, admin,
auditor, compliance, estrategia, operador2), 1440×900 e 390×844 (DPR 2), claro e escuro, login sem sessão; teste de
renderização da Inter em pesos 300–500 no próprio app.

Capturas e scripts ficaram só na máquina local (não versionados); cito apenas o nome do arquivo (prefixo `boreal-<usuario>_<rota>`; `_dark`, `_m` = celular,
`_m_menu`, `_detalhe`; `login_*.png`; `tipografia-pesos-1x.png` e `-2x.png`). O convite "Primeira vez por aqui?" foi
ocultado por CSS nas capturas feitas com `dshot.mjs` (sem alterar o estado no servidor).

Legenda: **[F]** fato verificado (código ou captura) · **[S]** suposição/julgamento a validar.
Severidade: Alta / Média / Baixa. Esforço: P (≤ 1 d) · M (2–4 d) · G (≥ 5 d).

---

## 1. Diagnóstico geral

A fundação é boa e acima da média para um produto nesse estágio: parametrização do Bootstrap disciplinada e
documentada, tokens semânticos com contraste medido, tema escuro nativo, densidade compacta, foco visível, estados
vazios, `app-status-chip` com ícone + texto, sem cor hex solta nas telas (só 4 ocorrências legítimas). O que impede o
salto para "Linear/Stripe" é **hierarquia** e **consistência fina**, não falta de base:

1. **Tipografia em peso 300 no corpo** + `antialiased` deixa o texto acinzentado e "fraco" a 1x; ao mesmo tempo as
   telas usam **105 literais `font-weight: 600`**, o que inverte a hierarquia (título de cartão mais pesado que o título
   da página, que está em 400).
2. **Ações sem hierarquia**: fichas com 5–7 botões de contorno azul do mesmo peso (+ "Cancelar" vermelho); no celular
   isso ocupa a primeira dobra inteira.
3. **Padrões duplicados por feature** (cartão de KPI em ≥ 7 versões, tabelas fora do `app-data-table`), com tamanhos e
   pesos divergentes.
4. **Celular**: tabelas só com rolagem horizontal, filtros empurrando os números para baixo da dobra, cabeçalho com 7
   ícones.
5. Detalhes que denunciam "não acabado": hífen espaçado ("E - mail", "sexta - feira", "CSR - PESSOAL - 00908") causado
   pelo `tabular-nums` global, raios grandes aninhados, cinzas quentes nos gráficos contra uma UI de cinzas frios,
   KPIs "R$ 1 mi" iguais para valores diferentes.

---

## 2. Diagnóstico por dimensão

### 2.1 Tipografia

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| T1 | Corpo em **300** a 14 px (`$font-weight-base: 300`), títulos em 400, `-webkit-font-smoothing: antialiased` no body. Na comparação lado a lado, 300 a 1x fica visivelmente cinza e "quebradiço"; 400 é sólido sem perder leveza. No escuro (texto claro sobre fundo escuro) o 300 afina ainda mais. | [F] `styles/bootstrap/_variables.scss:89,98`; `styles.scss:33`; `shots/design/tipografia-pesos-1x.png` | Alta |
| T2 | O contraste WCAG (cor) passa — corpo 15,6:1, secundário 6,87:1 claro / 8,63:1 escuro — mas o WCAG 2.x não considera peso. Em monitores 1080p de operação (provavelmente Windows/ClearType) o 300 a 13–14 px tende a pior legibilidade em turnos longos. | [F] cálculo dos tokens; [S] parque de máquinas Windows 1x | Alta |
| T3 | Hierarquia invertida: `.app-page-header__title` 22 px/400 vs títulos de cartão/gráfico em 600 ("Carteira por credor", "Dados cadastrais" parecem mais fortes que "Painel operacional"). | [F] `styles.scss:84-90`; captura `boreal-gestor_paineis.png` | Média |
| T4 | Escala de pesos furada: `$font-weight-semibold: 500` e `$font-weight-bold: 600` são "moderados", mas há **105** `font-weight: 600` literais (71 em `styles:` inline de `.ts`, 34 em SCSS) e 2× 700; `fw-semibold` usado 4×. Ninguém usa a variável. | [F] grep; ex.: `features/negotiation/simulator/simulator.scss:92,138,154,192`, `features/settlement/settlement.scss:48,55,75,116,152` | Média |
| T5 | **Hífen espaçado**: `font-variant-numeric: tabular-nums` no `body` faz a Inter usar o hífen tabular (largura de dígito) em todo texto. Aparece em "E-mail" no login, "sexta-feira" no início, códigos de título ("CSR-PESSOAL-00908-P1") em títulos e tabelas. | [F] `styles.scss:36`; `tipografia-pesos-2x.png` (linhas "tnum ON/OFF"); `login_m.png`; `boreal-gestor_titulos_m_detalhe.png` | Média |
| T6 | Rótulos de KPI em CAIXA ALTA com tracking (12 px/600) quebram em 2 linhas em quase todos os cartões (desktop e celular) e competem com o valor. | [F] `analytics/components/kpi-card.ts:46-47`; `boreal-gestor_paineis.png`, `_paineis_m.png` | Média |
| T7 | Tamanhos fora da escala: `15px` (6×, inclusive `.app-card__title` em `styles.scss:139`), `17px`, `11px`, `10px` (`case-sheet-panel.scss:61`), `18px`. A escala `--app-text-*` existe e é bem usada (≈140 usos). | [F] grep font-size | Baixa |
| T8 | `.app-mono` 12 px em `ui-monospace` destoa (lote de carga, códigos de perfil) — aceitável, mas a Inter tem `ss01`/`zero`/`tnum` que resolvem códigos sem trocar de família. | [F] `styles.scss:255-258`; `boreal-admin_admin_perfis.png` | Baixa |

### 2.2 Cor e tokens

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| C1 | Paleta semântica sólida, documentada e com contraste ≥ 7:1 nos tons; primária `#3068b5` 5,57:1 com branco e 5,23:1 sobre a página. **Preservar.** | [F] `_variables.scss:32-60` | — |
| C2 | Gráficos usam **cinzas quentes** (`--app-chart-grid: #e1e0d9`, `--app-chart-axis: #c3c2b7`; escuro `#2c2c2a`/`#383835`) numa UI de cinzas frios (`#dde2e9`, `#eceff3`). | [F] `_tokens.scss:34-35,48-49` | Baixa |
| C3 | Separação de superfícies muito baixa: cartão branco sobre `#f6f8fb` = 1,06:1; borda `#dde2e9` = 1,30:1; escuro 1,10:1 / borda 1,36:1. Funciona por causa da borda, mas a página "lava" em monitor de baixo contraste. | [F] cálculo | Baixa |
| C4 | Excesso de azul de link: abas inativas, ações de linha, "Ver tabela", "Atualizar agora", nomes nas tabelas, 5–7 botões de contorno — o azul deixa de sinalizar "a ação". | [F] `boreal-gestor_paineis.png`, `_titulos_detalhe.png`, `boreal-estrategia_campanhas_segmentos.png` | Média |
| C5 | Navy da barra: comentário diz `#1d4e89` e o token usa `#0f2f57`; `ux.md` §7 cita os dois. | [F] `_variables.scss:29` vs `_tokens.scss:58` | Baixa |

### 2.3 Espaço, grid e alinhamento

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| E1 | Grade de KPIs com `auto-fill`: no painel operacional 6 cartões viram 5 + 1 órfão; no início 4 cartões deixam um buraco à direita. | [F] `analytics/pages/panel.scss:3`, `analytics/components/home-kpis.ts:55`; `boreal-gestor_.png`, `_paineis.png` | Média |
| E2 | Filtros com largura fixa 220 px deixam linhas irregulares e ocupam muito: trilha de auditoria ≈ 190 px de filtros + botões antes da tabela; no celular os 3 filtros do painel empurram os KPIs para a 2ª tela. | [F] `styles.scss:161-168`; `boreal-auditor_governanca_auditoria.png`, `_paineis_m.png` | Média |
| E3 | Padrão de filtro inconsistente: auditoria tem "Filtrar/Limpar" explícitos; as demais listas filtram ao digitar. | [F] capturas auditoria vs devedores | Baixa |
| E4 | Escala de espaço `--app-space-*` existe mas o próprio `styles.scss` e o shell usam px soltos (6, 10, 14). Sem prejuízo visível grande. | [F] `shell.scss:17,19,75-76`; `styles.scss:80-81` | Baixa |
| E5 | Estação: cabeçalho próprio (título + seletor + botão na mesma linha) e link "Agenda" solto com recuo de 16 px; colunas vazias muito altas sem caso aberto. | [F] `boreal-operador2_cobranca_fila.png` | Baixa |

### 2.4 Componentes

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| K1 | **Botões sem hierarquia**: ficha do título com 6 contornos azuis + "Cancelar" vermelho no mesmo nível; ficha do devedor com 4 contornos + 1 sólido; "← Títulos/Devedores" (navegação) misturado às ações e redundante com a trilha. No celular são 4 linhas de botões antes do primeiro dado. | [F] `boreal-gestor_titulos_detalhe.png`, `_devedores_detalhe.png`, `_titulos_m_detalhe.png` | Alta |
| K2 | Posição das ações varia: à direita do título (devedor, usuário) vs em linha própria abaixo (título). | [F] capturas de detalhe | Média |
| K3 | **KPI duplicado ≥ 7×** com valores divergentes: `kpi-card` (600, até 22 px), `tl-kpi__value` 22 px/400, `st/pd/md/in-kpi__value` 16 px/600, `sp-kpi__value` 14 px/500, cartões da ficha (outro padrão). | [F] `telephony.scss:29-31`, `settlement.scss:53-55`, `negotiation/detail-page.scss:62-64`, `legal/matters/matter-detail.scss:57-59`, `incentive.scss:63-65`, `collection/station/case-sheet-panel.scss:54-56`, `analytics/components/kpi-card.ts:56-57` | Média |
| K4 | Tabelas: `app-data-table` é bom (cabeçalho fixo 12 px, hover discreto, selos); mas "Por pessoa" (produtividade) e tabelas de ficha usam marcação própria, com cabeçalho em outro peso/cor. Paginação completa («« « 1 » ») mesmo com 1 página. Ações de linha só com ícone azul sem rótulo visível (lápis, gráfico, duplicar). | [F] `boreal-gestor_paineis_produtividade.png`, `boreal-gestor_campanhas.png`, `boreal-compliance_compliance_restricoes.png` | Média |
| K5 | **Raios grandes e aninhados**: botão/campo 12 px em altura 37 px (quase pílula), cartão 16 px, tabela 16 px dentro de cartão 16 px com 20 px de padding (devedor/título), diálogos 20 px. Lê como "app de consumo", não ferramenta densa; aninhamento sem regra (raio interno = externo − padding) gera curvas desencontradas. | [F] `_variables.scss:108-113`; `boreal-gestor_devedores_detalhe.png` | Média |
| K6 | Cartão de pendência com `border-left-width: 4px` + raio 16 px → a faixa colorida vira um "C" curvo. | [F] `features/pending-work/pending-work-list.ts:45-47`; `boreal-gestor__m.png` | Baixa |
| K7 | Abas (`nav-tabs`) com ícone + texto azul em todas as inativas e a ativa em caixa — visual de 2015; no celular cortam ("Conversã"). As abas do painel repetem 1:1 o submenu "Painéis" do menu lateral. | [F] `boreal-gestor_paineis.png`, `_paineis_m.png` | Média |
| K8 | Trilha: "Campanhas › Campanhas"; detalhe de usuário termina em "Usuário" genérico, enquanto devedor/título usam o nome do registro. | [F] `boreal-gestor_campanhas.png`, `boreal-admin_admin_usuarios_detalhe.png` | Baixa |
| K9 | Estados vazios consistentes (ícone em círculo + título + texto) — bom. Selos (`app-status-chip`) consistentes e legíveis — bom. | [F] `boreal-gestor_posicao_credor.png`, listas | — |

### 2.5 Iconografia

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| I1 | Mistura de glifos de contorno fino com glifos de massa cheia no menu: "Produtividade" (`people`), "Contestações" (`hammer`), "Devedores" (`person-bounding-box`), "Agenda" — chamam mais atenção que o item ativo. | [F] `boreal-gestor__m_menu.png` | Média |
| I2 | **16 ícones reutilizados** para itens diferentes do menu: `sliders` 4×, `headset` 3× (Estação, Operadores, Pedidos do portal), `receipt` 3×, `card-checklist` 3×, `activity` 3×, `cash-stack` (Pagamentos e Recuperação), `file-earmark-text` (Templates e Títulos)… | [F] `core/layout/navigation.ts` (agrupamento por `icon`) | Baixa |
| I3 | Logo = ícone genérico de gráfico de barras em quadrado; no login fica num quadrado cinza claro sem presença. | [F] `login_desk.png` | Baixa |

### 2.6 Gráficos

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| G1 | Paleta azul/laranja validada para daltonismo e ≥ 3:1 (chart-1 4,42:1, chart-2 3,20:1 claro); valores rotulados nas barras; resumo textual ("Maior valor: …") e "Ver tabela" — excelente para acessibilidade. **Preservar.** | [F] `_tokens.scss:29-36`; `boreal-gestor_paineis.png` | — |
| G2 | Barras retas sem arredondamento, finas e muito próximas (barras agrupadas azul/laranja coladas), grade em cinza quente (C2). Coluna única ocupa um gráfico de 300 px de altura com eixo até "R$ 10 mil". | [F] `boreal-gestor_paineis_recuperacao.png` | Baixa |
| G3 | KPIs compactos com 1 casa: "R$ 1 mi" para Valor da carteira, Saldo vencido e Saldo elegível (valores distintos) — o número de destaque não informa. | [F] `shared/components/amount/amount.ts:5`; `boreal-gestor_paineis.png` | Média |

### 2.7 Tema escuro

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| D1 | Bem resolvido: neutros frios, textos ≥ 7:1, primária mantida, selos legíveis, foco claro (`#a7c8ff`). **Preservar.** | [F] `_variables-dark.scss`; `boreal-gestor_paineis_dark.png` | — |
| D2 | Peso 300 em texto claro sobre escuro fica ainda mais fino (irradiação inversa). | [F] `tipografia-pesos-1x.png` (bloco escuro) | Média |
| D3 | Cabeçalho escuro `#161b22` quase igual ao menu `#171c23`: some o "chrome" que no claro é a barra navy. | [F] `_tokens.scss:64`; `boreal-gestor_devedores_dark.png` | Baixa |
| D4 | Gráficos escuros com grade quente `#2c2c2a` (C2). | [F] `_tokens.scss:48` | Baixa |

### 2.8 Celular (390×844)

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| M1 | Tabelas só rolam na horizontal; nomes quebram em 3 linhas e a coluna "Situação" fica cortada. Para o gestor no celular isso é a principal fricção. | [F] `boreal-gestor_devedores_m.png` | Alta |
| M2 | Painel: título + Exportar + abas + 3 filtros ocupam a primeira tela; KPIs só depois da rolagem; KPIs 2 por linha com rótulo em 2 linhas. | [F] `boreal-gestor_paineis_m.png` | Alta |
| M3 | Ficha do título: 7 botões em 4 linhas antes do primeiro dado. | [F] `boreal-gestor_titulos_m_detalhe.png` | Alta |
| M4 | Cabeçalho com 7 alvos (menu, logo, busca, pendências, chat, tema, ajuda, avatar) — apertado; tema e chat não precisam estar no topo no celular. | [F] `boreal-gestor__m.png`; `shell.scss:191-196` | Baixa |
| M5 | Início no celular: alerta de MFA + pendências ocupam toda a 1ª tela; os números da operação ficam 2–3 telas abaixo. | [F] `boreal-gestor__m.png` | Média |
| M6 | Menu offcanvas legível e com alvos grandes — bom; lista longa (≈60 itens para o gestor) sem grupos recolhíveis. | [F] `boreal-gestor__m_menu.png` | Baixa |

### 2.9 Login

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| L1 | Cartão funcional e correto (rótulos, ajuda, mostrar senha, aviso de auditoria), mas sem presença de marca: logo genérico em quadrado cinza, nome em 400 azul, fundo com um borrão radial no canto superior esquerdo que parece acidental; "E - mail" com hífen espaçado (T5). | [F] `login_desk.png`, `login_m.png`; `core/layout/auth-layout/auth-layout.scss` | Média |

### 2.10 Restos da migração e divergências de tokens

| # | Achado | Evidência | Sev. |
|---|---|---|---|
| R1 | Material removido do `package.json` [F], mas documentação/comentários ainda citam "telas ainda em Angular Material" e "tema Material transitório". | [F] `_tokens.scss:6-7`; `docs/tecnico/desenho/ux.md` §7 (`data-theme` … "tema Material transitório") | Baixa |
| R2 | 19 usos de `var(--app-*, fallback)` (ex.: `var(--app-radius-lg, 1rem)`) — resquício de quando o token podia não existir; mascaram erro de digitação de token. | [F] grep; `features/pending-work/pending-work-list.ts:47` | Baixa |
| R3 | 177 componentes com `styles:` inline + 38 SCSS, com prefixos ad hoc (`tl-`, `st-`, `sp-`, `pd-`, `md-`, `in-`…) reimplementando KPI, resumo, status. Raio e cor estão quase sempre por token (bom); peso e tamanho não. | [F] grep | Média |
| R4 | `#fff` fixo em `totp-setup.scss:26`, `pix-qr.ts:31` (QR precisa de fundo branco — legítimo) e `pending-work-indicator.ts:94`, `portal-config-edit.scss:6` (prévia da marca do credor — legítimo). Nada grave. | [F] grep | — |

---

## 3. Proposta de evolução visual (valores concretos)

Tudo pelos parâmetros oficiais (variáveis Sass `!default` em `styles/bootstrap/_variables*.scss`, CSS vars `--bs-*` /
`--app-*`, classes do Bootstrap 5.3). Nenhuma dependência nova.

### 3.1 Tipografia — **decisão do responsável** (contraria "traço fino" aprovado)

Proposta A (recomendada) — "leve, mas sólido":

| Variável | Hoje | Proposta |
|---|---|---|
| `$font-weight-base` | 300 | **400** |
| `$headings-font-weight` | 400 | **600** (h1/título de página) — ver tracking abaixo |
| `$font-weight-medium` | 500 | 500 |
| `$font-weight-semibold` | 500 | **600** (passa a ser o único "forte" das telas) |
| `$font-weight-bold` | 600 | **700** (uso raro) |
| `$btn-font-weight` | 400 | **500** |
| `$form-label-font-weight` | 400 | **500** (rótulo 13 px, cor `--bs-emphasis-color` não; manter body) |
| `$table-th-font-weight` | 500 | 500 (cabeçalho 12 px, cor secundária — manter) |
| `$display-font-weight`, `$lead-font-weight` | 300 | 300 (o "fino" fica nos números grandes/leads — preserva a intenção estética onde o tamanho permite) |
| `-webkit-font-smoothing: antialiased` | ligado | manter só se A for adotada (com 400 fica bom); com 300, remover |

Proposta B (compromisso, se o PO quiser manter a sensação fina): Inter Variable aceita peso contínuo —
`$font-weight-base: 380`, `$headings-font-weight: 550`, e no escuro `--bs-body-font-weight: 400` via mixin
`color-mode(dark)` (CSS var documentada). Esforço igual.

Escala (px, `rem` sobre 16): 12 (rótulo/legenda) · 13 (tabela/denso) · **14 (corpo)** · 16 (título de cartão, 600) ·
**20 (título de página, 600, `letter-spacing: -0.015em`)** · 24/28 (valor de KPI, 600, `-0.02em`). Substituir 15/17/11/10
pelos degraus vizinhos. `.app-card__title` → `var(--app-text-lg)`/600. Ajustar `$h1-font-size: $font-size-base * 1.43`
(20 px), `$h2: 1.143` (16 px), `$h3: 1` (14 px/600).

Números: **tirar `tabular-nums` do `body`** (`styles.scss:36`) e aplicar só em `.app-numeric`, `td.text-end`,
valores de KPI, `.app-definition-list dd.app-numeric`, datas — resolve T5. Valor de KPI/montante: `font-feature-settings:
"tnum", "cv11"` opcional para o "1" de base simples (Inter) — [S] gosto.

Esforço: **P** (variáveis) + **M** (varrer os 105 `600` literais → `$font-weight-semibold`/`fw-semibold`).

### 3.2 Forma (raios) e elevação

| Variável | Hoje | Proposta | Motivo |
|---|---|---|---|
| `$border-radius-sm` | 8 px | **6 px** | botões/campos pequenos, tooltips |
| `$border-radius` | 12 px | **8 px** | botões, campos, menus (37 px de altura ≠ pílula) |
| `$border-radius-lg` | 16 px | **12 px** | cartões, alertas, tabela |
| `$border-radius-xl` | 20 px | **16 px** | diálogos |
| `$border-radius-pill` | — | manter | selos, busca, avatar |
| Regra | — | raio interno = externo − padding (mín. 6 px): tabela dentro de cartão usa `--bs-border-radius` (8) | K5 |
| `$box-shadow-sm` | 0 2px 6px /6% | **0 1px 2px rgba(11,18,32,.05), 0 1px 3px rgba(11,18,32,.06)** (duas camadas, mais nítida) | estilo Linear/Stripe |
| hover de cartão clicável | borda + `shadow-sm` | manter | — |

Mantém "componentes arredondados" (decisão do PO), só menos "bolha". Esforço **P** (variáveis) + revisão visual **P**.

### 3.3 Cor

| Token | Hoje | Proposta |
|---|---|---|
| `--app-chart-grid` (claro/escuro) | `#e1e0d9` / `#2c2c2a` | **`#e6e9ee` / `#262c34`** (frio) |
| `--app-chart-axis` | `#c3c2b7` / `#383835` | **`#c3cad4` / `#3a424d`** |
| `$body-tertiary-bg` (página) | `#f6f8fb` | **`#f3f5f8`** (cartão 1,09:1 em vez de 1,06:1) [S] testar |
| `$border-color` | `#dde2e9` | manter (ou `#d9dee6`) |
| `--app-topbar-bg` escuro | `#161b22` | **`#0b0e12`** ou navy escuro `#0d1b2e` (separar o chrome do menu) [S] gosto |
| Links em listas | `--bs-link-color` | nomes em tabela com `link-body-emphasis` + sublinhado no hover (Bootstrap `.link-body-emphasis`); azul fica para ação |

### 3.4 Botões (hierarquia)

- **Primária** (`.btn-primary`): no máximo 1 por tela/região.
- **Secundária**: `.btn-outline-secondary` (cinza frio, texto `--bs-body-color`) — não o contorno azul. Opcional:
  `$btn-border-width` mantém 1 px.
- **Terciária**: `.btn-link`/`.btn-ghost` via `.btn` com `--bs-btn-hover-bg: var(--bs-secondary-bg)` (CSS vars
  documentadas, sem seletor interno).
- **Destrutiva**: dentro de "Mais ações" (`ngbDropdown`, item `text-danger`), nunca alinhada às ações comuns.
- Ficha: 1 primária + até 2 secundárias + menu "Mais ações"; remover o "← Voltar" (a trilha já faz isso). No celular,
  barra de ações compacta (primária + "⋯").
Esforço **M** (componente `app-page-header` já existe; padronizar `actions` + overflow).

### 3.5 Componentes a consolidar

1. **`app-kpi-card` em `shared/components`** (promover o de analytics): rótulo 12–13 px/500 *sentence case*, cor
   secundária, 1 linha com reticências + `title`; valor 24 px/600 (`clamp(18px, 8cqi, 28px)`); detalhe 12 px secundário;
   variante `tone` para atraso/alerta (selo). Substituir `tl-/st-/sp-/pd-/md-/in-kpi` e os cartões das fichas. **M**.
2. Grade de KPI: `grid-template-columns: repeat(auto-fit, minmax(180px, 1fr))` (+ 2 colunas fixas abaixo de 576 px; 6
   KPIs → 3×2 em 1440). **P**.
3. Abas: trocar `nav-tabs` por **`.nav-underline`** (Bootstrap 5.3: `--bs-nav-underline-gap`,
   `--bs-nav-underline-border-width: 2px`, `--bs-nav-underline-link-active-color`), inativas em `--bs-secondary-color`,
   sem ícone; no celular `flex-nowrap overflow-auto` com degradê nas bordas ou `select`. **P/M**.
4. Tabelas fora do `app-data-table` → usar o componente; esconder paginação quando `total ≤ pageSize`; ações de linha
   em `⋯` (dropdown) quando > 2, ou ícone + tooltip + `aria-label` (já existe [S]). **M**.
5. Pendências: trocar `border-left` colorido por ícone/selo de tom (o contador já é colorido). **P**.
6. Trilha: label do registro em todas as fichas (`BreadcrumbService.setCurrentLabel`); evitar nível repetido. **P**.

### 3.6 Celular

- `app-data-table` com modo **lista empilhada** abaixo de 576 px (coluna primária como título, 2–3 colunas como
  metadados, selo à direita, linha inteira clicável). Uma mudança no componente compartilhado beneficia todas as
  listas. **G** (ou **M** se a tabela já declara coluna primária).
- Filtros: abaixo de 768 px, recolher em botão "Filtros (n)" que abre `NgbOffcanvas` (já usado no menu). **M**.
- Painéis no celular: KPIs antes dos filtros; filtros recolhidos; abas roláveis. **M**.
- Cabeçalho < 600 px: tema e chat para o menu do usuário; manter menu, busca, pendências, ajuda, avatar. **P**.
- Início no celular: alerta de MFA compacto (1 linha + ação) e bloco "Operação" antes das pendências para o perfil
  gestor [S] confirmar com o PO. **P**.

### 3.7 Login

Duas colunas ≥ 992 px: painel esquerdo navy (`--app-topbar-bg`) com logo, nome do produto 600 e uma frase de valor;
direita com o cartão 400 px (título 20/600). Remover o borrão radial. No celular, só o cartão com logo maior. **P/M**.

### 3.8 Iconografia

- Preferir glifos de **contorno leve** do Bootstrap Icons no menu (ex.: Produtividade `people` → `speedometer2`;
  Contestações `hammer` → `flag`; Devedores `person-bounding-box` → `person`); regra: menu só contorno, `-fill` só
  para estado ativo. **P**.
- Ícones únicos por item do menu (rever os 16 repetidos). **P**.

### 3.9 Números compactos

`amount.ts:5` → `maximumSignificantDigits: 3` (ou 2 casas quando < 10): "R$ 1,01 mi", "R$ 5,85 mil". O valor exato
já está no `title`. **P**.

---

## 4. Plano priorizado

| Prioridade | Item | Sev. | Esforço | Depende do PO? |
|---|---|---|---|---|
| 1 | Tipografia: peso base 400 (ou 380) e escala de pesos coerente (3.1) | Alta | P + M | **Sim** (reverte "traço fino") |
| 2 | `tabular-nums` só em números (T5) | Média | P | Não |
| 3 | Hierarquia de botões e "Mais ações" nas fichas (3.4) | Alta | M | Sim (padrão de tela) |
| 4 | Lista empilhada no celular no `app-data-table` (3.6) | Alta | G | Sim |
| 5 | Filtros recolhíveis + KPIs primeiro no celular (3.6) | Alta | M | Não |
| 6 | `app-kpi-card` compartilhado + grade `auto-fit` (3.5.1–2) | Média | M | Não |
| 7 | Raios 6/8/12/16 e sombra em 2 camadas (3.2) | Média | P | Sim (forma é decisão do PO) |
| 8 | Abas `.nav-underline` (3.5.3) | Média | P/M | Não |
| 9 | Números compactos com 3 dígitos significativos (3.9) | Média | P | Não |
| 10 | Cinzas frios nos gráficos + página `#f3f5f8` (3.3) | Baixa | P | Não |
| 11 | Ícones: contorno no menu, sem repetição (3.8) | Média/Baixa | P | Não |
| 12 | Login com presença de marca (3.7) | Média | P/M | Sim |
| 13 | Limpeza: fallbacks `var(--app-*, x)`, comentários/docs de Material, px fora da escala, trilha, paginação de 1 página | Baixa | P | Não |

Cada item que mexe em variável do Bootstrap entra na tabela da `ux.md` §11.3 e, se aprovado, nas decisões.

## 5. O que preservar

- Parametrização 100 % por variáveis documentadas + `bootstrap:check` + `_index.scss` seletivo.
- Tokens semânticos `--app-<tom>-bg/fg/border` com contraste ≥ 7:1 e `--app-control-border` ≥ 3:1 (WCAG 1.4.11) — raro
  ver isso feito corretamente.
- Tema escuro nativo com neutros frios; densidade compacta só com ponteiro fino.
- `app-status-chip` (ícone + texto), estados vazios, "Ver tabela" + resumo textual nos gráficos, paleta de gráfico
  validada para daltonismo.
- Barra navy institucional com contexto (assessoria + escopo) — forte e útil.
- Menu lateral com seções em caixa alta 11 px e item ativo em `primary-bg-subtle` — limpo, no padrão dos SaaS de
  referência.
- Quase nenhuma cor/raio fora de token nas telas.
