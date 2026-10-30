# Perfis de acesso padrão

Toda assessoria nasce com um perfil para cada tipo de usuário do sistema. Eles são criados no provisionamento da assessoria e mantidos atualizados automaticamente a cada versão. Os perfis podem ter as permissões ajustadas pelo administrador; perfis de sistema não podem ser desativados. A assessoria pode criar perfis próprios a partir deles.

| Perfil (código) | Para quem | O que faz | MFA |
| --- | --- | --- | --- |
| Administrador (`ADMIN`) | Responsável pela assessoria na plataforma | Acesso total à assessoria (todas as permissões, exceto o provisionamento de assessorias, que é da plataforma) | Obrigatório |
| Gestor (`GESTOR`) | Gestão da operação | Credores, contratos, carteiras, políticas e alçadas, importação, estratégias, templates, score, portal, jurídico, telefonia, assistente, prestação de contas (aprovação), tokens, consumo e faturamento da API pública | Obrigatório |
| Supervisor (`SUPERVISOR`) | Coordenação de equipes | Tudo do operador, mais distribuição e redistribuição, filas, contestações, aprovações de proposta, caixa de entrada, monitoria, gravações e revisão do assistente | — |
| Operador (`OPERADOR`) | Negociador | Estação de cobrança: casos, interações, retornos, simulação, propostas, formalização dentro da alçada, Pix, mensagens, telefone | — |
| Financeiro (`FINANCEIRO`) | Tesouraria e repasses | Pagamentos, aprovação de pagamento externo, estornos, conciliação, comissão, fechamento e repasse ao credor, consumo e extratos da API pública | Obrigatório |
| Auditor (`AUDITOR`) | Auditoria interna/externa | Somente leitura de cadastros, operação, finanças, logs de acesso, trilha de auditoria, tokens e consumo da API pública | Obrigatório |
| Jurídico (`JURIDICO`) | Jurídico interno | Casos jurídicos, autorização do credor, custas, garantias e prescrição | Obrigatório |
| Advogado parceiro (`ADVOGADO_PARCEIRO`) | Escritório parceiro | Somente os casos jurídicos atribuídos ao escritório | Obrigatório |
| Analista de carteiras (`ANALISTA_CARTEIRA`) | Back-office de cargas | Importação e saneamento de carteiras, títulos, garantias e devedores (não aprova a própria carga) | — |
| Estratégia de cobrança (`ESTRATEGIA`) | Inteligência e campanhas | Segmentos, réguas, testes A/B, templates e score de propensão, com painéis | — |
| Monitoria e qualidade (`QUALIDADE`) | Qualidade do atendimento | Avaliação de atendimentos, revisão do assistente de IA e escuta de gravações | Obrigatório |
| Compliance e privacidade (`COMPLIANCE`) | Encarregado de dados / compliance | Restrições de contato, solicitações de titulares (LGPD), retenção, logs de acesso e auditoria | Obrigatório |
| Integrações (`INTEGRACAO`) | Equipe técnica | Canais, provedores e cargas automáticas; tokens de integração da API pública (só com os escopos que o perfil tem: carteiras e importação) e consumo; sem acesso a dados de devedores | Obrigatório |

Além desses, o tenant da plataforma tem o perfil **Administrador da plataforma** (`PLATFORM_ADMIN`), com MFA **obrigatório** (decisão 521): provisiona e suspende assessorias, reenvia o convite do administrador inicial enquanto a assessoria não tem administrador ativo e administra os usuários da própria plataforma. O autenticador é cadastrado no primeiro login, logo depois da ativação. São 10 perfis com MFA obrigatório: os 9 marcados acima e o `PLATFORM_ADMIN` (`app.security.mfa.required-for-roles`).

A lista exata de permissões de cada perfil está em `backend/src/main/java/br/com/gestorcobranca/identity/domain/model/DefaultRoles.java`.

## Como os perfis se mantêm atualizados

- **Assessoria nova:** recebe todos os perfis acima no provisionamento.
- **Versão nova do sistema:** uma sincronização roda logo após a subida da aplicação e a cada 6 horas. Ela cria os perfis padrão que faltarem e acrescenta aos perfis de sistema as permissões novas da definição padrão.
- **Ajustes do administrador são respeitados:** cada permissão padrão é acrescentada uma única vez. Se o administrador retirar uma permissão de um perfil de sistema, ela não volta. Perfis criados pela assessoria nunca são alterados. Nada é removido automaticamente.
- **Rastreabilidade:** cada perfil criado e cada permissão acrescentada pela sincronização ficam na trilha de auditoria.

## API pública de integração

| Permissão | O que libera | Perfis padrão |
| --- | --- | --- |
| `PUBLIC_API_TOKEN_VIEW` | Consultar tokens de integração | Administrador, Gestor, Integrações, Auditor |
| `PUBLIC_API_TOKEN_MANAGE` | Criar, editar, rotacionar e revogar tokens (só com escopos que o próprio perfil tem) | Administrador, Gestor, Integrações |
| `PUBLIC_API_USAGE_VIEW` | Consumo, tabelas de preço e extratos | Administrador, Gestor, Financeiro, Integrações, Auditor |
| `PUBLIC_API_BILLING_MANAGE` | Tabelas de preço (exige todos os credores) e fechamento de extratos | Administrador, Gestor, Financeiro |

Exportar consumo ou extrato exige também `REPORT_EXPORT`. Ver [`api-publica.md`](api-publica.md).

## Criar perfis

Quem gerencia perfis só pode conceder permissões que o próprio perfil possui (proteção contra escalada de privilégio). A tela mostra bloqueadas, com o aviso "fora do seu perfil", as permissões que o usuário não pode conceder, e o botão "Marcar todas que posso conceder" seleciona o restante. O administrador da assessoria tem todas as permissões e pode criar um perfil com acesso total.

O administrador **da plataforma** (tenant da plataforma) só tem as permissões de provisionamento e gestão da própria plataforma; perfis de assessoria são criados pelo administrador de cada assessoria.
