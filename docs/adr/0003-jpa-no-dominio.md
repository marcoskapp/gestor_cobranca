# ADR-0003 — Anotações JPA no modelo de domínio

**Contexto.** A arquitetura hexagonal pura separa entidades de domínio e entidades de persistência, com mapeadores entre elas. Com dezenas de agregados, isso dobraria o código e criaria divergências.

**Decisão.** As entidades de domínio carregam anotações `jakarta.persistence` (padrão da especificação, não de um framework). O domínio continua proibido de depender de Spring Data, Spring Web, JDBC e das camadas externas (verificado por ArchUnit). Repositórios de domínio são interfaces Java; as implementações Spring Data ficam em `infrastructure.persistence` e estendem a interface de domínio.

**Consequências.** Menos código e um único modelo. A regra de negócio continua testável sem banco. O mapeamento é validado contra as migrations a cada inicialização (`ddl-auto=validate`).
