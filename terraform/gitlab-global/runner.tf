# Creates the conduit-api.push IAM user (scoped to conduit's pkgs/nix S3 prefixes), mints its access key, and
# writes AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_DEFAULT_REGION into hypervolt/conduit's protected CI/CD
# variables (via the gitlab provider), so the `publish` CI job can push artifacts to the pkgs + nix buckets.
# Mirrors athena/terraform/gitlab-global. Apply MANUALLY from a dev laptop:
#
#   export GITLAB_TOKEN=<PAT with api scope on hypervolt/conduit>
#   aws-vault exec default -- terraform init
#   aws-vault exec default -- terraform apply
#
# Never run from CI.
module "gitlab-push-user" {
  source = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/iam-gitlab-roles"

  gitlab_project_id = "82852185" # hypervolt/conduit
  aws_iam_name      = "conduit-api"

  # scripts/publish-app writes pkgs keys conduit-api/* and conduit-consumer/*; scripts/module-publish writes
  # nix keys conduit/module-* and conduit-consumer/module-*. common_s3_names defaults to { conduit = true }
  # (the project slug), which covers the nix base name; we add the rest explicitly per bucket below.
  nix_s3_module_names = {
    conduit-consumer = true
  }

  pkgs_s3_names = {
    conduit          = false # the base name is published as a nix module, not a pkgs prefix
    conduit-api      = true
    conduit-consumer = true
  }

  # Conduit's CI runs no integration tests, so the ".test" user / TEST_ credentials aren't needed.
  test_aws_iam_role_enable = false
}
