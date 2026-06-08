terraform {
  backend "s3" {
    bucket = "eu-west-1.tf.hypervolt"
    key    = "backend/conduit/terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "30m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-conduit-api-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  required_version = "~> 1.6"
}

locals {
  region  = "eu-west-1"
  env     = terraform.workspace
  service = "conduit-api"
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

# Conduit's own config bucket (application.conf, logback.xml, mk-env.sh) — the nixos-bootstrap module
# pulls these onto the instance at boot.
resource "aws_s3_bucket" "conduit_api" {
  bucket = "conduit-api.${local.env}.${local.region}.hypervolt"
}

data "aws_iam_policy_document" "conduit_api_bucket" {
  statement {
    actions = ["s3:GetObject", "s3:PutObject"]
    resources = [
      aws_s3_bucket.conduit_api.arn,
      "${aws_s3_bucket.conduit_api.arn}/*",
    ]
  }
}

# WORM document store (doc 17 §6): the finalised-PDF bucket the API reads/writes (S3DocumentStorage).
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

module "bootstrap" {
  source     = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/nixos-bootstrap"
  key_prefix = local.service
  env        = local.env

  pin_versions = true

  service_module = {
    name    = "conduit"
    service = "conduit-api"
    version = local.deploy_versions[local.env]["conduit"]

    nix_extra_lines = <<-EOT
      hv.javaServices.$${serviceName}.package.startPath = serviceName;
      services.hv-telemetry.metrics.prometheusScrapeSources."$${name}-metrics".port = "9464";
    EOT
  }

  files = {
    "/etc/default/conduit-api/application.conf" = {
      file    = "${local.env == "prod" ? "" : "${local.env}-"}application.conf"
      s3_key  = "application.conf"
      trigger = false
    }
    "/etc/default/conduit-api/logback.xml" = {
      file    = "logback.xml"
      s3_key  = "logback.xml"
      trigger = false
    }
    "/etc/default/conduit-api/mk-env.sh" = {
      template = "mk-env.sh.tmpl"
      template_vars = {
        env     = local.env
        region  = local.region
        service = local.service
      }
      mode    = "+x"
      s3_key  = "mk-env.sh"
      trigger = false
    }
  }

  pre_switch_script = <<-EOT
    nix-shell /etc/default/conduit-api/mk-env.sh
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

# Allow the API into the Conduit RDS (security group provisioned in terraform/rds).
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
    staging = { instance_type = "t4g.small" }
    prod    = { instance_type = "t3a.medium" }
  }
}

module "autoscaling-group" {
  source  = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/ec2-autoscaling-group"
  env     = local.env
  service = local.service

  instance_type = local.per_env[local.env].instance_type

  ami_phase_canary = true

  health_check_grace_period_seconds = 600

  replicas                            = local.env == "prod" ? 2 : 1
  max_replicas                        = local.env == "prod" ? 4 : 1
  maintenance_policy                  = "terminate_and_launch"
  instance_auto_refresh               = true
  instance_auto_refresh_skip_matching = true

  iam_inline_policy_documents = {
    main    = data.aws_iam_policy_document.main.json
    bucket  = data.aws_iam_policy_document.conduit_api_bucket.json
    records = data.aws_iam_policy_document.conduit_records_bucket.json
  }

  security_group_ids = [aws_security_group.main.id]

  bootstrap_script = module.bootstrap.script

  # The API serves an internal load balancer (desk + service-to-service); not public.
  public_ip_address = false
}

# The runtime IAM policy: Keycloak + RDS credentials from Secrets Manager, Conduit SSM params,
# the deployment package, and EC2 discovery (Consul / machine-id). Pulsar topics are provisioned
# via the admin API at deploy, not Terraform (CLAUDE.md §6).
data "aws_iam_policy_document" "main" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      "arn:aws:secretsmanager:eu-west-1:${local.account_id}:secret:${local.env}/keycloak-configuration/conduit-api/*",
      "arn:aws:secretsmanager:*:*:secret:${local.env}/conduit/rds-db-credentials/*",
      "arn:aws:secretsmanager:eu-west-1:${local.account_id}:secret:${local.env}/conduit/stripe/*",
    ]
  }

  statement {
    actions   = ["s3:GetObject*"]
    resources = ["${data.aws_s3_bucket.pkgs.arn}/conduit/app/*"]
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
