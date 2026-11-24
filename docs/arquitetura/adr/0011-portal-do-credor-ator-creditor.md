# ADR-0011 — Portal do credor: ator `CREDITOR`, audiência própria e módulo `creditorportal`

**Situação.** Aceita (24/11/2026). Implementa o portal do credor aprovado pelo responsável
(`docs/negocio/portal-credor.md`, decisões 1 a 12 da §10). Parecer do `arquiteto` de 24/11/2026; desenho em
`docs/tecnico/desenho/portal-credor.md`.

**Contexto.** O credor precisa de acesso próprio para acompanhar a operação e agir: aceitar ou contestar a prestação
de contas, responder contestações e informar pagamento feito direto a ele. Hoje existem os atores `USER` (assessoria),
`DEBTOR` (portal do devedor), `INTEGRATION` (API pública), `SYSTEM` e `ASSISTANT`, todos com tokens assinados pela
mesma chave. A API interna (`/api/**`) aceitava qualquer autenticação que não fosse do devedor (lista de exclusão), e
vários serviços tratavam "não é usuário" como processo confiável, dispensado de responsável e de permissões.

**Decisão.**

1. **Ator próprio** `Actor.ActorType.CREDITOR` (`Actor.creditor(...)`): escopo de um credor (uma conta por credor) e
   permissões resolvidas no servidor pelo perfil do credor — só o conjunto `Permission.CREDITOR_ONLY`; as leituras do
   portal são métodos próprios dos casos de uso, sem permissão interna. Invariante no construtor do `Actor`: só o ator
   `CREDITOR` tem `CREDITOR_ONLY` (nem o ator de sistema), e ele enxerga exatamente um credor.
2. **Token com audiência própria:** `typ=creditor` e as etapas `creditor_mfa`, `creditor_mfa_enroll`, `creditor_pwd`,
   todos com a audiência `gestor-cobranca-creditor` e exclusivamente ela (`JwtKeyConfig.audienceMatchesType`). Sem
   permissões nos claims.
3. **API própria** `/api/creditor/v1` (`ApiPaths.CREDITOR`), que exige `ACTOR_CREDITOR`. A **API interna passa a
   exigir `ACTOR_USER`** (lista de permissão): devedor, credor e qualquer ator futuro ficam de fora por padrão.
4. **"Não é usuário" deixa de ser dispensa automática:** `Actor.bypassesStaffRules()` — `switch` exaustivo, lista de
   permissão (sistema, assistente, integração e devedor, o sentido de sempre) — substitui `!isUser()` nos serviços que
   dispensavam responsável ou permissão; o credor nunca é dispensado. Os pontos que davam escopo interno (jurídico,
   trabalho do caso e da conversa) seguem a mesma lista: dispensado → regra antiga; usuário → regra do usuário; qualquer
   outro tipo → recusado. Um teste-armadilha (`ActorTest`) falha quando surge um tipo de ator novo.
5. **Identidade do credor no `identity`**, em tabelas e agregados próprios (nunca `app_user`/`auth_session`), com as
   portas na interface nomeada `identity :: creditor`, reaproveitando TOTP, Argon2, bloqueio, sessões e o envio de
   e-mail. **API de negócio no módulo novo `creditorportal`** (faixa V2600–V2699), no topo do grafo como o
   `publicapi`, com DTOs de lista de campos permitidos (nunca os records de `port/in`).
6. **Escritas do credor por métodos próprios** (`*AsCreditor`, `reportCreditorDirect`) que exigem o tipo do ator e a
   permissão `CREDITOR_*` e gravam origem e autor externo; o pagamento informado pelo portal sempre vai para revisão.

**Alternativas consideradas.**

- **Usuário interno com perfil "credor":** rejeitada — um erro de configuração daria acesso interno a alguém de fora.
- **Tudo num módulo novo:** rejeitada — duplicaria credenciais, MFA e sessões.
- **Tudo no `identity`:** rejeitada — o `identity` passaria a depender de `settlement`, `collection`, `payment` e
  `analytics` (para cima no grafo).
- **Manter a lista de exclusão na API interna** e acrescentar o credor: rejeitada — cada ator novo seria uma brecha
  até alguém lembrar de excluí-lo.

**Consequências.**

- Um ator a mais nos `switch` e nas checagens de tipo (`ConversationAccess.senderOf`, `AuthorityService.mine`).
- Contratos novos em `settlement`, `payment`, `collection` e `analytics`; tabelas novas no `identity`.
- Revogação e desativação do portal são imediatas: o resolvedor do credor não usa cache (uma consulta por requisição;
  volume baixo).
- Sem custo nem serviço novo (o e-mail usa o SMTP existente; antivírus na issue #33).
- Testes: `InternalApiAccessTest`, `PortalAudienceTest`, `ActorTest`, `DefaultRolesTest` e os ITs de isolamento de
  token, escopo e fluxos do portal do credor.
