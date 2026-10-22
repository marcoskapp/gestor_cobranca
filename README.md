# Gestor de Cobrança

Plataforma SaaS multi-tenant para assessorias de cobrança e recuperação de crédito: carteiras multi-credor, operação de cobrança, negociação com alçadas, acordos, pagamentos, conciliação, comissão e prestação de contas, com rastreabilidade de ponta a ponta.

- Produto e infraestrutura: [`documentação/`](documentação/)
- Arquitetura técnica: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Convenções de código: [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md)

## Estrutura

```
backend/     Java 21 + Spring Boot (monólito modular)
frontend/    Angular
infra/       Terraform (AWS) e Docker Compose (local)
scripts/     scripts de banco e deploy
```

## Executar localmente

Pré-requisitos: JDK 21, Maven 3.9+, Docker.

```bash
# 1. banco e captura de e-mails
docker compose -f infra/docker/docker-compose.yml up -d

# 2. chaves JWT de desenvolvimento (não versionadas)
mkdir -p backend/config/dev
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out backend/config/dev/jwt-private.pem
openssl pkey -in backend/config/dev/jwt-private.pem -pubout -out backend/config/dev/jwt-public.pem

# 3. aplicação (perfil local; cria o tenant da plataforma na primeira execução)
cd backend
APP_BOOTSTRAP_PLATFORM_ADMIN_EMAIL=admin@plataforma.local \
APP_BOOTSTRAP_PLATFORM_CNPJ=11222333000181 \
mvn spring-boot:run
```

O link de ativação do administrador chega no Mailpit (http://localhost:8025). Depois de ativar, entre com o tenant `plataforma`.

## Testes

```bash
cd backend
mvn test      # unitários e regras de arquitetura/modularidade
mvn verify    # + integração (Testcontainers com PostgreSQL)
```

Sem Docker, os testes de integração usam um PostgreSQL existente com os papéis já criados (`scripts/db/bootstrap-roles.sql`):

```bash
IT_DB_ADMIN_URL=jdbc:postgresql://localhost:5432/postgres IT_DB_ADMIN_USER=postgres IT_DB_ADMIN_PASSWORD=... \
IT_DB_OWNER_PASSWORD=... IT_DB_RUNTIME_PASSWORD=... mvn verify
```

## Fluxo de trabalho

Branches `feature/*` a partir da `master` e pull request com aprovação do responsável pelo projeto.
