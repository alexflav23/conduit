#!/usr/bin/env nix-shell
#! nix-shell -i bash -p awscli2 jq

set -euo pipefail

build=""

typeset -a args
while (( $# > 0 )); do
  arg="${1}"; shift
  case "${arg}" in
    (--build)
      build=yes
      ;;
    (--)
      break
      ;;
    (-*)
      echo >&2 "$0: Unknown option \"${arg}\"."
      exit 127
      ;;
    (*)
      args+=("${arg}")
      ;;
  esac
done

set -- "${args[@]}" "$@"

if (( $# != 1 )); then
  echo >&2 "Usage: $0 [--build] staging|prod|..."
  exit 127
fi

env="${1}"; shift

: ${AWS_DEFAULT_REGION=eu-west-1}
export AWS_DEFAULT_REGION

if [[ ${AWS_USE_DUALSTACK_ENDPOINT:-false} == true ]]; then
  # As of 2024-07-30, SSM does not have an IPv6 endpoint.
  # https://docs.aws.amazon.com/vpc/latest/userguide/aws-ipv6-support.html
  if [[ -z ${AWS_ENDPOINT_URL_SSM-} && -z ${AWS_ENDPOINT_URL-} ]]; then
    export AWS_ENDPOINT_URL_SSM="https://ssm.${AWS_DEFAULT_REGION}.amazonaws.com";
  fi
fi

parameter_path="/${env}/conduit/"

trap '[[ -n ${parameters-} ]] && rm -f -- "${parameters}"' EXIT
parameters=$(mktemp "parameters-XXXXXX.json")

aws ssm get-parameters-by-path \
    --path="${parameter_path}" \
    --recursive \
    --with-decryption \
    --query="Parameters[*].{Name:Name,Value:Value}" \
    --output=json \
    | jq "map({\"key\":.Name | ltrimstr(\"${parameter_path}\"),\"value\":.Value}) | from_entries" \
    > "${parameters}"

function show_parameter {
  if (( $# != 2 )); then
    echo >&2 "Usage: show_parameter env_var parameter_subpath"
    return 127
  fi
  # We need to separate fetching the value and echoing it: a failure
  # in $(...) expansion in variable assignment sets $?, but when
  # building arguments does not.
  value=$(jq --exit-status --raw-output ".[\"${2}\"]" < "${parameters}")
  echo "${1}='${value}'"
}

if [[ ${build} == "yes" ]]; then
  show_parameter HYPERVOLT_AUTH_JWT_PUBLIC_KEY auth/jwt-public-key
else
  show_parameter HYPERVOLT_AUTH_JWKS_HOST auth/jwks-host
  show_parameter HYPERVOLT_AUTH_JWKS_HOST_2 auth/jwks-host-2
  show_parameter HYPERVOLT_AUTH_JWT_SUBJECT_2 auth/jwt-subject-2

  aws secretsmanager get-secret-value \
      --secret-id="${env}/conduit/rds-db-credentials/pricing.json" \
      --query="SecretString" \
      --output=text \
      | jq -r "to_entries | .[] |  \"PRICING_DB_RW_\" + (.key | ascii_upcase) + \"='\" + (.value | tostring) + \"'\""

fi

show_parameter HYPERVOLT_STRIPE_KEY stripe
show_parameter HYPERVOLT_STRIPE_PAYOUTS_KEY stripe-payouts-key
show_parameter HYPERVOLT_STRIPE_WEBHOOK_SECRET stripe-webhook-secret
show_parameter HYPERVOLT_STRIPE_WEBHOOK_SECRET_PAYOUTS stripe-webhook-secret-payouts
show_parameter HYPERVOLT_STRIPE_FIN_ACCOUNT_ID stripe-fin-acc-id

show_parameter HYPERVOLT_HUBSPOT_API_KEY hubspot

show_parameter HYPERVOLT_TICKET_PIPELINE hubspot/ticket-pipeline
show_parameter HYPERVOLT_TICKET_PIPELINE_NEW_STAGE hubspot/ticket-pipeline-new-stage

echo HYPERVOLT_ENV="'${env}'"

show_parameter HYPERVOLT_AUTH_JWT_SUBJECT auth/jwt-subject
show_parameter HYPERVOLT_AUTH_JWT_AUDIENCE auth/jwt-audience

show_parameter HYPERVOLT_QUOTE_SENDER_EMAIL hubspot/quote-sender-email

show_parameter JUMPTECH_API_URL jumptech/api-url
show_parameter JUMPTECH_API_KEY jumptech/api-key
show_parameter JUMPTECH_INTEGRATION_NAME jumptech/integration-name

show_parameter JUMPTECH_API_KEY_AU jumptech/api-au-key
show_parameter JUMPTECH_INTEGRATION_NAME_AU jumptech/integration-name-au

show_parameter JUMPTECH_WEBHOOK_KEY jumptech/webhook-key
show_parameter JUMPTECH_WEBHOOK_KEY_AU jumptech/webhook-key-au

show_parameter HYPERVOLT_SLACK_ATHENA_TOKEN slack/token

show_parameter MRPEASY_API_KEY mrpeasy/api-key
show_parameter MRPEASY_ACCESS_KEY mrpeasy/access-key
show_parameter MRPEASY_WEBHOOK_EXPECT_PASSWORD mrpeasy/webhook-expect-password

show_parameter XERO_CLIENT_ID xero/client-id
show_parameter XERO_CLIENT_SECRET xero/client-secret
show_parameter XERO_WEBHOOK_SIGNING_KEY xero/webhook-signing-key

show_parameter AXLE_PASSWORD axle/password

show_parameter HYPERVOLT_REPORTING_SECRET reporting/reporting-secret
show_parameter HEATABLE_WEBHOOK_SECRET heatable/webhook-secret
show_parameter RHENUS_WEBHOOK_SECRET rhenus/webhook-secret

show_parameter INSTALLERCOM_API_KEY installercom/api-key
show_parameter INSTALLERCOM_WORKFLOW_ID installercom/workflow-id

echo HYPERVOLT_PRODUCT_CATALOGUE_BUCKET="'checkout.${env}.eu-west-1.hypervolt'"
echo HYPERVOLT_PRODUCT_CATALOGUE_KEY="'product-catalogue.json'"

echo HYPERVOLT_INSTALLER_LOOKUP_HOST="'installer-lookup.${env}.hypervolt.co.uk'"

aws secretsmanager get-secret-value \
    --secret-id="${env}/conduit/rds-db-credentials/pricing-ro.json" \
    --query="SecretString" \
    --output=text \
    | jq -r "to_entries | .[] |  \"PRICING_DB_RO_\" + (.key | ascii_upcase) + \"='\" + (.value | tostring) + \"'\""
