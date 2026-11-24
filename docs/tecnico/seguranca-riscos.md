# Segurança — registro de riscos

Riscos de segurança **conhecidos e ainda não eliminados**: vulnerabilidades abertas (até a produção, decisão 1383),
controles que dependem de aprovação do responsável e riscos aceitos formalmente. Os controles já implementados ficam
em [seguranca.md](seguranca.md); as vulnerabilidades corrigidas e validadas, no histórico deste arquivo (§5) e no
commit da correção (a revisão independente de 25/10/2026 continua em [seguranca.md](seguranca.md) §7). Quem mantém este registro é a sessão principal, a partir dos
relatórios do subagente `engenheiro-seguranca` ([.claude/agents/engenheiro-seguranca.md](../../.claude/agents/engenheiro-seguranca.md)).

## 1. O que entra aqui

Até a entrada em produção (decisão 1383), **todos** os achados de segurança confirmados ficam neste arquivo, inclusive
as vulnerabilidades abertas Críticas e Altas, com evidência, cenário de exploração e correção, para que todas as
frentes de trabalho enxerguem o mesmo quadro. Quando o software for para produção, as vulnerabilidades abertas passam a
ficar fora do repositório (security advisory privado no GitHub) e este arquivo volta a registrar só o controle que
falta.

| Situação | Onde fica |
| --- | --- |
| Vulnerabilidade aberta (qualquer severidade) | §4, com o identificador `SEG-AAAAMMDD-NN` do relatório do `engenheiro-seguranca` |
| Controle que depende de aprovação (serviço AWS, ferramenta, custo) | §3, com o número da issue |
| Risco aceito formalmente | §3, com quem aceitou, quando, o motivo e a data de reavaliação |
| Vulnerabilidade corrigida e validada | sai da §4 para o histórico (§5), com o commit e o teste que a cobre; o controle vai para [seguranca.md](seguranca.md) |

Mesmo antes da produção: nunca registrar segredo, token, senha, dado pessoal ou dado real de cliente, nem em exemplo;
prova de conceito só com usuários e dados fictícios do sandbox.

## 2. Estados

`aberto` (confirmado, sem correção) → `em correção` → `corrigido` (só depois da validação do `engenheiro-seguranca`,
com teste que falharia sem a correção) — ou `aguardando aprovação` (depende de issue) — ou `aceito` (decisão do
responsável, com prazo de reavaliação). Suspeita sem evidência não entra no registro.

## 3. Riscos abertos

| ID | Risco (controle que falta) | Severidade | Estado | Mitigação atual | Referência |
| --- | --- | --- | --- | --- | --- |
| R-01 | Sem WAF à frente do ALB nem limite global por token entre instâncias na API pública | Média | aguardando aprovação | limite de taxa por instância, bloqueio progressivo no login, limites nos webhooks | issues #5, #17, #18 |
| R-02 | Segredos de produção sem cofre gerenciado e sem rotação automática | Média | aguardando aprovação | variáveis de ambiente/arquivos `0600`; caminhos `app/*` × `migration/*` separados | issues #6, #8 |
| R-03 | Sem detecção de ameaças na conta AWS | Média | aguardando aprovação | logs no CloudWatch e alarmes | issues #11, #7 |
| R-04 | Documentos de sistemas externos (portal, API, e-mail) sem antivírus | Média | aguardando aprovação | conferência de tipo pelo conteúdo, limites de tamanho e quarentena para origem externa | issue #33; decisões 816 e 819 |
| R-05 | Webhook Pix sem mTLS no ALB | Média | aguardando aprovação | mTLS só com `app.payment.webhook.mtls-enabled`; cabeçalhos `X-Amzn-Mtls-*` descartados fora do ALB | issue #30 |
| R-06 | AAD da criptografia de campo sem tabela/coluna/id (só a assessoria) | Baixa | aberto — aceite formal pendente do responsável (limitação do converter JPA) | AAD com a assessoria (formato v2) | [seguranca.md](seguranca.md) §7, achado A10 |
| R-07 | Sem varredura automatizada de dependências, segredos e código (SCA, secret scanning, SAST) nem CI | Média | aguardando aprovação | revisão pelo `revisor-de-codigo` e pelo `engenheiro-seguranca`; `npm audit` manual | issues #10, #28 e #34 |
| R-08 | Sem teste de intrusão independente antes da produção | Média | aguardando aprovação | revisão independente de 25/10/2026 ([seguranca.md](seguranca.md) §7) | [seguranca.md](seguranca.md) §8 |
| R-09 | Vulnerabilidades abertas descritas (evidência e exploração) em repositório público | Média | aceito — responsável, 24/11/2026: todas as frentes enxergam o mesmo quadro e não há dado real em produção | nenhum segredo, token ou dado pessoal no registro; prova de conceito só com dados fictícios do sandbox; issue pública nunca descreve vulnerabilidade aberta | decisão 1383; reavaliar antes da entrada em produção (abertas → security advisory privado) |
| R-10 | Sem trilha CloudTrail própria (eventos de gerenciamento além de 90 dias, leitura de segredos) nem cópia de backup fora da conta ou região | Média | aguardando aprovação | histórico de eventos de 90 dias; logs de acesso do S3; backups do RDS com proteção contra exclusão | issue #35 |

## 4. Vulnerabilidades abertas

Preenchido a partir das auditorias e revisões do `engenheiro-seguranca`. Última auditoria: [auditoria 24/11](seguranca-auditorias/2026-11-24.md) (detalhe, evidência e testes de cada item). Achado de auditoria: detalhe completo no
relatório da auditoria (pasta `seguranca-auditorias/`, um arquivo por auditoria). Achado de revisão antes do commit: a
evidência, o cenário de exploração e a correção ficam no próprio item, abaixo da tabela.

| ID | Título | Severidade | Estado | Onde | Correção prevista |
| --- | --- | --- | --- | --- | --- |
| SEG-20261124-C01 | Extrato CSV enviado por uma pessoa confirma pagamento (instrução paga, parcelas e títulos quitados) sem autenticidade nem segunda pessoa; conciliação por extrato não barra quem informou o externo | Alta | aberto — limite de confirmação imediata a decidir | `payment` `StatementMatcher#againstInstruction`, `#againstPayment`; `Payment#reconcile` | pagamento do extrato nasce `INFORMED` com revisão por outra pessoa; segregação também no modo `STATEMENT`; ITs em `PaymentReconciliationIT` |
| SEG-20261124-D01 | Anonimização do titular não alcança communication, collection (observações, contestações, .eml), legal, payment (evidência) e ingestion (arquivos de carga) | Alta | aberto — arquivos de carga a decidir | `DebtorAnonymized` sem consumidor nesses módulos; `DataSubjectRequestService`, `RetentionService` | consumidor por módulo dono apagando conteúdo, contato e arquivos (mantendo hash); exceção de gatilho para o .eml; IT por módulo |
| SEG-20261124-A01 | Ramais por conta de telefonia sem escopo de credor (remoção sem conferência; conta padrão sem exigir todos os credores; listagem de ramais de contas fora do escopo) | Média | aberto | `telephony` `AgentService#endpoints`, `#configureEndpoint`, `#removeEndpoint` | regra única de conta (`TelephonyAccountAccess`); IT em `TelephonyScopeIT` |
| SEG-20261124-B01 | MFA obrigatório decidido pelo código do perfil: perfis personalizados ou ampliados com permissões administrativas entram sem segundo fator | Média | aberto — lista de permissões a decidir | `identity` `AuthenticationService#mfaRequiredFor`; `app.security.mfa.required-for-roles` | MFA também por permissões efetivas (`required-for-permissions`); IT de perfil personalizado |
| SEG-20261124-B02 | Troca de senha expirada ou obrigatória antes do segundo fator, sem aviso nem revogação de sessões, registrada como `LOGIN_SUCCESS` | Média | aberto | `AuthenticationService#nextStepAfterPassword`, `#changeExpiredPassword` | MFA antes da troca; revogar sessões; alerta; evento de acesso correto; IT do fluxo expirado com MFA |
| SEG-20261124-C02 | Estorno consumido antes da confirmação é descartado no negotiation e no collection (parcela/acordo pagos com Pix devolvido) | Média | aberto | `AgreementPaymentService#paymentReversed`; `PromiseService#paymentReversed`; origem `PixSettlement#apply` | lock do acordo antes da leitura e aviso de estorno guardado até a confirmação; IT com eventos invertidos |
| SEG-20261124-C03 | Limite de revisão de pagamento externo sem teto: o ADMIN eleva e confirma sozinho | Média | aberto — decisão do responsável | `PaymentSettingsService#update`, `PaymentSettings.Values` | teto por ambiente e/ou aumento aprovado por outro ADMIN |
| SEG-20261124-C04 | Template de e-mail/SMS de campanha publicado por quem o escreveu e aplicado na hora às réguas ativas, sem revisão | Média | aberto — decisão do responsável | `MessageTemplate#publish`, `ActionExecutor` | quatro olhos na publicação ou versão fixada na régua; checagem de chave Pix/URL |
| SEG-20261124-D02 | Evidência de pagamento da API pública gravada sem quarentena nem verificação | Média | aberto | `publicapi` `PaymentController#register` → `EvidenceStore` | origem externa em `QUARANTINE` + job de varredura; download e aprovação só com veredito (junto da issue #33) |
| SEG-20261124-D04 | Chamado do suporte publica nome da assessoria e e-mail do usuário em repositório público; trava de repositório privado desligada em produção (inclui o E05) | Média | aberto — decisão do responsável | `helpdesk` `TicketComposer`; `application.yml`; `infra/terraform/envs/producao` | tirar e-mail e assessoria do corpo e/ou `precondition` exigindo repositório privado; `true` fixo no `prod` |
| SEG-20261124-E01 | Com SSM ligado, o papel das instâncias lê `migration/*` (política gerenciada com `ssm:GetParameter*` em `*`; mesma chave KMS) — anula a A6 | Média | aberto (latente até `use_ssm_parameters`) | `infra/terraform/modules/compute/iam.tf` | política própria do agente SSM sem `GetParameter*`; teste de plano |
| SEG-20261124-E02 | Túnel público do sandbox sobe mesmo com credenciais AWS/Bedrock do desenvolvedor na aplicação | Média | aberto (conferido em 24/11: túnel no ar sem credenciais) | `scripts/sandbox/tunnel.sh`, `up.sh` | recusar túnel com `AWS_*`/Bedrock no contêiner e vice-versa; checagem no `verify.sh` |
| SEG-20261124-E03 | Compose de desenvolvimento publica PostgreSQL (superusuário) e Mailpit em todas as interfaces | Média | aberto | `infra/docker/docker-compose.yml` | portas em `127.0.0.1`; Mailpit com versão fixada |
| SEG-20261124-E04 | Sessões do Session Manager sem registro (comandos de operador na instância não rastreados) | Média | aberto | `infra/terraform/modules/compute`, `infra/terraform/README.md` | preferências de sessão com log cifrado e `runAs` sem sudo; CloudTrail (R-10) |
| SEG-20261124-A02 | Aviso de controle de tokens pela plataforma enviado a administradores de tokens sem o credor do token | Baixa | aberto | `PlatformTokenNoticeListener` | credor por token no evento; destinatários filtrados pelo escopo |
| SEG-20261124-A03 | Reclamação vinculada a devedor sem relação com o credor da reclamação | Baixa | aberto | `ComplaintService#linkedDebtor` | exigir relação devedor × credor; `ComplaintScopeIT` |
| SEG-20261124-B03 | Troca da própria senha sem limite de tentativas da senha atual | Baixa | aberto | `MyAccountService#changePassword` | contar falha no bloqueio progressivo e limitar por usuário |
| SEG-20261124-B04 | Token do link de evidências de contestação no caminho da URL (logs do ALB, histórico) | Baixa | aberto | `DisputeCreditorChannel#LINK_PATH`, `DisputeEvidenceLinkController` | token no fragmento + `POST` com o token no corpo |
| SEG-20261124-B05 | Requisições anônimas e já limitadas geram uma gravação no log de acesso cada | Baixa | aberto | `SecurityConfig`, `AuthController`, `PortalAccessController`, `IntegrationAuthenticationService`, webhooks | registrar só a primeira recusa por chave e minuto; limite por IP antes da autenticação na API pública |
| SEG-20261124-B06 | Sem aviso ao titular em troca de senha e cadastro de autenticador | Baixa | aberto | `CredentialService#changePassword`, `#completeMfaEnrollment` | `sendSecurityAlert` nos dois pontos |
| SEG-20261124-C05 | Confirmação Pix não confere a conta da instrução do txid | Baixa | aberto | `PixSettlement#apply` | filtrar a instrução pela conta Pix que recebeu |
| SEG-20261124-D03 | `GET /api/v1/debtors?q=` aceita CPF/CNPJ completo (documento na URL) | Baixa | aberto | `DebtorController#search`, `DebtorService` | recusar documento em `q` com 400 |
| SEG-20261124-E06 | Hash do JAR conferido contra arquivo do mesmo bucket | Baixa | aberto | `scripts/instance/gestor-lib.sh`, `scripts/deploy/release.sh` | hash fixado em tag da instância |
| SEG-20261124-E07 | Imagens base sem digest | Baixa | aberto | `infra/docker/app/Dockerfile`, Compose | fixar `@sha256:` |
| SEG-20261124-E08 | Perfil padrão `local` sem guarda (chaves de desenvolvimento versionadas, simuladores) | Baixa | aberto | `application.yml`, `application-local.yml` | `LocalProfileGuard` |
| SEG-20261124-E09 | Advisories em dependências de desenvolvimento do frontend (vite, minimatch); produção sem achados no `npm audit` | Baixa | aberto | `frontend/package-lock.json` | `npm audit fix` |
| SEG-20261124-F05 | Respostas de `/api/creditor/v1` sem teste de snapshot de contrato: campo novo nos records do `creditorportal` chega ao credor sem nenhum teste falhar | Baixa | aberto | `creditorportal` (controllers de `/api/creditor/v1`) | snapshot das respostas permitidas (RS-26) |
| SEG-20261124-F07 | Aceite do demonstrativo pelo portal confia no `mfaVerifiedAt` informado pelo chamador | Baixa | aberto — reforço previsto na fatia 6 | `settlement` (aceite pelo portal), chamado pelo `creditorportal` | comprovante opaco de uso único do `CreditorStepUp` |

### Achados de revisão antes do commit

**SEG-20261124-F05 e F07** — revisão da fatia 4 do portal do credor (prestação de contas), 24/11/2026; F01–F04 e F06
da mesma revisão foram corrigidos antes do commit (§5).

- **F05.** As respostas da API interna do credor (`/api/creditor/v1`) não têm teste de snapshot, como a API pública já
  tem. O `ArchitectureTest` já impede que o portal responda com records dos módulos donos, mas um campo acrescentado
  aos records do próprio `creditorportal` passa a ser enviado ao credor sem que nenhum teste falhe. Correção: snapshot
  das respostas da API do credor (RS-26, em [portal-credor.md](desenho/portal-credor.md) §4).
- **F07.** O caso de uso de aceite do `settlement` recebe do chamador o instante da verificação do MFA
  (`mfaVerifiedAt`) e confia nele. Hoje só o `creditorportal` declara a interface `settlement :: creditor`, e o
  `ModularityTest` recusa qualquer outro módulo que a use sem declará-la. Por isso a exploração exige uma mudança de
  código interna (outro módulo passar a declarar a interface e chamar o caso de uso com um instante inventado), que
  passa pela revisão. Correção prevista com a fatia 6: um comprovante opaco e de uso único emitido pelo
  `CreditorStepUp`, conferido pelo `settlement`.

## 5. Histórico de correções

| ID | Título | Severidade | Corrigido em (commit) | Teste que cobre | Validado em |
| --- | --- | --- | --- | --- | --- |
| SEG-20261124-F01 | Assessoria registrava aceite por cima de contestação feita pelo credor no portal | Média | e9a9cbbd | `SettlementLifecycleTest#creditorPortalContestationIsOnlyRevertedByTheCreditor`; `CreditorSettlementIT#creditorSeesOnlyItsSentStatementsAcknowledgesWithMfaAndContests` (409 `statement.creditor_portal_response`; decisão 1420) | 24/11/2026, revisão antes do commit |
| SEG-20261124-F02 | Tentativa sobre demonstrativo de outro credor respondia 404 sem registrar `SCOPE_DENIED` | Média | e9a9cbbd | `CreditorSettlementIT#creditorSeesOnlyItsSentStatementsAcknowledgesWithMfaAndContests` (`SCOPE_DENIED` no demonstrativo e na contestação de outro credor; arquivo de outro demonstrativo → 404; RS-06; decisão 1422) | 24/11/2026, revisão antes do commit |
| SEG-20261124-F03 | Termo de resposta sem a observação do credor | Baixa | e9a9cbbd | `CreditorSettlementIT#creditorSeesOnlyItsSentStatementsAcknowledgesWithMfaAndContests` (observação gravada no termo do aceite e da contestação; o SHA-256 da observação entra no texto canônico, ainda sem teste próprio) | 24/11/2026, revisão antes do commit |
| SEG-20261124-F04 | Código do autenticador conferido antes da permissão de responder | Baixa | e9a9cbbd | `CreditorSettlementIT#creditorSeesOnlyItsSentStatementsAcknowledgesWithMfaAndContests` (perfil só de consulta → 403 sem chamar a verificação do código; decisão 1422) | 24/11/2026, revisão antes do commit |
| SEG-20261124-F06 | Download salvava arquivo com SHA-256 divergente do código de integridade | Baixa | e9a9cbbd | `creditor-statement-detail.spec.ts` › "arquivo que não confere com o código de integridade não é salvo" (decisão 1419) | 24/11/2026, revisão antes do commit |
