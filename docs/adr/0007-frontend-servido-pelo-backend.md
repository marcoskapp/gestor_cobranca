# ADR-0007 — Frontend Angular servido pelo backend

**Contexto.** Hospedar a SPA separadamente exigiria S3 público ou CloudFront (fora dos serviços autorizados), além de outro domínio e configuração de CORS em produção.

**Decisão.** O build do Angular é empacotado em `classpath:/static/` do JAR e servido pelo próprio Spring Boot atrás do ALB (`SpaResourceConfig`), com fallback de rotas da SPA para `index.html`, cache imutável para arquivos com hash e sem cache para o `index.html`.

**Consequências.** Mesma origem para SPA e API (sem CORS em produção, cookies `SameSite=Strict` funcionam naturalmente), um único artefato por versão. Na fase de escala, o frontend pode migrar para S3 + CloudFront sem mudança de código.
