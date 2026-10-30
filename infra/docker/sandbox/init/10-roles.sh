#!/bin/bash
# Sandbox: cria os papéis de banco com o mesmo script de produção (scripts/db/bootstrap-roles.sql),
# com as senhas exclusivas do sandbox definidas no compose.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_password="${SANDBOX_DB_OWNER_PASSWORD}" -v runtime_password="${SANDBOX_DB_RUNTIME_PASSWORD}" \
     -f /bootstrap/bootstrap-roles.sql
