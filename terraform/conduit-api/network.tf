module "vpc" {
  source = "git::ssh://git@gitlab.com/hypervolt/infrastructure/aws-vpc-remote.git"

  env = local.env
}
