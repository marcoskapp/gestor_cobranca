# ADR-0005 — Autenticação própria com JWT, refresh rotativo e MFA

**Contexto.** Opções avaliadas: Spring Security próprio, Amazon Cognito (serviço fora da lista autorizada) e Keycloak (componente adicional para operar).

**Decisão (aprovada pelo responsável).** Spring Security próprio: access token JWT RS256 de curta duração, refresh token opaco rotativo com detecção de reuso em cookie HttpOnly, sessão validada no servidor, MFA TOTP, bloqueio progressivo e Argon2id. Detalhes em `docs/tecnico/seguranca.md`.

**Consequências.** Controle total do fluxo e do log de acesso, sem dependência externa. A responsabilidade por manter o fluxo seguro é do projeto: mudanças nessa área exigem revisão específica.
