locals {
  snapshot_path     = "${path.module}/snapshot-suffixes-${local.env}.yaml"
  snapshot_suffixes = yamldecode(file(local.snapshot_path)).suffixes
}

data "aws_db_instance" "this" {
  db_instance_identifier = "${local.env}-${local.service}"
}

resource "aws_db_snapshot" "this" {
  for_each = toset(local.snapshot_suffixes)

  db_instance_identifier = data.aws_db_instance.this.db_instance_identifier
  db_snapshot_identifier = "${local.env}-${local.service}-${each.key}"
}
