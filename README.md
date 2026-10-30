# Gestor de Cobrança

Plataforma SaaS multi-tenant para assessorias de cobrança e recuperação de crédito: carteiras multi-credor, operação de cobrança, negociação com alçadas, acordos, pagamentos, conciliação, comissão e prestação de contas, com rastreabilidade de ponta a ponta.

Toda a documentação está em [`docs/`](docs/README.md), organizada em três visões:

- **Negócio** ([`docs/negocio/`](docs/negocio)): regras de produto, workflows e decisões para o responsável pelo produto.
- **Arquitetura** ([`docs/arquitetura/`](docs/arquitetura)): visão geral, topologia, stack, infraestrutura AWS e ADRs.
- **Técnico** ([`docs/tecnico/`](docs/tecnico)): convenções, segurança, logs e métricas e o desenho de cada módulo.

## Estrutura

```
backend/     Java 21 + Spring Boot (monólito modular)
frontend/    Angular
infra/       Terraform (AWS), Docker Compose (local e sandbox) e imagem da aplicação
scripts/     scripts de banco, deploy e do sandbox de demonstração
```

## Sandbox de demonstração (um comando)

Ambiente completo no Docker, já com dados **fictícios** (2 assessorias × 3 credores × 4 carteiras, um usuário por perfil, acordos, pagamentos, jurídico e réguas), sem precisar de JDK nem Node:

```bash
./scripts/sandbox/up.sh        # app em http://localhost:58080 · Mailpit em http://localhost:58025
./scripts/sandbox/verify.sh    # logins por perfil, volumes, isolamento entre assessorias e API pública
./scripts/sandbox/tokens.sh    # tokens de demonstração da API pública (/public-api/v1)
./scripts/sandbox/down.sh      # para (mantém os dados) · reset.sh recria do zero
```

Usuários como `gestor@aurora.exemplo.test` ou `operador@boreal.exemplo.test` (assessoria `aurora` ou `boreal` no login), senha `Sandbox@2026!`. Guia completo, lista de usuários e roteiro de demonstração: [`docs/tecnico/operacao/sandbox.md`](docs/tecnico/operacao/sandbox.md).

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

Frontend (Node 22.22.3+): `cd frontend && npm ci && npm start` — abre em http://localhost:4200 com `/api` encaminhado ao backend (detalhes em [`frontend/README.md`](frontend/README.md)).

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
