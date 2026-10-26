# Pagamentos (módulo `payment`)

M07 parcial, F10 (instruções de pagamento), F14 (pagamentos), F15 (conciliação), RB06. Código em `backend/src/main/java/br/com/gestorcobranca/payment`; migration `V0800__payment_tables.sql`. Dependências: `negotiation :: usecases` (acordos e parcelas — leitura), `debt :: usecases` (títulos, devedor do Pix com vencimento, razão do título para pagamentos avulsos), `organization :: usecases`. Layout do extrato para clientes: [negocio/layout-extrato-conciliacao.md](../../negocio/layout-extrato-conciliacao.md).

## 1. Componentes

| Componente | Onde | Papel |
| --- | --- | --- |
| `PixAccount` | `domain.model` | conta Pix (PSP) da assessoria (`creditorId` nulo) ou de um credor; URL da API, URL do token, chave Pix (chave CPF — pessoa física — é recusada: a conta é sempre do credor ou da assessoria), segredos e certificado mTLS criptografados, modo de autenticação do webhook (MTLS/HMAC), `routingKey` de 256 bits |
| `PaymentInstruction` | `domain.model` | Pix imediato (`cob`), com vencimento (`cobv`), boleto ou link para UMA parcela de acordo ou UM título; `PENDING → ACTIVE → PAID | EXPIRED | CANCELLED`, `FAILED` |
| `Payment` | `domain.model` | pagamento integrado (`PROVIDER`) ou externo (`EXTERNAL`, evidência obrigatória); `INFORMED → CONFIRMED → RECONCILED`, `INFORMED → REJECTED`, `CONFIRMED/RECONCILED → REVERSED` |
| `PaymentAllocation` (+ linhas) | `domain.model` | parte do pagamento para UM destino (parcelas de um acordo, títulos avulsos, devolução); resultado da baixa no negotiation; liberação para realocação; compensação publicada |
| `PaymentReversal` | `domain.model` | estorno (append-only: gatilho + privilégio) |
| `StatementImport`, `BankStatementLine`, `Divergence` | `domain.model` | extrato/arquivo de retorno, linhas com impressão digital, fila de divergências |
| `AllocationPlanner`, `LargestRemainder` | `domain.service` | regras puras de alocação (parcelas, títulos, componentes) |
| `WebhookAuthentication`, `ClientCertificates`, `PixIdentifiers`, `StatementFingerprint` | `domain.service` | HMAC/mTLS, leitura do PKCS#12, `txid`/`endToEndId`, identificação de transações repetidas |
| `InstructionService`, `PaymentService`, `ReconciliationService`, `PixAccountService`, `PaymentSettingsService` | `application.service` | casos de uso (`port.in`) |
| `AllocationService`, `PaymentConfirmation`, `PaymentReversals`, `PixSettlement`, `StatementMatcher` | `application.service` | alocação e publicação de eventos, efeitos da confirmação, estorno, aplicação do Pix confirmado, conciliação por linha |
| `PixWebhookService` + `PixSyncJob` | `application.service` | webhook Pix → consulta ao PSP (`payment.pix_sync`) |
| `InstructionRegistrationJob`, `InstructionCancellationJob`, `StatementImportJob`, `PaymentJobs` | `application.service` | registro/cancelamento no PSP, processamento do extrato, expiração e limpeza de recibos |
| `PixProvider`, `BoletoProvider`, `PaymentLinkProvider`, `StatementParser`, `PixSandbox` | `port.out` | portas de saída |
| `BacenPixProvider` + `MutualTlsClientFactory` | `infrastructure.provider.pix` | adaptador genérico da API Pix do BACEN (qualquer PSP) |
| `SandboxPixProvider` + `PaymentSandboxGuard` | `infrastructure.provider.sandbox`, `.config` | PSP simulado local/teste, recusado no perfil `prod` |
| `GenericCsvStatementParser` | `infrastructure.statement` | leitor do CSV genérico |

## 2. Instruções de pagamento (F10)

- Destino: parcela de acordo (`agreementId` + `installmentId`, acordo `FORMALIZED`/`IN_PAYMENT`) **ou** título avulso (`titleId`; título coberto por acordo em vigor é recusado — paga-se pela parcela). Valor padrão = saldo em aberto (parcela: valor − pago − alocações deste módulo ainda não baixadas); nunca acima dele.
- Instrução aberta do mesmo tipo e valor para o mesmo destino é devolvida em vez de duplicada; `Idempotency-Key` aceito.
- Pix imediato: validade `pixExpirationSeconds` (padrão 24 h) ou informada (300 s–30 dias). Pix com vencimento: vencimento da parcela (ou informado, nunca no passado), validade após o vencimento `pixDueValidityDays` (padrão 30). A `cobv` exige nome e CPF/CNPJ do pagador (API do BACEN): o job lê o documento com `DebtorUseCases#revealDocument` como ator de sistema — gera `SENSITIVE_DATA_VIEWED`. A `cob` não leva dados do pagador (minimização).
- `txid` = 32 caracteres hexadecimais fixados antes do registro; `PUT /cob/{txid}` é idempotente, então repetir o job não cria outra cobrança.
- Registro assíncrono (`payment.instruction_register`): aceite → `ACTIVE` com `pixCopiaECola` (conteúdo do QR Code; o frontend renderiza o QR a partir dele) e localização; recusa definitiva → `FAILED` com o código do PSP; falha transitória → nova tentativa (6).
- Conta usada: a ativa do credor ou, sem ela, a da assessoria.
- Encerramento automático: acordo `SETTLED/BROKEN/CANCELLED/RENEGOTIATED`, parcela `PAID/CANCELLED` ou título `SETTLED/CANCELLED/RETURNED` cancelam as instruções abertas (consumidores de `AgreementStatusChanged`, `InstallmentStatusChanged`, `TitleStatusChanged`); a cobrança é removida no PSP (`payment.instruction_cancel`). Expiração por varredura (`payment.expiration`, 15 min). Pagamento que ainda chegar para instrução expirada/cancelada é aceito (o dinheiro entrou) e alocado; se o destino não tiver saldo, vira sobra.
- **Boleto e link**: somente as portas `BoletoProvider` e `PaymentLinkProvider`; sem provedor definido (issue #12) a criação é recusada com `payment_instruction.provider_unavailable`.

## 3. API Pix do BACEN (adaptador genérico)

- OAuth2 `client_credentials` (Basic com `client_id:client_secret`) na URL de token da conta, com escopos `cob.* cobv.* pix.read webhook.*`; token em cache por conta até `expires_in − 60 s`, descartado em 401 ou rotação de credenciais.
- Todas as chamadas com TLS mútuo (certificado de cliente PKCS#12 da conta), TLS 1.2+, cadeia do servidor validada pelo trust store da JVM, sem redirecionamento, timeouts 3 s / 10 s.
- Operações: `PUT /cob/{txid}`, `PUT /cobv/{txid}`, `PATCH /cob[v]/{txid}` (`REMOVIDA_PELO_USUARIO_RECEBEDOR`), `GET /pix/{e2eid}` (com `devolucoes`), `PUT /webhook/{chave}`.
- Erros: 429/5xx/rede/401 transitórios; demais 4xx definitivos com código `PIX_<tipo do erro RFC 7807>`. Métricas `integration.calls{provider=pix_bacen,operation,outcome}` e `integration.calls.duration`.
- URLs da conta exigem HTTPS, sem credenciais/parâmetros (o simulador aceita qualquer endereço porque não faz chamadas).

### 3.1 Onde ficam credenciais e certificado

| Segredo | Armazenamento |
| --- | --- |
| `client_id`, `client_secret`, segredo HMAC do webhook, senha do PKCS#12 | `payment.pix_account.credentials_encrypted` — JSON cifrado com AES-256-GCM (`FieldEncryptor`/`EncryptedStringConverter`, chave da aplicação com rotação), sob RLS |
| Certificado de cliente mTLS (PKCS#12, com chave privada) | `payment.pix_account.certificate_encrypted` — Base64 do PKCS#12 cifrado com AES-256-GCM; validado no envio (abre com a senha, contém chave privada, dentro da validade); só a impressão digital SHA-256 e a validade são exibidas e auditadas |
| Certificado do PSP que chama o webhook (mTLS) | só a impressão digital SHA-256 (`webhook_client_fingerprints`), sem segredo |

Nenhum segredo é devolvido pela API (a view lista apenas os nomes das chaves gravadas) nem vai para log ou auditoria. O PKCS#12 só é decifrado em memória, ao montar o `SSLContext` (cache por conta e versão das credenciais). **Pendência (issues #6 e #8):** quando KMS/Secrets Manager/SSM forem aprovados, mover o PKCS#12 e os segredos para lá (a entidade passa a guardar a referência), com rotação gerida e chave de dados por tenant.

## 4. Webhook Pix (`POST /api/v1/webhooks/pix/{routingKey}` e `/{routingKey}/pix`)

O PSP acrescenta `/pix` à URL cadastrada; os dois caminhos são aceitos. URL registrada no PSP: `app.payment.webhook.public-base-url + /api/v1/webhooks/pix/<routingKey>` (`POST /payment/pix-accounts/{id}/webhook`).

Ordem de proteção (mesmo padrão do webhook do WhatsApp):
1. limite por rota e IP (429) e tamanho do corpo (413) no adaptador web;
2. rota (tabela global `payment.webhook_route`, só chave e ids) → tenant e conta; inexistente/inativa → 404;
3. autenticação antes de interpretar o corpo: **mTLS** (padrão da API Pix) — o ALB com *mutual TLS* em modo `verify` valida a cadeia contra o trust store dos PSPs e repassa o certificado folha em `X-Amzn-Mtls-Clientcert-Leaf`; a aplicação exige que a impressão digital esteja entre as fixadas na conta e que esteja na validade — ou **HMAC** (`X-Pix-Signature: sha256=<hex>` do corpo bruto com o segredo da conta, para PSPs que assinam). Falha → 401 + `WEBHOOK_REJECTED`. Com `app.payment.webhook.mtls-enabled=false` (hoje, decisão 316) contas MTLS são recusadas e a tela de contas Pix abre em HMAC, com o mTLS desabilitado e explicado, a partir de `GET /api/v1/payment/pix-accounts/webhook-options` (`mtlsAvailable`, `defaultAuth`; decisão 522);
4. recibo do corpo (SHA-256 por conta): reentrega idêntica → 200 sem efeito;
5. cada item com `endToEndId` válido e `horario` dentro da janela (`max-event-age` 7 dias, `max-clock-skew` 5 min) agenda `payment.pix_sync` (deduplicado por `endToEndId`); fora da janela → descartado (métrica `stale`).

A notificação **nunca** é prova de pagamento: o `PixSyncJob` consulta `GET /pix/{e2eid}` e cria o pagamento com valor, `txid`, horário e devoluções devolvidos pelo PSP. O índice único `(tenant, provider_transaction_id)` e o bloqueio da instrução (`txid … FOR UPDATE`) tornam a confirmação idempotente mesmo com notificações concorrentes.

`PixSettlement`: transação já conhecida → só devoluções novas; sem instrução para o `txid` → divergência `UNMATCHED`; pagamento da mesma instrução criado antes pelo arquivo de retorno sem identificador e com o mesmo valor → vincula o `endToEndId` (sem nova baixa); demais → pagamento `CONFIRMED` + `RECONCILED` (`PROVIDER_API`), alocado ao destino da instrução. Segundo Pix da mesma instrução → alocado normalmente (parcelas seguintes) e divergência `DUPLICATE_PAYMENT`; valor diferente do da instrução → `AMOUNT_MISMATCH`. Devolução `DEVOLVIDO` total → estorno `PROVIDER_REFUND` (idempotente pelo `rtrId`); parcial → divergência `PARTIAL_REFUND` (estorno é sempre total no sistema).

## 5. Pagamentos externos (F14)

- Informados por operador (`PAYMENT_REGISTER_EXTERNAL`): credor, devedor, valor, data (não futura), meio, fonte (`AGENCY_ACCOUNT`, `CREDITOR_DIRECT`, `CREDITOR_REPORT`, `OTHER`), referência, observação (criptografada) e **destino obrigatório** (parcelas de um acordo ou títulos).
- **Evidência obrigatória**: PDF, PNG ou JPEG até 10 MB, tipo conferido pela assinatura do conteúdo; `FileStorage` (`EVIDENCE`) com SHA-256; removida se a transação for desfeita; download com `FILE_DOWNLOADED`.
- **Revisão conforme risco**: valor ≤ `externalReviewThreshold` (padrão R$ 500,00, por assessoria) → confirmado no registro (`AUTO_LOW_RISK`); acima → `INFORMED` até aprovação por **outra pessoa** com `PAYMENT_REVIEW` (`DUAL_CONTROL`); a fila de revisão não mostra ao revisor os próprios pagamentos e o domínio recusa `payment.self_review`. Recusa exige motivo (`REJECTED`, sem baixa). A `PaymentView` traz o **destino solicitado** (`requestedTarget`: tipo, acordo, parcelas/títulos em ordem e estratégia) do pagamento externo, para quem aprova ver para onde o valor vai antes de a confirmação gerar as alocações (nulo nos integrados).
- `Idempotency-Key` (escopo `payment.external`): mesma chave e conteúdo → mesmo pagamento.

### 5.1 Situação de revisão no acordo e no caso (decisões 430–438)

Quem negocia precisa saber que o pagamento que informou (ou que outro informou) ainda aguarda a aprovação de outra pessoa e que a parcela não baixou, ou que ele foi recusado e é preciso agir. Como o negotiation não depende do payment, o resumo é servido aqui (`PaymentReviewStatusUseCases`, `PaymentReviewStatusService`) e o frontend o compõe nas telas de negociação e cobrança.

- **Permissão**: `AGREEMENT_VIEW` (menor privilégio; não exige `PAYMENT_VIEW`). Itens com valor, data do pagamento, meio, quem informou e quando, destino (tipo, acordo, parcelas/títulos indicados), quem recusou e quando. **Sem** evidência, observação nem referência externa. Motivo da recusa (`rejectionReason`) só para quem informou ou tem `PAYMENT_VIEW`; os demais recebem `reasonRestricted = true`.
- **Acordo** (`GET /review-status/agreements/{id}`): lê o acordo pelo caso de uso do negotiation (permissão e escopo, 404 fora dele); pendentes do mais antigo ao mais recente, recusados recentes e `expectedCoverage` — previsão por parcela com o `AllocationPlanner` sobre o saldo atual (descontadas as alocações em trânsito e os pendentes anteriores), só com acordo em vigor. É previsão: a aprovação recalcula.
- **Lote para listagens** (`GET /review-status/agreements?ids=`, até 200): um resumo por acordo com pendente ou recusa (contagem, total pendente, último registro, último recusado); filtro pelo escopo de credores, sem ler cada acordo (o credor do pagamento é o do acordo).
- **Devedor no credor** (`GET /review-status/debtors/{debtorId}?creditorId=`): para a ficha do caso; inclui pagamentos a títulos avulsos; credor fora do escopo → 403.
- **Recusados recentes**: `REJECTED` com `reviewed_at` dentro de `app.payment.review-status.rejected-window` (padrão 30 dias), até `max-rejected` (padrão 5) por acordo/devedor, do mais recente ao mais antigo.
- Somente leitura: sem auditoria nem log de negócio. Índice parcial `ix_payment_review_agreement` (`V0803`).
- **Pagamentos de um acordo** (`PAYMENT_VIEW`): filtro `agreementId` em `GET /payments` — destino solicitado do externo ou alguma alocação ao acordo.
- **Frontend**: `app-payment-review-alert` (`features/payment/components`) no detalhe do acordo (aviso completo, "Destino previsto" e selo "Em revisão" na parcela), selo "Pagamento em revisão"/"Pagamento recusado" na listagem de acordos (uma consulta por página) e aviso compacto na ficha do caso da estação de cobrança.

## 6. Alocação (RB06)

- Cada alocação a acordo ou títulos publica **um** `PaymentConfirmed` cujo `paymentId` é o **id da alocação** (unidade de baixa). Consumidores (negotiation, collection, incentive) deduplicam por esse id; um pagamento dividido entre destinos, ou realocado, nunca é contado duas vezes, e o estorno é por alocação. `GET /payment/payments/by-allocation/{id}` resolve o pagamento.
- **Acordo**: parcelas indicadas primeiro e o excedente às seguintes por número (mesma regra do negotiation), cada uma até o saldo aberto descontadas as alocações em trânsito. Evento com `agreementId`, `portfolioId` e `Allocation(titleId = null, agreementInstallmentId, amount)`. **O payment não lança no razão** essas alocações: o negotiation distribui entre títulos e componentes e lança (§10.5 de `negociacao-e-alcadas.md`).
- **Títulos avulsos**: vencidos primeiro (padrão, CC art. 355) ou proporcional ao saldo; até o saldo de cada título; lançamento no razão (`DebtLedgerPort.applyPayment`, distribuição proporcional ao saldo de cada componente por maiores restos, referência = id da alocação; idempotente no debt). Evento sem `agreementId`, com `Allocation(titleId, null, amount)` — o collection usa para cumprir promessas.
- **Sobra**: o que não couber (acordo encerrado, destino quitado, valor acima do saldo) fica como saldo não alocado do pagamento (`unallocatedAmount`).
- **Resultado no negotiation**: o negotiation publica `AgreementPaymentRecorded` (novo, `shared.events.negotiation`) com aplicado/não aplicado; a alocação registra `APPLIED`, `PARTIAL` ou `UNAPPLIED` (WARN + auditoria `payment.unapplied`).
- **Realocação** (`RECONCILIATION_MANAGE`, justificativa obrigatória): fila `GET /payments/pending-reallocation` (sobra + não aplicado ainda não realocado). Origem: saldo não alocado ou alocação a acordo com valor não aplicado (liberado; se o negotiation não aplicou nada, publica-se `PaymentReversed` do valor liberado com motivo `REALLOCATED` — o negotiation ignora, o incentive retira a base de comissão daquele acordo). Destino: novo acordo/títulos (nova alocação `REALLOCATION`) ou devolução ao pagador (registro `REFUND` com referência da transferência, sem evento).

## 7. Estorno

`REVERSED` por evento compensatório (`PaymentReversal` append-only), nunca exclusão: manual (`PAYMENT_REVERSE`, motivo e justificativa) ou devolução do PSP. Cada alocação ativa é revertida: títulos avulsos recebem `DebtLedgerPort.reversePayment` (espelho); para cada alocação publicada sai um `PaymentReversed` com o valor ainda não compensado (`reversalId` novo, `paymentId` = alocação, `agreementId`, `portfolioId`, motivo).

## 8. Conciliação (F15)

- Importação (`POST /payment/reconciliation/statements`, multipart): arquivo no `FileStorage` (`PAYMENT_RETURN`) com SHA-256; o mesmo arquivo de novo → 409; processamento assíncrono (`payment.statement_import`), restrito opcionalmente a um credor. Formatos: `CSV_GENERIC`; `CNAB_240`/`CNAB_400` aceitos na API como porta futura (sem leitor → 422).
- Por linha (`StatementMatcher`): débito → ignorado; mesma transação já importada (impressão digital: `id_transacao` + sentido, ou data+valor+sentido+referência+ocorrência no arquivo) → `IGNORED_DUPLICATE`, sem nova baixa; `id_transacao` = `endToEndId`/id do provedor ou referência externa de pagamento confirmado → concilia; `referencia` = `txid` ou id de instrução (sem pagamento ainda: **o arquivo de retorno é a confirmação** — cria pagamento integrado `STATEMENT`, aloca e concilia), id de pagamento ou id de parcela (pagamento confirmado com mesmo valor e data na tolerância `reconciliationDateToleranceDays`); sem correspondência → `UNMATCHED`.
- Divergências (`payment.divergence`): `AMOUNT_MISMATCH`, `DUPLICATE_PAYMENT`, `UNMATCHED`, `NOT_CONFIRMED`, `PARTIAL_REFUND`. Resolução com justificativa obrigatória e auditoria: `MATCHED_PAYMENT` (concilia manualmente o pagamento indicado e vincula a linha; **quem informou o pagamento externo não o concilia** — `payment.self_reconciliation`), `ACCEPTED` ou `DISMISSED`. Divergência sem credor só é visível a quem tem todos os credores. A descrição da linha do banco (pode conter nome/documento do pagador) fica criptografada e é exibida mascarada.

## 9. Eventos de integração

| Evento | Produtor → consumidores | Campos usados |
| --- | --- | --- |
| `PaymentConfirmed` | payment → negotiation (`agreementId`, `Allocation.agreementInstallmentId`, `paymentId`, `amount`, `paidAt`, `source`, `method`), collection (`agreementId` nulo, `debtorId`, `creditorId`, `portfolioId` ou `Allocation.titleId`, `amount`, `paidAt`), incentive (`agreementId`, alocações a parcelas, `portfolioId`, `paidAt`, `occurredAt`), settlement (títulos avulsos: `Allocation.titleReference`, `daysPastDue`, `honorariumAmount` — novos, 23/10/2026) | todos preenchidos; `portfolioId` = carteira do acordo ou dos títulos (nulo se títulos de carteiras diferentes); nas alocações a títulos, `titleReference` = `externalId/parcela` do título, `daysPastDue` = atraso na data do pagamento e `honorariumAmount` = parte distribuída ao componente `HONORARIUM` |
| `PaymentReversed` | payment → negotiation (`agreementId`, `paymentId`, `reversalId`, `reason`), collection (`debtorId`, `paymentId`), incentive e settlement (`reversalId`, `paymentId`, `agreementId`, `amount`) | `paymentId` = alocação; `reason` = motivo (`REALLOCATED` na liberação) |
| `PaymentInstructionStatusChanged` (24/10/2026, ADR-0009) | payment → assistant (`instructionId`, `status`, `paidAt`) | publicado em toda mudança de situação da instrução (`ACTIVE` no registro, `FAILED`, `PAID`, `EXPIRED`, `CANCELLED`) com `previous`, `type`, `creditorId`, `debtorId`, `agreementId`, `installmentId`, `amount`; nunca o "copia e cola", txid ou dados do pagador |
| `AgreementPaymentRecorded` (**novo**) | negotiation → payment | `paymentId` (alocação), `appliedAmount`, `unappliedAmount`, `status`, `agreementStatus` |
| consumidos | `AgreementStatusChanged`, `InstallmentStatusChanged`, `TitleStatusChanged` | cancelamento de instruções abertas |

## 10. Permissões (catálogo existente, sem migration de identity)

| Ação | Permissão |
| --- | --- |
| consultar pagamentos/instruções, fila de realocação, evidência | `PAYMENT_VIEW` |
| gerar/cancelar instrução | `PAYMENT_INSTRUCTION_CREATE` |
| informar pagamento externo | `PAYMENT_REGISTER_EXTERNAL` |
| aprovar/recusar informado | `PAYMENT_REVIEW` (nunca o próprio) |
| situação de revisão no acordo, na listagem e no caso (§5.1) | `AGREEMENT_VIEW` (motivo da recusa: quem informou ou `PAYMENT_VIEW`) |
| estornar | `PAYMENT_REVERSE` |
| importar extrato, resolver divergência, realocar | `RECONCILIATION_MANAGE` |
| contas Pix, simulador | `INTEGRATION_MANAGE` (contas da assessoria exigem todos os credores) |
| parâmetros | leitura `PAYMENT_VIEW`; alteração `ORG_SETTINGS_MANAGE` + todos os credores |

Escopo por credor em tudo (404 em leitura, 403 em comando). Perfis padrão: operador gera instruções e informa externos; financeiro revisa, estorna e concilia; o administrador tem tudo, mas a segregação (quem informa ≠ quem aprova/concilia) vale para ele também.

## 11. REST (`/api/v1/payment`)

| Recurso | Métodos |
| --- | --- |
| `/instructions` · `/instructions/{id}` · `/instructions/{id}/cancel` | `POST`, `GET` · `GET` · `POST` |
| `/payments` (filtros: credor, devedor, situação, origem, meio, período, saldo, `agreementId`) · `/payments/{id}` · `/payments/by-allocation/{id}` · `/payments/{id}/evidence` | `GET` |
| `/review-status/agreements/{id}` · `/review-status/agreements?ids=` · `/review-status/debtors/{id}?creditorId=` | `GET` (`AGREEMENT_VIEW`) |
| `/payments/external` (multipart `payment` + `evidence`, `Idempotency-Key`) | `POST` |
| `/payments/review-queue` · `/payments/{id}/approve` · `/reject` | `GET` · `POST` |
| `/payments/{id}/reverse` · `/payments/pending-reallocation` · `/payments/{id}/reallocations` | `POST` · `GET` · `POST` |
| `/reconciliation/statements` · `/{id}` · `/{id}/lines` · `/reconciliation/divergences` · `/{id}` · `/{id}/resolve` | `POST`/`GET` · `GET` · `GET` · `GET` · `GET` · `POST` |
| `/pix-accounts` · `/{id}` · `/{id}/credentials` · `/{id}/webhook` · `/{id}/activate` · `/deactivate` | CRUD |
| `/settings` | `GET`, `PUT` |
| `/sandbox/instructions/{id}/pay` · `/sandbox/pix/refund` | `POST` (só com sandbox) |

## 12. Parâmetros (`app.payment.*`)

`defaults.external-review-threshold` (500,00), `defaults.pix-expiration-seconds` (86400), `defaults.pix-due-validity-days` (30), `defaults.reconciliation-date-tolerance-days` (3); `webhook.public-base-url`, `max-body-bytes`, `requests-per-minute`, `max-event-age`, `max-clock-skew`, `client-certificate-header`; `pix.connect-timeout`, `read-timeout`, `token-refresh-margin`, `scope`; `evidence.max-size-bytes`; `statement.max-size-bytes`, `max-lines`; `expiration-check-interval`; `review-status.rejected-window` (P30D), `review-status.max-rejected` (5); `sandbox.enabled` (recusado em `prod`). Testes: sandbox ligado e URL pública local — nenhum teste acessa a rede (o adaptador do BACEN é testado com `MockRestServiceServer`).

## 13. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | `paymentId` dos eventos = id da alocação; um evento por destino | Um evento por pagamento com várias alocações | `PaymentConfirmed` tem um só `agreementId` e consumidores deduplicam por `paymentId`; realocação e estorno parcial ficam exatos sem mudar contratos |
| 2 | Webhook só agenda; confirmação por consulta `GET /pix/{e2eid}` | Confiar no corpo do webhook | Notificação pode ser forjada/replay; o PSP é a fonte autoritativa |
| 3 | mTLS terminado no ALB (verify) + fixação da impressão digital do certificado do PSP na conta; HMAC como alternativa | mTLS na instância | Padrão AWS; instâncias continuam atrás do ALB |
| 4 | Certificado e segredos cifrados com `FieldEncryptor` no banco | KMS/SSM | Serviços ainda não aprovados (issues #6, #8) — pendência registrada |
| 5 | Externo até R$ 500,00 confirmado no registro; acima, segunda pessoa com `PAYMENT_REVIEW`; limite por assessoria | Revisar todos | Volume operacional × risco; segregação onde o valor importa |
| 6 | Destino obrigatório no pagamento externo | Aceitar sem destino | Evita dinheiro sem alocação; sobra continua possível e vai à fila |
| 7 | Títulos avulsos: vencidos primeiro; componentes proporcionais ao saldo | Principal primeiro | CC art. 355 para a ordem; critério neutro igual ao do negotiation |
| 8 | Novo evento `AgreementPaymentRecorded` publicado pelo negotiation | Payment consultar o acordo | Grafo não permite negotiation → payment; o payment precisa do não aplicado para realocar |
| 9 | Liberação de não aplicado de acordo encerrado publica `PaymentReversed` (`REALLOCATED`) | Nenhuma compensação | O incentive já registrou a base de comissão daquele acordo |
| 10 | Arquivo de retorno confirma instrução sem webhook; webhook posterior vincula o `endToEndId` | Exigir webhook | Baixa mesmo com webhook indisponível, sem duplicidade |
| 11 | Estorno sempre total; devolução parcial do PSP vira divergência | Estorno parcial | Simplicidade e exatidão dos consumidores; análise humana decide |
| 12 | Segundo Pix da mesma instrução é aceito e alocado às parcelas seguintes + divergência | Recusar | O dinheiro entrou; a operação decide devolver/realocar |
| 13 | `cobv` lê o documento do devedor como sistema (registrado no log de acesso); `cob` sem pagador | Pedir o documento ao operador | Exigência da API; minimização no Pix imediato |
| 14 | CNAB 240/400 só como formato reconhecido sem leitor | Implementar agora | Layout depende do banco/PSP escolhido (issue #12) |
| 15 | Situação de revisão servida pelo payment com `AGREEMENT_VIEW`, sem evidência/observação; motivo da recusa só para quem informou ou `PAYMENT_VIEW` | Projeção no negotiation; exigir `PAYMENT_VIEW` | Grafo proíbe negotiation → payment; menor privilégio (decisões 430–438) |

## 14. Pendências

- KMS/Secrets Manager para certificado e segredos (issues #6, #8); WAF com regra de taxa no webhook.
- Provedor de boleto e link (issue #12): adaptadores das portas e leitores CNAB 240/400.
- Devolução Pix iniciada pelo sistema (`PUT /pix/{e2eid}/devolucao/{id}`) para as devoluções de sobra (hoje registro manual da transferência).
- campaign: adaptador do `CampaignOfferPort` (oferta + instrução/link de pagamento numa etapa de régua) depende de o negotiation ter sujeito de alçada para campanhas; quando existir, ele usará `PaymentInstructionUseCases#create` (o campaign não depende do payment pelo grafo — a ligação fica num adaptador que o grafo permita). O campaign já consome `PaymentConfirmed`/`PaymentReversed` pelo `paymentId` (= alocação), coerente com §6.
- Frontend: telas de instruções, pagamentos, revisão, conciliação e contas Pix.
- Suspender a quebra do acordo enquanto houver pagamento informado aguardando aprovação (decisão 437): exigiria evento payment → negotiation e mudar a regra de quebra; a decidir pelo responsável.
- debt: estorno de reconhecimento de encargo (já listado no negotiation) afeta estornos de acordo.
