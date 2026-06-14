terraform {
  backend "s3" {
    bucket = "terraform.eu-west-1.hypervolt"
    key    = "private-kms/gitlab-global-gitlab-terraform/conduit-terraform.tfstate"
    region = "eu-west-1"

    encrypt    = true
    kms_key_id = "arn:aws:kms:eu-west-1:242724708940:alias/gitlab-global/gitlab-terraform/terraform-state"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/gitlab-global-gitlab-terraform-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.2"
    }

    gitlab = {
      source  = "gitlabhq/gitlab"
      version = "~> 18.0"
    }
  }
}

locals {
  region = "eu-west-1"
}

provider "aws" {
  region = local.region

  default_tags {
    tags = {
      service = "conduit"
      env     = "gitlab-global"
    }
  }
}

provider "gitlab" {
  # Authenticates via the GITLAB_TOKEN environment variable (a PAT with api scope on hypervolt/conduit).
  # Export it in your shell before `terraform apply`; never commit it to a file.
}
