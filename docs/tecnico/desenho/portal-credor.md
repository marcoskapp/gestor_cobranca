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
| 3 | Telas de identidade (`/credor`) e seção interna "Portal do credor" na ficha do credor | feita |
| 4 | `creditorportal` + prestação de contas (lista, ficha, arquivos, aceite, contestação) | feita |
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

## 3b. Telas de acesso (fatia 3)

| Peça | Onde |
| --- | --- |
| Rotas | `frontend/src/app/creditor/creditor.routes.ts`: `/credor/:slug/{entrar, ativar, esqueci-a-senha, redefinir-senha, inicio}`; `creditorSessionGuard` renova pelo cookie se a memória estiver vazia |
| Sessão | `CreditorSession` (token só em memória, `restore()` com trava entre abas `gc-creditor-refresh` e resultado `renewed`/`ended`/`unavailable`) |
| Interceptor | `creditorInterceptor`: token só em `/api/creditor/v1/*` fora de `/auth/` e `/sites/`; renova antes de vencer e uma vez no 401; só sai para a entrada quando o servidor recusa |
| Link do e-mail | `CreditorLinkToken`: a moldura tira `#token=` da URL ao abrir e a tela o lê uma vez |
| Moldura | `CreditorLayout`: marca da assessoria, credor e pessoa só da sessão desta assessoria, erro de carregamento com "Tentar novamente" |
| Assessoria | `features/organization/creditors/creditor-detail/creditor-portal-access.ts` + `creditor-portal.api.ts` |
| Testes | `creditor.interceptor.spec.ts`, `creditor-session.spec.ts` (memória, link, moldura de outra assessoria), `creditor-login.spec.ts` |

**Pendências de UX da fatia 3** (revisão de 24/11/2026):

- **Antes de convidar um credor real:** ajuda pública curta em `/credor/:slug/ajuda` (ativar, autenticador, código não funciona, perdi o celular), ligada na entrada, na ativação e no rodapé; contato da assessoria na marca (`/sites/{slug}`), mostrado no rodapé; e-mail do convite com o passo a passo do autenticador (UX-08).
- Convite vencido na lista da assessoria ("Convite vencido em…") — exige `invitationExpiresAt` na resposta (UX-05); explicação do bloqueio por tentativas ao lado do selo.
- Motivo obrigatório ao desativar o portal (UX-10) — muda o contrato da API.
- Tour da ficha do credor com o passo do portal; tour leve, "i" dos indicadores e "Ajuda" no menu na fatia 7.
- Avisar as outras abas na saída (`BroadcastChannel`), junto com as telas que mostram dados do credor (RC-10).

## 3c. Prestação de contas (fatia 4)

| Peça | Onde | O quê |
| --- | --- | --- |
| Porta do dono | `settlement.application.port.in.creditor.CreditorSettlementUseCases` (interface `settlement :: creditor`; `CreditorSettlementService`) | Só o ator `CREDITOR` (outro tipo → `denyActorType`, 403) com `CREDITOR_PORTAL_OVERVIEW` (ler) ou `CREDITOR_STATEMENT_RESPOND` (responder); o credor vem do ator; demonstrativo de outro credor → 404 com `SCOPE_DENIED`; fora de `SENT`/`ACKNOWLEDGED`/`CONTESTED` → 404 |
| Aceite | mesma porta + `creditorportal` | `requireCanRespond` (sem efeito: permissão e demonstrativo visível), depois `CreditorStepUp.verify(code, "statement_acknowledge")` e o aceite **na mesma transação** (`CreditorStatementService`); o conjunto de SHA-256 que a pessoa viu precisa ser igual ao dos arquivos atuais (senão 409 `statement.files_changed`); a transição é a mesma do domínio (`acknowledge`), com a origem `CREDITOR_PORTAL` passada na transição (`ResponseOrigin`); a assessoria não aceita por cima de contestação feita no portal (decisão 1420) |
| Contestação | idem | Motivo obrigatório, sem código do autenticador; só de `SENT` (regra do domínio); valores nunca mudam |
| Termo | `settlement.statement_creditor_term` (V0901, só de inclusão: gatilho e `revoke update, delete`) | Decisão, pessoa, sessão, instante, IP, instante da nova verificação (obrigatório no aceite por `check`), observação ou motivo, arquivos (id, nome, formato, SHA-256) e `term_sha256` do texto canônico `v1\|tenant\|demonstrativo\|decisão\|pessoa\|sessão\|instante\|mfa\|líquido\|sha256(observação)\|hashes` |
| Download | `GET /statements/{id}/files/{fileId}/content` | Só arquivo do demonstrativo visível; `attachment`, `no-store`, `X-Content-SHA256`; `FILE_DOWNLOADED` com `origin=creditor_portal`; arquivo de outro demonstrativo → 404 (com `SCOPE_DENIED` se for de outro credor); a tela confere o SHA-256 (conteúdo e cabeçalho) e só salva se conferir |
| API | `creditorportal.web.CreditorStatementController` (`/api/creditor/v1/statements`, `/open-period`, `/{id}`, `/{id}/acknowledge`, `/{id}/contest`) | Records próprios do `creditorportal` (lista de campos permitidos); nenhum id de pessoa da assessoria nem a memória de cálculo |
| Período em aberto | `SettlementPositionService.positionWithoutAccessCheck` + `SettlementBalances` | Posição estimada do credor (mesmo cache da posição interna); o saldo dos demonstrativos aprovados sai de `SettlementBalances` (só cálculo, também usado pelos repasses), sem as checagens internas |
| Assessoria | `StatementView.creditorResponseOrigin` | A ficha interna do demonstrativo mostra "Resposta do próprio credor no portal do credor"; nessa origem `creditorResponseBy` vem nulo (a pessoa do credor fica no termo) |
| Regras | `ModularityTest`, `ArchitectureTest` | Interfaces `<modulo> :: creditor` só declaradas pelo `creditorportal` (e pelo `sandbox`); `creditorPortalWebAnswersOnlyWithItsOwnRecords` (a web e a porta do portal não usam records de `port/in` de outros módulos); `onlyTheCreditorPortalUsesCreditorOnlyUseCases` (`CreditorStepUp` só no `creditorportal`) |
| Telas | `frontend/src/app/creditor/pages/creditor-statements.ts` e `creditor-statement-detail.ts` | Período em aberto e lista; ficha com valores, arquivos (código de integridade), aceite (código do autenticador) e contestação; item "Prestação de contas" no menu só com `CREDITOR_PORTAL_OVERVIEW`; guarda `creditorAccessGuard(permissão)` (sessão e permissão juntas, sem corrida ao recarregar) |
| Testes | `CreditorSettlementIT`, `CreditorApiBoundaryIT`, `SettlementLifecycleTest`, `StatementTermDigestTest` (o hash do termo muda com a observação, a decisão, o valor e os arquivos), `creditor-statement-detail.spec.ts` | Período em aberto sem permissão interna, escopo e 404 com `SCOPE_DENIED`, perfil de consulta sem gastar o código, hash divergente, sem código, termo gravado e imutável (gatilho e privilégio), contestação, aceite da assessoria recusado sobre contestação do portal, download registrado e de outro demonstrativo; na tela, 409 recarrega a ficha e arquivo divergente não é salvo |

**Pendências da fatia 4:** lista dos repasses com comprovante no portal (hoje: já pago e falta pagar); prazo para
responder ao demonstrativo (entra em "Aguardando você", fatia 7); lista dos arquivos efetivamente enviados (o envio
grava os ids só na auditoria — o portal mostra todos os arquivos gerados do demonstrativo aprovado, decisão 1414);
manual e tour das telas do portal do credor (fatia 7); da revisão de 24/11/2026: snapshot do contrato das respostas de `/api/creditor/v1` (RS-26, SEG-F05), comprovante opaco de uso único da nova verificação no lugar do `mfaVerifiedAt` informado pelo chamador (SEG-F07, junto com a fatia 6), um colaborador único para os efeitos da resposta (auditoria, evento, métrica) nos dois caminhos (AQ-04), o nome da pessoa do credor que respondeu na ficha interna e no portal (UX-02, UX-08), retenção do IP do termo no catálogo de retenção e o termo visível na ficha interna.

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
