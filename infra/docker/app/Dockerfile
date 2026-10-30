# =============================================================================
# Imagem da aplicação (backend Spring Boot com o frontend Angular embutido — ADR-0007).
#
#   docker build -f infra/docker/app/Dockerfile -t gestor-cobranca:local .
#
# Contexto do build = raiz do repositório. Três estágios:
#   1. frontend  npm ci + ng build (produção) → dist/gestor-cobranca-web
#   2. backend   mvn package; o pom copia frontend/dist/gestor-cobranca-web para classpath:/static
#   3. runtime   JRE 21, usuário sem privilégios, entrypoint que só gera segredos locais quando pedido
#                (/sandbox-data: volume exclusivo do sandbox, vazio e sem uso fora dele)
#
# As imagens base são argumentos para permitir espelhos/registros internos (ex.: ambiente sem acesso ao
# Docker Hub). Os padrões são as imagens oficiais. Os testes não rodam aqui: rodam no CI/`mvn verify`.
# =============================================================================
ARG NODE_IMAGE=node:22-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

# ---------------------------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
# o prebuild confere se a base de conhecimento exportada para o backend está atualizada
COPY backend/src/main/resources/helpdesk/knowledge-base.json /build/backend/src/main/resources/helpdesk/knowledge-base.json
RUN npm run build -- --configuration production \
    && test -f dist/gestor-cobranca-web/index.html

# ---------------------------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src ./src
# contrato publicado da API pública: o pom o copia para o classpath (servido em /public-api/v<n>/openapi.json)
COPY docs/api/public-api /build/docs/api/public-api
COPY --from=frontend /build/frontend/dist/gestor-cobranca-web /build/frontend/dist/gestor-cobranca-web
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp package -DskipTests \
    && cp target/gestor-cobranca.jar /build/gestor-cobranca.jar \
    && jar tf /build/gestor-cobranca.jar | grep -q 'BOOT-INF/classes/static/index.html' \
    && jar tf /build/gestor-cobranca.jar | grep -q 'BOOT-INF/classes/publicapi/contract/v1/openapi.json'

# ---------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime
RUN set -eu; \
    if ! command -v openssl >/dev/null 2>&1; then \
        apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*; \
    fi; \
    groupadd --system --gid 10001 gestor; \
    useradd --system --uid 10001 --gid gestor --home-dir /var/lib/gestor-cobranca --shell /usr/sbin/nologin gestor; \
    mkdir -p /opt/gestor-cobranca /var/lib/gestor-cobranca/secrets /var/lib/gestor-cobranca/storage \
             /var/lib/gestor-cobranca/logs /sandbox-data; \
    chown -R gestor:gestor /var/lib/gestor-cobranca /sandbox-data; \
    chmod 700 /var/lib/gestor-cobranca/secrets /sandbox-data
COPY --from=backend /build/gestor-cobranca.jar /opt/gestor-cobranca/gestor-cobranca.jar
COPY infra/docker/app/entrypoint.sh /opt/gestor-cobranca/entrypoint.sh
USER gestor
WORKDIR /var/lib/gestor-cobranca
EXPOSE 8080
ENTRYPOINT ["/opt/gestor-cobranca/entrypoint.sh"]
