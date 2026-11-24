# Portal do credor — desenho técnico

Negócio e decisões do responsável: [`negocio/portal-credor.md`](../../negocio/portal-credor.md). Decisão de
arquitetura: [ADR 0011](../../arquitetura/adr/0011-portal-do-credor-ator-creditor.md). Parecer do `arquiteto` e
avaliação preventiva do `engenheiro-seguranca` de 24/11/2026; decisões autônomas a partir da 1391.

## 1. Visão geral

| Peça | Onde | Responsabilidade |
| --- | --- | --- |
| Ator | `shared.security.Actor` (`ActorType.CREDITOR`, `Actor.creditor`) | Pessoa do credor: escopo de um credor, permissões só de `Permission.CREDITOR_ONLY`, nunca `bypassesStaffRules()` |
| Token | `TokenClaims.TYPE_CREDITOR` (+ etapas `creditor_mfa`, `creditor_mfa_enroll`, `creditor_pwd`) | Audiência `gestor-cobranca-creditor`, exclusiva; mapa explícito em `JwtKeyConfig` (tipo desconhecido → recusado) |
| API | `/api/creditor/v1` (`ApiPaths.CREDITOR`) | Só `ACTOR_CREDITOR`; a API interna (`/api/**`) só `ACTOR_USER` |
| Identidade | `identity` (subpacotes `creditor`, interface nomeada `identity :: creditor`), migrations V0210+ | Usuário do credor, convite, senha, MFA, sessão, refresh, nova verificação, liberação do portal por credor, "Minha atividade" |
| Negócio | módulo `creditorportal` (V2600–V2699, reservada) | Monta as respostas a partir dos casos de uso dos módulos donos, com DTOs de lista de campos permitidos |
| Frontend | `frontend/src/app/creditor/` (rota `/credor/:slug/...`) | Moldura própria, token só em memória, interceptor que só anexa o token a `/api/creditor/v1` |

## 2. Fatias de implementação

| # | Conteúdo | Situação |
| --- | --- | --- |
| 1 | Fundação de segurança: ator, permissões `CREDITOR_ONLY` e `CREDITOR_PORTAL_MANAGE`, API interna por lista de permissão, audiência por mapa explícito, `bypassesStaffRules()`, frontend sem token interno em `/api/creditor` | feita |
| 2 | Identidade no backend: V0210, convite, ativação, login, MFA, sessão, refresh, revogação, nova verificação, logs | feita |
| 3 | Telas de identidade (`/credor`) e aba interna "Portal do credor" na ficha do credor | — |
| 4 | `creditorportal` + prestação de contas (lista, ficha, arquivos, aceite, contestação) | — |
| 5 | Contestações aguardando o credor e resposta com documentos | — |
| 6 | Pagamento direto informado (sempre revisado) | — |
| 7 | Início (indicadores, "Aguardando você") e Minha atividade | — |
| 8 | Sandbox (carga fictícia), `audit.sh`, `verify.sh`, logs e alarme | — |

## 3. Fronteira de autenticação (fatia 1)

- `SecurityConfig`: `/api/portal/v1/**` → `ACTOR_DEBTOR`; `/api/creditor/v1/**` → `ACTOR_CREDITOR`; `/api/**` e
  `/v3/api-docs/**` → `INTERNAL_ONLY` (exige `ACTOR_USER`; tipo de ator novo fica de fora por padrão). Os controllers
  que usavam `isAuthenticated()` exigem `ACTOR_USER`.
- `UserAccessTokenResolver` confere a audiência interna (defesa em profundidade, como o `PortalTokenResolver`).
- `Actor.bypassesStaffRules()` (`switch` exaustivo): sistema, assistente, integração e devedor mantêm o sentido de "não é
  usuário" nos serviços (dispensa de responsável e da permissão de pessoa da assessoria); o credor não. `CaseAccess`,
  `ConversationAccess` e `LegalAccess` em lista de permissão: dispensado → regra antiga; usuário → regra do usuário;
  outro tipo → recusado.
- Invariante no construtor do `Actor`: só o ator `CREDITOR` tem `CREDITOR_ONLY` (nem o ator de sistema) e ele enxerga
  exatamente um credor; `ActorTest` tem uma armadilha que falha quando surge um tipo de ator novo.
- Perfis: `Role` recusa `CREDITOR_ONLY` (inclusive os padrões aplicados ao perfil de sistema); o administrador não as
  recebe; `CREDITOR_PORTAL_MANAGE` só para ADMIN e GESTOR.
- Frontend: `isCreditorRequest` — o `authInterceptor` nunca anexa o token interno a `/api/creditor/` nem renova a
  sessão interna num 401 de lá.

## 3a. Identidade do credor (fatia 2)

| Peça | Onde |
| --- | --- |
| Tabelas (V0210, todas com RLS) | `identity.creditor_portal_access` (liberação por credor), `creditor_user` (conta ativa sempre com senha e MFA — `check`), `creditor_action_token` (convite e redefinição, só o hash), `creditor_session`, `creditor_refresh_token`, `creditor_password_history`, `creditor_login_throttle` (HMAC do IP) |
| Perfis | `CreditorRole` (decisão 1396) → permissões `CREDITOR_ONLY`, resolvidas a cada requisição |
| Portas | `identity :: creditor`: `CreditorAuthentication`, `CreditorStepUp`; `identity :: usecases`: `CreditorPortalAdministration` |
| Token | `CreditorJwtTokenIssuer` (audiência do credor; etapas `creditor_mfa`, `creditor_pwd`); `CreditorTokenResolver` **sem cache** (sessão, conta, portal liberado com o credor ativo — `CreditorPortalGate` —, assessoria ativa e credor do token conferidos a cada requisição) |
| Rotas públicas (padrões exatos, Origin obrigatório) | `GET /api/creditor/v1/sites/{slug}`; `POST /api/creditor/v1/auth/{login, mfa, password/expired, activation/start, activation/complete, refresh, password/forgot, password/reset}` |
| Rotas do credor | `GET /api/creditor/v1/me`, `POST /api/creditor/v1/session/logout` |
| Rotas da assessoria (`CREDITOR_PORTAL_MANAGE`) | `/api/v1/creditors/{id}/portal` (`GET`, `enable`, `disable`, `users`, `users/{id}/resend-invitation`, `reset-access`, `role`, `revoke`) |
| Cookie | `gc_creditor_refresh` (`HttpOnly`, `Secure`, `SameSite=Strict`, caminho `/api/creditor/v1/auth`) |
| Links | `<app.base-url>/credor/<assessoria>/ativar#token=…` (72 h) e `/redefinir-senha#token=…` (1 h), token no fragmento |
| Testes | `CreditorPortalIdentityIT` (convite, ativação, login em duas etapas, fronteira do token, refresh com reuso, revogação imediata, portal desativado, bloqueio, nova verificação), `CreditorUserTest` |

## 4. Requisitos de segurança (checklist das próximas fatias)

Da avaliação preventiva (RS-xx). Crítica ou Alta confirmada bloqueia o commit da fatia.

| Fatia | Requisitos |
| --- | --- |
| 2 | Resolvedor do credor **sem cache** (revogação imediata), monta o ator a partir da sessão no banco e confere audiência e `cid` (RS-04, RS-22); etapas do credor só no leitor do credor (RS-03); convite: `CREDITOR_PORTAL_MANAGE` + escopo + portal liberado, e-mail que não seja de usuário interno ativo, `OpaqueToken` no fragmento, uso único, 72 h, reemissão invalida o anterior, link com `app.base-url` (RS-09, RS-10); ativação numa transação (senha + MFA confirmado), conta sem MFA nunca abre sessão (RS-11, RS-13); respostas iguais para token inválido/vencido/usado e no login/esqueci (RS-12, RS-15); bloqueio persistido por conta e por IP (RS-16); `PublicEndpoint` com padrões exatos (RS-17); contador único senha + TOTP e anti-replay de passo (RS-18, RS-19); sessão 30 min ociosa / 8 h absoluta / até 3, refresh rotativo com detecção de reuso, cookie `gc_creditor_refresh` (`HttpOnly`, `Secure`, `SameSite=Strict`, caminho `/api/creditor/v1/auth`), Origin obrigatório (RS-20); segredo TOTP cifrado (RS-14) |
| 2 | Da auditoria de 24/11/2026 (`seguranca-riscos.md`): MFA do credor **sempre** obrigatório, nunca decidido pelo código do perfil (B01); troca de senha expirada só **depois** do MFA — não reaproveitar a ordem do `AuthenticationService` (B02); token nunca no caminho da URL, só no fragmento ou no corpo (B04); não gravar log de acesso a cada requisição anônima sem limite (B05) |
| 2/4/6 | Nova verificação **na própria requisição da ação** (código no corpo, verificado na mesma transação, sem "sessão elevada"); falhas somam ao bloqueio e revogam a sessão no limite (RS-23, RS-24) |
| 4 | Métodos próprios `acknowledgeAsCreditor`/`contestAsCreditor` (`CREDITOR_STATEMENT_RESPOND` + tipo do ator), nunca `SETTLEMENT_CLOSE` (RS-30); termo append-only com origem, ator, sessão, IP, instante e SHA-256 de cada arquivo (hash divergente → 409) (RS-31); demonstrativo visível só em `SENT`/`ACKNOWLEDGED`/`CONTESTED` (RS-27); download só do próprio credor, `attachment`, `no-store`, com `FILE_DOWNLOADED` (RS-37, RS-38) |
| 5 | Quarentena para **toda** origem externa no `DisputeCreditorChannel` (hoje só `PUBLIC_API`) e origem `CREDITOR_PORTAL` (RS-32, RS-39); `receivedAt` do servidor; a resposta não resolve a contestação |
| 6 | Revisão sempre, imposta no caso de uso pelo tipo do ator (RS-33); só ids de título/parcela, devedor e credor derivados no servidor (RS-34); comprovante pela quarentena com recusa de PDF com JavaScript (RS-39); `Idempotency-Key` com prefixo do usuário do credor (RS-35); limites de informes em análise (RS-36) |
| 4–6 | Restrições `check` de `actor_type` que só aceitam `USER`/`ASSISTANT`/`DEBTOR`/`SYSTEM` (negotiation V0702, V0750, V1300; incentive V1801; `ck_message_sender` em communication V1000): o credor nunca grava nessas tabelas ou o módulo dono mapeia uma origem própria (`CREDITOR_PORTAL`) com migration na faixa dele |
| todas | DTOs com lista branca e teste de contrato por snapshot (RS-26); nenhum endpoint recebe `creditorId` do cliente; outro credor → 404 igual + `SCOPE_DENIED` (RS-06); ArchUnit para o `creditorportal` (RS-08); limite de tamanho antes de gravar (RS-40); logs sem dado pessoal e "Minha atividade" restrita ao próprio credor (RS-47 a RS-49) |

## 5. Riscos aceitos no MVP

| Risco | Mitigação no MVP | Pendência |
| --- | --- | --- |
| Pessoa da assessoria convidar um e-mail próprio e "aceitar como credor" | E-mail convidado não pode ser de usuário interno ativo; o termo registra quem convidou; auditoria do convite (decisão 1395 [revisar]) | Segunda pessoa no convite ou código por outro canal, se o responsável pedir |
| Arquivos externos sem antivírus | Quarentena, tipo pelo conteúdo, recusa de PDF com JavaScript, limites, download como `attachment` | Issue #33 |
| Mesma origem do app interno | CSP `script-src 'self'`, cookie com nome e caminho próprios, token em memória, Origin obrigatório | Subdomínio próprio (opcional, item C da proposta) |
| Limite de login em memória por instância | Bloqueio persistido por conta e por IP | WAF (#5, #18) |
| IP e user agent das sessões do credor (e do usuário interno), tokens vencidos e o limite por IP guardados sem prazo | Volume baixo; nenhum dado de devedor | **Pendente:** job de retenção (anular IP e user agent de sessões encerradas, apagar tokens e limites antigos), com o prazo definido pelo `analista-negocio` e o jurídico — vale também para `auth_session` |
