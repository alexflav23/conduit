terraform {
  backend "s3" {
    bucket = "terraform.eu-west-1.hypervolt"
    key    = "shared-kms/conduit-roles-global/terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-terraform-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.49"
    }
  }
}

locals {
  region  = "eu-west-1"
  env     = "global"
  service = "conduit"
  team    = "infrastructure"
}

provider "aws" {
  region = local.region

  assume_role {
    duration = "15m"
    role_arn = "arn:aws:iam::242724708940:role/rbac/global-terraform-operator"
  }

  default_tags {
    tags = {
      env     = local.env
      service = "${local.service}-roles"
      team    = local.team
    }
  }
}

data "aws_caller_identity" "current" {
  lifecycle {
    precondition {
      condition     = terraform.workspace == "default"
      error_message = "supported only in default; modifies global resources"
    }
  }
}
