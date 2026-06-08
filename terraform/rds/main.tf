terraform {
  backend "s3" {
    bucket = "terraform.eu-west-1.hypervolt"
    key    = "shared-kms/conduit/db-terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-conduit-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.2"
    }
  }
}

locals {
  region  = "eu-west-1"
  env     = terraform.workspace
  service = "conduit"
  name    = "${local.env}-${local.service}-rds"
  team    = "ecommerce"
}

provider "aws" {
  region = local.region

  assume_role {
    duration = "30m"
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
  account_id = data.aws_caller_identity.current.id
}
