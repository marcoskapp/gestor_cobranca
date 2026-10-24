# Desenho — módulo `portal` (autonegociação do devedor, F19)

Escopo: F19 — o devedor consulta as condições autorizadas e formaliza o acordo com segurança. Schema `portal`, migrations V1300–V1399 (`V1300` no negotiation, `V1301` tabelas do portal, `V1302` no compliance — cada uma na pasta do schema dono). Dependências: `shared`, `negotiation`, `payment`, `communication`, `debt`, `organization` (interfaces `usecases`). Visão de negócio: [negocio/portal-autonegociacao.md](../../negocio/portal-autonegociacao.md).

## 1. Componentes

| Componente | Onde | Papel |
| --- | --- | --- |
| `PortalConfiguration` (+ carteiras) | `domain.model` | portal de um credor: endereço (`slug`), marca (nome, cores, logotipo), carteiras habilitadas, canais do código, desafio de conhecimento, validade do link, aviso de privacidade versionado pelo conteúdo |
| `AccessLink`, `AccessChallenge`, `PortalSession`, `ServiceRequest` | `domain.model` | link de uso único (SHA-256 do token), desafio (OTP ou etapa após o link; fictício para documento inexistente), sessão curta e revogável com as etapas do funil, pedido de atendimento (observação criptografada) |
| `ThrottlePolicy`, `BrandingRules`, `AccessSecrets` | `domain.service` | bloqueio progressivo puro; contraste WCAG, slug e tipo de imagem pelo conteúdo; segredos (`SecureRandom`) e comparação em tempo constante |
| `PortalAccessService` | `application.service` | acesso público: marca, pedido de código, verificação, resgate do link |
| `PortalSessionService`, `PortalSelfServiceService` | idem | sessão (renovação, saída, aviso de privacidade) e autoatendimento (dívidas, ofertas, aceite, Pix, termo, atendimento) |
| `PortalConfigurationService`, `PortalOperationsService` | idem | gestão (`PORTAL_MANAGE`) e operação (link pelo operador, pedidos) |
| `ServiceRequestDispatchJob`, `PortalMaintenance`, `PortalEventListeners`, `PortalJobs` | idem | encaminhamento à fila humana; pagamento confirmado, anonimização e expiração de sessões |
| `PortalJwtTokenIssuer`, `PortalTokenResolver` | `infrastructure.security` | token `typ=portal` com audiência própria; ator `DEBTOR` com a sessão validada no servidor |
| `Jdbc*` | `infrastructure.persistence` | rota pública global, bloqueio (`FOR UPDATE`), acordos do portal, funil |
| `PortalAccessController`, `PortalSelfServiceController`, `PortalAdminController`, `PortalRateLimiter` | `web` | `/api/portal/v1/sites/{slug}/…` (público), `/api/portal/v1/me/…` (devedor), `/api/v1/portal/…` (interno) |

## 2. Acesso seguro (ator `DEBTOR`)

```
Devedor ─► /portal/{slug}               (marca pública, aviso de privacidade)
  ├─ CPF/CNPJ + canal ─► POST access/code ─► desafio (real ou fictício) ─► communication envia o código (porteiro)
  │                        └─ resposta idêntica, tempo mínimo de 400 ms
  ├─ código (+ conhecimento) ─► POST access/challenges/{id}/verify ─► sessão + token do portal
  └─ link #token ─► POST access/link ─► sessão (ou etapa de conhecimento) — token consumido
```

* **Fatores**: posse (código de 6 dígitos no contato cadastrado do devedor, ou link de uso único com ~256 bits) + conhecimento opcional por credor (data de nascimento ou 4 últimos caracteres de um contrato/título em cobrança no portal). O banco guarda só o HMAC do código (por assessoria e documento) e o SHA-256 do token.
* **Anti-enumeração**: documento inexistente, anonimizado ou sem dívida nas carteiras habilitadas recebe o mesmo 202 e o mesmo formato (desafio fictício, nada enviado); toda falha de verificação é `401 portal.access_invalid`; o bloqueio vale igualmente para documento inexistente. O motivo real só vai ao log de acesso.
* **Limites**: por instância, 20 requisições/min por IP nos endpoints públicos (429 + `RATE_LIMITED`, antes do banco); persistido por tenant (`portal.access_throttle`, chaves HMAC): 5 falhas ou mais de 3 pedidos de código por documento e 20 falhas ou 15 pedidos por IP em 30 min bloqueiam por 15 min, dobrando a cada reincidência até 24 h (`429 portal.access_locked` + `Retry-After`). Acesso concluído zera as contagens (no documento, também o histórico).
* **Código**: 10 min, 5 tentativas, uso único; reenvio gera outro desafio (conta como pedido). Link: validade da configuração (1–168 h, padrão 48 h); novo link revoga os abertos; mensagem negada pelo porteiro revoga o link na hora.
* **Sessão**: JWT RS256 da plataforma (`shared.security`, mesma chave e `kid`) com `typ=portal`, audiência `gestor-cobranca-portal`, `sub` = devedor, `sid`, `tid`, `cid` (credor); 10 min, renovável enquanto a sessão vale (15 min de inatividade, 60 min absolutos, 3 por devedor — a mais antiga é revogada). O decodificador exige audiência coerente com o tipo (`JwtKeyConfig.audienceMatchesType`): token do portal nunca vale na API interna e vice-versa; a cadeia de segurança só aceita o ator `DEBTOR` em `/api/portal/v1/**` e o recusa (403) no restante de `/api/**`. O `PortalTokenResolver` revalida a sessão no servidor (cache de 15 s, como as sessões de usuário) — revogação e saída valem em até 15 s.
* **Ator `DEBTOR`**: id = devedor; escopo de credores = só o credor do portal; permissões mínimas fixadas no servidor (`NEGOTIATION_SIMULATE/PROPOSE`, `AGREEMENT_VIEW/FORMALIZE`, `PAYMENT_INSTRUCTION_CREATE`, `PAYMENT_VIEW`). Nenhum endpoint do devedor recebe id de devedor.

## 3. Escopo estrito do devedor (defesa em profundidade)

1. O portal só usa o devedor e o credor da sessão (`PortalContext`) e confere todo id recebido (carteira habilitada, simulação, acordo) — fora disso 404.
2. negotiation (`DebtorSelfScope`): o `NegotiationGuard` recusa negociar outro devedor; leituras por id de simulação, proposta e acordo de outro devedor → 404 + `SCOPE_DENIED`; listagens forçam o filtro do próprio devedor; o devedor recebe a visão mínima (como o assistente) e nunca o envelope da política.
3. payment (`InstructionService`): criar/consultar instrução de outro devedor → 404 + `SCOPE_DENIED`; busca restrita ao próprio devedor.
4. RLS por tenant em todas as tabelas do portal (exceto a rota pública global, que só tem slug e ids).

## 4. Negociação pelo mesmo `NegotiationGuard` (perfil `PORTAL`)

* O canal é um sujeito de alçada (`AssignmentSubject.PORTAL`, id fixo `PORTAL_CHANNEL_ID`): perfil padrão semeado `PORTAL` — nível 1, 50% do envelope, até 6 pagamentos, até R$ 5.000,00, entrada ≥ 20%, sem renegociação, formaliza sozinho. A operação pode atribuir outro perfil por credor (`AuthorityUseCases.assign`, sujeito `PORTAL`). Limite efetivo = mínimo(política, perfil), como para humanos e o assistente.
* A concessão acima do nível 0 usa o motivo `SELF_SERVICE_PREAUTHORIZED`, exclusivo do devedor (que não pode usar motivos humanos, e humanos não podem usar este).
* Ofertas: cotação (`validate`, à vista sem desconto) para o valor atualizado; simulação no nível máximo da alçada com à vista e 2…N pagamentos; o portal mostra **só** as válidas e dentro da alçada, a de menor total por número de pagamentos.
* Aceite (uma transação): a oferta pedida precisa estar entre as válidas da simulação do próprio devedor (`portal.offer_unavailable`); proposta (o guard revalida; acima da alçada → `proposal.outside_self_service`, nunca aprovação); aceite com termo PDF (canal `PORTAL`, referência `portal-session:<id>`, IP e agente da requisição, instante, SHA-256); formalização (`canFormalize`), registro em `portal.portal_agreement`. O negotiation publica `AgreementAttributionRecorded` com origem `PORTAL_SELF_SERVICE` e `CLOSER`/`ORIGINATOR` = `DEBTOR` (crédito ao influenciador pelo incentive).
* Pix: em transação própria após a formalização (falha não desfaz o acordo; o devedor gera a 2ª via): `PIX_IMMEDIATE` da 1ª parcela, válido até o fim do dia do vencimento (mín. 1 h, máx. 30 dias), reaproveitando instrução aberta.

## 5. Operação

* **Atendimento humano**: `ServiceRequest` (protocolo de 10 caracteres, motivo, canal de retorno, observação criptografada, até 5 por dia) → job `portal.service_request_dispatch`: mensagem de confirmação pelo template `PORTAL_ATENDIMENTO_<CANAL>` (abre/reutiliza a conversa; porteiro) + `HandoffRequested` para essa conversa (`DEBTOR_REQUESTED_HUMAN`, `DISPUTE` ou `PORTAL_NEGOTIATION`, resumo sem dados pessoais). Sem contato/template/conta → `UNDELIVERED`, visível em `GET /api/v1/portal/service-requests` (`INBOX_VIEW`). A contestação é registrada pelo atendente no collection.
* **Link pelo operador**: `POST /api/v1/portal/access-links` (`MESSAGE_SEND`, credor no escopo, devedor com dívida nas carteiras habilitadas) — template `PORTAL_LINK_<CANAL>` com `link` (URL `https://…/portal/<slug>/entrar#<token>` — o token vai no fragmento) e `horas`.
* **Templates exigidos** (por canal habilitado; publicados como os demais): `PORTAL_CODIGO_*` (`codigo` CODE, `minutos` INTEGER), `PORTAL_LINK_*` (`link` URL, `horas` INTEGER), `PORTAL_ATENDIMENTO_*` (`protocolo` CODE). O token do link só tem letras e o protocolo não tem documento, para passar nos filtros de dados pessoais das variáveis.
* **Funil**: etapas marcadas uma vez por sessão (`simulated_at`, `accepted_at`, `pix_issued_at`) e primeiro pagamento por acordo (consumidor `portal.payment-confirmed`); `GET /api/v1/portal/configurations/{id}/funnel` agrega numa consulta; métricas `portal.funnel{step}`.
* **LGPD**: aviso de privacidade versionado pelo conteúdo (SHA-256); o devedor confirma antes de consultar (`409 portal.privacy_ack_required`); `PortalPrivacyAcknowledged` → compliance (`compliance.portal-privacy`) grava `compliance.privacy_notice_acknowledgment` com a base legal vigente do credor (sem base vigente: grava, WARN e métrica). Anonimização (`DebtorAnonymized`, consumidor `portal.debtor-anonymized`): revoga sessões e links, apaga IP/agente das sessões, índice do documento dos desafios e observações dos pedidos.
* **Logotipo**: `FileStorage` (`PORTAL_BRANDING`, chave por tenant), PNG/JPEG/WebP até 256 KiB pelo conteúdo (SVG recusado); servido em `GET /api/portal/v1/sites/{slug}/logo` com `nosniff`, CSP `sandbox` e cache de 1 h (público controlado: só o logotipo do portal habilitado).

## 6. REST

| Recurso | Métodos | Autenticação |
| --- | --- | --- |
| `/api/portal/v1/sites/{slug}` · `/logo` | `GET` | pública |
| `/api/portal/v1/sites/{slug}/access/code` · `/access/challenges/{id}/verify` · `/access/link` | `POST` | pública (limites) |
| `/api/portal/v1/me/session` · `/session/refresh` · `/session/logout` · `/session/privacy` | `GET` · `POST` | token do portal |
| `/api/portal/v1/me/debts` · `/portfolios/{id}/offers` · `/portfolios/{id}/simulations` | `GET` · `POST` | idem |
| `/api/portal/v1/me/agreements` · `/{id}` · `/{id}/term` · `/{id}/installments/{iid}/pix` | `POST`/`GET` · `GET` · `GET` · `POST` | idem |
| `/api/portal/v1/me/service-requests` | `POST`, `GET` | idem |
| `/api/v1/portal/configurations` · `/{id}` · `/{id}/logo` · `/{id}/funnel` · `/{id}/sessions` · `/sessions/{id}/revoke` | CRUD | `PORTAL_MANAGE` |
| `/api/v1/portal/access-links` · `/api/v1/portal/service-requests` | `POST` · `GET` | `MESSAGE_SEND` · `INBOX_VIEW` |

## 7. Frontend

* Rotas públicas `/portal/:slug/{entrar,privacidade,dividas,dividas/:id,acordos,acordos/:id,atendimento}` carregadas sob demanda (`portal.routes.ts`), com moldura própria (`PortalLayout`: marca do credor via `mat.theme-overrides`, sem shell, menu ou sessão interna; o inicializador não restaura a sessão interna em `/portal`). Orçamento inicial do app interno mantido (~786 kB, limite 800 kB de aviso): só o interceptor e o estado da sessão do portal entram no pacote inicial.
* Token do portal só em memória (regra do projeto); recarregar exige nova entrada. `portalInterceptor` anexa o token só em `/api/portal/v1/me`, renova perto do vencimento e trata 401; o `authInterceptor` ignora `/api/portal/`.
* Mobile-first (390 px), linguagem simples, WCAG 2.2 AA: contraste das cores do credor validado no servidor (4,5:1 primária, 3:1 destaque; no escuro a cor é clareada), alvos ≥ 44 px, foco visível, `aria-live` para mensagens, QR Code com texto alternativo e "copia e cola" sempre em texto, link de pular para o conteúdo.
* Interno: `/autonegociacao` (lista, configuração com prévia da marca, logotipo, funil, sessões com revogação — `PORTAL_MANAGE`), `/autonegociacao/pedidos` (`INBOX_VIEW`) e "Enviar link do portal" na ficha do devedor (`MESSAGE_SEND`).

## 8. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | JWT da plataforma com `typ=portal` e audiência própria, validada por tipo no decodificador; sessão no servidor | Cookie de sessão; outro par de chaves | Reaproveita `shared.security` sem misturar sessões; o tipo + audiência impedem uso cruzado |
| 2 | Alçada do canal (`AssignmentSubject.PORTAL`) e perfil semeado `PORTAL` | Alçada por devedor; política especial | Mesmo `NegotiationGuard`; configurável por credor sem novo conceito |
| 3 | Motivo `SELF_SERVICE_PREAUTHORIZED` exclusivo do devedor | Pedir justificativa ao devedor | O nível foi autorizado pela operação no perfil; justificativas humanas não se aplicam |
| 4 | Acima da alçada do canal → recusa (`proposal.outside_self_service`), nunca fila de aprovação | Aprovação humana | Autonegociação só fecha o pré-autorizado; o resto vai ao atendimento |
| 5 | Desafio fictício + tempo mínimo de resposta no pedido de código | Responder "documento não encontrado" | Anti-enumeração (LGPD e fraude) |
| 6 | Bloqueio progressivo persistido por documento e IP (HMAC) + limite por instância | Só limite em memória | Vale entre instâncias e não guarda documento/IP em claro |
| 7 | Token do link só com letras, no fragmento da URL, apagado do endereço antes do envio | Dígitos/hex; parâmetro de consulta | Passa nos filtros de dados pessoais dos templates; não vai a logs de proxy nem ao `Referer` |
| 8 | Aceite + formalização numa transação; Pix em transação própria | Pix na mesma transação | Falha do PSP não desfaz o acordo aceito |
| 9 | Pedido de atendimento por mensagem + `HandoffRequested` na conversa criada | Chamar `ConversationUseCases` diretamente | Usa os contratos existentes; a conversa fica com caso, dono e porteiro corretos |
| 10 | Aviso de privacidade registrado no compliance por evento | Portal gravar no compliance | Grafo (portal não depende do compliance); a base do tratamento continua a do credor |
| 11 | Token do portal só em memória no navegador | `sessionStorage` | Regra do projeto; sessão curta |
| 12 | Migrations do portal que alteram outros schemas ficam na pasta do schema dono, na faixa V1300 | Faixa do outro módulo | Faixa pedida para o módulo; pasta mantém a organização por schema |

## 9. Pendências

* WAF com regra de taxa nos endpoints públicos do portal (recomendação já registrada em segurança).
* Boleto/link de pagamento no portal quando houver provedor (issue #12); hoje só Pix.
* Renegociação de acordo quebrado e mais de 6 parcelas pelo portal: dependem de a operação atribuir outro perfil ao canal (o perfil `PORTAL` padrão não renegocia).
* Registro automático da contestação no collection a partir do pedido `DISPUTE` (hoje o atendente registra).
* Modelo de termo por credor (pendência do negotiation) aparecerá automaticamente no portal.
* Templates `PORTAL_*` precisam ser cadastrados e aprovados pelos canais antes de ativar um portal (a tela não verifica).
