# Estudo 3: facilidade de uso e entendimento operacional, tela a tela

**Data:** 06/11/2026 · **Ambiente:** sandbox `http://localhost:58080` (dados fictícios), assessoria `boreal`.
**Perfis usados:** operador (Juliana), supervisor (André), financeiro (Tereza), analista (Priscila), jurídico (Vanessa) e qualidade (Simone).
**Viewports:** 1366×768 (padrão das capturas) e 1440×900 (estação e início).
**Capturas:** ficaram só na máquina local (não versionadas); cito apenas o nome do arquivo (`boreal-<perfil>-<tela>.png`).
**Referência de contrato:** `docs/tecnico/desenho/ux.md` §1–§6, §8.3 e §10.

Legenda: **[F]** é fato verificado (captura ou código). **[S]** é suposição ou inferência que precisa de validação com usuário ou dado de uso.
Notas de 1 a 5 em **C**lareza, **H**ierarquia e **E**ficiência. Severidade: Alta, Média ou Baixa. Esforço: P (horas a 1 dia), M (2 a 5 dias), G (mais de uma semana).

---

## Sumário executivo

A fundação está boa: status com ícone e texto, documentos mascarados com revelação registrada, estados vazios que orientam, atalhos visíveis, trilha de navegação e confirmação forte. Os problemas de uso ficam em quatro frentes:

1. **Primeira dobra das telas de alto volume a 1366×768.** Na estação, o formulário de registro fica abaixo da dobra e só 3 das 7 abas do caso aparecem. O simulador exige três etapas com rolagem até chegar ao "Propor".
2. **Menu e início montados por permissão de leitura, não por tarefa.** O operador vê 31 itens no menu (entre eles os 5 do Score, Políticas, Índices, Credores, Carteiras, Formulários de monitoria e Lançamentos de comissão). O "Acesso rápido" repete o menu inteiro: são cerca de 55 blocos para o supervisor.
3. **Localizar um registro.** A busca global só leva a telas. Casos, Propostas e Acordos não filtram por devedor. Nenhuma lista ordena por coluna: o código não tem nenhum uso de `sortable`.
4. **Ruído concorrente.** Banner de MFA, item de MFA no checklist, convite de tour que repete outro item do checklist, cartão de score vazio no caso e tom amarelo em quase tudo.

---

## Padrões transversais (resolver uma vez em `shared`/`core`)

| # | Padrão | Evidência | Recomendação | Sev. | Esf. |
|---|---|---|---|---|---|
| T1 | **Menu por permissão de leitura**: seções inteiras de configuração aparecem para quem só precisa ler um dado em outra tela | [F] `core/layout/navigation.ts:121-129`: os 5 itens do Score exigem só `SCORING_VIEW`, que o operador tem para ver o cartão do caso (`scoring/case/case-score-card.ts:213`). Menu do operador com 31 itens (captura `boreal-operador-inicio.png`): Score ×5, Políticas de cobrança, Índices de correção, Credores, Carteiras, Instruções de pagamento, Lançamentos de comissão, Ocorrências de conduta e Formulários de monitoria (`navigation.ts`: monitoria aceita `PERFORMANCE_VIEW_OWN`) | Separar "ver o dado embutido" de "abrir a área": criar `SCORING_ANALYZE` (ou usar `SCORING_MANAGE`) para os itens do menu e manter `SCORING_VIEW` só para o cartão. Mesma lógica para `POLICY_VIEW` (operador consulta a política pelo simulador), `ORG_*_VIEW` (Credores/Carteiras) e os itens de monitoria/lançamentos. Alternativa mais barata: `NavItem.audience` para tirar do menu, sem tirar a permissão, itens de "consulta embutida" | Alta | M |
| T2 | **"Acesso rápido" é cópia do menu** | [F] `features/dashboard/dashboard.ts:39-45` usa `NAVIGATION.flatMap(...)` com todos os itens permitidos. Operador: 30 blocos. Supervisor: cerca de 55 (menu com 56 itens, captura `boreal-supervisor-inicio.png`) | Trocar por "Atalhos do perfil" com 4 a 6 destinos curados por papel (ou os mais usados, já que existe estado de interface no servidor, ux.md §9). Se nada for curado, remover o bloco | Alta | P |
| T3 | **Abas repetem itens do menu** | [F] Painel operacional: 6 abas iguais aos 6 itens de "Painéis" (`boreal-supervisor-paineis.png`). Monitoria: abas Avaliações/Ocorrências/Formulários iguais a 3 itens do menu (`boreal-qualidade-_remuneracao_qualidade_avaliacoes.png`). Produtividade do operador: barra com **uma** aba só (`boreal-operador-_paineis_produtividade.png`) | Um único caminho por destino: ou o menu mostra só "Painéis" e as abas navegam, ou o menu mostra os itens e a tela não tem abas. Esconder a barra de abas quando houver uma aba só | Média | P |
| T4 | **Convite "Primeira vez por aqui?" em todas as telas** até ser dispensado, cobrindo o canto inferior direito (290×180 px) | [F] Presente em início, pendências, estação, casos, conversas, simulador, devedores e agenda. Cobre a coluna "Próxima ação" e os chevrons de Casos (`boreal-operador_cobranca_casos.png`) e a lista de Devedores. Repete o item "Conhecer a navegação" do checklist (`boreal-operador-inicio-full.png`). ux.md §8.3.5 diz "cada convite aparece no máximo uma vez" | Mostrar o convite de boas-vindas **só no Início** (onde o checklist já o cobre; aí, nem mostrar) ou registrar `DISMISSED` implícito depois da 1ª exibição. Nas telas de trabalho, nunca sobrepor conteúdo: usar um ponto discreto no botão de ajuda | Média | P |
| T5 | **Nenhuma lista ordena por coluna** | [F] `grep sortable features/` = 0 usos, embora `shared/components/data-table` suporte `sortable`/`sortChange` (`data-table.ts:19-20,73-77`) | Ligar a ordenação nas colunas de valor, atraso, data e situação de Casos, Devedores, Títulos, Propostas, Acordos, Pagamentos e Importações (o backend precisa aceitar `sort`) | Alta | M |
| T6 | **Localizar devedor ou caso**: a busca global não procura registros, e listas centrais não filtram por devedor | [F] `core/layout/command-palette/command-palette.service.ts` só lista comandos e telas. Casos (`collection/cases/case-list.ts:70-115`): credor, situação, fila, atendimento, responsável e elegibilidade, **sem devedor/CPF**. Propostas: credor, situação e autor. Acordos (`negotiation/agreements/agreement-list.ts:55-60`): credor e situação. Pagamentos tem "Devedor" (inconsistente) | Na paleta Ctrl+K, um grupo "Devedores" com a busca por nome/CPF completo/telefone, que já existe no `DebtorLookup`, abrindo a ficha ou o caso. Filtro "Devedor (nome ou CPF completo)" em Casos, Propostas e Acordos com o mesmo `app-entity-picker` | Alta | M |
| T7 | **Paginação em lista vazia ou de uma página** | [F] Agenda vazia mostra "Itens por página 20 · 0 de 0 · «‹›»" (`boreal-operador_cobranca_agenda.png`). Igual em Conciliação, Divergências e Monitoria | Em `data-table.html:76`, esconder o rodapé quando `total <= pageSize` (manter só o "1–5 de 5" discreto) | Baixa | P |
| T8 | **Rótulo de indicador + ⓘ quebrando linha** e cartões desalinhados | [F] "TOTAL A PAGAR ⓘ", "DÍVIDA ATUALIZADA ⓘ" (aprovação), "VALOR LIBERADO JUDICIALMENTE ⓘ" (jurídico), "TOTAL DO ACORDO ⓘ" (acordo) e "RECUPERADO EM 30 DIAS" (início) com o ícone sozinho na 2ª linha | No componente de KPI, ⓘ absoluto no canto superior direito (como já está em "Valor da carteira"), rótulo com `text-wrap: balance` e no máximo 2 linhas | Baixa | P |
| T9 | **Valor compacto perde a informação** | [F] Início e painel: "Valor da carteira R$ 1 mi" e "Saldo vencido R$ 1 mi" lado a lado, idênticos (`boreal-supervisor-inicio.png`) | Compactar só acima de 10 mi, ou com 1 casa ("R$ 1,03 mi"). O valor exato já é acessível (ux.md §5), mas precisa ficar visível no hover ou no subtítulo | Baixa | P |
| T10 | **Tabelas cortadas sem pista de rolagem** em 1366 | [F] Títulos na estação ("Valor or…", sem coluna Saldo, `boreal-operador-estacao-caso.png`), Jurídico ("Liberado judicia…", `boreal-juridico-_juridico.png`), cronograma do acordo ("Em revisão: R$ 291…", `boreal-financeiro-pagamento-aprovacao-detalhe.png`) | Para tabelas de painel estreito, definir colunas prioritárias e esconder as secundárias com `@container` (não só rolar). Onde a rolagem continuar, usar sombra na borda (gradiente) indicando mais conteúdo | Média | M |
| T11 | **Excesso de tom "warning" (amarelo)** | [F] `shared/status/status.ts:185`: `PROMISE` é `warning` (estado positivo do ponto de vista do operador). Na fila da estação, o selo de atraso e o status ficam amarelos lado a lado. Alertas de promessa, MFA, aguardando aprovação e janela de contato também em amarelo | Reservar `warning` para "exige atenção". Promessa vigente em `info`/`success`, e só `warning` se vencendo hoje. Revisar o mapa de tons em `status.ts` | Baixa | P |
| T12 | **Enum ou código vazando para a tela** | [F] "alçada JUNIOR" no histórico da proposta (`boreal-supervisor-aprovacao-detalhe-rolado.png`), "PADRAO v1" no lote de importação (`boreal-analista-lote.png`), "Política v1 · motor v1" no simulador | Mapear para rótulos ("Negociador júnior", "Layout padrão") nos dicionários de `shared/status` ou equivalentes | Baixa | P |
| T13 | **Ação primária e "voltar" fora do padrão** | [F] ux.md §2.1 pede "botão preenchido à direita do cabeçalho". Importação põe 4 botões numa linha abaixo do subtítulo, à esquerda (`boreal-analista-importacao.png`). Ficha do devedor e lote têm "← Devedores"/"← Importações" à direita, repetindo a trilha. O Acordo não tem ação primária (2 links) | Padronizar `app-page-header` com slot `primary` (1 botão) e `secondary` (menu "Mais"). Remover os "← Voltar" quando a trilha já existe | Baixa | P |
| T14 | **Linhas de lista com baixa densidade** | [F] Propostas: 4 linhas por registro (data, hora, "válida até", data), 5 registros visíveis a 1366 (`boreal-operador-propostas.png`) | Data e hora em uma linha, validade como selo só quando vence hoje ou amanhã. Mirar 8 a 10 linhas visíveis na densidade confortável | Média | P |

---

## Telas

### 1. Início: operador (`/`) · C 3 · H 2 · E 2
Capturas: `boreal-operador_.png`, `boreal-operador-inicio-full.png`, `boreal-operador-inicio-1440.png`.

**Atritos**
- [F] Cinco blocos concorrem na 1ª dobra: banner MFA (`dashboard.html:8-16`, sem fechar), "Suas pendências" (2 cartões), "Acesso rápido" (30 blocos, cópia do menu: T2), checklist "Comece a negociar" e convite de tour (T4). A 1366×768, o convite cobre o checklist.
- [F] **Redundância tripla**: MFA aparece no banner e como 1º item do checklist. "Conhecer a navegação" aparece no checklist e no convite flutuante.
- [F] Falta o essencial do dia do operador: casos na minha fila, retornos de hoje ou atrasados, promessas vencendo hoje e um botão "Próximo caso". As pendências trazem conversas e e-mails do credor, mas não a carteira dele. [S] A pendência "retornos vencidos" existe (`pending-work-panel.spec.ts:47`), mas não havia dado para hoje.
- [F] Os KPIs só aparecem com `DASHBOARD_VIEW`/`REPORT_VIEW` (`dashboard.ts:49-52`), então o operador não vê nenhum número próprio no início, embora tenha "Meu desempenho".

**Recomendação.** Reorganizar o início do operador em "Seu dia": linha 1 com o CTA **Próximo caso (Alt N)**, contadores (casos atribuídos, retornos hoje ou atrasados, promessas que vencem hoje, conversas na fila) e 2 a 3 KPIs próprios (contatos efetivos, acordos, recuperado no mês); linha 2 com pendências; depois, sem "Acesso rápido". Tornar o banner de MFA dispensável por sessão e tirá-lo quando o checklist estiver visível (o item do checklist já cobre). Checklist recolhido numa faixa estreita depois de 3 itens concluídos. **Alta · M.**

### 2. Início: supervisor e financeiro · C 3 · H 3 · E 2
Capturas: `boreal-supervisor-inicio.png`, `boreal-financeiro-inicio.png`.
- [F] Pendências bem resolvidas: 6 cartões com selo "2 vencidos"/"1 vence hoje", barra de severidade e "Mais 1 na lista completa".
- [F] "Operação" com "R$ 1 mi" repetido (T9). "Recuperado em 30 dias: Taxa —" sem explicação visível.
- [F] Financeiro: cartão "Repasses aos credores" todo zerado (R$ 0,00 / R$ 0,00 / 0) ocupando uma faixa inteira. [S] Com dado real, faz sentido; zerado, poderia virar uma linha "Nada a repassar".
- [F] Abaixo da dobra, "Acesso rápido" com dezenas de blocos (T2).

**Recomendação.** Igual ao item 1 (sem Acesso rápido), KPIs com 1 casa decimal, estado zerado compacto. **Média · P.**

### 3. Menu lateral (todas as telas) · C 3 · H 2 · E 2
- [F] Operador com 31 itens, supervisor com 56 e financeiro com 41 (extraído do DOM). Detalhe em T1.
- [F] "Contestações" aparece duas vezes para o supervisor (Painéis e Gestão da cobrança), com o mesmo ícone de martelo.
- [F] Ícone `headset` repetido em Estação, "Operadores e ramais" e "Pedidos do portal" (`navigation.ts`).
- [F] Monitoria de qualidade e Ocorrências de conduta ficam dentro de "Remuneração variável" (trilha "Remuneração variável › Monitoria de qualidade"). Para a analista de qualidade, a seção errada.
- [F] Operador: "Produtividade" (Painéis) e "Desempenho" (Remuneração) são duas portas para o próprio desempenho.

**Recomendação.** T1 + seção "Qualidade" própria + ícones únicos por destino + renomear "Contestações" do painel para "Painel de contestações". Para o operador, alvo de cerca de 12 itens: Início, Pendências, Estação, Agenda, Casos, Devedores, Caixa de entrada, Simulador, Propostas, Acordos, Meu desempenho e Ajuda. **Alta · M.**

### 4. Minhas pendências (`/pendencias`) · C 4 · H 4 · E 3
Captura: `boreal-operador_pendencias.png`.
- [F] Bom: agrupado por área, contador, "2 vencidos" em vermelho, "atualizado às 13:20" e cartão com link para a tela de ação filtrada.
- [F] Cartões de largura fixa (cerca de 345 px) em uma coluna, deixando 2/3 da tela vazios a 1366. O texto auxiliar ocupa 3 a 4 linhas.
- **Recomendação.** Grade de 2 a 3 colunas como no início, ou lista densa (uma linha por tipo: contador · título · mais antigo · ação). **Baixa · P.**

### 5. Estação de cobrança, sem caso aberto (`/cobranca/fila`) · C 4 · H 4 · E 4
Captura: `boreal-operador_cobranca_fila.png`.
- [F] Bom: "Próximo caso" primário com atalho visível, estado vazio com o mesmo botão, fila "Meus casos (6)" com saldo, atraso, status e próxima ação.
- [F] O link "Agenda" fica sozinho numa 2ª linha da barra (cerca de 45 px verticais perdidos), a 1366 e a 1440 (`collection-station.html:37-40`).
- [S] A fila "Meus casos" não diz a ordem (prioridade? próxima ação?). Um rótulo "ordenado por próxima ação" ajudaria.

### 6. Estação de cobrança, caso aberto · C 3 · H 2 · E 2 (tela mais crítica)
Capturas: `boreal-operador-estacao-caso.png`, `-estacao-acoes-rolado.png`, `-estacao-aba-historico.png`, `-estacao-1440-aba4.png`, `-estacao-1440-aba6.png`.

**Atritos**
- [F] **A ação principal (registrar interação) fica abaixo da dobra a 1366×768**: na coluna direita vêm antes "Posso contatar agora?" (cerca de 190 px, 4 canais todos "Permitido"), 5 botões em 3 linhas e o formulário. Tabulação, observação e o botão "Registrar interação" só aparecem com rolagem. A 1440×900, o botão também fica fora.
- [F] **Abas do caso cortadas**: só "Títulos", "Contatos", "Histórico" e metade de "Agenda e…" aparecem. Contestações, Negociação e Conversas (Alt 5–7) ficam fora da vista, sem seta ou sombra (`case-sheet-panel.scss:70-74`, `overflow-x: auto`). Com Alt 6, a aba ativa continua invisível (`-estacao-1440-aba6.png`).
- [F] **Área útil do centro**: cabeçalho, 4 KPIs (o 4º quebra para uma 2ª linha a 1366), cartão de Score e alerta de promessa deixam cerca de 120 px para a aba. A tabela de títulos mostra 2 linhas e corta "Valor original" e "Saldo".
- [F] **Cartão de Score vazio** ("Sem score calculado… Recalcule") ocupa cerca de 70 px nobres e oferece "Recalcular" ao operador (`case-score-card.ts:214`: `canRecalculate = SCORING_VIEW && CASE_WORK`).
- [F] "Contatos efetivos 1 / 1": a notação "x / y" não diz que y é o total de interações.
- [F] Status "Promessa" em amarelo (T11), junto com o atraso amarelo.

**Recomendação**
1. Coluna direita com o formulário **no topo**, com Canal, Contato e Tabulação sempre visíveis e o botão fixo no rodapé da coluna (`position: sticky; bottom: 0`). "Posso contatar agora?" condensado em uma linha de chips (Tel ✓ · WA ✓ · SMS ✓ · E-mail ✓), expandindo só quando algum canal estiver bloqueado.
2. Ações rápidas numa barra de ícones com rótulo curto em uma linha (Ligar · Mensagem · Agendar · Simular · ⋯), com Contestação dentro do ⋯.
3. Abas com overflow visível: "Mais ▾" para as que não cabem, ou rótulos curtos ("Agenda", "Negociação"), sem `<kbd>` dentro do rótulo (mover o atalho para o tooltip). Rolar a aba ativa para a vista.
4. KPIs numa faixa única de 4 colunas compactas. Cartão de Score **oculto quando não houver score** para o operador; com score, uma linha "Propensão: alta (0,72) ⓘ" junto dos KPIs.
5. Na tabela de títulos da estação, mostrar só Título · Vencimento · Atraso · Saldo (valor original no hover).

**Alta · M.**

### 7. Caso por rota direta (`/cobranca/casos/:id`) · C 3 · H 3 · E 3
Captura: `boreal-operador-caso-detalhe-full.png`.
- [F] Reaproveita a estação inteira (bom para consistência), mas a trilha diz "Cobrança › Casos › Comércio de Alimentos Ipê Ltda" enquanto o título da página diz "Estação de cobrança" e o menu destaca "Casos". Fica a dúvida sobre "onde estou".
- [F] Caso "Na fila" (de ninguém) mostra todo o formulário de registro. [S] Pode ser intencional; vale ter um aviso "Este caso está na fila: ao registrar, você assume o atendimento".
- **Recomendação.** Título "Caso · <devedor>" quando aberto por Casos, mantendo o leiaute. **Baixa · P.**

### 8. Casos (`/cobranca/casos`) · C 4 · H 3 · E 2
Captura: `boreal-operador_cobranca_casos.png`.
- [F] 6 filtros em 2 linhas, **sem busca por devedor/CPF** (T6) e **sem ordenação** por saldo ou atraso (T5).
- [F] Bom: devedor como link, credor/carteira em subtítulo, saldo alinhado à direita e atraso com selo.
- [F] A coluna "Próxima ação" mostra "—" em quase tudo e fica coberta pelo convite (T4).
- **Recomendação.** Campo "Devedor (nome ou CPF)" como 1º filtro. Ordenar por atraso e saldo. Filtros secundários (Atendimento, Elegibilidade) recolhidos em "Mais filtros". **Alta · M** (junto com T5/T6).

### 9. Agenda de retornos (`/cobranca/agenda`) · C 4 · H 4 · E 4
Captura: `boreal-operador_cobranca_agenda.png`.
- [F] Bom: segmentado Hoje/7 dias/Atrasados/Período, estado vazio explicando como agendar e atalho para a estação.
- [F] Paginação no vazio (T7). [S] "Em aberto" como situação padrão está correto.
- [F] Inconsistência: a estação mostra "Próxima ação 07/11 14:00" no caso de Bruno, e a agenda "Hoje" está vazia, o que está correto. [S] Ao abrir em um dia sem itens, sugerir "Próximos 7 dias" no estado vazio ("Nada hoje. Ver próximos 7 dias").

### 10. Devedores (`/devedores`) · C 4 · H 4 · E 3
Captura: `boreal-operador_devedores.png`.
- [F] Bom: busca por nome/CPF em 1º lugar, CPF mascarado com rótulo "CPF".
- [F] As colunas "Tipo" e "Cadastrado em" (todos 06/11/2026) têm baixo valor para a operação. Faltam **saldo em cobrança** e **maior atraso**, que a ficha calcula.
- **Recomendação.** Trocar Tipo/Cadastrado por Saldo e Atraso (ordenáveis). **Média · M** (depende do backend devolver o agregado).

### 11. Ficha do devedor (`/devedores/:id`) · C 4 · H 3 · E 2
Captura: `boreal-operador-ficha-devedor-full.png`.
- [F] Bom: 4 KPIs, dados cadastrais e abas (Títulos, Contatos, Contratos de origem, Garantias, Razão, Titularidade, Restrições).
- [F] **Beco sem saída operacional**: as ações são "← Devedores", "Títulos" e "Enviar link do portal" (`debtor-detail.html:21-50`). Não há "Abrir caso/estação", "Simular" nem "Conversas", e não existe aba de **histórico de interações**. Quem chega pela lista de devedores (ex.: devedor ligou) precisa voltar e procurar o caso em Casos, que não tem busca por devedor (T6).
- [F] O cartão "Dados cadastrais" ocupa uma faixa inteira para 4 linhas curtas.
- **Recomendação.** Ação primária "Abrir caso" (ou seletor se houver mais de um credor) e secundárias "Simular" e "Conversas". Aba "Histórico" com a mesma linha do tempo da estação. Dados cadastrais em coluna lateral (padrão das fichas de proposta e acordo). **Alta · M.**

### 12. Caixa de entrada (`/conversas`) · C 3 · H 3 · E 2
Capturas: `boreal-operador_conversas.png`, `boreal-operador-caixa-conversa.png`.
- [F] **Abre na aba "Minhas", vazia, enquanto "Fila" tem 2 conversas vencidas** (cabeçalho: "2 além da meta de resposta"). Padrão `'mine'` em `communication/inbox/inbox.ts:43`. O estado vazio não aponta para a Fila.
- [F] Os filtros (Canal e "Aguardando resposta") ocupam cerca de 130 px da coluna da lista, que tem só 360 px de largura.
- [F] Bom: "Ao vivo", meta de 1ª resposta com ⓘ, "Assumir" primário, aviso de janela de 24 h do WhatsApp com template obrigatório.
- **Recomendação.** Abrir na aba com trabalho (Minhas se tiver, senão Fila), ou no estado vazio de "Minhas" mostrar "2 conversas aguardando na fila da equipe → Ver fila". Filtros em uma linha (select compacto + switch) ou dentro de um botão "Filtrar". O selo "2 além da meta" deve ser clicável e levar à Fila filtrada. **Média · P.**

### 13. Simulador de negociação (`/negociacao/simulador`) · C 3 · H 2 · E 2
Capturas: `boreal-operador-simulador-a-partir-do-caso.png`, `-simulador-resultado.png`, `-rolado.png`, `-rolado2.png`.

**Atritos**
- [F] **Aberto pela estação com credor, devedor e caso preenchidos, não simula sozinho**: o operador clica "Simular" duas vezes (na estação e no simulador). O `effect` em `simulator.ts:197-208` só preenche os campos.
- [F] **Caminho até propor**: Simular → escolher oferta ("Ajustar estas condições" copia para o formulário, mas não rola nem foca: `simulator.ts:287-295`) → rolar até "Validar condições" → Validar → rolar → "Propor"/"Pedir aprovação" (`simulator.html:382,418`) → confirmação. São cerca de 6 cliques e 2 a 3 rolagens. Não existe "Propor esta oferta" direto no cartão da oferta, que já foi calculada dentro da alçada.
- [F] Depois de simular, a coluna esquerda (formulário de contexto e "Sua alçada") continua com 380 px. Os cartões de oferta ficam espremidos ("Desconto R$ 0,00 (0%)" e "11 × R$ 230,61" quebram linha).
- [F] "Validar condições" com **6 campos de % por componente** (Desc. Principal, Correção, Juros, Multa, Encargos, Honorários) sempre visíveis, mais "Pagamentos" (= entrada + parcelas, com hint "Inclui a entrada"), enquanto a oferta fala "Entrada + 11 parcelas". [S] Risco real de digitar 11 em vez de 12.
- [F] Jargão: "Fatia do desconto máximo 70%", "nível 0 da escada de concessões", "Política v1 · motor v1".
- [F] Bom: "Dívida atualizada" com composição e memória sob demanda, selo "Dentro da alçada", "Nível 0 de 2 permitido pela sua alçada", motivo obrigatório para subir na escada e botão que muda para "Pedir aprovação" (ux.md §10).

**Recomendação**
1. Com `caso` na URL, simular automaticamente ao abrir.
2. Botão primário **"Propor esta oferta"** em cada cartão dentro da alçada, abrindo direto o `ConfirmDialog`. A validação roda por trás, já que a oferta veio do motor.
3. "Ajustar estas condições" abre o ajuste **em painel lateral ou diálogo** com foco no 1º campo. Os descontos por componente ficam recolhidos em "Desconto por componente (avançado)", com um campo "Desconto total %" ou "Valor final" como entrada principal.
4. Trocar "Pagamentos" por "Entrada (sim/não) + Nº de parcelas", espelhando a oferta.
5. Depois de simular, recolher o contexto num resumo de uma linha ("Coop Sertão · Isabela N. L. · 2 títulos · hoje ✎") e "Sua alçada" num ⓘ, dando a largura inteira às ofertas.

**Alta · M/G.**

### 14. Propostas (`/negociacao/propostas`) · C 3 · H 3 · E 2
Captura: `boreal-operador-propostas.png`.
- [F] 4 linhas por registro (T14), "Criada em" é o link (o nome do devedor seria o alvo natural), "válida até 06/11/2026" repetido em tudo sem destaque quando vence hoje.
- [F] Sem filtro por devedor (T6) e sem ordenação (T5).
- [F] Bom: motivo de "Aguardando aprovação" na própria linha ("Desconto acima da alçada · Nível da escada acima da alçada").
- **Recomendação.** Devedor como link, data e hora em uma linha, selo "vence hoje" e filtro por devedor. **Média · P.**

### 15. Aprovações de propostas: lista e detalhe (supervisor) · C 4 · H 4 · E 3
Capturas: `boreal-supervisor-_negociacao_aprovacoes.png`, `-aprovacao-detalhe.png`, `-aprovacao-detalhe-rolado.png`.
- [F] Bom: "Aprovar" (primário) e "Reprovar" no topo, "Por que precisa de aprovação" em lista, justificativa visível, memória sob demanda e "Abrir caso".
- [F] Falta **quantificar o excesso**: o detalhe diz "Desconto acima da alçada", mas não mostra "pedido 3,6% × alçada do negociador 2% × sua alçada 10%". "Uso do desconto máximo 13,72%" é opaco.
- [F] A lista não mostra % de desconto nem prazo. A pendência diz "1 vence hoje", mas a linha só mostra "válida até 06/11/2026".
- [F] "alçada JUNIOR" no histórico (T12).
- [S] Falta contexto de risco para decidir: histórico de acordos quebrados do devedor e score. ux.md §1 pede "aprovação com resumo do impacto".
- **Recomendação.** Bloco "Impacto": desconto pedido (R$ e %) contra o limite de quem negociou e o seu, nível pedido contra o permitido e acordos anteriores do devedor (quebrados ou cumpridos). Na lista: colunas "Desconto %" e "Vence", e ordenação por vencimento. **Média · M.**

### 16. Acordo, detalhe · C 4 · H 3 · E 3
Captura: `boreal-financeiro-pagamento-aprovacao-detalhe.png` (o link "destino" da aprovação de pagamentos abre o acordo).
- [F] Bom: banner que explica a suspensão da quebra enquanto o pagamento é revisado, "0 de 2 parcelas pagas (0%)" e regras do acordo à direita.
- [F] O cronograma corta a coluna de situação ("Em revisão: R$ 291…"): T10.
- [F] Sem ação primária: "Baixar termo" e "Informar pagamento" como links (T13).
- **Recomendação.** "Informar pagamento" como primário (para quem tem permissão). Selo de revisão abaixo do status, não numa coluna extra. **Baixa · P.**

### 17. Pagamentos (`/pagamentos`) · C 4 · H 3 · E 3
Captura: `boreal-financeiro-_pagamentos.png`.
- [F] 7 filtros em 2 linhas mais checkbox e "Limpar filtros" numa 3ª linha. A tabela começa em cerca de 390 px, com 5 linhas visíveis.
- [F] Bom: valor alinhado à direita, meio + origem e status com ícone. Estornado em vermelho.
- [F] [S] Estorno sem sinal explícito "−" no valor (ux.md §3, 1.4.1 pede sinais explícitos).
- **Recomendação.** Filtros principais (Devedor, Situação, Período) em linha e o resto em "Mais filtros" com contador de filtros ativos. **Média · P.**

### 18. Aprovação de pagamentos (`/pagamentos/aprovacoes`) · C 4 · H 4 · E 4
Captura: `boreal-financeiro-_pagamentos_aprovacoes.png`.
- [F] Bom: aviso de segregação ("quem informa não aprova"), destino solicitado explícito, anexo indicado e "Revisar" na linha.
- [F] Inconsistência com Aprovações de propostas: lá é chevron e link na data, aqui é botão "Revisar". O link azul da linha leva ao **acordo**, não à revisão.
- **Recomendação.** Padronizar aprovações (propostas, pagamentos, correções de valor) com o mesmo componente: botão "Revisar" na linha e link do devedor indo para a ficha. **Baixa · P.**

### 19. Conciliação e Divergências · C 4 · H 4 · E 3
Capturas: `boreal-financeiro-_pagamentos_conciliacao.png`, `-_pagamentos_divergencias.png`.
- [F] Bom: estado vazio com orientação ("Exporte o extrato do banco em CSV…") e ação. Divergências com filtros por tipo em chips.
- [F] Os chips de tipo não mostram **contagem** ("Valor diferente (3)"). O financeiro não sabe onde está o volume antes de clicar.
- [S] Sem extrato no sandbox, o fluxo de conciliar linha a linha (`conciliacao/:id`) não foi avaliado.
- **Recomendação.** Contadores nos chips e o tipo com mais itens pré-selecionado. **Baixa · P.**

### 20. Importação de carteiras: lista, lote e nova · C 4 · H 3 · E 4
Capturas: `boreal-analista-importacao.png`, `-lote.png`, `-importacao-nova.png`.
- [F] Bom: assistente com 6 etapas (Arquivo → Formato → Linhas → Totais → Alertas → Importar), explicação da ambiguidade de decimal, link para PDF inteligente, lote com 9 indicadores e SHA-256.
- [F] Lista: 4 botões no topo, à esquerda (T13). "Modelo do layout padrão" e "Layouts" competem com "Nova importação".
- [F] O nome do arquivo (`api-2026-11-06T14-47-20.678243962Z.json`) é o link e o título do lote. Para lotes via API, o nome técnico não identifica nada. [S] "Credor · Carteira · data" seria um título melhor.
- [F] Lote: rótulos compostos "Devedores novos / atualizados 14 / 0" exigem leitura dupla.
- **Recomendação.** Primário "Nova importação" à direita e o resto num menu "Mais". Título do lote pela carteira. Indicadores separados ou com rótulo inline ("14 novos · 0 atualizados"). **Baixa · P.**
- [S] A **conferência** (`/importacao/conferencias/:id`) não tinha registro no sandbox e não foi avaliada.

### 21. Painel operacional (`/paineis`) · C 4 · H 3 · E 3
Captura: `boreal-supervisor-paineis.png`.
- [F] Abas repetem o menu (T3). "R$ 1 mi" ×3 (T9). Bom: "Dados até…", "Atualizar agora", "Ver tabela" acessível ao lado do gráfico e frase-resumo do gráfico ("Maior carteira: Coop Sertão, R$ 497.229,37").
- **Recomendação.** T3 + T9. **Média · P.**

### 22. Produtividade, visão do operador (`/paineis/produtividade`) · C 2 · H 3 · E 3
Captura: `boreal-operador-_paineis_produtividade.png`.
- [F] Menu "Produtividade", trilha "Produtividade", título "Meu desempenho", **uma** aba "Produtividade", banner "somente você" e tabela "Por pessoa" com uma linha (a própria).
- [F] Jargão: "TMA", "Acordos por hora logada — (6 acordos em 0,0 h)".
- **Recomendação.** Para quem só tem `PERFORMANCE_VIEW_OWN`: esconder a aba única, o banner e a tabela "Por pessoa". Nome igual em menu, trilha e título ("Meu desempenho"). "TMA" por extenso. Quando não houver horas logadas, ocultar "Acordos/hora" em vez de "—". **Média · P.**

### 23. Score de propensão, visão do operador (`/score/*`) · C 1 · H 3 · E 1
Captura: `boreal-operador-_score_modelos.png`.
- [F] Operador acessa "Modelos do score" com "AUC de validação", "campeão/desafiante", "scorecard" e "base legal do score". Conteúdo de analista de dados, sem tarefa possível para o operador. Confirma a observação do PO.
- **Recomendação.** T1 (tirar do menu do operador). O operador consome o score só no caso e na fila. **Alta · P** (se for regra de menu) **/ M** (se for nova permissão com migration de perfis).

### 24. Jurídico, painel (`/juridico`) · C 4 · H 3 · E 3
Captura: `boreal-juridico-_juridico.png`.
- [F] Título "Jurídico" contra menu "Painel jurídico". Tabela "Casos por fase" corta a coluna "Liberado judicia…" (T10). Rótulos de KPI com ⓘ quebrando (T8).
- [F] Bom: "Somente prazos sob minha responsabilidade", prazos perdidos e próximos lado a lado.
- **Recomendação.** T8/T10. Título igual ao menu. **Baixa · P.**

### 25. Monitoria de qualidade (`/remuneracao/qualidade/avaliacoes`) · C 3 · H 4 · E 3
Captura: `boreal-qualidade-_remuneracao_qualidade_avaliacoes.png`.
- [F] Bom: "Registrar monitoria" desabilitado com o motivo em banner ("Crie um formulário de monitoria ativo antes…").
- [F] O banner não leva à ação: falta o link "Criar formulário" (aba Formulários). Seção errada no menu ("Remuneração variável"). Abas repetem o menu (T3).
- **Recomendação.** Link no aviso e seção "Qualidade" no menu. **Baixa · P.**

---

## Ranking consolidado dos achados

| # | Achado | Sev. | Esf. |
|---|---|---|---|
| 1 | Estação: registro de interação abaixo da dobra, abas 4 a 7 escondidas e centro sem espaço útil a 1366×768 | Alta | M |
| 2 | Menu por permissão de leitura: operador com 31 itens (Score ×5, políticas, credores…), supervisor com 56 | Alta | M |
| 3 | Início do operador: 5 blocos concorrendo, Acesso rápido igual ao menu, redundâncias de MFA e tour, sem "Seu dia" | Alta | M |
| 4 | Simulador: não simula ao vir do caso, ~6 cliques e rolagens até propor, 6 campos de % sempre visíveis | Alta | M/G |
| 5 | Localizar registro: busca global só de telas, Casos/Propostas/Acordos sem filtro de devedor | Alta | M |
| 6 | Nenhuma lista ordena por coluna | Alta | M |
| 7 | Ficha do devedor sem caminho para caso, simulação ou conversas e sem histórico | Alta | M |
| 8 | Caixa de entrada abre em "Minhas" vazia com 2 conversas vencidas na Fila | Média | P |
| 9 | Convite "Primeira vez por aqui?" em todas as telas, cobrindo conteúdo | Média | P |
| 10 | Aprovação de proposta sem quantificar o excesso de alçada nem o prazo na lista | Média | M |
| 11 | Cartão de Score vazio com "Recalcular" no caso, ocupando espaço nobre | Média | P |
| 12 | Abas que repetem o menu (Painéis, Monitoria, aba única em Produtividade) | Média | P |
| 13 | Tabelas cortadas sem indicação em 1366 (estação, acordo, jurídico) | Média | M |
| 14 | Listas de baixa densidade (Propostas com 4 linhas por registro), filtros em 2 a 3 linhas (Pagamentos, Casos) | Média | P |
| 15 | Ruído visual: amarelo em excesso, "R$ 1 mi" repetido, enums vazando, ⓘ quebrando rótulo | Baixa | P |

## O que está bom e deve ser preservado
- [F] **Status com ícone e texto** em todas as telas (`app-status-chip`), selo de atraso com dias e valores com algarismos tabulares alinhados à direita.
- [F] **Documento e contato mascarados**, com revelação explícita "(acesso registrado)".
- [F] **"Posso contatar agora?"** por canal na estação (só precisa ficar mais compacto) e aviso da janela de 24 h do WhatsApp com template obrigatório.
- [F] **Atalhos visíveis** nos botões (Alt N, Alt R, Alt S, Ctrl+Enter) e cronômetro discreto.
- [F] **Estados vazios que orientam** ("Retornos são agendados ao registrar…", "Exporte o extrato do banco em CSV…").
- [F] **Pendências** com severidade (barra lateral, "2 vencidos", "1 vence hoje") e link para a tela filtrada.
- [F] **Simulador**: dívida atualizada com composição, memória de cálculo sob demanda, "Dentro da alçada", escada com motivo obrigatório e botão que vira "Pedir aprovação".
- [F] **Aprovações**: "Por que precisa de aprovação", segregação explícita em pagamentos e Aprovar/Reprovar no topo.
- [F] **Importação**: assistente em 6 etapas, decisão de decimal com exemplos do arquivo e lote com totais e SHA-256.
- [F] **Banner do acordo** explicando a suspensão da quebra durante a revisão do pagamento.
- [F] **Ação desabilitada com motivo** (Monitoria) seguindo a exceção documentada.
- [F] Trilha de navegação, "Dados até…" com "Atualizar agora" nos painéis e "Ver tabela" acessível junto dos gráficos.

## Limitações do estudo
- [F] Telas sem dado no sandbox (conferência de importação, extrato de conciliação, divergências) foram avaliadas só no estado vazio.
- [F] Sessões do operador e do supervisor foram revogadas algumas vezes durante as capturas (outros subagentes usando o mesmo usuário, limite de 3 sessões). As capturas foram refeitas com novo login. Não afeta os achados.
- [S] Nenhum dado de uso real (cliques, tempo por tarefa). A priorização usa as personas de ux.md §1 e heurísticas de CRM operacional. Vale validar os itens 1, 3 e 4 com 2 a 3 operadores em sessão observada.
