terraform {
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
  service = "conduit-records"
  name    = "${local.env}-${local.service}"
  team    = "ecommerce"
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

data "aws_iam_account_alias" "current" {}
