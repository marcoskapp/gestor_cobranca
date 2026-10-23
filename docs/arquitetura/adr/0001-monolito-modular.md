# ADR-0001 — Monólito modular com Spring Modulith

**Contexto.** O produto cobre 17 contextos de negócio (carteiras, cobrança, negociação, pagamentos…) com regras financeiras que exigem consistência transacional. A equipe é pequena e o custo operacional inicial precisa ser baixo.

**Decisão.** Um único artefato Spring Boot, dividido em módulos por contexto (pacotes de primeiro nível), com fronteiras verificadas pelo Spring Modulith e ArchUnit. Módulos só se comunicam pela interface `application.port.in` (síncrono, na direção do grafo) ou por eventos de integração do kernel (reações no sentido contrário).

**Consequências.** Um deploy, uma base de dados e transações locais simples para operações financeiras. Fronteiras explícitas permitem extrair um módulo como serviço no futuro (ex.: `communication` ou `campaign` sob alto volume) sem reescrever o domínio. O custo é a disciplina das regras, garantida por testes no build.
