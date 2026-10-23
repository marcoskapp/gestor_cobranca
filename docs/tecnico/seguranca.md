# Segurança

Controles implementados na plataforma, com a localização no código. Mudanças nesses pontos exigem revisão específica no pull request.

## 1. Autenticação

| Controle | Implementação |
| --- | --- |
| Senhas com Argon2id (64 MiB, 3 iterações) | `identity/infrastructure/config/SecurityConfig#passwordEncoder` |
| Política de senha: 12+ caracteres, 3 classes, sem senhas comuns ou dados do usuário, histórico das 5 últimas, expiração em 90 dias | `identity/domain/service/PasswordPolicy`, `CredentialService` |
| Bloqueio progressivo: 5 falhas → 5 min, dobrando até 1 h | `identity/domain/model/User#registerFailedLogin`, `LockoutPolicy` |
| Respostas genéricas e tempo equalizado (não revelam se o usuário existe) | `AuthenticationService` (hash fictício) |
| MFA TOTP (RFC 6238) obrigatório para ADMIN, GESTOR, FINANCEIRO, AUDITOR, JURIDICO e ADVOGADO_PARCEIRO; anti-replay de código | `Totp`, `User#acceptMfaStep`, `app.security.mfa.required-for-roles` |
| Access token JWT RS256 de 10 min com `kid`, emissor e audiência validados | `shared/security/JwtKeyConfig`, `JwtTokenIssuer` |
| Sessão validada no servidor a cada requisição (cache de 15 s); revogação imediata | `UserAccessTokenResolver` |
| Refresh token opaco (256 bits), só o hash armazenado, rotativo, com detecção de reuso que revoga a sessão e alerta o usuário | `RefreshToken`, `AuthenticationService#rotate` |
| Cookie do refresh: HttpOnly, Secure, SameSite=Strict, caminho `/api/v1/auth`, verificação de Origin | `RefreshCookie`, `AuthController#requireAllowedOrigin` |
| Expiração por inatividade (30 min) e absoluta (12 h); máximo de 3 sessões simultâneas | `AuthSession`, `SessionManager`, `SessionCleanupJob` |
| Limite de tentativas por IP nos endpoints de autenticação | `identity/web/LoginRateLimiter` (recomendado também WAF) |
| Links de ativação (72 h) e redefinição (1 h) de uso único, invalidando anteriores | `ActionToken`, `CredentialService` |

## 2. Autorização e isolamento

| Controle | Implementação |
| --- | --- |
| Permissões granulares por perfil configurável; `@PreAuthorize` em todo endpoint | `shared/security/Permission`, `DefaultRoles`, controllers |
| Prevenção de escalada: não se concede perfil/permissão/escopo que o próprio administrador não tenha; último administrador protegido | `UserManagementService`, `RoleManagementService` |
| Escopo por credor em todos os casos de uso (404 para leitura fora do escopo, 403 para comandos) | `shared/security/AccessScope` |
| Row Level Security forçado em toda tabela de tenant; aplicação conecta com papel sem BYPASSRLS e sem DDL | migrations, `TenantAwareJpaTransactionManager`, `scripts/db/bootstrap-roles.sql` |
| Alterações de perfil/escopo revogam as sessões do usuário afetado | `UserManagementService` |
| Remuneração variável: escopo próprio/equipe/tudo (`PERFORMANCE_VIEW_*`); consulta a extrato, razão, prévia ou simulação de terceiro gera `COMPENSATION_DATA_VIEWED` | `incentive/application/service/IncentiveAccess` |
| Segregação de funções nos extratos: quem fecha o período, ajusta ou resolve contestação não aprova; ninguém aprova ou ajusta o próprio extrato; ajuste acima do limite exige segunda pessoa | `CommissionStatement#approve`, `StatementAdjustment#decide`, `StatementService` |
| Alçada de negociação: humanos e assistente passam pelo mesmo `NegotiationGuard` (política, envelope, perfil, escopo, elegibilidade); ninguém atribui a própria alçada; atribuição geral exige todos os credores; mudanças vão ao log de acesso (`PERMISSION_CHANGED`); o assistente não recebe o envelope da política e só relê as próprias simulações | `negotiation/application/service/NegotiationGuard`, `AuthorityService`, `SimulationService` |
| Políticas de cobrança publicadas imutáveis no banco (vigência só encurta) e simulações append-only | `V0700__negotiation_policies_and_indexes.sql`, `V0702__negotiation_simulation.sql` |
| Operação de cobrança: escopo por credor em casos, filas, tarefas, promessas e contestações; caso de outro operador só com `CASE_ASSIGN`; filas/tabulações/parâmetros sem credor exigem acesso a todos os credores | `collection/application/service/CaseAccess`, serviços do módulo |
| Interações e evidências de contestação append-only; histórico de atribuição só aceita o fim de vigência, uma vez (privilégio de coluna + gatilho) | `V0600__collection_tables.sql` |
| Jurídico: escopo por credor e, para membros de escritório parceiro, somente casos atribuídos ao escritório ativo (404 + `SCOPE_DENIED`); parâmetros, regras, escritórios e suspensões só para usuários internos; aceite de indicação e decisão do credor com `LEGAL_AUTHORIZE`; envio automático de medida exige `DEBTOR_SENSITIVE_VIEW` | `legal/application/service/LegalAccess`, serviços do módulo |
| Jurídico: linha do tempo, análises de viabilidade, andamentos e documentos append-only; despesas sem exclusão e com colunas financeiras imutáveis; parecer, pesquisa patrimonial, teor de andamentos e dados de audiência criptografados; documentos conferidos pela assinatura do conteúdo, com SHA-256 e download registrado | `V1700__legal_schema_and_tables.sql`, `DocumentStore`, `LegalDocumentService` |
| Razão de comissão append-only (colunas financeiras imutáveis por privilégio de coluna e gatilho) e extrato aprovado imutável no banco | `V1802__incentive_ledger_and_statements.sql` |

## 3. Proteção de dados (LGPD)

| Controle | Implementação |
| --- | --- |
| Criptografia de campos pessoais AES-256-GCM com rotação de chave | `shared/crypto/FieldEncryptor`, `EncryptedStringConverter` |
| Busca sem dado em claro (blind index HMAC por tenant) | `shared/crypto/BlindIndex` |
| Exibição mascarada por padrão; revelação exige `DEBTOR_SENSITIVE_VIEW` e gera evento de acesso | casos de uso de devedor |
| Importação de carteiras: limite de tamanho e de linhas, tipo conferido pelo conteúdo (não pela extensão), XLSX com limites de descompactação (zip bomb) e sem XXE, fórmulas nunca avaliadas e recusadas em campos de texto, relatório de erros com valores mascarados e células neutralizadas contra fórmulas (CSV injection), download do arquivo original só com `DEBTOR_SENSITIVE_VIEW` | `ingestion/infrastructure/parsing`, `FileSignature`, `RowValidator`, `CsvIssueReportWriter`, `ImportBatchService` |
| Mascaramento automático de CPF, CNPJ, e-mail e telefone em todos os logs | `SensitiveDataMasker`, `JsonLogFormatter` |
| Observações de interação e tarefa, descrição e parecer de contestação criptografados; evidências com SHA-256, download registrado (`FILE_DOWNLOADED`) | `collection/domain/model/Interaction`, `Dispute`, `DisputeService` (limpeza de arquivo de transação desfeita: `DisputeFileCleanup`) |
| Arquivos isolados por tenant no S3 com criptografia no servidor e URLs temporárias | `shared/storage` |
| Criptografia em trânsito: TLS no ALB; TLS obrigatório no RDS (`rds.force_ssl`) | Terraform |

## 4. Rastreabilidade

- **Log de acesso** (`shared.access_log` + `access.log`): login, falhas, MFA, bloqueios, sessões, alterações de usuário/perfil/escopo, acessos negados, dados sensíveis visualizados, exportações e downloads. Gravado em transação própria (persiste mesmo se a operação falhar).
- **Trilha de auditoria** (`shared.audit_event`): antes/depois de toda alteração relevante, na mesma transação da alteração; append-only no banco (UPDATE/DELETE negados por privilégio e gatilho); hash de conteúdo calculado no banco e encadeado por tenant; verificação de integridade sob demanda (`POST /api/v1/governance/audit/integrity-check`).

## 5. Superfície HTTP

- Cabeçalhos: HSTS, CSP restritiva, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`.
- CORS restrito às origens configuradas.
- Erros sem detalhes internos (RFC 9457 com `requestId` para suporte); stack traces apenas no log técnico.
- Endpoints públicos declarados explicamente (`PublicEndpoint`) e listados no log de inicialização.
- Actuator exposto apenas para health/info; demais endpoints negados.

## 6. Segredos

Nenhum segredo em código ou configuração versionada. Em produção, senha do banco, chaves JWT e chaves de criptografia são provisionadas na instância (Parameter Store/KMS, sujeito a aprovação) e lidas como variáveis de ambiente ou arquivos `0600`. Chaves de desenvolvimento ficam fora do repositório (`backend/config/dev`) e as de teste são exclusivas de teste.

## 7. Recomendações pendentes de aprovação

AWS WAF (regras gerenciadas e limite de taxa), GuardDuty, Parameter Store + KMS para segredos e rotação, ACM para certificados, e testes de intrusão antes da entrada em produção.
