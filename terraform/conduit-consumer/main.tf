terraform {
  backend "s3" {
    bucket = "eu-west-1.tf.hypervolt"
    key    = "backend/conduit-consumer/terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-conduit-consumer-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  region  = "eu-west-1"
  env     = terraform.workspace
  service = "conduit-consumer"
  name    = "${local.env}-${local.service}"
  team    = "ecommerce"

  deploy_versions = yamldecode(file("${path.root}/../deploy-versions.yaml"))
}

provider "aws" {
  region = local.region

  assume_role {
    duration = "15m"
    role_arn = "arn:aws:iam::242724708940:role/rbac/${local.name}-operator"
  }

  default_tags {
    tags = {
      Name    = local.name
      env     = local.env
      service = local.service
      team    = local.team
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

data "aws_s3_bucket" "pkgs" {
  bucket = "pkgs.eu-west-1.hypervolt"
}

module "bootstrap" {
  source                 = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/nixos-bootstrap"
  key_prefix             = local.service
  env                    = local.env
  iam_role_name          = module.autoscaling-group.iam_role_name
  iam_role_policy_inline = true

  pin_versions = true

  swap_devices = (
    local.swap_device == null
    ? {}
    : { "${local.swap_device}" = {} }
  )

  service_module = {
    name    = local.service
    version = local.deploy_versions[local.env][local.service]

    nix_extra_lines = <<-EOT
      hv.javaServices.$${serviceName}.package.startPath = serviceName;
      services.hv-telemetry.metrics.prometheusScrapeSources."$${name}-metrics".port = "9464";
    EOT
  }

  files = {
    "/etc/default/conduit-consumer/application.conf" = {
      file    = "conduit-consumer-application.conf"
      s3_key  = "application.conf"
      trigger = false
    }
    "/etc/default/conduit-consumer/logback.xml" = {
      file    = "conduit-consumer-logback.xml"
      s3_key  = "logback.xml"
      trigger = false
    }
    "/etc/default/conduit-consumer/mk-env.sh" = {
      template = "mk-env.sh.tmpl"
      template_vars = {
        env     = local.env
        region  = local.region
        service = "conduit-consumer"
      }
      files_root = "${path.root}/../conduit-api/files"
      mode       = "+x"
      s3_key     = "mk-env.sh"
      trigger    = false
    }
  }

  pre_switch_script = <<-EOT
    nix-shell /etc/default/conduit-consumer/mk-env.sh
  EOT
}

resource "aws_security_group" "main" {
  name   = local.service
  vpc_id = module.autoscaling-group.vpc_id

  tags = {
    Name    = local.name
    service = local.service
    env     = local.env
    team    = local.team
  }

  lifecycle {
    create_before_destroy = true
  }
}

data "aws_security_group" "rds" {
  vpc_id = module.autoscaling-group.vpc_settings.id
  name   = "conduit-rds"
}

locals {
  postgresql_port = 5432
}

resource "aws_vpc_security_group_ingress_rule" "rds-client" {
  security_group_id = data.aws_security_group.rds.id

  description = "PostgreSQL ingress from ${local.service}"

  from_port   = local.postgresql_port
  to_port     = local.postgresql_port
  ip_protocol = "tcp"

  referenced_security_group_id = aws_security_group.main.id

  tags = {
    Name     = "${local.name}-rds-client"
    env      = local.env
    service  = local.service
    team     = local.team
    protocol = "tcp/postgresql"
  }
}

locals {
  per_env = {
    staging = {
      instance_type = "t4g.small"
      swap_device   = "/dev/sdf"
    }
    prod = {
      instance_type = "t3a.small"
      swap_device   = null
    }
  }
  swap_device = local.per_env[local.env].swap_device
}

module "autoscaling-group" {
  source  = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/ec2-autoscaling-group"
  env     = local.env
  service = local.service

  instance_type = local.per_env[local.env].instance_type

  ami_phase_canary = true

  health_check_grace_period_seconds = 600

  # Exactly one consumer: the outbox relay + downstream consumers are designed for a single publisher
  # ordering by partition_key; Pulsar Shared subscriptions still allow scale-out per topic if needed.
  replicas                            = 1
  max_replicas                        = 1
  maintenance_policy                  = "terminate_and_launch"
  instance_auto_refresh               = true
  instance_auto_refresh_skip_matching = true

  iam_inline_policy_documents = {
    main    = data.aws_iam_policy_document.main.json
    records = data.aws_iam_policy_document.conduit_records_bucket.json
  }

  security_group_ids = [aws_security_group.main.id]

  bootstrap_script = module.bootstrap.script

  public_ip_address = false
}

data "aws_s3_bucket" "conduit_records" {
  bucket = "conduit-records.${local.env}.${local.region}.hypervolt"
}

data "aws_iam_policy_document" "conduit_records_bucket" {
  statement {
    actions = ["s3:GetObject", "s3:PutObject", "s3:ListBucketVersions", "s3:GetObjectVersion"]
    resources = [
      data.aws_s3_bucket.conduit_records.arn,
      "${data.aws_s3_bucket.conduit_records.arn}/*",
    ]
  }
}

# Runtime IAM: RDS credentials + Stripe webhook secret from Secrets Manager, Conduit SSM params,
# the package, EC2 discovery, and the TigerBeetle cluster settings (the consumer holds the TB client).
data "aws_iam_policy_document" "main" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      "arn:aws:secretsmanager:*:*:secret:${local.env}/conduit/rds-db-credentials/*",
      "arn:aws:secretsmanager:eu-west-1:${local.account_id}:secret:${local.env}/conduit/stripe/*",
      "arn:aws:secretsmanager:eu-west-1:${local.account_id}:secret:${local.env}/conduit/xero/*",
    ]
  }

  statement {
    actions   = ["s3:GetObject*"]
    resources = ["${data.aws_s3_bucket.pkgs.arn}/conduit-consumer/app/*"]
  }

  statement {
    actions   = ["ssm:GetParameter*"]
    resources = ["arn:aws:ssm:eu-west-1:${local.account_id}:parameter/${local.env}/conduit/*"]
  }

  statement {
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "ec2:Region"
      values   = ["eu-west-1"]
    }
  }

  statement {
    actions   = ["s3:GetObject*"]
    resources = ["${data.aws_s3_bucket.pkgs.arn}/conduit-tigerbeetle/files/${local.env}/etc/nixos/modules.d/tigerbeetle/tigerbeetle-settings.json"]
  }
}
