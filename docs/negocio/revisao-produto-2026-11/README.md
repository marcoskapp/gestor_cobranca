# Revisão de usabilidade, design e produto — novembro/2026

Estudo pedido pelo responsável em 06/11/2026 para responder a quatro perguntas antes da versão inicial em produção:

1. **Fluidez da operação por perfil:** as telas se conversam? A navegação dentro de uma jornada é fácil? Dá para voltar
   ao passo anterior?
2. **Amplitude de funcionalidades e lacunas:** o que falta e onde ampliar o uso de IA, com o ganho de cada uso.
3. **Facilidade de uso em cada tela:** coesão, posicionamento e localização rápida da informação.
4. **Design:** fontes, cores, componentes; uma aplicação bonita e elegante.

O que se segue é a síntese. Cada frente tem o estudo completo, com evidência (arquivo e linha, ou captura), nos anexos:

| Anexo | Frente | Como foi feito |
| --- | --- | --- |
| [1-jornadas.md](1-jornadas.md) | Jornadas e navegação | Jornadas percorridas clicando no sandbox (`aurora`): operador, supervisor, gestor, financeiro, analista, jurídico |
| [2-features-ia.md](2-features-ia.md) | Funcionalidades, lacunas e IA | Leitura de `docs/`, código dos 23 módulos, issues do GitHub e decisões pendentes |
| [3-telas.md](3-telas.md) | Ergonomia tela a tela | 25 telas a 1366×768 e 1440×900 (`boreal`), com nota de 1 a 5 em clareza, hierarquia e eficiência |
| [4-design.md](4-design.md) | Design visual | Tokens, SCSS e componentes; capturas em tema claro e escuro, no computador e no celular (390×844) |

Esforço: **P** até 2 dias · **M** até 1–2 semanas · **G** mais de 2 semanas (estimativas, a refinar na encomenda).
Severidade: impacto em produtividade, erro operacional ou risco. Os estudos marcam o que foi verificado e o que é suposição.

---

## 1. Onde estamos

**O produto é funcionalmente amplo e tecnicamente sólido.** São 23 módulos, quase todos completos, com escopo por
credor, auditoria, LGPD e onboarding levados a sério. A interface é consistente na base: tokens com contraste medido,
tema escuro, densidade compacta, status sempre com ícone e texto, estados vazios que orientam e atalhos de teclado.

**O que nos separa da produção não é falta de tela; são três coisas:**

| Frente | Situação | Resumo |
| --- | --- | --- |
| Jornadas | **Fraca** | Cada tela funciona sozinha, mas as telas pouco se conversam. Voltar perde filtros, página, aba e até o atendimento em curso; as entidades não se ligam nos dois sentidos; não há busca de devedor ou caso. |
| Telas | **Média** | Boas telas de back-office (importação, aprovação de pagamentos, agenda). As de uso intenso — estação, simulador, início do operador — gastam a primeira dobra com o que não é a tarefa. |
| Design | **Boa base, acabamento a fazer** | O texto em peso 300 fica acinzentado, a hierarquia de pesos está invertida, há botões demais com o mesmo peso e componentes de KPI duplicados. O celular é usável, mas não é bom. |
| Funcionalidades | **Amplas, presas a integrações** | Os módulos parciais são justamente os que dependem de provedor: telefonia homologada, SMS, boleto ou CNAB, birôs. Toda a IA está pronta e desligada até a decisão 371. |

A raiz comum dos problemas de jornada e de tela é uma só: **menu, início e ficha são montados pela permissão, não pela
tarefa do perfil.** O operador vê 31 itens de menu (5 deles do Score), o supervisor 56 e o gestor 77. O "Acesso rápido"
do início repete o menu inteiro.

---

## 2. Fluidez das jornadas (frente 1)

| # | Achado | Sev. | Esf. |
| --- | --- | --- | --- |
| J1 | **Sair da estação apaga o atendimento:** abrir a ficha do devedor ou o simulador e voltar zera a observação digitada e o cronômetro. O início da interação vive no componente (`collection-station.ts:176-190`), então a **duração registrada sai menor** sempre que o operador consulta algo. Se essa duração alimenta produtividade e comissão, há distorção de dado — conferir no backend. | Alta | M |
| J2 | **Filtros, página e rolagem se perdem ao voltar** em todas as listas: as listas leem a URL só ao abrir e nunca gravam nela, a página fica em memória e o roteador sobe a tela ao topo (`app.config.ts:34`). Os painéis já resolvem isso com `analytics/data/url-filter.ts`, que serve de modelo. | Alta | M |
| J3 | **As entidades não se ligam nos dois sentidos:** a ficha do devedor e o título não levam ao caso, acordo, pagamentos, contestações ou jurídico; o acordo não liga aos pagamentos aplicados; o caso mostra contestação e jurídico sem link. | Alta | M |
| J4 | **Não há busca de devedor ou caso:** Ctrl+K só busca telas. No atendimento receptivo o operador não tem caminho até o caso de quem ligou. | Alta | M |
| J5 | **Fechar um acordo leva cerca de 20 cliques e 4 trocas de tela** (estação → simulador → proposta → aceite → formalização → acordo → Pix → volta, que cai em outro endereço e com o cronômetro zerado). | Alta | M |
| J6 | **Os painéis do gestor não chegam ao registro:** nenhum indicador leva à lista filtrada. O painel jurídico já faz isso e é o modelo. | Alta | M |
| J7 | **A monitoria pede o identificador da chamada ou conversa digitado à mão;** não há "Avaliar este atendimento" a partir do atendimento. | Alta | M |
| J8 | Aprovação de propostas sem "próxima" e sem volta à fila; a trilha marca "Propostas". | Média | P |
| J9 | Quem não opera (jurídico, gestor) vê a estação do operador ao abrir um caso, com cronômetro rodando; a mesma ficha tem dois endereços. | Média | M |
| J10 | Abas não ficam na URL; a trilha mostra o menu, não o caminho percorrido; "← Devedores" volta para a lista sem os filtros. | Média | P–M |

**O que funciona e fica:** Minhas pendências (leva direto ao registro), estação com o caso na URL, "Próximo caso"
(Alt+N), simulador aberto pelo caso com dados preenchidos, conciliação ↔ divergências ↔ pagamento, demonstrativo ↔
repasse, painel jurídico até o registro, importação com avanço e retorno em todos os passos, menu sem tela "acesso negado".

---

## 3. Funcionalidades, lacunas e IA (frente 2)

### 3.1 Bloqueadores da produção

Quase todos dependem de **decisão ou aprovação sua**, não de código. Viram issue quando envolvem custo ou provedor.

| # | Bloqueador | Natureza |
| --- | --- | --- |
| B1 | **Telefonia homologada:** conta e número brasileiro na Twilio ou Plivo; aviso de gravação e mensagem de abandono validados (issue #12) | Aprovação de provedor e custo |
| B2 | **Provedor de SMS** (adaptador do `ChannelGateway`; hoje o SMS é recusado fora do sandbox) | Aprovação (#12) |
| B3 | **Boleto registrado ou, no mínimo, leitor de retorno CNAB 240/400.** O CNAB é layout Febraban e não depende de provedor, mas a decisão 79 o adiou. | Boleto: aprovação. CNAB: sua decisão (código, M) |
| B4 | **Validação jurídica** dos templates, do aviso de privacidade, do roteiro do assistente, da retenção de gravações e dos prazos do DPO (901) | Processo |
| B5 | **Checklist de go-live** (`ativacao-aws.md` §10/§12): cópia das chaves fora da conta (472), restauração do RDS ensaiada (473), alarmes que avisem alguém, TLS e DNS (há caminho sem aprovação) | Operação; parte com aprovação |
| B6 | **Chamados do chat de suporte:** o repositório público foi aceito por você até a migração. Antes de cliente real: tornar o repositório privado e ligar `HELPDESK_GITHUB_REQUIRE_PRIVATE`, ou manter os chamados desligados | Sua decisão |
| B7 | **Antivírus de arquivos** desligado por padrão (816), com evidências vindas de credores e de e-mail | Aprovação (infra) |
| B8 | **Teste de intrusão** | Aprovação |

Pesa, mas não bloqueia: WAF e Multi-AZ (#5), teste de carga (não há nenhum no repositório) e CI/CD (#10).

### 3.2 Lacunas importantes (primeiros 90 dias)

| # | Lacuna | Esf. | Aprovação? |
| --- | --- | --- | --- |
| I1 | **Mídia no WhatsApp:** receber o comprovante em foto ou PDF e enviar QR Code do Pix e PDF do termo | M | Não (depende do antivírus) |
| I6 | **Marcador de vulnerabilidade e superendividamento** (Lei 14.181, CDC art. 104-A): o `RestrictionType` não tem o tipo; o assistente detecta, mas não há onde guardar | M | Não |
| I5 | **Gestão de reclamações** (Procon, consumidor.gov, Bacen, Reclame Aqui) com prazo, responsável e vínculo ao caso | M | Não |
| I7 | **Homologação de templates da Meta pela API** (hoje registrada à mão) | M | Não |
| I8 | **Ação `OFFER` da régua** (oferta com link ou Pix na etapa) | M | Não |
| I14 | Contestação aberta no portal vira contestação no collection (o prazo do CDC começa ali) | P | Não |
| I11 | Acesso do credor ao demonstrativo (aceite e contestação) | M | Não |
| I9 | Envio fora da transação no communication (TEL-02); bounce de e-mail pelo SES | M / P | SES: sim |
| I2–I4 | Negativação em massa na régua, protesto eletrônico, enriquecimento de contatos | G / G / M | **Sim** (birô, convênio, bureau) |
| I10 | Escuta e sussurro nas ligações | M | Depende do provedor |

Os desenhos têm pendências desatualizadas que devem ser limpas: `pagamentos.md` §14 e `campanhas.md` §11 dizem que
falta frontend, mas as telas existem; `ux.md` §7 ainda cita o Angular Material.

### 3.3 IA: o que existe e onde ampliar

**O que existe:** assistente de negociação no WhatsApp com copiloto do operador, chat de suporte e Importação PDF, sobre
uma fundação reutilizável (`shared.ai` com Bedrock, IAM mínimo, minimização de dados e trilha cifrada). **Tudo desligado
até a decisão 371** (nenhum Claude 5.5 processa no Brasil; é preciso aprovar a transferência internacional, LGPD art.
33). Pré-requisito de qualquer ampliação: a 371, o roteiro de homologação e as **conversas de referência** (avaliação
automática a cada mudança de prompt ou modelo), que estão pendentes.

**Onde ampliar, em ordem de valor, esforço e risco:**

| # | Oportunidade | Onde / quem | O que ganhamos | Guarda principal | Esf. |
| --- | --- | --- | --- | --- | --- |
| IA1 | **Mapeamento automático de colunas na importação CSV/XLSX** (mesmo padrão do PDF) | Importação · analista | Entrada mais rápida de carteira e credor novos | Mapa validado no servidor; conferência humana já existe | P |
| IA2 | **Resumo do caso ao abrir a ficha** e na transferência | Estação · operador | TMA menor; mais acordos por contato; o devedor não repete a história | Só de fatos estruturados, cada frase com referência à interação | P–M |
| IA3 | **Classificação das mensagens recebidas** (promessa, alegação de pagamento, contestação, óbito, número errado, opt-out, vulnerabilidade, Procon) | Caixa de entrada · operador e supervisor | Primeira resposta mais rápida; opt-out, óbito e terceiro tratados no mesmo minuto | Regra determinística como piso; IA só **sugere** a tabulação | M |
| IA4 | **Leitura do comprovante de pagamento**, pré-preenchendo o pagamento externo informado | Caixa e ficha · operador → revisão segregada | Menos tempo de conciliação; quebra indevida evitada | Nunca baixa automática; depende de I1 | M |
| IA5 | **Triagem de contestações** e leitura da resposta do credor por e-mail | Contestações · analista e jurídico | Prazo do CDC cumprido | Só rascunho; confirmação humana já é obrigatória | M |
| IA6 | **Monitoria automática de 100% das conversas de texto** contra o formulário de qualidade | Qualidade · supervisor | Cobertura de amostra para 100%; menos reclamação e Procon | Nota oficial continua humana (risco trabalhista) | M |
| IA7 | **Indicadores em linguagem natural** no chat e **copiloto do supervisor** (resumo do dia: SLA estourado, abandono, promessas a vencer) | Início e painéis · gestor e supervisor | Reação no mesmo dia | Números só das ferramentas, nunca do modelo | P–M |
| IA8 | Assistente no chat do portal e no e-mail | Portal · devedor | Autoatendimento maior | Mesmas guardas do assistente | M |
| IA9 | Detecção de vulnerabilidade fora do assistente, gravando o marcador | Caixa · operador sênior | Conformidade com a Lei 14.181 | Dado sensível: só a categoria; depende de I6 | M |
| IA10 | Transcrição e monitoria de ligações | Telefonia · qualidade | Tabulação correta; monitoria de voz | Serviço novo → aprovação | G |

Ficam por último: melhor canal e horário por modelo estatístico (exige mudar `score-propensao.md` §4 e RIPD) e proposta
inicial ótima por segmento (risco de isonomia). As guardas obrigatórias para toda IA nova estão no anexo 2, §4.3.

---

## 4. Facilidade de uso tela a tela (frente 3)

| # | Achado | Sev. | Esf. |
| --- | --- | --- | --- |
| T1 | **Estação com caso aberto a 1366×768:** o registro de interação fica abaixo da dobra; 3 das 7 abas do caso ficam escondidas sem pista; a tabela de títulos corta "Valor original" e "Saldo". Proposta: formulário no topo da coluna direita com botão fixo, "Posso contatar agora?" em chips, ações em barra de ícones, aba "Mais ▾". | Alta | M |
| T2 | **Início do operador:** aviso de MFA, pendências, "Acesso rápido" (30 blocos repetindo o menu), checklist e convite de tour competem na primeira dobra; MFA e "conhecer a navegação" aparecem duas vezes; **falta o dia do operador** (casos atribuídos, retornos de hoje, promessas vencendo, "Próximo caso", um número próprio). | Alta | M |
| T3 | **Simulador:** aberto pelo caso, não simula sozinho; até propor são cerca de 6 cliques e 2–3 rolagens; os 6 campos de desconto por componente ficam sempre à vista; o campo "Pagamentos" inclui a entrada enquanto a oferta fala em "Entrada + 11 parcelas" (risco de erro). | Alta | M/G |
| T4 | **Menu por permissão de leitura:** os 5 itens do Score aparecem ao operador porque ele tem `SCORING_VIEW` para ver o cartão no caso (`navigation.ts:121-129`); também vê Políticas, Índices, Credores, Carteiras, Formulários de monitoria. | Alta | M |
| T5 | **Nenhuma lista ordena por coluna,** embora a tabela compartilhada suporte; Casos, Propostas e Acordos não filtram por devedor (Pagamentos filtra). | Alta | M |
| T6 | **Ficha do devedor sem saída:** sem abrir caso, simular, conversas ou histórico de interações. | Alta | M |
| T7 | Caixa de entrada abre em "Minhas", vazia, com 2 conversas vencidas na "Fila" (`inbox.ts:43`); o vazio não aponta para a Fila. | Média | P |
| T8 | Convite "Primeira vez por aqui?" aparece em todas as telas até ser dispensado e cobre colunas e setas — contradiz `ux.md` §8.3. | Média | P |
| T9 | Aprovação de proposta não mostra quanto passa da alçada; a lista não tem % de desconto nem prazo; "alçada JUNIOR" em inglês. | Média | M |
| T10 | Ruído: cartão de score vazio com "Recalcular" para o operador; abas que repetem o menu; "TMA" sem explicação; "Promessa" em amarelo de alerta; "R$ 1 mi" em cartões diferentes; códigos na tela ("PADRAO v1"); paginação com uma página só; Propostas com 4 linhas por registro. | Média/Baixa | P |

**O que funciona e fica:** status com ícone, texto e selo de atraso; CPF mascarado com revelação registrada; "Posso
contatar agora?" por canal e aviso da janela de 24 h do WhatsApp; atalhos visíveis nos botões; simulador com memória de
cálculo e "Pedir aprovação"; aprovações com "por que precisa de aprovação"; assistente de importação em 6 etapas;
ação desabilitada com o motivo.

---

## 5. Design (frente 4)

| # | Ajuste | Sev. | Esf. | Sua decisão? |
| --- | --- | --- | --- | --- |
| V1 | **Peso da fonte:** corpo em 300 e títulos em 400 deixam o texto cinza e frágil, pior no Windows a 1x e no escuro; 105 `font-weight: 600` escritos direto invertem a hierarquia (título do cartão mais forte que o da página). Proposta: corpo 400 (ou 380 com a Inter Variable), títulos 600, botões 500; o "fino" fica nos números grandes e nos textos de destaque. | Alta | P+M | **Sim** — reverte o "traço fino" |
| V2 | **Hierarquia de botões nas fichas:** 5 a 7 contornos azuis no mesmo nível, com o "Cancelar" vermelho entre eles. Proposta: uma primária, até duas secundárias em cinza e um menu "Mais ações" com a destrutiva dentro. | Alta | M | Sim (padrão de tela) |
| V3 | **Celular:** filtros empurram os números para baixo da primeira tela (proposta: botão "Filtros (n)" em painel lateral, M); tabelas só rolam na horizontal (proposta: lista empilhada no `app-data-table`, G). | Alta | M + G | Lista empilhada: sim |
| V4 | **Cartão de KPI reimplementado ao menos 7 vezes,** com pesos e tamanhos diferentes; grade com `auto-fill` deixa cartões órfãos. Proposta: `app-kpi-card` único e `auto-fit`. | Média | M | Não |
| V5 | `tabular-nums` no body inteiro espaça hífens ("E - mail", "sexta - feira"). Aplicar só em números. | Média | P | Não |
| V6 | Raios 12/16/20 px, com cartão arredondado dentro de cartão arredondado. Proposta: 6/8/12/16 e sombra em duas camadas. | Média | P | **Sim** — forma foi decisão sua |
| V7 | Abas inativas em azul com ícone, cortando no celular. Proposta: `.nav-underline` nativa do Bootstrap. | Média | P/M | Não |
| V8 | Números compactos ("R$ 1 mi" para valores diferentes) → 3 dígitos significativos ("R$ 1,01 mi"). | Média | P | Não |
| V9 | Ícones do menu misturam cheio e contorno, 16 repetidos. Proposta: só contorno, um por item. | Média/Baixa | P | Não |
| V10 | Cinzas quentes nos gráficos numa interface de cinzas frios; login sem presença de marca (logo genérico, borrão radial). | Baixa/Média | P/M | Login: sim |

**O que preservar:** parametrização só por variáveis oficiais do Bootstrap com `bootstrap:check`; tokens
`--app-<tom>-*` com contraste medido; tema escuro de neutros frios; densidade compacta só com mouse; `app-status-chip`;
gráficos com paleta validada para daltonismo, valores rotulados e "Ver tabela"; barra navy com assessoria e escopo.

---

## 6. Decisões que pedem você

| # | Decisão | Recomendação do estudo | Destrava |
| --- | --- | --- | --- |
| D1 | **371 — transferência internacional para a IA** (opção A, EUA, ou B, global) | Decidir já; sem ela nenhuma IA vai a produção | Onda 6 inteira |
| D2 | **Provedores:** telefonia (Twilio ou Plivo), SMS, boleto | Abrir/atualizar issue #12 com a escolha | B1–B3 |
| D3 | **CNAB 240/400 sem provedor** (revisar a decisão 79) | Construir o leitor de retorno CNAB 240 já, independente do boleto | B3 |
| D4 | **Peso da fonte** (V1) | Proposta A: corpo 400, títulos 600 | Onda 4 |
| D5 | **Raios e login** (V6, V10) | Raios 6/8/12/16; login com painel de marca | Onda 4 |
| D6 | **Menu por tarefa** (T4): permissão própria para abrir a área do Score, separada de ver o score no caso; mesma análise para Políticas, Credores, Carteiras no operador | Separar; alvo de cerca de 12 itens para o operador | 1.5 |
| D7 | **Fechamento do acordo num fluxo só na estação** (J5): juntar aceite e formalização quando a alçada permitir, mantendo a confirmação digitada no passo irreversível | Sim | 2.4 |
| D8 | **"Seu dia" no início do operador** no lugar do "Acesso rápido" (T2) | Sim, para operador e supervisor primeiro | 2.5 |
| D9 | Repositório privado e trava dos chamados (B6) | Migrar antes do primeiro cliente real | B6 |
| D10 | Marcador de vulnerabilidade e gestão de reclamações (I6, I5) dentro da versão inicial? | Sim para o marcador (risco legal); reclamações logo após | Onda 5 |

---

## 7. Ordem priorizada para encomenda

Ondas pensadas para construção fluida: cada uma entrega valor sozinha e prepara a seguinte. A **Onda A** corre em
paralelo desde já, porque depende de você e de terceiros, não de código. A **Onda 4** (visual) pode correr em paralelo
às ondas 2 e 3 numa frente separada, porque mexe sobretudo em estilos e componentes compartilhados.

### Onda A — Go-live (paralela, decisões e aprovações)

| ID | Encomenda | Origem | Depende |
| --- | --- | --- | --- |
| A1 | Decidir a 371 e registrar a base contratual da transferência | D1 | — |
| A2 | Escolher provedores de telefonia e SMS e abrir a homologação | B1, B2, D2 | — |
| A3 | Decidir boleto e CNAB; se aprovado, encomendar o leitor CNAB 240 (M) | B3, D3 | — |
| A4 | Enviar templates, aviso de privacidade e roteiro do assistente ao jurídico; prazos do DPO | B4 | — |
| A5 | Checklist de go-live: chaves, restauração ensaiada, alarmes com destinatário, TLS e DNS | B5 | Parte com aprovação |
| A6 | Repositório privado + trava dos chamados; antivírus; teste de intrusão | B6–B8 | Aprovação |

### Onda 1 — Fundação de navegação e listas (código compartilhado; destrava as ondas 2 e 3)

| ID | Encomenda | Origem | Esf. |
| --- | --- | --- | --- |
| 1.1 | **Estado das listas e abas na URL** (filtros, página, ordenação, aba) em todas as listas, com rolagem restaurada ao voltar, a partir de `url-filter.ts` | J2, J10 | M |
| 1.2 | **Ordenação por coluna** nas listas, **filtro "Devedor (nome ou CPF)"** padrão e paginação oculta com uma página | T5, T10 | M |
| 1.3 | **Busca de devedor e caso no Ctrl+K** por nome, CPF/CNPJ, telefone e título, com "Recentes" e log de acesso a dado sensível | J4 | M |
| 1.4 | **Cartão "Relacionados"** em todas as fichas (só com o que o perfil pode abrir) + **ficha do devedor com ações** (abrir caso, simular, conversas, histórico) + links pontuais (fila → casos, lote → títulos, acordo → pagamentos, demonstrativo → pagamento) | J3, T6 | M |
| 1.5 | **Menu por tarefa:** permissão de área separada (D6), seções recolhíveis; trilha com "← Voltar para <origem>" | T4, J10 | M |
| 1.6 | **Ajustes rápidos sem decisão:** `tabular-nums` só em números, grade `auto-fit`, números compactos, ícones do menu, cinzas dos gráficos, trilhas "X › X", códigos na tela traduzidos, tom de "Promessa", caixa de entrada abrindo na Fila quando "Minhas" está vazia, convite de ajuda que nunca cobre o conteúdo e aparece uma vez | V5, V7–V10, T7, T8, T10 | P cada |
| 1.7 | **Limpeza de documentação:** pendências desatualizadas em `pagamentos.md`, `campanhas.md` e `ux.md` §7 | 3.2 | P |

### Onda 2 — Jornada do operador (maior retorno operacional)

| ID | Encomenda | Origem | Esf. |
| --- | --- | --- | --- |
| 2.1 | **Atendimento preservado por caso** (início, rascunho, aba) num serviço, e conferência da duração registrada no backend | J1 | M |
| 2.2 | **Estação redesenhada para 1366×768:** registro no topo da coluna direita, chips de contato, barra de ações, aba "Mais", cartão de score só com conteúdo | T1, T10 | M |
| 2.3 | **Simulador e ficha do devedor como painel lateral na estação;** simulação automática vinda do caso, "Propor esta oferta" em cada cartão, descontos por componente em "avançado", campo de parcelas sem ambiguidade | J1, T3 | M/G |
| 2.4 | **Fechamento do acordo sem sair da estação** (propor → aceite com canal preenchido → formalizar → Pix → cartão "Acordo formalizado" no caso) | J5, D7 | M |
| 2.5 | **Início "Seu dia"** (casos atribuídos, retornos de hoje, promessas vencendo, "Próximo caso", um número próprio) no lugar do "Acesso rápido", sem MFA e tour duplicados | T2, D8 | M |

### Onda 3 — Supervisor, gestor e qualidade

| ID | Encomenda | Origem | Esf. |
| --- | --- | --- | --- |
| 3.1 | **Componente único de aprovação** (propostas, pagamentos, correções) com "Aprovar e abrir a próxima", contador "1 de N", quanto passa da alçada e colunas de desconto e prazo | J8, T9 | M |
| 3.2 | **Drill-down dos painéis:** cada indicador, barra e linha leva à lista com os mesmos filtros | J6 | M (depende de 1.1) |
| 3.3 | **"Avaliar este atendimento"** a partir de chamadas, conversas e histórico do caso, já preenchido, com link de volta | J7 | M |
| 3.4 | **Ficha do caso em modo consulta** para quem não tem `CASE_WORK`, com endereço único | J9 | M |
| 3.5 | Abas que repetem o menu removidas; "Produtividade" × "Meu desempenho" unificados; glossário no "i" (TMA) | T10 | P |

### Onda 4 — Identidade visual (frente paralela; depende de D4 e D5)

| ID | Encomenda | Origem | Esf. |
| --- | --- | --- | --- |
| 4.1 | **Tipografia:** pesos e escala novos nas variáveis do Bootstrap e troca dos 105 `600` escritos direto | V1 | P+M |
| 4.2 | **Hierarquia de botões** e menu "Mais ações" no cabeçalho das fichas (padrão em `shared`) | V2 | M |
| 4.3 | **`app-kpi-card` único** em `shared`, substituindo as 7 implementações | V4 | M |
| 4.4 | Raios e sombras; abas `.nav-underline`; login com marca | V6, V7, V10 | P/M |
| 4.5 | **Celular:** filtros em painel lateral e KPIs primeiro (M); depois lista empilhada no `app-data-table` (G) | V3 | M + G |

### Onda 5 — Lacunas funcionais sem aprovação externa

| ID | Encomenda | Origem | Esf. |
| --- | --- | --- | --- |
| 5.1 | **Marcador de vulnerabilidade e superendividamento** com roteamento e bloqueio de oferta | I6, D10 | M |
| 5.2 | **Contestação do portal → collection** | I14 | P |
| 5.3 | **Mídia no WhatsApp** (receber comprovante, enviar QR Code e PDF) | I1 | M (depende do antivírus, A6) |
| 5.4 | **Gestão de reclamações** com prazo e responsável | I5 | M |
| 5.5 | Homologação de templates pela API da Meta; ação `OFFER` da régua; envio fora da transação (TEL-02) | I7, I8, I9 | M cada |
| 5.6 | Acesso do credor ao demonstrativo; devolução Pix pelo sistema | I11; anexo 2 §3.3 | M / P |

Itens com aprovação externa viram issue, não código: negativação em massa, protesto, enriquecimento de contatos, SES,
escuta e sussurro, WAF, Multi-AZ, CI/CD, transcrição.

### Onda 6 — IA (depois da 371)

| ID | Encomenda | Esf. |
| --- | --- | --- |
| 6.0 | Conversas de referência e roteiro de homologação (pré-requisito) | P–M |
| 6.1 | Mapeamento automático de colunas na importação CSV/XLSX (IA1) | P |
| 6.2 | Resumo do caso na estação (IA2) — encaixa no painel redesenhado da onda 2 | P–M |
| 6.3 | Classificação das mensagens recebidas na caixa de entrada (IA3) | M |
| 6.4 | Leitura do comprovante (IA4) — depois de 5.3 | M |
| 6.5 | Triagem de contestações (IA5) | M |
| 6.6 | Monitoria automática de texto (IA6) e copiloto do supervisor (IA7) | M + P–M |

---

## 8. Corte sugerido para a versão inicial

Para operar com uma assessoria real com segurança e com a operação fluindo:

- **Onda A completa** (sem ela não há produção).
- **Ondas 1 e 2 completas** — são as que mudam o dia a dia de quem mais usa o sistema.
- **Onda 3:** 3.1 a 3.4.
- **Onda 4:** 4.1, 4.2, 4.3 e os filtros do celular (4.5, primeira parte).
- **Onda 5:** 5.1, 5.2 e 5.3 (esta se o antivírus for aprovado a tempo).
- **Onda 6:** 6.0 a 6.2, se a 371 for decidida a tempo; senão a IA entra logo depois do lançamento, sem afetar a operação.

Fica para depois do lançamento: lista empilhada no celular, reclamações, demais itens da onda 5 e IA a partir de 6.3.

**Validação recomendada:** antes de fechar as encomendas das ondas 2 e 3, observar 2 a 3 operadores e 1 supervisor
trabalhando por uma hora no sandbox. Os estudos não tiveram dado de uso real; os números de cliques e a dobra da tela
são medidos, mas o peso relativo de cada atrito é estimativa.

---

## 9. Limitações deste estudo

- Conciliação, divergências e repasses estavam vazios no sandbox; foram avaliados pelo código e no estado vazio.
- Analista de carteiras e jurídico foram cobertos de forma resumida nas jornadas.
- O estudo criou no sandbox `aurora` 1 proposta, 1 aceite e 1 acordo fictícios (devedor "Henrique Prudente Escobar",
  usuário `operador`); `./scripts/sandbox/reset.sh --yes` restaura o estado original.
- As capturas de tela ficaram locais, não versionadas; os anexos citam os nomes dos arquivos.

---

## 10. Andamento

| Data | O que foi entregue | Itens | Decisões |
| --- | --- | --- | --- |
| 07/11/2026 | **Acabamento visual:** tipografia (corpo 400, títulos 600), raios e sombras, `app-kpi-card` único, "Mais ações" nas fichas, abas sublinhadas, números compactos, tons de status, celular (filtros recolhíveis, lista empilhada), login | 1.6 (visual), 4.1–4.5 | 927–945 (D4, D5) |
| 07/11/2026 | **Navegação e listas:** filtros, página, ordenação e abas na URL (sem dado pessoal), rolagem restaurada, ordenação por coluna, filtro de devedor, menu por tarefa (operador de 31 para 16 itens), seções recolhíveis, trilha com "Voltar para" a origem, painéis sem abas duplicadas | 1.1, 1.2, 1.5, 3.5 | 946–963 (D6) |
| 07/11/2026 | **Jornada do operador:** atendimento preservado por caso, estação para 1366×768, simulador e ficha do devedor em painel lateral, acordo fechado sem sair do caso (de ~20 para 7 cliques), "Seu dia" no início, convite de tour uma vez, caixa de entrada na Fila | 2.1–2.5, 1.6 (T7, T8) | 964–983 (D7, D8) |
| 07/11/2026 | **Integração:** frentes unidas; abas da estação no padrão `.app-tabs`, colunas com `mobile` e `sortable`, retorno pela trilha, ficha do devedor embutida sem mexer na aba da estação | — | 963, 984, 985 |

Pendentes desta revisão: 1.3 (busca de devedor e caso no Ctrl+K), 1.4 ("Relacionados" e ações da ficha do devedor),
3.1–3.4 (aprovações, drill-down dos painéis, avaliar a partir do atendimento, modo consulta do caso) e os índices das
ordenações novas (medir antes de criar). Ondas A, 5 e 6 seguem como descritas.
