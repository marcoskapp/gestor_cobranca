#!/bin/bash
# =============================================================================
# Cria (uma vez por conta) o bucket do state do Terraform, com versionamento,
# criptografia, bloqueio de acesso público, política exigindo TLS e retenção de versões.
# A trava usa o arquivo .tflock no próprio bucket (use_lockfile), sem DynamoDB.
#
#   scripts/ops/bootstrap-state.sh [--region sa-east-1]
# =============================================================================
set -Eeuo pipefail

REGION=sa-east-1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    *) echo "argumento desconhecido: $1" >&2; exit 1 ;;
  esac
done

command -v aws >/dev/null || { echo "aws CLI ausente" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq ausente" >&2; exit 1; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="gestor-cobranca-tfstate-${ACCOUNT_ID}"

if aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  echo "bucket ${BUCKET} já existe; reaplicando as configurações de segurança"
else
  aws s3api create-bucket --bucket "${BUCKET}" --region "${REGION}" \
    --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
  echo "bucket ${BUCKET} criado"
fi

aws s3api put-bucket-ownership-controls --bucket "${BUCKET}" \
  --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'
aws s3api put-public-access-block --bucket "${BUCKET}" \
  --public-access-block-configuration 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
aws s3api put-bucket-versioning --bucket "${BUCKET}" --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "${BUCKET}" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-lifecycle-configuration --bucket "${BUCKET}" --lifecycle-configuration '{
  "Rules": [{
    "ID": "noncurrent-state-versions",
    "Status": "Enabled",
    "Filter": {},
    "NoncurrentVersionExpiration": {"NoncurrentDays": 365},
    "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}
  }]
}'
policy="$(jq -nc --arg b "${BUCKET}" '{
  Version: "2012-10-17",
  Statement: [{
    Sid: "DenyInsecureTransport",
    Effect: "Deny",
    Principal: "*",
    Action: "s3:*",
    Resource: ["arn:aws:s3:::\($b)", "arn:aws:s3:::\($b)/*"],
    Condition: {Bool: {"aws:SecureTransport": "false"}}
  }]
}')"
aws s3api put-bucket-policy --bucket "${BUCKET}" --policy "${policy}"

echo "pronto. Em infra/terraform/envs/<ambiente>/backend.hcl use: bucket = \"${BUCKET}\""
