terraform {
  backend "s3" {
    bucket = "terraform.eu-west-1.hypervolt"
    key    = "shared-kms/conduit-records/terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-conduit-records-state-operator"
    }
  }
}
