locals {
  region    = "eu-west-1"
  env       = terraform.workspace
  service   = "conduit"
  component = "rds-snapshot"
  name      = "${local.env}-${local.service}-${local.component}"
  team      = "ecommerce"
}
