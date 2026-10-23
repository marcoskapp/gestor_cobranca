# Registros de decisão de arquitetura (ADR)

Cada decisão estrutural fica registrada com contexto, decisão e consequências. Uma decisão só muda por um novo ADR que substitua o anterior.

| ADR | Decisão | Situação |
| --- | --- | --- |
| [0001](0001-monolito-modular.md) | Monólito modular com Spring Modulith | Aceita |
| [0002](0002-multi-tenancy-rls.md) | Multi-tenancy em schema compartilhado com Row Level Security | Aceita |
| [0003](0003-jpa-no-dominio.md) | Anotações JPA no modelo de domínio | Aceita |
| [0004](0004-eventos-e-fila-no-postgresql.md) | Eventos de integração e fila de jobs no PostgreSQL | Aceita |
| [0005](0005-autenticacao-propria-jwt.md) | Autenticação própria com JWT, refresh rotativo e MFA | Aceita |
| [0006](0006-spring-boot-4.md) | Spring Boot 4 / Java 21 | Aceita |
| [0007](0007-frontend-servido-pelo-backend.md) | Frontend Angular servido pelo backend | Aceita |
| [0008](0008-tempo-real-sse-listen-notify.md) | Tempo real da caixa de entrada: SSE por instância e LISTEN/NOTIFY do PostgreSQL | Aceita |
