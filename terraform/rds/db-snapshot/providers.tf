terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.2"
    }
  }
}

provider "aws" {
  region = local.region

  assume_role {
    duration = "30m"
    role_arn = "arn:aws:iam::242724708940:role/rbac/${local.env}-${local.service}-rds-operator"
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
