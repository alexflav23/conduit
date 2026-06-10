locals {
  databases = toset(["pricing"])
  database_extra_roles = {
    "pricing" = {
      "pricing-ro" = {
        database_privileges = ["CONNECT"]
        schema_table_privileges = {
          "public" = ["SELECT"]
        }
      }
      "hyperview-ro" = {
        database_privileges = ["CONNECT"]
        schema_privileges = {
          "axle" = ["USAGE"]
        }
        schema_table_privileges = {
          "public" = ["SELECT"]
          "axle"   = ["SELECT"]
        }
        services = ["hyperview"]
        role_secret_extra = {
          dialect = "postgresql"
        }
      }
    }
  }

  bastion = join(".", [
    "bastion",
    local.env,
    replace(
      local.region,
      "/([^-]+)-(.)(?:ast|orth|est|outh|entral)(?:(.)(?:ast|est))?-(\\d+)/",
      "$1$2$3$4",
    ),
    "aws.hypervolt.co.uk",
  ])

  database_roles = merge(
    {
      for database in local.databases :
      database => {
        database = database
        # Explicitly list privileges: Otherwise, ALL will be expanded by PostgreSQL
        # and future Terraform runs will think they need to be revoked and replaced
        # with ALL.
        database_privileges = toset(["CONNECT", "CREATE", "TEMPORARY"])
        schema_privileges = {
          # public is magic; privileges are granted automatically on schemas
          # created but public already exists so isn't created.
          "public" = toset(["CREATE"])
        }
      }
    },
    merge(
      [
        for database, roles in local.database_extra_roles :
        {
          for role, config in roles :
          role => merge(
            config,
            { database = database },
          )
        }
    ]...)
  )
}

# We cannot connect directly to the database: it is in our AWS Virtual
# Private Cloud and only accepts connections via its local network. So
# we need to port-forward through the bastion host. You will need
# sufficient SSH credentials to do so.
# TODO: Use AWS SSM and RBAC instead.
module "db-tunnel" {
  source  = "flaupretre/tunnel/ssh"
  version = "~> 2.0"

  local_port = var.tunnel_local_port

  target_host = module.db.db_instance_address
  target_port = module.db.db_instance_port

  gateway_host = local.bastion
}

provider "postgresql" {
  host    = module.db-tunnel.host
  port    = module.db-tunnel.port
  sslmode = "require"

  database  = module.db.db_instance_name
  username  = module.db.db_instance_username
  password  = aws_secretsmanager_secret_version.root-password.secret_string
  superuser = false
}

data "aws_secretsmanager_random_password" "role-password" {
  for_each = toset(keys(local.database_roles))

  password_length = 16

  exclude_punctuation = true
}

resource "terraform_data" "role-password" {
  for_each = data.aws_secretsmanager_random_password.role-password

  input = sensitive(each.value.random_password)

  lifecycle {
    ignore_changes = [
      input
    ]
  }
}

locals {
  role_config = {
    for role, config in local.database_roles :
    role => merge(
      lookup(config, "role_secret_extra", {}),
      {
        user     = role
        database = config.database
        password = terraform_data.role-password[role].input
        address  = module.db.db_instance_address
        port     = module.db.db_instance_port
      },
    )
  }
}

resource "aws_secretsmanager_secret" "role" {
  for_each = local.role_config

  name                    = "${local.env}/${local.service}/rds-db-credentials/${each.key}.json"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "role" {
  for_each = aws_secretsmanager_secret.role

  secret_id     = each.value.id
  secret_string = jsonencode(local.role_config[each.key])
}

data "aws_service_principal" "ec2" {
  service_name = "ec2"
}

data "aws_iam_policy_document" "role-secret" {
  for_each = {
    for role, config in local.database_roles :
    role => config.services
    if length(lookup(config, "services", [])) > 0
  }

  statement {
    # Grant Terraform operator permission to describe.
    actions = [
      "secretsmanager:DescribeSecret",
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:ListSecretVersionIds",
    ]
    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalTag/env"
      values   = [local.env]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalTag/role"
      values   = ["operator"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalTag/service"
      values   = each.value
    }
    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalType"
      values   = ["AssumedRole"]
    }
    condition {
      test     = "ForAnyValue:ArnLike"
      variable = "aws:PrincipalArn"
      values   = ["arn:aws:iam::${local.account_id}:role/rbac/*"]
    }
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
    resources = ["*"]
  }
}

resource "aws_secretsmanager_secret_policy" "role-secret" {
  for_each = data.aws_iam_policy_document.role-secret

  secret_arn = aws_secretsmanager_secret.role[each.key].arn
  policy     = each.value.minified_json
}

resource "postgresql_database" "this" {
  for_each = local.databases

  name = each.key
}

resource "postgresql_role" "this" {
  for_each = local.role_config

  name     = each.value.user
  login    = true
  password = each.value.password
}

resource "postgresql_grant" "database" {
  for_each = local.role_config

  role     = postgresql_role.this[each.key].name
  database = postgresql_database.this[each.value.database].name

  object_type = "database"

  privileges = local.database_roles[each.key].database_privileges
}

resource "postgresql_grant" "schemas" {
  for_each = merge([
    for role, config in local.database_roles :
    {
      for schema, privileges in lookup(config, "schema_privileges", {}) :
      "${role}-${schema}" => {
        role       = role
        schema     = schema
        database   = config.database
        privileges = privileges
      }
    }
  ]...)

  role     = postgresql_role.this[each.value.role].name
  database = postgresql_database.this[each.value.database].name
  schema   = each.value.schema

  object_type = "schema"

  privileges = each.value.privileges
}

# This resource will modify any existing tables to match what
# we're going to issue as default.
# BE AWARE: Creating tables as a role other than that which matches
# the database name may result in drift on this resource.
resource "postgresql_grant" "tables" {
  for_each = merge([
    for role, config in local.database_roles :
    {
      for schema, privileges in lookup(config, "schema_table_privileges", {}) :
      "${role}-${schema}" => {
        role       = role
        schema     = schema
        database   = config.database
        privileges = privileges
      }
    }
  ]...)

  role     = postgresql_role.this[each.value.role].name
  database = postgresql_database.this[each.value.database].name
  schema   = each.value.schema

  object_type = "table"
  # Empty/no objects means "ALL TABLES IN SCHEMA"

  privileges = each.value.privileges
}

# And this makes sure new tables are given the same grants.
# BE AWARE: Revoking any of these grants will result in drift on the
# corresponding postgresql_grant.tables resource.
resource "postgresql_default_privileges" "tables" {
  for_each = merge([
    for role, config in local.database_roles :
    {
      for schema, privileges in lookup(config, "schema_table_privileges", {}) :
      "${role}-${schema}" => {
        role       = role
        schema     = schema
        database   = config.database
        privileges = privileges
      }
    }
  ]...)

  role     = postgresql_role.this[each.value.role].name
  database = postgresql_database.this[each.value.database].name
  owner    = postgresql_role.this[each.value.database].name
  schema   = each.value.schema

  object_type = "table"

  privileges = each.value.privileges
}
