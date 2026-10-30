# Sandbox de demonstração

Ambiente completo do Gestor de Cobrança que sobe com **um comando**, já com dados **fictícios** em todas as telas principais: duas assessorias, três credores por assessoria, quatro carteiras por credor, um usuário ativo para cada perfil e casos em vários estágios da operação. Serve para demonstração, treinamento e testes exploratórios. **Nunca** use com dados reais nem como base para produção.

> Tudo aqui é inventado: nomes, empresas, CPFs/CNPJs (dígitos verificadores válidos, números gerados ao acaso), e-mails no domínio reservado `exemplo.test` e telefones que só chegam aos simuladores. Nenhuma mensagem, ligação, Pix ou chamada de IA sai do ambiente.

## 1. Subir, parar e recriar

Pré-requisitos: Docker com Compose v2 e `curl` (o `verify.sh` também usa `jq`). Não é preciso JDK, Maven nem Node: o build acontece dentro da imagem.

```bash
./scripts/sandbox/up.sh            # constrói a imagem, sobe tudo e espera a carga terminar (1º build: alguns minutos)
./scripts/sandbox/up.sh --no-build # sobe de novo reaproveitando a imagem já construída
./scripts/sandbox/verify.sh        # verificação de fumaça: logins por perfil, volumes, isolamento entre assessorias e API pública
./scripts/sandbox/tokens.sh        # tokens de demonstração da API pública (segredos exibidos só aqui)
./scripts/sandbox/down.sh          # para, mantendo os dados
./scripts/sandbox/reset.sh         # apaga os volumes do sandbox e recria do zero (pede confirmação; --yes dispensa)
```

O `up.sh` só termina quando a aplicação está no ar **e** a carga de dados foi concluída (grupo de prontidão `/actuator/health/readiness`, que inclui `sandboxSeed`). A carga leva cerca de um minuto; o progresso aparece no terminal e em `docker compose -p gestor-cobranca-sandbox logs -f app`.

| Serviço | Endereço | Observação |
| --- | --- | --- |
| Aplicação (frontend + API) | http://localhost:58080 | frontend servido pelo backend (ADR-0007) |
| Swagger | http://localhost:58080/swagger-ui/index.html | protegido por token (como nos demais ambientes): o navegador precisa enviar `Authorization: Bearer <accessToken>` (extensão que injete o cabeçalho) — ou baixe a especificação com `curl -H "Authorization: Bearer …" http://localhost:58080/v3/api-docs` |
| Mailpit (e-mails) | http://localhost:58025 | links de ativação, alertas de segurança e e-mails aos devedores fictícios; preservados entre subidas (volume `sandbox-mail`) |
| PostgreSQL | `localhost:55432`, banco `cobranca` | só para inspeção; usuário `app_runtime_login` / `app_runtime_sandbox` (sujeito a RLS) |

O sandbox é um projeto Compose separado (`gestor-cobranca-sandbox`, arquivo [`infra/docker/sandbox/compose.yml`](../../../infra/docker/sandbox/compose.yml)), com volumes próprios e portas que não colidem com o ambiente de desenvolvimento (`infra/docker/docker-compose.yml`: 5432, 1025, 8025; backend 8080; `ng serve` 4200). As portas ficam presas a `127.0.0.1` e podem ser trocadas com `SANDBOX_APP_PORT`, `SANDBOX_MAIL_PORT` e `SANDBOX_DB_PORT`.

**Registro de imagens interno ou sem acesso ao Docker Hub.** As imagens base são parâmetros: `SANDBOX_NODE_IMAGE`, `SANDBOX_MAVEN_IMAGE`, `SANDBOX_JRE_IMAGE`, `SANDBOX_POSTGRES_IMAGE` e `SANDBOX_MAILPIT_IMAGE` (padrões: imagens oficiais `node:22-bookworm-slim`, `maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre-noble`, `postgres:16-alpine`, `axllent/mailpit:v1.27.10`). Atrás de proxy local, `SANDBOX_BUILD_NETWORK=host` faz o build usar a rede do host.

## 2. Acesso

Senha de demonstração de **todos** os usuários: `Sandbox@2026!` (trocável com `SANDBOX_DEMO_PASSWORD` antes da primeira subida). No login, o campo **Assessoria** recebe o identificador da assessoria (`aurora`, `boreal` ou `plataforma`).

**Administrador da plataforma** — assessoria `plataforma`, e-mail `admin@plataforma.exemplo.test`: provisiona e suspende assessorias.

Usuários das assessorias — e-mail `<usuário>@<assessoria>.exemplo.test`:

| Usuário | Perfil | Escopo de credores | Aurora Cobrança (`aurora`) | Boreal Crédito (`boreal`) |
| --- | --- | --- | --- | --- |
| `admin` | Administrador (`ADMIN`) | todos | Helena Prado | Sérgio Albuquerque |
| `gestor` | Gestor (`GESTOR`) | todos | Rafael Monteiro | Mônica Teixeira |
| `supervisor` | Supervisor (`SUPERVISOR`) | todos | Camila Torres | André Paiva |
| `operador` | Operador (`OPERADOR`), alçada **pleno** | todos | Lucas Ferraz | Juliana Costa |
| `operador2` | Operador (`OPERADOR`), alçada **júnior** | **só o 1º credor** | Bianca Nogueira | Caio Martins |
| `financeiro` | Financeiro (`FINANCEIRO`) | todos | Marcos Viana | Tereza Lima |
| `auditor` | Auditor (`AUDITOR`) | todos | Patrícia Leal | Roberto Assis |
| `juridico` | Jurídico (`JURIDICO`) | todos | Eduardo Sales | Vanessa Moura |
| `advogado` | Advogado parceiro (`ADVOGADO_PARCEIRO`) | **só o 1º credor** | Fernanda Rocha | Henrique Dias |
| `analista` | Analista de carteiras (`ANALISTA_CARTEIRA`) | todos | Gustavo Pires | Priscila Neves |
| `estrategia` | Estratégia de cobrança (`ESTRATEGIA`) | todos | Larissa Campos | Daniel Freitas |
| `qualidade` | Monitoria e qualidade (`QUALIDADE`) | todos | Renata Duarte | Simone Arruda |
| `compliance` | Compliance e privacidade (`COMPLIANCE`) | todos | Otávio Brandão | Fábio Rezende |
| `integracao` | Integrações (`INTEGRACAO`) | todos | Igor Matos | Natália Prates |

Exemplo: `gestor@aurora.exemplo.test` / `Sandbox@2026!`, assessoria `aurora`.

**MFA.** No sandbox nenhum perfil tem MFA obrigatório (`app.security.mfa.required-for-roles` vazio em `application-sandbox.yml`, decisão 633), para a demonstração entrar direto com a senha publicada. Para demonstrar o MFA: entre com qualquer usuário, vá em **Minha conta → Autenticador**, cadastre um aplicativo TOTP e, a partir daí, o login daquele usuário pede o código. O código de produção não muda; em produção a lista de perfis obrigatórios continua a de `application.yml`.

**Limites de segurança continuam valendo:** bloqueio progressivo após 5 senhas erradas (o administrador desbloqueia em Administração → Usuários), 20 logins por minuto por IP, sessão de 30 min de inatividade e senha que expira em 90 dias (se o volume do sandbox ficar mais que isso, use `reset.sh`).

## 3. O que vem carregado

| Área | Conteúdo |
| --- | --- |
| Assessorias | `aurora` (Banco Horizonte, Varejo Estrela, Vale Conecta) e `boreal` (Coop Sertão, Energia Litoral, Monte Claro), com janela de contato 8h–20h, segunda a sábado |
| Credores | 3 por assessoria, com contrato de prestação de serviço (vigência desde o 1º dia de 12 meses atrás, repasse no dia 10), base legal (LGPD) e regra de comissão publicada |
| Carteiras | 4 por credor (cartão, veículos, crédito pessoal, cheque especial; crediário, boletos; telefonia; energia; mensalidades…), ativas, carregadas pela **importação via API** (lotes visíveis em Importações) |
| Devedores e títulos | ~220 devedores, ~460 títulos e ~235 casos de cobrança por assessoria, atrasos de 10 a 720 dias **relativos ao dia em que o sandbox subiu**, valores variados, pessoas físicas e jurídicas, 10 devedores com títulos em mais de um credor, contratos com garantia de veículo (alienação fiduciária) e títulos executivos |
| Políticas de negociação | uma por credor, por segmento: juros, multa, honorários, desconto máximo por forma de pagamento, entrada mínima, parcelas máximas e escada de concessões; sobreposição de "atraso longo" no banco |
| Alçadas | operador pleno, supervisor e gestor com perfis de alçada; `operador2` no padrão júnior (propostas acima vão para a fila de aprovação) |
| Comissionamento | regras da assessoria por contrato (percentual padrão, faixa de atraso, à vista, renegociação, valor fixo por acordo, honorários) e plano de remuneração variável dos negociadores com metas do mês |
| Política de contato | por canal (WhatsApp, SMS, e-mail, telefone): janela, dias, limites por dia/semana e intervalo mínimo; o 1º credor restringe o telefone a dias úteis |
| Operação | equipe "Mesa de negociação", fila por credor (a do 1º credor em rodízio, já distribuída), tabulações padrão, ramais de telefonia |
| Canais e provedores | WhatsApp e SMS pelo **simulador**, e-mail pelo SMTP da plataforma (**Mailpit**), Pix pelo **PSP simulado**, telefonia pelo **simulador**, assistente de IA e chat de suporte com **modelo simulado**; templates de mensagem publicados (aprovação da Meta simulada) |
| Portal do devedor | portal do 1º credor (`/portal/<assessoria>-<credor>`, ex.: `/portal/aurora-banco-horizonte`) com desafio por data de nascimento |
| Réguas | régua de atraso inicial (abertura → retomada → tarefa de ligação) e régua de lembrete de parcela de acordo, ativas no 1º credor |
| Cenários (1º credor) | interações tabuladas, promessa, retorno agendado, acordo em pagamento (1ª parcela paga por Pix), acordo quitado à vista, acordo cancelado, quitação por transferência **pendente de revisão** (quebra suspensa), pagamento **estornado**, proposta **aguardando aprovação**, contestação aberta e em análise, restrição de contato (opt-out), conversa recebida no WhatsApp |
| Outros credores | acordos em pagamento e formalizado, promessa, tentativas sem sucesso, conversa recebida |
| Jurídico | escritório parceiro com o `advogado` como membro; regra de indicação; um caso **ajuizado e citado** (viabilidade, autorização do credor com evidência, processo com número CNJ fictício válido, prazo para embargos, custas) atribuído ao escritório e outro **em análise** |
| Compliance | pedido de titular (LGPD) aberto; trilha de auditoria e log de acesso de tudo o que a carga fez |
| Prestação de contas | demonstrativo do 1º credor calculado |
| Painéis | snapshots do analytics atualizados pelo agendamento (a cada 15 min) |
| API pública | 8 tokens de demonstração (um completo por credor e um só de leitura no 1º credor de cada assessoria) e condições de cobrança das integrações em cada contrato (§4a) |

A carga também deixa e-mails no Mailpit: os links de ativação usados para ativar cada usuário e as mensagens de e-mail da operação.

## 4. Roteiro sugerido de demonstração (30–40 min)

1. **Operador** (`operador@aurora…`): *Minhas pendências* e *Meus casos* → abra um caso (ficha com títulos, contatos mascarados, interações) → simule um acordo, mostre a escada de concessões e a alçada → registre uma interação com promessa.
2. **Operador júnior** (`operador2@aurora…`): veja que só aparecem casos do Banco Horizonte (escopo por credor); proponha um desconto acima da alçada → vai para aprovação.
3. **Supervisor**: *Aprovações* (a proposta do operador júnior), *Caixa de entrada* (conversas do WhatsApp simulado, com sugestão do assistente em copiloto), contestações, distribuição da fila.
4. **Financeiro**: *Pagamentos* — Pix confirmado e conciliado, pagamento externo pendente de revisão (aprove ou recuse; o selo de quebra suspensa aparece no acordo), estorno; *Prestação de contas* — demonstrativo calculado.
5. **Gestor**: credores, contratos e carteiras; políticas de negociação; réguas e campanhas (inscrições e envios); painéis e relatórios; portal do devedor (abra o endereço público do portal em outra aba).
6. **Jurídico** e **advogado parceiro**: o caso ajuizado (processo, prazo, custas, garantias) — o advogado parceiro só vê o caso atribuído ao escritório.
7. **Analista de carteiras**: *Importações* — lotes de carga com totais de controle; importe um arquivo pelo assistente de importação.
8. **Compliance** e **auditor**: pedido de titular, restrições de contato, log de acesso (os logins da demonstração aparecem) e trilha de auditoria (inclusive as ações da carga, marcadas "carga do sandbox").
9. **Administrador da plataforma** (`admin@plataforma…`, assessoria `plataforma`): lista de assessorias; provisione uma terceira e acompanhe o convite no Mailpit.
10. **API pública** (terminal + tela): `./scripts/sandbox/tokens.sh` → com o token `sandbox-hzn-completo`, liste carteiras e títulos e importe um lote pelos exemplos da §4a; mostre a recusa (403) do token `sandbox-hzn-leitura` ao importar e o 404 ao tentar a carteira de outro credor; abra a documentação pública e o changelog (`/public-api/docs`, `/public-api/docs/changelog`); depois, como **gestor**, *Integrações → Tokens de integração* (prefixo, escopos, último uso, revogar/rotacionar) e *Consumo da API* (as chamadas aparecem em até ~1 min).
11. **Faturamento da API** (gestor ou financeiro): ficha do credor → contrato → condições de cobrança das integrações (dia de fechamento, mensalidade, franquias, excedente; cada credor diferente) e *Integrações → Extratos da API* (extratos por ciclo depois do primeiro fechamento).
12. **Simuladores** (Swagger, com o token do `integracao`): `POST /api/v1/communication/sandbox/inbound` (mensagem de um devedor), `POST /api/v1/payment/sandbox/instructions/{id}/pay` (pagar um Pix), `POST /api/v1/telephony/sandbox/inbound` (ligação recebida).

## 4a. API pública dos credores

A carga emite **tokens de integração de demonstração** pelo mesmo caso de uso das telas (`IssueIntegrationToken`), como o administrador de cada assessoria — a regra que só deixa conceder escopos que o emissor tem é aplicada normalmente:

| Token | Credor | Escopos |
| --- | --- | --- |
| `sandbox-<prefixo>-completo` | cada credor (HZN, EST, TVC na `aurora`; CSR, ELT, UMC na `boreal`) | todos os do catálogo: leitura e escrita de carteiras, importação, títulos, devedores, contestações e pagamentos diretos; leitura de cobrança e acordos |
| `sandbox-<prefixo>-leitura` | só o 1º credor de cada assessoria (`hzn`, `csr`) | somente os escopos `*:read` |

O segredo do token só existe na emissão (o sistema guarda o HMAC e o log mascara `gck_`). Para a demonstração, a carga o grava em `/sandbox-data/api-tokens.txt` (volume `sandbox-data`, exclusivo do sandbox, permissão 600). Para ver:

```bash
./scripts/sandbox/tokens.sh          # assessoria, credor, nome, escopos e token completo
./scripts/sandbox/tokens.sh --raw    # linhas separadas por tabulação (para scripts)
```

Subir de novo não emite tokens novos (identificados pelo nome); se o arquivo se perder sem o banco, o segredo é reemitido pela rotação do próprio token. No `reset.sh` os tokens são recriados com segredos novos.

**Endereços**

| O quê | Endereço | Token |
| --- | --- | --- |
| API v1 | http://localhost:58080/public-api/v1 | `Authorization: Bearer gck_…` |
| Documentação pública (Swagger UI) | http://localhost:58080/public-api/docs | não |
| Changelog público | http://localhost:58080/public-api/docs/changelog (JSON: `/public-api/changelog`) | não |
| Versões | http://localhost:58080/public-api/versions | não |
| Contrato OpenAPI publicado | http://localhost:58080/public-api/v1/openapi.json | não |
| Telas internas | **Integrações → API pública**: Tokens de integração, Consumo da API, Extratos da API (`/integracoes/tokens`, `/integracoes/consumo`, `/integracoes/extratos`) | login de usuário |

**Exemplos**

```bash
TOKEN=$(./scripts/sandbox/tokens.sh --raw | awk -F'\t' '$4 == "sandbox-hzn-completo" {print $6}')
API=http://localhost:58080/public-api/v1

curl -s -H "Authorization: Bearer $TOKEN" "$API/creditor" | jq
curl -s -H "Authorization: Bearer $TOKEN" "$API/portfolios" | jq '.data[] | {id, code, status}'
curl -s -H "Authorization: Bearer $TOKEN" "$API/titles?limit=5" | jq '.data[] | {externalId, dueDate, openBalance}'

# importação de um lote JSON (Idempotency-Key obrigatório; repetir com a mesma chave devolve o mesmo lote)
PORTFOLIO=$(curl -s -H "Authorization: Bearer $TOKEN" "$API/portfolios" | jq -r '.data[] | select(.code=="HZN-CARTAO") | .id')
curl -s -X POST "$API/imports" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-lote-0001' \
  -d "{\"portfolioId\":\"$PORTFOLIO\",\"mode\":\"IMPORT\",\"records\":[{\"documento\":\"52998224725\",
       \"nome\":\"Cliente Demonstração API (fictício)\",\"id_titulo\":\"DEMO-1\",\"vencimento\":\"2026-01-15\",
       \"valor_principal\":199.90}]}" | jq '{id, status}'
curl -s -H "Authorization: Bearer $TOKEN" "$API/imports/<id do lote>" | jq '{status, counters}'
```

Listas usam paginação por cursor e não trazem o último minuto (janela de estabilização da sincronização). O `verify.sh` exercita tudo isso e confere: 200/202 com o token certo; 401 sem token e com token inválido; 404 ao acessar ou importar em carteira de outro credor e de outra assessoria; 403 do token só de leitura num endpoint de escrita; páginas públicas sem token; e o consumo aparecendo em `GET /api/v1/public-api/usage` (gravado em lote a cada minuto).

**Faturamento.** O contrato de cada credor tem condições de cobrança das integrações (organization, `ContractIntegrationPricing`, definidas pelo gestor): dia de fechamento do ciclo de consumo variado — HZN 5, EST 15, TVC 31 (último dia), CSR 10, ELT 20, UMC 31 —, mensalidade (R$ 190 a R$ 490 por segmento), franquias de chamadas, MB recebidos/enviados e registros, e preço do excedente. Elas aparecem na ficha do credor (contrato). Como as condições valem a partir do próximo ciclo de consumo (não retroativas) e o extrato só é fechado depois do fim do ciclo (job diário às 02h20), um sandbox recém-criado mostra o consumo do dia em **Consumo da API**, e os extratos aparecem em **Extratos da API** a partir do primeiro fechamento.

## 5. Como a carga funciona

A carga é o módulo `sandbox` do backend (`br.com.gestorcobranca.sandbox`). Ela roda numa thread própria assim que a aplicação fica no ar e usa **somente os casos de uso públicos** dos módulos — as mesmas regras de domínio, criptografia de dados pessoais com blind index, auditoria, logs de negócio e eventos da operação real. Não há SQL de dados nem migration de carga.

- **Assessorias:** provisionamento da plataforma (perfis padrão e configurações iniciais semeadas pelos módulos).
- **Usuários:** criados pela administração de usuários e ativados pelo fluxo de um usuário real: o link de ativação chega ao Mailpit, a carga lê o link pela API do Mailpit e define a senha de demonstração (política de senha, histórico, auditoria e log de acesso normais). O administrador da plataforma é criado pelo bootstrap padrão (`APP_BOOTSTRAP_PLATFORM_*`).
- **Ações de negócio:** cada uma é feita pelo perfil que a faria na operação (o analista importa, o gestor publica políticas, o operador negocia, o financeiro estorna, o compliance registra o pedido de titular…), com as permissões e o escopo de credores daquele perfil. Sem sessão HTTP, a trilha de auditoria registra o usuário com sessão vazia e nome marcado "(carga do sandbox)".
- **Datas relativas a hoje:** vencimentos, atrasos e vigências são calculados a partir do dia da primeira subida; os devedores são gerados de forma determinística (mesma semente → mesmos documentos e nomes).
- **Idempotente:** cada etapa confere o que já existe antes de agir. Subir de novo (`down.sh` + `up.sh`) não duplica nada; o `verify.sh` dá os mesmos números.
- **Cenários opcionais:** se uma regra de negócio recusar um cenário (ex.: mudança futura de regra), ele vira aviso em `/actuator/health/readiness` (`components.sandboxSeed.details.warnings`) e no log técnico, e a carga segue. Falha de infraestrutura (banco, tempo esgotado) interrompe a carga e deixa o sandbox fora de prontidão (`state: FAILED`).
- **Chaves:** o entrypoint da imagem gera, na primeira subida, o par RSA do JWT e as chaves de criptografia no volume `sandbox-secrets` (nada versionado; [`infra/docker/app/entrypoint.sh`](../../../infra/docker/app/entrypoint.sh)).
- **Observabilidade:** log técnico por etapa (`Etapa da carga do sandbox concluída`, com `step` e `durationMs`), métricas `sandbox.seed.step{step}` e `sandbox.seed.duration{outcome}`; os eventos de negócio e de acesso são os da própria operação.

### Proteções contra ativação acidental

| Proteção | Onde |
| --- | --- |
| Todos os componentes da carga exigem o perfil `sandbox` **e** `app.sandbox.seed.enabled=true` | `@SandboxSeedComponent` |
| Com a flag ligada, a aplicação **não sobe** se o perfil `prod` estiver ativo, se o perfil `sandbox` não estiver ativo ou se `app.environment` ≠ `sandbox` | `SandboxSeedGuard` (+ `SandboxSeedGuardTest`) |
| A flag só é ligada pelo compose do sandbox (`SANDBOX_SEED_ENABLED=true`); o padrão em `application-sandbox.yml` é desligado | `application-sandbox.yml` |
| Provedores simulados (mensagens, Pix, telefonia, IA) recusam o perfil `prod` | guardas de cada módulo |
| O entrypoint recusa gerar chaves locais com o perfil `prod` | `entrypoint.sh` |
| Produção não usa contêiner: roda o JAR nas instâncias EC2 com `SPRING_PROFILES_ACTIVE=prod` e segredos do Secrets Manager | `scripts/instance/user-data.sh` |

### Tokens de integração (API pública)

Etapa `IntegrationTokensSeeder` ("tokens de integração", entre a configuração dos credores e a carga das carteiras): emite os tokens de demonstração pelo caso de uso público `IssueIntegrationToken` como o administrador da assessoria, idempotente pelo nome do token, e guarda o segredo pelo `FileDemoTokenVault` (arquivo do volume `sandbox-data`, permissão 600, regravado de forma atômica). O log técnico registra só o id e o prefixo público do token. Uso e exemplos na §4a. As condições de cobrança das integrações de cada contrato são definidas na etapa de configuração operacional (`IntegrationPricingCatalog`). A chave HMAC dos segredos (`APP_PUBLIC_API_TOKEN_HASH_KEY`) é gerada pelo entrypoint junto com as demais chaves locais.

## 6. Problemas comuns

| Sintoma | Causa provável e correção |
| --- | --- |
| `up.sh` falha no build ao baixar imagens base | sem acesso ao Docker Hub: aponte `SANDBOX_*_IMAGE` para o registro interno |
| `state: FAILED` na prontidão | veja `docker compose -p gestor-cobranca-sandbox logs app` (linha "Carga do sandbox falhou na etapa …"); corrija e rode `up.sh --no-build` (a carga retoma de onde parou) ou `reset.sh` |
| Porta em uso | troque `SANDBOX_APP_PORT`/`SANDBOX_MAIL_PORT`/`SANDBOX_DB_PORT` |
| Login recusado com a senha certa | usuário bloqueado por tentativas erradas (desbloqueie como `admin`) ou senha expirada após 90 dias (`reset.sh`) |
| Mensagens da régua "adiadas" | fora da janela da política de contato (8h–20h; SMS 9h–19h) — comportamento esperado |
