#!/bin/bash
# Cria os papéis de banco usando o mesmo script de produção, com senhas de desenvolvimento.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_password=app_owner_dev -v runtime_password=app_runtime_dev \
     -f /bootstrap/bootstrap-roles.sql
