# Perfis de acesso padrão

Toda assessoria nasce com um perfil para cada tipo de usuário do sistema. Eles são criados no provisionamento da assessoria e mantidos atualizados automaticamente a cada versão. Os perfis podem ter as permissões ajustadas pelo administrador; perfis de sistema não podem ser desativados. A assessoria pode criar perfis próprios a partir deles.

| Perfil (código) | Para quem | O que faz | MFA |
| --- | --- | --- | --- |
| Administrador (`ADMIN`) | Responsável pela assessoria na plataforma | Acesso total à assessoria (todas as permissões, exceto o provisionamento de assessorias, que é da plataforma) | Obrigatório |
| Gestor (`GESTOR`) | Gestão da operação | Credores, contratos, carteiras, políticas e alçadas, correção manual do valor do título e aprovação das correções de outra pessoa (correcao-de-valor.md), importação, estratégias, templates, score, portal, jurídico, telefonia, assistente, prestação de contas (aprovação), tokens, consumo e faturamento da API pública | Obrigatório |
| Supervisor (`SUPERVISOR`) | Coordenação de equipes | Tudo do operador, mais distribuição e redistribuição, filas, gestão das contestações (atribuir, redistribuir, prazos, distribuição, modelos e a quarentena dos e-mails do credor), aprovações de proposta, caixa de entrada, monitoria, gravações e revisão do assistente | — |
| Operador (`OPERADOR`) | Negociador | Estação de cobrança: casos, interações, retornos, simulação, propostas, formalização dentro da alçada, Pix, mensagens, telefone; contestações atribuídas a si e as sem responsável que assumir, inclusive confirmar as respostas do credor por e-mail (`DISPUTE_WORK`, contestacoes.md §2 e §4) | — |
| Financeiro (`FINANCEIRO`) | Tesouraria e repasses | Pagamentos, aprovação de pagamento externo, correção manual do valor do título e aprovação das correções de outra pessoa, estornos, conciliação, comissão, fechamento e repasse ao credor, consumo e extratos da API pública | Obrigatório |
| Auditor (`AUDITOR`) | Auditoria interna/externa | Somente leitura de cadastros, operação, finanças, logs de acesso, trilha de auditoria, tokens e consumo da API pública | Obrigatório |
| Jurídico (`JURIDICO`) | Jurídico interno | Casos jurídicos, autorização do credor, custas, garantias e prescrição | Obrigatório |
| Advogado parceiro (`ADVOGADO_PARCEIRO`) | Escritório parceiro | Somente os casos jurídicos atribuídos ao escritório | Obrigatório |
| Analista de carteiras (`ANALISTA_CARTEIRA`) | Back-office de cargas | Importação e saneamento de carteiras, títulos, garantias e devedores (não aprova a própria carga) | — |
| Estratégia de cobrança (`ESTRATEGIA`) | Inteligência e campanhas | Segmentos, réguas, testes A/B, templates e score de propensão, com painéis | — |
| Monitoria e qualidade (`QUALIDADE`) | Qualidade do atendimento | Avaliação de atendimentos, revisão do assistente de IA e escuta de gravações | Obrigatório |
| Compliance e privacidade (`COMPLIANCE`) | Encarregado de dados / compliance | Restrições de contato, solicitações de titulares (LGPD), retenção, logs de acesso e auditoria | Obrigatório |
| Integrações (`INTEGRACAO`) | Equipe técnica | Canais, provedores e cargas automáticas; tokens de integração da API pública (só com os escopos que o perfil tem: carteiras e importação) e consumo; sem acesso a dados de devedores | Obrigatório |

Além desses, o tenant da plataforma tem o perfil **Administrador da plataforma** (`PLATFORM_ADMIN`), com MFA **obrigatório** (decisão 521): provisiona e suspende assessorias, reenvia o convite do administrador inicial enquanto a assessoria não tem administrador ativo, administra os usuários da própria plataforma e mantém o faturamento da plataforma às assessorias (`PLATFORM_BILLING_VIEW` e `PLATFORM_BILLING_MANAGE`, exclusivas da plataforma como `TENANT_MANAGE` — api-publica.md §9) e controla os tokens de integração de qualquer assessoria — pausar, retomar e revogar para bloquear consumo indevido (`PLATFORM_API_TOKENS_MANAGE`, também exclusiva da plataforma — api-publica.md §9.3). O autenticador é cadastrado no primeiro login, logo depois da ativação. São 10 perfis com MFA obrigatório: os 9 marcados acima e o `PLATFORM_ADMIN` (`app.security.mfa.required-for-roles`).

A lista exata de permissões de cada perfil está em `backend/src/main/java/br/com/gestorcobranca/identity/domain/model/DefaultRoles.java`.

## Como os perfis se mantêm atualizados

- **Assessoria nova:** recebe todos os perfis acima no provisionamento.
- **Versão nova do sistema:** uma sincronização roda logo após a subida da aplicação e a cada 6 horas. Ela cria os perfis padrão que faltarem e acrescenta aos perfis de sistema as permissões novas da definição padrão.
- **Ajustes do administrador são respeitados:** cada permissão padrão é acrescentada uma única vez. Se o administrador retirar uma permissão de um perfil de sistema, ela não volta. Perfis criados pela assessoria nunca são alterados. Nada é removido automaticamente.
- **Rastreabilidade:** cada perfil criado e cada permissão acrescentada pela sincronização ficam na trilha de auditoria.

## Áreas de consulta (menu por tarefa)

O menu mostra a cada perfil o que é **tarefa** dele, não tudo o que ele pode ler (decisão D6 da revisão de produto de
novembro/2026). Ver um dado dentro de outra tela não põe no menu a área que administra esse dado. Por isso, três
permissões abrem as áreas de consulta, separadas das permissões que liberam os dados. A tela da área só abre com as duas (a de área e a de consulta):

| Permissão | Abre | Dados (continuam exigidos pelo servidor) | Perfis padrão |
| --- | --- | --- | --- |
| `SCORING_AREA_VIEW` | Área do score: modelos, execuções, monitoramento, decisões e variáveis, parâmetros | `SCORING_VIEW` (o operador e o supervisor veem o score no caso) | Administrador, Gestor, Estratégia, Auditor |
| `POLICY_AREA_VIEW` | Políticas de cobrança e índices de correção | `POLICY_VIEW` (o simulador e as propostas usam a política) | Administrador, Gestor, Supervisor, Financeiro, Auditor, Jurídico, Analista de carteiras, Estratégia, Compliance |
| `ORGANIZATION_AREA_VIEW` | Credores e carteiras | `CREDITOR_VIEW`, `PORTFOLIO_VIEW` (nomes de credor e carteira nas listas) | Administrador, Gestor, Financeiro, Auditor, Jurídico, Analista de carteiras, Estratégia, Compliance, Integrações |

- **Operador:** fica sem a área do score, políticas, índices, credores e carteiras no menu, e o próprio desempenho vira
  um item só ("Meu desempenho"), de onde ele abre a produtividade, os extratos e lançamentos de comissão, a monitoria
  e as ocorrências — telas que continuam acessíveis a ele, só fora do menu. O formulário de monitoria só abre para
  quem avalia ou acompanha outras pessoas (o negociador vê o formulário dentro da avaliação).
- **Supervisor:** consulta as políticas (aprova propostas acima da alçada do operador), mas não a área do score nem os
  cadastros de credores e carteiras.
- **Perfis de sistema** recebem as permissões novas pela sincronização. **Perfis criados pela assessoria** recebem, uma
  vez, a permissão de área correspondente a cada permissão de consulta que já tinham (migration `V0209`): ninguém perde
  acesso; o administrador retira a permissão nova onde a área não for tarefa.
- O menu é dividido em seções que o usuário recolhe com um clique no título; o estado acompanha o usuário entre
  dispositivos e a seção da tela atual fica sempre aberta.

Itens de menu por perfil padrão (antes → depois): Operador 31 → 16; Supervisor 56 → 49; Monitoria e qualidade 27 → 25;
os demais não mudaram (Administrador 93, Gestor 77, Financeiro 41, Auditor 49, Jurídico 19, Advogado parceiro 4,
Analista de carteiras 15, Estratégia 33, Compliance 25, Integrações 11). O teste `navigation.spec.ts` confere essas
contagens.

## API pública de integração

| Permissão | O que libera | Perfis padrão |
| --- | --- | --- |
| `PUBLIC_API_TOKEN_VIEW` | Consultar tokens de integração | Administrador, Gestor, Integrações, Auditor |
| `PUBLIC_API_TOKEN_MANAGE` | Criar, editar, rotacionar e revogar tokens (só com escopos que o próprio perfil tem), inclusive os pausados pela plataforma — que só a plataforma retoma; quem tem a permissão recebe o aviso por e-mail das ações da plataforma | Administrador, Gestor, Integrações |
| `PUBLIC_API_USAGE_VIEW` | Consumo, extratos por ciclo e posição do ciclo em andamento (bloco de faturamento da tela Posição do credor) | Administrador, Gestor, Financeiro, Integrações, Auditor |
| `PUBLIC_API_BILLING_MANAGE` | Fechamento e revisão de extratos (os preços ficam no contrato do credor, com `CONTRACT_MANAGE`) | Administrador, Gestor, Financeiro |
| `TENANT_BILLING_VIEW` | Consultar, sem alterar, a cobrança da plataforma à assessoria pelo consumo da integração (política, posição do ciclo e extratos; exige todos os credores) | Administrador |

**Posição do credor** (`/posicao-credor`): o bloco de faturamento da API exige `PUBLIC_API_USAGE_VIEW`; o bloco de repasse do período em aberto e o cartão de saldos do painel inicial exigem `SETTLEMENT_VIEW` (Administrador, Gestor, Financeiro, Auditor) — a mesma permissão de consultar demonstrativos, já que a posição é o cálculo do demonstrativo em simulação. Escopos de token `billing:read` (exige `PUBLIC_API_USAGE_VIEW` de quem concede) e `settlement:read` (exige `SETTLEMENT_VIEW`).

Exportar consumo ou extrato exige também `REPORT_EXPORT`. Ver [`api-publica.md`](api-publica.md).

## Criar perfis

Quem gerencia perfis só pode conceder permissões que o próprio perfil possui (proteção contra escalada de privilégio). A tela mostra bloqueadas, com o aviso "fora do seu perfil", as permissões que o usuário não pode conceder, e o botão "Marcar todas que posso conceder" seleciona o restante. O administrador da assessoria tem todas as permissões e pode criar um perfil com acesso total.

O administrador **da plataforma** (tenant da plataforma) só tem as permissões de provisionamento e gestão da própria plataforma; perfis de assessoria são criados pelo administrador de cada assessoria.
