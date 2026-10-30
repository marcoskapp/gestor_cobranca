# Gestor de Cobrança

Plataforma SaaS multi-tenant para assessorias de cobrança e recuperação de crédito: carteiras multi-credor, operação de cobrança, negociação com alçadas, acordos, pagamentos, conciliação, comissão e prestação de contas, com rastreabilidade de ponta a ponta.

Toda a documentação está em [`docs/`](docs/README.md), organizada em três visões:

- **Negócio** ([`docs/negocio/`](docs/negocio)): regras de produto, workflows e decisões para o responsável pelo produto.
- **Arquitetura** ([`docs/arquitetura/`](docs/arquitetura)): visão geral, topologia, stack, infraestrutura AWS e ADRs.
- **Técnico** ([`docs/tecnico/`](docs/tecnico)): convenções, segurança, logs e métricas e o desenho de cada módulo.

## Sandbox de demonstração

Ambiente completo do Gestor de Cobrança no Docker, que sobe com **um comando** e já vem com dados **fictícios** em todas as telas principais: 2 assessorias × 3 credores × 4 carteiras, um usuário ativo para cada perfil, casos, acordos, pagamentos, jurídico, réguas e tokens da API pública. Serve para demonstração, treinamento e testes exploratórios.

> **Aviso:** os dados são 100% fictícios (nomes, empresas, CPFs/CNPJs gerados, e-mails no domínio reservado `exemplo.test`) e todos os provedores são simulados: nenhuma mensagem, ligação, Pix ou chamada de IA sai do ambiente. **Não use com dados reais nem em produção** — a aplicação recusa subir o perfil `sandbox` junto com o perfil `prod`.

### 1. Pré-requisitos

| Item | Requisito |
| --- | --- |
| Docker | Docker Engine **23.0 ou mais recente** (BuildKit, usado pelo build da imagem) ou Docker Desktop equivalente — testado com o Docker 29.9 |
| Docker Compose | **v2 ou mais recente**, o plugin `docker compose` (não o antigo `docker-compose`) — testado com o Compose v5.6 |
| Utilitários | `bash`, `curl` e `git`; `jq` para o `verify.sh` e para os exemplos da API |
| Memória | **6 GB livres para o Docker** recomendados (mínimo 4 GB; no ar o sandbox usa ~1,5 GB, o build usa mais; no Docker Desktop, *Settings → Resources*) |
| Disco | **15 GB livres** (imagens: ~3 GB; cache do build: até ~8 GB; volumes do sandbox: ~150 MB) |
| Portas (só em `127.0.0.1`) | **58080** (aplicação), **58025** (Mailpit) e **55432** (PostgreSQL); trocáveis, veja os problemas comuns |

**Não é preciso JDK, Maven nem Node**: o frontend e o backend são compilados dentro da imagem Docker.

Para conferir o que está instalado:

```bash
docker version            # "Client" e "Server" com Version 23.0+ (sem "Server", o Docker não está rodando)
docker compose version    # "Docker Compose version v2..." ou mais recente
docker info --format '{{.MemTotal}}'   # memória disponível para o Docker, em bytes
jq --version              # usado pelo verify.sh
```

No macOS, o Docker Engine e o Compose vêm com o **Docker Desktop** (ou OrbStack/Colima); abra o aplicativo antes de rodar os scripts. O `jq` instala com `brew install jq`. Os scripts funcionam com o Bash 3.2 que acompanha o macOS.

### 2. Passo a passo

```bash
git clone https://github.com/marcoskapp/gestor_cobranca.git
cd gestor_cobranca
./scripts/sandbox/up.sh
```

O `up.sh` constrói a imagem, sobe PostgreSQL, Mailpit e aplicação e **espera a carga dos dados fictícios terminar**, mostrando as etapas (`carga do sandbox: aurora: usuários de demonstração`…). A primeira subida costuma levar **10 a 20 minutos** (estimativa: depende da rede e da máquina, porque baixa as imagens base e as dependências de Maven e npm e compila tudo); as seguintes usam o cache do build — numa máquina de 2 CPUs e 8 GB, com o cache pronto, o build levou ~1 min. A carga dos dados leva de 2 a 3 minutos.

**Terminou quando** o terminal mostra o quadro `Sandbox do Gestor de Cobrança no ar (dados FICTÍCIOS)` com os endereços e o comando volta ao prompt com sucesso. A qualquer momento, `curl -s http://localhost:58080/actuator/health/readiness` responde `"status":"UP"` só depois da carga concluída.

### 3. Endereços

| O quê | Endereço | Login |
| --- | --- | --- |
| Aplicação | http://localhost:58080 | usuário e senha da tabela abaixo |
| Mailpit (e-mails enviados) | http://localhost:58025 | não |
| Swagger da API interna | http://localhost:58080/swagger-ui/index.html | **não** no sandbox (as chamadas *Try it out* pedem o `accessToken` de um login em *Authorize*) |
| Especificações OpenAPI | http://localhost:58080/v3/api-docs/interna e http://localhost:58080/v3/api-docs/public-v1 | **não** no sandbox |
| Documentação pública da API | http://localhost:58080/public-api/docs | não |
| Changelog da API pública | http://localhost:58080/public-api/docs/changelog | não |

### 4. Acesso

Senha de **todos** os usuários: `Sandbox@2026!`. No login, o campo **Assessoria** recebe `aurora` (Aurora Cobrança) ou `boreal` (Boreal Crédito); o e-mail segue o padrão `<usuário>@<assessoria>.exemplo.test`.

| Perfil | E-mail na Aurora (`aurora`) | E-mail na Boreal (`boreal`) |
| --- | --- | --- |
| Administrador | `admin@aurora.exemplo.test` | `admin@boreal.exemplo.test` |
| Gestor | `gestor@aurora.exemplo.test` | `gestor@boreal.exemplo.test` |
| Supervisor | `supervisor@aurora.exemplo.test` | `supervisor@boreal.exemplo.test` |
| Operador (alçada pleno) | `operador@aurora.exemplo.test` | `operador@boreal.exemplo.test` |
| Operador (alçada júnior, só o 1º credor) | `operador2@aurora.exemplo.test` | `operador2@boreal.exemplo.test` |
| Financeiro | `financeiro@aurora.exemplo.test` | `financeiro@boreal.exemplo.test` |
| Auditor | `auditor@aurora.exemplo.test` | `auditor@boreal.exemplo.test` |
| Jurídico | `juridico@aurora.exemplo.test` | `juridico@boreal.exemplo.test` |
| Advogado parceiro (só o 1º credor) | `advogado@aurora.exemplo.test` | `advogado@boreal.exemplo.test` |
| Analista de carteiras | `analista@aurora.exemplo.test` | `analista@boreal.exemplo.test` |
| Estratégia de cobrança | `estrategia@aurora.exemplo.test` | `estrategia@boreal.exemplo.test` |
| Monitoria e qualidade | `qualidade@aurora.exemplo.test` | `qualidade@boreal.exemplo.test` |
| Compliance e privacidade | `compliance@aurora.exemplo.test` | `compliance@boreal.exemplo.test` |
| Integrações | `integracao@aurora.exemplo.test` | `integracao@boreal.exemplo.test` |

**Administrador da plataforma** (provisiona e suspende assessorias): assessoria `plataforma`, e-mail `admin@plataforma.exemplo.test`, mesma senha.

Nenhum perfil exige MFA no sandbox (pode ser cadastrado em *Minha conta → Autenticador*). Os limites de segurança continuam valendo: 5 senhas erradas bloqueiam o usuário (o `admin` da assessoria desbloqueia) e são aceitos 20 logins por minuto por IP.

### 5. API pública

A carga emite tokens de demonstração para cada credor (`sandbox-<prefixo>-completo`, com todos os escopos, inclusive a posição do faturamento da API e do repasse: `billing:read` e `settlement:read`) e um só de leitura da carteira no 1º credor de cada assessoria (`sandbox-hzn-leitura`, `sandbox-csr-leitura`; sem escrita e sem os escopos de posição). Para ver os tokens:

```bash
./scripts/sandbox/tokens.sh          # tabela: assessoria, credor, nome, escopos e token
./scripts/sandbox/tokens.sh --raw    # separado por tabulação, para scripts
```

Exemplo pronto (carteiras do Banco Horizonte, da assessoria `aurora`):

```bash
TOKEN=$(./scripts/sandbox/tokens.sh --raw | awk -F'\t' '$4 == "sandbox-hzn-completo" {print $6}')
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:58080/public-api/v1/portfolios | jq '.data[] | {code, status}'
```

Posição do credor, calculada agora sem esperar o fechamento (o token `sandbox-hzn-leitura` recebe 403 nesses endpoints):

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:58080/public-api/v1/billing/position | jq '{periodStart, periodEnd, amountSoFar, currency}'
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:58080/public-api/v1/settlement/position | jq '{periodStart, asOf, direction}'
```

Na aplicação, a mesma posição aparece na tela **Posição do credor** (menu *Prestação de contas → Posição do credor*, `/posicao-credor`; entre como `financeiro` ou `gestor`): ciclo de consumo da API em andamento e repasse do período em aberto. Tokens criados antes desses escopos existirem não os ganham depois (os escopos não mudam após a emissão): rode `./scripts/sandbox/reset.sh` para recriá-los.

### 5a. Importação PDF (sem IA)

A IA da Importação PDF fica desligada no sandbox, mas a carga salva o mapa do layout "Histórico Financeiro por Cliente" no 1º credor de cada assessoria e gera PDFs **fictícios** de demonstração:

```bash
./scripts/sandbox/pdfs.sh            # copia para ./sandbox-pdfs/<assessoria>/ (fora do Git) e explica cada arquivo
```

Entre como `analista@aurora.exemplo.test` → *Importações → Importação PDF inteligente*, carteira `HZN-CARTAO`:

1. `historico-financeiro-hzn.pdf` → mapa salvo reaproveitado, totais conferindo → *Concordo, importar* (abre a conferência da importação);
2. `historico-financeiro-hzn-totais-divergentes.pdf` → mesmo mapa, total "Pendente" divergente: aprovação bloqueada sem justificativa;
3. `tabela-simples-hzn.pdf` → layout sem mapa: a tela abre o editor e o mapa é montado à mão (passo a passo e gabarito em [`sandbox.md` §4b](docs/tecnico/operacao/sandbox.md)).

Para testar com IA real (Amazon Bedrock, credenciais AWS temporárias por variável de ambiente e **aprovação explícita da transferência internacional**, LGPD art. 33 — só com os PDFs fictícios): `INGESTION_PDF_BEDROCK_ENABLED=true` e `INGESTION_PDF_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED=true` (opcionais: `INGESTION_PDF_BEDROCK_REGION`, `INGESTION_PDF_BEDROCK_MODEL_ID`) antes do `up.sh`; detalhes e avisos em [`sandbox.md` §4b](docs/tecnico/operacao/sandbox.md).

### 6. Comandos do dia a dia

```bash
./scripts/sandbox/verify.sh          # confere logins, volumes, isolamento entre assessorias, API pública, Swagger, posição do credor e Importação PDF
./scripts/sandbox/pdfs.sh            # copia os PDFs fictícios de demonstração da Importação PDF para ./sandbox-pdfs/
./scripts/sandbox/down.sh            # para o sandbox MANTENDO os dados
./scripts/sandbox/up.sh --no-build   # sobe de novo sem reconstruir a imagem (os dados continuam)
./scripts/sandbox/reset.sh           # apaga todos os dados do sandbox e recria do zero (pede confirmação; --yes dispensa)
docker compose -p gestor-cobranca-sandbox logs -f app   # logs da aplicação (Ctrl+C para sair)
```

### 7. Problemas comuns

| Sintoma | O que fazer |
| --- | --- |
| Porta ocupada (`port is already allocated` / `address already in use`) | escolha outras portas e use-as em todos os comandos: `export SANDBOX_APP_PORT=58081 SANDBOX_MAIL_PORT=58026 SANDBOX_DB_PORT=55433` antes do `up.sh` (e do `verify.sh`/`tokens.sh` na mesma sessão) |
| Build interrompido ou a aplicação para sozinha (`o contêiner da aplicação parou`, código 137) | Docker sem memória: reserve ao menos 6 GB em *Settings → Resources* (Docker Desktop), feche outros contêineres e rode `./scripts/sandbox/up.sh` de novo |
| Primeira subida demorada | é o download das imagens base e das dependências (Maven e npm) e o build; as próximas usam o cache. Se passar de 30 min, aumente o limite: `./scripts/sandbox/up.sh --timeout 3600` |
| Painéis e relatórios vazios ou desatualizados logo após subir | os painéis são atualizados pelo agendamento a cada 15 minutos: aguarde até 15 min |
| Falha ao baixar imagens (`failed to resolve reference "docker.io/…"`, `Forbidden`, `pull access denied`) | sem acesso ao Docker Hub: aponte as imagens base para um espelho/registro interno com `SANDBOX_NODE_IMAGE`, `SANDBOX_MAVEN_IMAGE`, `SANDBOX_JRE_IMAGE`, `SANDBOX_POSTGRES_IMAGE` e `SANDBOX_MAILPIT_IMAGE` (padrões: `node:22-bookworm-slim`, `maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre-noble`, `postgres:16-alpine`, `axllent/mailpit:v1.27.10`); atrás de proxy local, acrescente `SANDBOX_BUILD_NETWORK=host` |

Guia aprofundado — dados carregados, roteiro de demonstração de 30–40 minutos, API pública e faturamento, como a carga funciona e as proteções contra uso fora do sandbox: [`docs/tecnico/operacao/sandbox.md`](docs/tecnico/operacao/sandbox.md).

## Estrutura

```
backend/     Java 21 + Spring Boot (monólito modular)
frontend/    Angular
infra/       Terraform (AWS), Docker Compose (local e sandbox) e imagem da aplicação
scripts/     scripts de banco, deploy e do sandbox de demonstração
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
