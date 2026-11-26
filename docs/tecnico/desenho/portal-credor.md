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
| 5 | Contestações aguardando o credor e resposta com documentos | feita |
| 6 | Pagamento direto informado (sempre revisado) | feita |
| 7 | Início (indicadores, "Aguardando você") e Minha atividade | feita (§3f) |
| 8 | Sandbox (carga fictícia), `audit.sh`, `verify.sh`, logs e alarme | feita (§3g) |

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

**Pendências da fatia 4:** lista dos repasses com comprovante no portal (hoje: já pago e falta pagar); ~~prazo para
responder ao demonstrativo~~ (feito na fatia 7, §3f); lista dos arquivos efetivamente enviados (o envio
grava os ids só na auditoria — o portal mostra todos os arquivos gerados do demonstrativo aprovado, decisão 1414);
manual e tour das telas do portal do credor (fatia 7); da revisão de 24/11/2026: ~~snapshot do contrato das respostas de `/api/creditor/v1` (RS-26, SEG-F05)~~ (feito na fatia 7: Início, atividade, demonstrativo, período em aberto, contestação e pagamentos informados), comprovante opaco de uso único da nova verificação no lugar do `mfaVerifiedAt` informado pelo chamador (SEG-F07 — feito na fatia 6, decisão 1436), um colaborador único para os efeitos da resposta (auditoria, evento, métrica) nos dois caminhos (AQ-04), o nome da pessoa do credor que respondeu na ficha interna e no portal (UX-02, UX-08), retenção do IP do termo no catálogo de retenção e o termo visível na ficha interna.

## 3d. Contestações que aguardam o credor (fatia 5)

| Peça | Onde | O quê |
| --- | --- | --- |
| Porta do dono | `collection.application.port.in.creditor.CreditorDisputeUseCases` (interface `collection :: creditor`; `CreditorDisputeService`) | Só o ator `CREDITOR` (outro tipo → `denyActorType`, 403) com `CREDITOR_PORTAL_OVERVIEW` (ler, baixar) ou `CREDITOR_DISPUTE_RESPOND` (responder); o credor vem do ator; só contestações com pedido ao credor (decisão 1423); outro credor → 404 com `SCOPE_DENIED`; sem pedido → 404 |
| Lista | `GET /api/creditor/v1/disputes` | Situação `AWAITING_YOU` (pedido na fila, enviado ou com falha de envio), `ANSWERED` (sem pedido aguardando, em análise) ou `CLOSED` (com o resultado); as que aguardam primeiro, pelo prazo: **todas** as que aguardam (teto de proteção de 2.000 pedidos, índice parcial `ix_dispute_request_creditor_awaiting`) e as dos 300 pedidos mais recentes (`ix_dispute_request_creditor`), com o resumo de cada contestação calculado sobre todos os pedidos dela; na tela, abas por situação em `?situacao=` (padrão "Aguardando você", decisão 1433) |
| Ficha | `GET /disputes/{id}` | Protocolo, natureza, valor, títulos (número no credor, parcela, vencimento, saldo; `DisputeViews.titles`, sem a visão interna), pedidos com assunto e texto como enviados (o link seguro trocado por aviso nos dois, decisão 1424) e documentos, respostas com posição, origem e documentos; o resumo só das respostas do portal (decisão 1425); nunca o relato do devedor, o responsável, notas internas, nome ou documento do devedor |
| Download | `GET /disputes/{id}/documents/{documentId}/content` | Só documentos dos pedidos ou das respostas do credor (decisão 1430); `attachment`, `no-store`, `X-Content-SHA256`; `FILE_DOWNLOADED` com `origin=creditor_portal`; a tela confere o SHA-256 (`CreditorVerifiedDownload`, o mesmo da prestação de contas) |
| Resposta | `POST /disputes/{id}/responses` (multipart: `response` JSON + `files`) | `requestId` de um pedido que aguarda (senão 409 `dispute_response.not_awaiting`), posição, resumo (até 4.000) e até 5 documentos de 10 MB, nenhum vazio (conferidos antes de gravar, depois de contar a tentativa no limite de envios); corpo multipart acima de 51 MiB → 413 e sem `Content-Length` → 411 antes de o multipart ser lido (`CreditorRequestSizeFilter`); `DisputeCreditorChannel.respond` com a origem `CREDITOR_PORTAL`: instante do servidor, documentos pela quarentena (toda origem externa, decisão 1426), pedido respondido, "aguardando o credor" encerrado, contestação **aberta**; limite de 10 envios com documentos por minuto por pessoa (`CreditorUploadLimiter`, decisão 1429); sem nova verificação do MFA (decisão 1428) |
| Banco | V0613 (collection) | `CREDITOR_PORTAL` no check `ck_dispute_response_source`; índice dos pedidos por credor; nenhuma tabela nova (resposta, quarentena e evidência nas tabelas existentes) |
| Assessoria | ficha da contestação | A resposta aparece com a origem "Portal do credor, pela pessoa do credor" (`recordedBy` = pessoa do credor) |
| Telas | `frontend/src/app/creditor/pages/creditor-disputes.ts` e `creditor-dispute-detail.ts` | Lista e ficha; diálogo de resposta (posição, resumo, anexos com conferência de quantidade e tamanho); 409/404 fecham o diálogo e recarregam a ficha; item "Contestações" no menu com `CREDITOR_PORTAL_OVERVIEW`; perfil sem a permissão vê o pedido e o aviso de quem responde |
| Testes | `CreditorDisputePortalIT`, `CreditorUploadLimiterTest` (429 com `Retry-After` e a janela seguinte; 413/411 sem ler o corpo), `creditor-dispute-detail.spec.ts` | Lista só com pedido, 404 sem pedido e de outro credor (com `SCOPE_DENIED`), porta recusa a assessoria, texto do pedido sem o link, download registrado e evidência interna 404, perfil de consulta não responde, mais de 5 documentos recusado, resposta com quarentena (só vira evidência depois da verificação), origem e pessoa na visão interna, contestação aberta, pedido respondido, 409 na segunda resposta, evento e auditoria com o ator `CREDITOR`, resumo da assessoria oculto; na tela, multipart com o pedido, a posição e os arquivos, 409 de pedido já respondido recarrega e 409 de regra (limite de documentos) mantém o diálogo com o texto |

**Pendências da fatia 5:** ~~recusa de PDF com JavaScript na quarentena~~ (feito na fatia 6, decisão 1441); "Aguardando você" e o prazo na tela
inicial (fatia 7); manual e tour das telas do portal (fatia 7); notificação por e-mail ao credor de pedido novo pelo
portal (fase 2, com as notificações); pedido de juntada de documento depois da resposta (fase 2); limite de envios em
memória por instância (o mesmo risco aceito do limite de login, §5; troca pelo limitador compartilhado quando ele existir); conferir, no ambiente com o balanceador e antes da produção, que os envios chegam com `Content-Length` (se o ALB repassar em chunked, trocar o 411 por um contador de bytes lidos que corta no teto); IT de respostas concorrentes (hoje: `@Version` do pedido → 409 `concurrent_modification` e os arquivos da quarentena apagados no rollback).

## 3e. Pagamento direto informado (fatia 6)

| Peça | Onde | O quê |
| --- | --- | --- |
| Porta do dono | `payment.application.port.in.creditor.CreditorPaymentUseCases` (interface `payment :: creditor`; `CreditorPaymentService`, `CreditorEvidenceScreening`) | Só o ator `CREDITOR`; `CREDITOR_PAYMENT_REPORT` para buscar títulos e informar, `CREDITOR_PORTAL_OVERVIEW` para a lista (decisão 1442) |
| Busca de título | `GET /api/creditor/v1/payments/titles?number=` | Número exato do título no credor da sessão; inexistente, de outro credor ou sem saldo → vazio; devolve vencimento, parcela, saldo e se está em acordo (nada do devedor); 30 por minuto por pessoa |
| Destino | `CreditorPaymentService#resolve` | Devedor derivado; títulos do mesmo devedor e em aberto; em acordo ativo → o acordo que cobre todos (parcelas em aberto, da mais antiga); mistura ou acordos diferentes → 422; valor acima do saldo → 422 (decisão 1438). Acordos lidos por `negotiation :: allocation` (decisão 1435) |
| Comprovante | `CreditorEvidenceScreening` (fora de transação) | Até 10 MB, tipo pelo conteúdo, conteúdo ativo em PDF recusado, verificador de malware: infectado → 422 e `MALWARE_DETECTED`, fora do ar → 503 com `Retry-After`, desligado → `NOT_SCANNED` gravado e destacado na revisão (decisão 1437) |
| Informe | `POST /api/creditor/v1/payments` (multipart: `report` JSON + `evidence`; `Idempotency-Key` obrigatória) | `CreditorPaymentPortalService`: verificação do comprovante fora de transação; depois, numa transação, `requireCanReport` (sem efeito), `CreditorStepUp.verify(código, PAYMENT_REPORT, alvo)` e `report` — trava por credor, limite de 20 em análise, duplicidade (409), consumo do comprovante de uso único (`StepUpProofs`, decisão 1436), idempotência no escopo da pessoa, `Payment.informedByCreditor` (sempre `INFORMED`, motivo `CREDITOR_PORTAL`, origem `CREDITOR_DIRECT`); envio conta no limite de 10 por minuto por pessoa antes das checagens |
| Banco | `V0812` (payment) | `informed_channel`, `creditor_title_numbers`, veredito do comprovante e `creditor_rejection_reason`; `check`s: canal do portal sempre revisado e só decidido com `reviewed_by`, motivo para o credor obrigatório na recusa (decisão 1443); índice `ix_payment_creditor_portal` |
| Assessoria | fila e ficha de aprovação (`/pagamentos/aprovacoes/:id`, `/pagamentos/:id`) | Motivo "Informado pelo próprio credor no portal", quem informou "Pessoa do credor (portal)", títulos informados e o resultado da verificação do comprovante; recusa com o motivo que o credor vê (`CreditorRejectDialog`; `POST /payment/payments/{id}/reject` com `creditorReason`, decisão 1440) |
| Telas | `frontend/src/app/creditor/pages/creditor-payments.ts` | Lista "Pagamentos informados" (situação e motivo da recusa) e diálogo "Informar pagamento" (títulos conferidos um a um, valor com `appMoney`, data não futura, meio, referência, observação, comprovante e código); chave de idempotência por diálogo |
| Testes | `CreditorPaymentPortalIT`, `CreditorPortalIdentityIT` (comprovante de uso único), `PdfActiveContentTest`, `CreditorApiBoundaryIT`, `creditor-payments.spec.ts` | Busca só dos próprios títulos, perfil de consulta sem gastar o código, porta recusa a assessoria, sempre em análise (R$ 300), alvo do comprovante, idempotência, duplicidade, saldo, título de outro credor, PDF com JavaScript sem gravar nada, recusa com motivo (a nota não vai ao credor), aprovação, destino do acordo e limite de 20 |

**Pendências da fatia 6:** verificação de malware com ClamAV em produção (desligada por padrão; risco aceito até antes da
produção, issue #33 — com o clamd ligado, o informe passa a depender dele de forma síncrona: 503 se cair, e o dimensionamento da instância muda); tempo limite próprio e curto (cerca de 10 s, com prazo total) para a verificação síncrona (SEG-20261125-I04; hoje vale o de 30 s por operação dos jobs); verificação de malware do comprovante pela API pública e pela assessoria (SEG-H02, falta a parte de malware); limite da busca de títulos compartilhado entre instâncias (decisão 1442); a pausa da cobrança dos títulos enquanto o informe está em análise (hoje só a quebra do acordo fica
suspensa, §5.2 de pagamentos); "Aguardando você" e indicadores (fatia 7); manual e tour do portal (fatia 7); destaque dos
títulos baixados por pagamento informado pela API pública e pela importação (decisão do responsável sobre o H07).

## 3f. Início e Minha atividade (fatia 7)

Decisões do responsável de 26/11/2026: "acordos em dia" é foto na data de corte e o número principal (alerta abaixo de
80%); o demonstrativo enviado tem prazo de 10 dias corridos, **só lembrete**; "saldo em cobrança" entra agora.
Definições em [indicadores.md](../../negocio/indicadores.md) §10; decisões autônomas 1529–1544.

| Peça | Onde | O quê |
| --- | --- | --- |
| Indicadores | `analytics :: creditor` — `CreditorIndicatorsQueries` ([analytics.md](analytics.md) §11) | Recuperado no período e no anterior, parte paga direto ao credor, série de 6 meses, 5 carteiras + "Outros", saldo em cobrança e acordos em dia (numerador e denominador); toda SQL com `creditor_id = :creditorId`; cache curto (60 s, invalidado pelo refresh) depois da permissão |
| Repasse estimado | `settlement :: creditor` — `openPeriod()` (já com o cache da posição) | Líquido e direção do período em aberto; o período em aberto ganhou `daily`/`previousDaily` (acumulado por dia, para o gráfico) |
| Prazo do demonstrativo | `settlement` V0902 (`statement.response_due_on`, `check` com `sent_at`, índice dos `SENT` por credor), `StatementResponseDeadline` (domínio) | Gravado no envio = data do envio no fuso de negócio + `app.settlement.statement-response-days` (padrão 10); os já enviados receberam envio + 10. Vencido continua `SENT`. Exposto em `responseDueOn`/`responseOverdue` na lista e na ficha do portal e em `StatementView.responseDueOn` na ficha interna; a lista do portal ganhou `transferred`/`outstanding` |
| Aguardando você | `collection :: creditor` `awaiting(limit)`, `settlement :: creditor` `awaitingResponse()`, `payment :: creditor` `recentlyRejected()` | Contestações `AWAITING_YOU` (o mesmo caminho da lista: índice parcial dos pedidos que aguardam, resumo sobre todos os pedidos), demonstrativos `SENT` pelo prazo e informes do portal recusados nos últimos 30 dias (pela data da decisão); "vence logo" = até 2 dias; **sem cache** (RS-63) |
| Minha atividade | `identity :: creditor` — `CreditorActivityQueries` (`CreditorActivityService`, `JdbcCreditorActivityReader`) | `shared.audit_event` com `actor_type = 'CREDITOR'` e `shared.access_log` das pessoas do credor do ator (`identity.creditor_user` com `creditor_id = :creditorId`, revogadas incluídas) — **nunca** por `audit_event.creditor_id`; lista branca em `CreditorActivityCatalog` (código curto + referência; nunca antes/depois, metadados, motivo, nome do ator, IP ou sessão); janela de 90 dias, até 50 por página, cursor opaco (`occurred_at`, `id`), nunca o `seq`; time só com `CREDITOR_ACTIVITY_VIEW` (Gestão e Compliance), com `USER_DIRECTORY_QUERIED` |
| API | `creditorportal.web.CreditorHomeController` (`GET /overview?period=`, `GET /awaiting`) e `CreditorActivityController` (`GET /activity?scope=own\|team&before=&size=`) | Records próprios (`CreditorHomeUseCases`, `CreditorActivityPortalUseCases`); período numa lista fechada (outro valor → 400); `Cache-Control: no-store`; 30 leituras por minuto por pessoa no Início e na atividade, 60 no "Aguardando você" (`CreditorReadLimiter`, 429 com `Retry-After`, `RATE_LIMITED` com `scope=creditor_read` só na primeira recusa da janela — decisão 1563; `CreditorReadLimitIT`) |
| Falha parcial | `CreditorHomeService` (sem transação própria) | Cada fonte roda na transação da porta dela; só falha técnica (`TemporarilyUnavailableException`, `DataAccessException`, `TransactionException`) deixa a seção nula e o nome em `unavailable` (`INDICATORS`, `TRANSFER`, `DISPUTES`, `STATEMENTS`, `PAYMENTS`); recusa de permissão, escopo, regra e erro de programação sobem (decisão 1558) |
| Tempo limite por fonte | `shared.persistence.CreditorPortalReadTimeout` (`app.creditor-portal.source-timeout`, padrão 2 s) | Cada porta `:: creditor` lida pelo Início (`CreditorIndicatorsService.indicators`, `openPeriod`, `awaitingResponse`, `disputes.awaiting`, `recentlyRejected`) aplica no começo da transação somente leitura um `statement_timeout` local (`set_config(..., true)`, via `shared.persistence.StatementTimeout`); a fonte que passar dele cai como `DataAccessException` e fica indisponível sozinha (decisão 1558) O limite é por comando SQL (não prazo total da fonte), aplicado depois de conferir o ator; vale também para a Prestação de contas, que lê o período em aberto pela mesma porta. |
| Regras | `ArchitectureTest` | `creditorPortsAreUsedOnlyByTheCreditorPortalAndTheirOwners` (fora do dono, só `creditorportal` e `sandbox` dependem de `..application.port.in.creditor..`) e `creditorPortalEndpointsNeverTakeACreditorId`; `creditorportal` declara `analytics :: creditor` |
| Telas | `frontend/src/app/creditor/pages/creditor-home.ts`, `creditor-activity.ts` (`/credor/:slug/atividade`), `creditor-statements.ts`, `creditor-statement-detail.ts`; `data/creditor-overview.api.ts` (`CreditorOverviewApi`), `creditor-awaiting.ts` (`CreditorAwaitingState`), `creditor-indicators.ts` | Início: período em `?periodo=` (`este-mes` padrão, `mes-passado`, `ultimos-3-meses`, `este-ano`), "Dados até", cartões "Acordos em dia" (primeiro, em destaque; alerta abaixo de 80% com o motivo escrito; sem acordo, texto), "Saldo em cobrança", "Recuperado no período" (variação com sinal e texto, parte paga direto), "Repasse estimado" (sempre positivo, com o início do período em aberto e quem paga quem; não segue o período escolhido) e "Aguardando você"; gráficos do recuperado nos últimos 6 meses e por carteira (5 + "Outros"), com resumo escrito e "Ver tabela"; falha do resumo num aviso único acima dos cartões (um "Tentar novamente"), com cartões e gráficos em "Indisponível agora"; grade de 5 cartões (decisão 1560); período em `<select>` no celular. "Aguardando você" e o contador do menu leem o mesmo `GET /awaiting`, relido quando o caminho muda (consulta e fragmento não releem); `youCanAct` decide "Aguardando o seu time". "Minha atividade" no menu da pessoa, abas "Minhas ações"/"Do seu time" (`?aba=time`, só com `CREDITOR_ACTIVITY_VIEW`), "Carregar mais" pelo cursor (com aviso "N ações carregadas" ao leitor de tela). Pagamento recusado e "Abrir o pagamento" levam a `/pagamentos?pagamento=<id>`, com a linha em destaque e o foco nela (decisão 1562). Prestação de contas: prazo de resposta e "Falta pagar" na lista, recuperado acumulado dia a dia no período em aberto (`daily`/`previousDaily`); ficha com o prazo e o medidor do líquido pago; a ficha interna mostra o prazo do credor |
| Testes | `CreditorOverviewIsolationIT`, `CreditorAwaitingIT`, `CreditorActivityIT`, `CreditorSettlementIT`, `CreditorPaymentPortalIT`, `CreditorDisputePortalIT`; `CreditorIndicatorRulesTest`, `StatementResponseDeadlineTest`, `CreditorActivityCatalogTest` (armadilhas: tipo do log de acesso ou ação de auditoria do credor sem decisão), `CreditorReadLimiterTest` | Isolamento entre credores e assessorias com valores distintos; "Aguardando você" pelo filtro da lista; atividade sem ação da assessoria nem nome dela, perfis sem a permissão recusados no time; snapshot do contrato (`src/test/resources/contracts/creditor-portal/`) |

**Pendências da fatia 7:** prazo do demonstrativo configurável **por assessoria** (hoje `app.settlement.*`, decisão
1534); limite de leituras compartilhado entre instâncias (o mesmo risco aceito do §5); referência legível dos itens de
"Minha atividade" (número do demonstrativo, protocolo) — hoje o portal mostra o tipo e o link do registro; os pedidos
do credor ("Meus pedidos recentes") e o aviso de eliminação do D01h (decisão 1469) entram no "Aguardando você" quando
existirem.

## 3g. Sandbox, auditoria, verificação e alarmes (fatia 8)

Avaliação preventiva de 26/11/2026 (RS-80 a RS-93); decisões autônomas 1564–1573. Guia de uso:
[operacao/sandbox.md](../operacao/sandbox.md) §2c.

| Peça | Onde | O quê |
| --- | --- | --- |
| Carga | `sandbox.application.service.CreditorPortalSeeder` (etapas "portal do credor (liberação e prestação de contas)" e "portal do credor (pessoas e ações)", antes da etapa final de demonstrativo e painéis) | Pela assessoria (Gestor com `CREDITOR_PORTAL_MANAGE`): portal liberado no 1º credor de cada assessoria e no 2º da `aurora`, convites (`DemoCatalog.creditorPortal`); cadeia de demonstrativos do 1º credor (um enviado 14 dias atrás pelo relógio de demonstração, com prazo vencido; um enviado hoje; um calculado) e pedido ao credor numa contestação aberta. Pelas pessoas do credor, **só no tempo real** (RS-84): ativação pelo fluxo real e as ações — Financeiro aceita o demonstrativo, Jurídico responde à contestação com um documento fictício (quarentena), Gestão informa um pagamento que o Financeiro da assessoria recusa com o motivo para o credor (RS-86); sessões encerradas no fim e aviso se sobrar convite pendente ou pessoa bloqueada (RS-87) |
| MFA (opção D) | `CreditorMfaVault` / `FileCreditorMfaVault` (`app.sandbox.seed.creditor-mfa-file`, `/sandbox-data/creditor-mfa.txt`, 600, regravação atômica), `DemoTotp` (RFC 6238 próprio, decisão 1565) | O segredo é o que o `activation/start` devolve; guardado antes do `activation/complete`. Cada código usa um passo de 30 s posterior ao último daquela pessoa (e ao início da execução); 429 espera o `Retry-After` e a nova tentativa usa outro código (RS-89) |
| Ator do credor | `HttpCreditorPortalClient` (`app.sandbox.seed.creditor-portal-url`) | Chama `/api/creditor/v1/...` com a `Origin` da aplicação e o token da sessão real (RS-83); multipart com `Content-Length`; erros só com a situação HTTP e o código do problema, nunca corpo, token ou código |
| Perfil sandbox | `application-sandbox.yml` | Nenhuma chave `app.security.creditor-portal.*`; MFA do credor sempre exigido, Origin obrigatório (RS-85) |
| Scripts | `scripts/sandbox/credor-codigo.sh`, `verify.sh` §10–11, `audit.sh` (perfis `assessoria/<pessoa do credor>`) | Código no host com o OpenSSL; verificação só com leituras e recusas esperadas (RS-91); Mailpit só em `127.0.0.1` e túnel só para a aplicação (RS-92); auditoria com o segredo por arquivo temporário 600 montado só leitura, sem trace, HAR nem vídeo, sem acionar ação (RS-88, RS-90) |
| Alarmes | `infra/terraform/modules/observability` (`alarms_creditor_portal.tf`, filtros `creditor-auth-failures` e `creditor-scope-denied` em `main.tf`, `tests/creditor_portal.tftest.hcl`) | Fontes do Início indisponíveis, picos de recusa por limite, falhas de autenticação do credor e sondagem de escopo, sem dimensão de assessoria, credor, pessoa, e-mail ou IP (RS-93, decisão 1571) |
| Regras | `ArchitectureTest` | `onlyTheCreditorIdentityServicesStartTheActivation`, `identityNeverDependsOnTheSandbox`, `creditorIdentityPortsNeverTakeASecret`, `onlyTheCreditorTokenResolverBuildsTheCreditorActor`; o `sandbox` deixa de poder usar as portas `:: creditor` |
| Testes | `CreditorPortalIdentityIT` (segredos aleatórios por convite, 32 caracteres Base32, cifrados), `SandboxCreditorPortalStrictIT` (perfil sandbox: login → `MFA_REQUIRED` sem token, Origin exigido, reuso recusado), `SandboxCreditorPortalNotRelaxedTest` (YAML sem `app.security.creditor-portal`; `toString` mascarados), `SandboxCreditorMfaVaultTest` (sem perfil ou sem a flag → sem bean; sandbox + prod → não sobe; arquivo 600; `toString` sem segredo), `DemoTotpTest` (vetores do RFC 6238) | |

**Pendências da fatia 8:** o `app.security.cookie-secure: false` do sandbox (geral, para `http://localhost`) também tira o
`Secure` do cookie de refresh do credor — aceitável só em `localhost`; com o túnel em https, avaliar ligar o `Secure`;
a lista de fontes e de operações dos alarmes é explícita (fonte ou operação nova pede ajuste no alarme); o alarme de
`LOGIN_BLOCKED` conta também as tentativas de quem já está bloqueado (falta um contador de bloqueios na aplicação); a
carga ainda abre sessão do Jurídico e da Gestão a cada subida sem reset antes de saber se há o que fazer (conferir pelo
lado da assessoria antes); o `HttpCreditorPortalClient` ainda não tem IT próprio (o contrato é exercitado no `up.sh` e
no `verify.sh`).

## 4. Requisitos de segurança (checklist das próximas fatias)

Da avaliação preventiva (RS-xx). Crítica ou Alta confirmada bloqueia o commit da fatia.

| Fatia | Requisitos |
| --- | --- |
| 2 | Resolvedor do credor **sem cache** (revogação imediata), monta o ator a partir da sessão no banco e confere audiência e `cid` (RS-04, RS-22); etapas do credor só no leitor do credor (RS-03); convite: `CREDITOR_PORTAL_MANAGE` + escopo + portal liberado, e-mail que não seja de usuário interno ativo, `OpaqueToken` no fragmento, uso único, 72 h, reemissão invalida o anterior, link com `app.base-url` (RS-09, RS-10); ativação numa transação (senha + MFA confirmado), conta sem MFA nunca abre sessão (RS-11, RS-13); respostas iguais para token inválido/vencido/usado e no login/esqueci (RS-12, RS-15); bloqueio persistido por conta e por IP (RS-16); `PublicEndpoint` com padrões exatos (RS-17); contador único senha + TOTP e anti-replay de passo (RS-18, RS-19); sessão 30 min ociosa / 8 h absoluta / até 3, refresh rotativo com detecção de reuso, cookie `gc_creditor_refresh` (`HttpOnly`, `Secure`, `SameSite=Strict`, caminho `/api/creditor/v1/auth`), Origin obrigatório (RS-20); segredo TOTP cifrado (RS-14) |
| 2 | Da auditoria de 24/11/2026 (`seguranca-riscos.md`): MFA do credor **sempre** obrigatório, nunca decidido pelo código do perfil (B01); troca de senha expirada só **depois** do MFA — não reaproveitar a ordem do `AuthenticationService` (B02); token nunca no caminho da URL, só no fragmento ou no corpo (B04); não gravar log de acesso a cada requisição anônima sem limite (B05) |
| 2/4/6 | Nova verificação **na própria requisição da ação** (código no corpo, verificado na mesma transação, sem "sessão elevada"); falhas somam ao bloqueio e revogam a sessão no limite (RS-23, RS-24) |
| 4 | Métodos próprios `acknowledgeAsCreditor`/`contestAsCreditor` (`CREDITOR_STATEMENT_RESPOND` + tipo do ator), nunca `SETTLEMENT_CLOSE` (RS-30); termo append-only com origem, ator, sessão, IP, instante e SHA-256 de cada arquivo (hash divergente → 409) (RS-31); demonstrativo visível só em `SENT`/`ACKNOWLEDGED`/`CONTESTED` (RS-27); download só do próprio credor, `attachment`, `no-store`, com `FILE_DOWNLOADED` (RS-37, RS-38) |
| 5 | Quarentena para **toda** origem externa no `DisputeCreditorChannel` (feito: `CreditorResponseSource.isExternal()`) e origem `CREDITOR_PORTAL` (RS-32, RS-39 — a recusa de PDF com JavaScript entra na fatia 6); `receivedAt` do servidor; a resposta não resolve a contestação (feito, §3d) |
| 6 | Revisão sempre, imposta no caso de uso pelo tipo do ator e no banco (RS-33, feito); só números de título, devedor e destino derivados no servidor (RS-34, feito); comprovante verificado antes de gravar, com recusa de conteúdo ativo em PDF (RS-39, decisão 1437, feito); `Idempotency-Key` obrigatória com prefixo da pessoa do credor (RS-35, feito); limite de informes em análise (RS-36, feito); comprovante de uso único da nova verificação (SEG-F07, decisão 1436, feito) |
| 4–6 | Restrições `check` de `actor_type` que só aceitam `USER`/`ASSISTANT`/`DEBTOR`/`SYSTEM` (negotiation V0702, V0750, V1300; incentive V1801; `ck_message_sender` em communication V1000): o credor nunca grava nessas tabelas ou o módulo dono mapeia uma origem própria (`CREDITOR_PORTAL`) com migration na faixa dele |
| 7 | RS-60 a RS-71 (avaliação preventiva de 26/11/2026), atendidos no §3f: credor sempre do ator dentro do módulo dono e nenhum `creditorId` em porta ou endpoint (ArchUnit); toda SQL dos indicadores com `creditor_id = :creditorId`; porta própria do analytics, sem as consultas da assessoria (nada de comissão, custo, operador, equipe, canal, origem ou motivo de suspensão); "Aguardando você" pelos mesmos filtros das listas, sem cache (RS-63); atividade só do ator `CREDITOR` e das pessoas do credor, nunca por `audit_event.creditor_id`, com lista branca de ações e de campos e armadilha para ação sem decisão; time só com `CREDITOR_ACTIVITY_VIEW` e registrado; cursor opaco sem `seq`, janela de 90 dias e página de até 50; limite de leituras por pessoa e `no-store`; período em lista fechada; snapshot do contrato |
| todas | DTOs com lista branca e teste de contrato por snapshot (RS-26); nenhum endpoint recebe `creditorId` do cliente; outro credor → 404 igual + `SCOPE_DENIED` (RS-06); ArchUnit para o `creditorportal` (RS-08); limite de tamanho antes de gravar (RS-40); logs sem dado pessoal e "Minha atividade" restrita ao próprio credor (RS-47 a RS-49) |

## 5. Riscos aceitos no MVP

| Risco | Mitigação no MVP | Pendência |
| --- | --- | --- |
| Pessoa da assessoria convidar um e-mail próprio e "aceitar como credor" | E-mail convidado não pode ser de usuário interno ativo; o termo registra quem convidou; auditoria do convite (decisão 1395 [revisar]) | Segunda pessoa no convite ou código por outro canal, se o responsável pedir |
| Arquivos externos sem antivírus | Quarentena, tipo pelo conteúdo, recusa de PDF com JavaScript, limites, download como `attachment` | Issue #33 |
| Mesma origem do app interno | CSP `script-src 'self'`, cookie com nome e caminho próprios, token em memória, Origin obrigatório | Subdomínio próprio (opcional, item C da proposta) |
| Limite de login em memória por instância | Bloqueio persistido por conta e por IP | WAF (#5, #18) |
| IP e user agent das sessões do credor (e do usuário interno), tokens vencidos e o limite por IP guardados sem prazo | Volume baixo; nenhum dado de devedor | **Pendente:** job de retenção (anular IP e user agent de sessões encerradas, apagar tokens e limites antigos), com o prazo definido pelo `analista-negocio` e o jurídico — vale também para `auth_session` |
