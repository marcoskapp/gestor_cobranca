# ADR-0006 — Spring Boot 4 / Java 21

**Contexto.** Projeto novo iniciado em outubro de 2026. A linha Spring Boot 3.x encerrou o suporte gratuito; a 4.x é a linha mantida, com Spring Framework 7, Spring Security 7, Hibernate 7 e Jackson 3.

**Decisão.** Spring Boot 4.1 com Java 21 LTS (disponível no Amazon Corretto). Recursos nativos substituem bibliotecas extras: `@Retryable` do Spring Framework 7, logs estruturados do Boot, `RestClient`.

**Consequências.** Suporte e correções de segurança garantidos por mais tempo. Migração para Java 25 LTS planejada quando o ecossistema de bibliotecas estiver certificado.
