# API pública — publicação, versionamento e changelog

Procedimento para publicar e atualizar o contrato da API pública a cada versão. Desenho: [`../desenho/api-publica.md`](../desenho/api-publica.md). Política de compatibilidade: [`../../negocio/api-publica.md`](../../negocio/api-publica.md) §7.

> **Até a produção, tudo fica na versão 1.0 (`v1`).** O versionamento efetivo (diff contra a release anterior, depreciação, novas versões maiores) só começa quando o responsável avisar. Até lá, o contrato `v1` pode evoluir, mas cada mudança passa pelo snapshot e pelo changelog.

## 1. Onde ficam as coisas

| Arquivo | Papel |
| --- | --- |
| `docs/api/public-api/v1/openapi.json` | **Contrato publicado** da v1 (forma canônica, chaves ordenadas). Servido em `/public-api/v1/openapi.json` e usado pela página `/public-api/docs`. |
| `backend/src/main/resources/publicapi/versions.json` | Registro de versões: situação (`CURRENT`, `DEPRECATED`, `RETIRED`), datas de depreciação e desligamento, guia de migração. Lido na subida (validado) e usado pelo filtro que emite `Deprecation`/`Sunset`/410. |
| `backend/src/main/resources/publicapi/changelog.json` | Changelog público por release (`ADDED`, `CHANGED`, `DEPRECATED`, `REMOVED`, `FIXED`, `SECURITY`; `breaking=true` só em mudança incompatível). Página `/public-api/docs/changelog` e JSON `/public-api/changelog`. |
| `PublicApiContractIT` | Falha se o contrato gerado pelo código diferir do arquivo publicado. |
| Perfil Maven `public-api-diff` | Diff do contrato contra a release anterior com `openapi-diff` (Java), falha em incompatibilidade. |

## 2. Fluxo de cada mudança na API

1. Altere controllers/DTOs em `publicapi/web/v1`.
2. Rode o teste de contrato; ele falha mostrando a diferença:
   ```bash
   cd backend
   mvn verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PublicApiContractIT
   ```
3. Revise a diferença e, se a mudança é intencional, regrave o contrato:
   ```bash
   mvn verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PublicApiContractIT -Dpublicapi.contract.update=true
   ```
4. Classifique a mudança pela política (compatível × incompatível). Incompatível **não** entra na v1 depois do início do versionamento: vira v2 (§4).
5. Acrescente a entrada em `changelog.json` (release nova ou a release em preparação), com o endpoint afetado.
6. No pull request, o revisor confere o diff de `openapi.json` e do changelog juntos.

## 3. Publicar uma release

Depois do início do versionamento:

1. Obtenha o contrato da release anterior (tag git): `git show v1.3.0:docs/api/public-api/v1/openapi.json > /tmp/openapi-anterior.json`.
2. Gere o relatório de diferenças e barre incompatibilidades:
   ```bash
   cd backend
   mvn -P public-api-diff verify -DskipTests -Dpublicapi.previous.contract=/tmp/openapi-anterior.json
   ```
   Saída: `target/public-api-diff/changes.md` e `changes.json`. Se houver incompatibilidade, o build falha.
3. Use `changes.md` para conferir/completar a entrada do `changelog.json` (versão semântica: maior para quebra — só em nova versão de URL —, menor para adição, correção para ajustes sem mudança de contrato).
4. Marque a release no git (`v1.4.0`). O pipeline de implantação já empacota o contrato e o changelog no JAR; após a implantação, `/public-api/v1/openapi.json`, `/public-api/docs` e `/public-api/docs/changelog` refletem a release.
5. Comunique os credores com o link do changelog.

## 4. Nova versão maior (v2) e depreciação da anterior

1. Crie os controllers `publicapi/web/v2` com `ApiPaths.PUBLIC_API + "/v2"` (adicione a constante), reaproveitando os mesmos casos de uso; um grupo springdoc `public-v2` em `PublicApiOpenApiConfig` e o contrato `docs/api/public-api/v2/openapi.json` (snapshot próprio no teste de contrato).
2. Em `versions.json`: v2 `CURRENT`; v1 `DEPRECATED` com `deprecated` (data do anúncio) e `sunset` (desligamento — prazo mínimo recomendado de 6 meses) e `migrationGuide`.
3. A partir da implantação, toda resposta da v1 traz `Deprecation: @<epoch>`, `Sunset: <data HTTP>` e `Link: <guia>; rel="deprecation"`.
4. No changelog, release da v2 com as quebras (`breaking: true`) e o `sunset` da v1.
5. Acompanhe o uso da v1 na tela **Consumo da API** (rotas `/public-api/v1/...`) e avise os credores que ainda a usam.
6. Na data de desligamento: v1 `RETIRED` (respostas 410); os controllers da v1 podem ser removidos numa release seguinte.

## 5. Segredo do hash dos tokens

`app/public-api-token-hash-key` (Base64, 32 bytes) é gerado por `scripts/ops/provision-secrets.sh --env <amb> generate` e entregue à instância como `APP_PUBLIC_API_TOKEN_HASH_KEY` (`scripts/instance/user-data.sh`). A subida falha sem ele.

Rotação da chave: gere um novo segredo com outro id (ex.: `app/public-api-token-hash-key-t2`), configure `app.public-api.token-hash-keys.t2` e `active-token-hash-key-id: t2`; tokens novos e rotações passam a usar `t2`. Mantenha `t1` enquanto existir token ativo com hash nela (consulte `select hash_key_id, count(*) from publicapi.integration_token where status = 'ACTIVE' group by 1` como dono do schema); para eliminar `t1`, peça aos credores a rotação dos tokens restantes.

## 6. Operação

- Alarmes sugeridos (CloudWatch): `publicapi.usage.flushed_rows{outcome=failed}` > 0, `publicapi.statements.close_failures` > 0, taxa de `publicapi.auth{outcome=secret_mismatch}` anômala (tentativa de adivinhação), `publicapi.requests{outcome=server_error}`.
- Investigação de uma chamada: o credor informa o `X-Request-Id` (devolvido em toda resposta e em todo erro); procure em `shared.access_log` (`request_id`) e no log técnico (`requestId`).
