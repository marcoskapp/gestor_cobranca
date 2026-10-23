# Segurança

Controles implementados na plataforma, com a localização no código. Mudanças nesses pontos exigem revisão específica no pull request.

## 1. Autenticação

| Controle | Implementação |
| --- | --- |
| Senhas com Argon2id (64 MiB, 3 iterações) | `identity/infrastructure/config/SecurityConfig#passwordEncoder` |
| Política de senha: 12+ caracteres, 3 classes, sem senhas comuns ou dados do usuário, histórico das 5 últimas, expiração em 90 dias | `identity/domain/service/PasswordPolicy`, `CredentialService` |
| Bloqueio progressivo: 5 falhas → 5 min, dobrando até 1 h | `identity/domain/model/User#registerFailedLogin`, `LockoutPolicy` |
| Respostas genéricas e tempo equalizado (não revelam se o usuário existe) | `AuthenticationService` (hash fictício) |
| MFA TOTP (RFC 6238) obrigatório para ADMIN, GESTOR, FINANCEIRO e AUDITOR; anti-replay de código | `Totp`, `User#acceptMfaStep`, `app.security.mfa.required-for-roles` |
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
| Razão de comissão append-only (colunas financeiras imutáveis por privilégio de coluna e gatilho) e extrato aprovado imutável no banco | `V1802__incentive_ledger_and_statements.sql` |

## 3. Proteção de dados (LGPD)

| Controle | Implementação |
| --- | --- |
| Criptografia de campos pessoais AES-256-GCM com rotação de chave | `shared/crypto/FieldEncryptor`, `EncryptedStringConverter` |
| Busca sem dado em claro (blind index HMAC por tenant) | `shared/crypto/BlindIndex` |
| Exibição mascarada por padrão; revelação exige `DEBTOR_SENSITIVE_VIEW` e gera evento de acesso | casos de uso de devedor |
| Mascaramento automático de CPF, CNPJ, e-mail e telefone em todos os logs | `SensitiveDataMasker`, `JsonLogFormatter` |
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
