locals {
  user_groups = {
    ops = toset(["Conduit.Ops"])
    dev = toset(["Conduit.Dev"])
  }

  user_tags = {
    for role, groups in local.user_groups :
    role => {
      for group in setunion(groups, compact([lookup({ ops = "Admins" }, role, null)])) :
      "group/${replace(lower(group), ".", "-")}" => "true"
    }
  }
}

# The per-(service, env) RBAC operator roles each component assumes for its own state + apply
# (arn:…/rbac/<env>-conduit-<service>-operator). Mirrors athena/terraform/roles-global.
module "rbac" {
  for_each = toset(["api", "consumer", "rds", "tigerbeetle", "records"])

  source  = "gitlab.com/hypervolt/iam-rbac-roles/aws"
  version = "~> 1.7, >= 1.7.2"

  terraform_secure = false

  service = trimsuffix(join("-", [local.service, each.key]), "-rds")
  envs    = ["staging", "prod"]

  ec2_ami_constraint = "git-clean"

  name_prefix = lookup({ api = local.service }, each.key, null)

  default_roles_enabled = lookup(
    { rds = { rds-operator = true, operator = false } },
    each.key,
    {},
  )

  role_principal_tags = merge(
    { operator = local.user_tags.ops },
    lookup({ rds = { rds-operator = local.user_tags.ops } }, each.key, {}),
  )

  role_principal_tags_per_env = {
    staging = merge(
      { operator = local.user_tags.dev },
      lookup({ rds = { rds-operator = local.user_tags.dev } }, each.key, {}),
    )
  }

  # The API fronts an internal load balancer (the desk); the others do not.
  enable_service_lb = lookup({ api = true }, each.key, false)

  enable_shared_lbs = lookup({ api = { infrastructure-lb = true } }, each.key, {})

  # The records bucket is operator-managed; api + consumer get a boundary to read/write it.
  enable_s3_bucket = lookup({ records = true }, each.key, false)

  role_boundaries = lookup(
    {
      api      = { operator = { s3-records = data.aws_iam_policy_document.s3-records-boundary-operator.minified_json } }
      consumer = { operator = { s3-records = data.aws_iam_policy_document.s3-records-boundary-operator.minified_json } }
    },
    each.key,
    {},
  )
}

output "terraform_backend_config" {
  value = { for component, mod in module.rbac : component => mod.terraform_backend_config }
}

output "terraform_aws_provider_config" {
  value = { for component, mod in module.rbac : component => mod.terraform_aws_provider_config }
}
