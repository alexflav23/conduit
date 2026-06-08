terraform {
  backend "s3" {
    bucket = "eu-west-1.tf.hypervolt"
    key    = "backend/conduit-tigerbeetle/terraform.tfstate"
    region = "eu-west-1"

    assume_role = {
      duration = "15m"
      role_arn = "arn:aws:iam::242724708940:role/rbac/global-conduit-tigerbeetle-state-operator"
    }
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  required_version = "~> 1.6"
}

locals {
  region  = "eu-west-1"
  env     = terraform.workspace
  service = "conduit-tigerbeetle"
  name    = "${local.env}-${local.service}"
  team    = "ecommerce"

  # 6 replicas in prod (full fault tolerance), 4 in staging — matches the house TigerBeetle topology.
  cluster_size     = (local.env == "prod" ? 6 : 4)
  tigerbeetle_port = 3000
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

module "bootstrap" {
  source                 = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/nixos-bootstrap"
  key_prefix             = local.service
  env                    = local.env
  iam_role_name          = module.instance.iam_role_name
  iam_role_policy_inline = true

  cloud_init_sentinel = true

  modules_d_files = {
    "tigerbeetle/tigerbeetle-script.nix"     = { file = "tigerbeetle-script.nix" }
    "tigerbeetle/tigerbeetle-ebs-volume.nix" = { file = "tigerbeetle-ebs-volume.nix" }
    "tigerbeetle/default.nix"                = { file = "tigerbeetle.nix" }
    "tigerbeetle/tigerbeetle-settings.json" = {
      pin_version      = false
      create           = false
      verify_existence = !var.cold_start
    }
  }
}

module "instance" {
  source = "git::ssh://git@gitlab.com/hypervolt/terraform.git//modules/ec2-instance"

  env     = local.env
  service = local.service

  stable_network_interface = true

  instance_type = "t4g.medium"

  replicas = local.cluster_size

  use_launch_template = true

  bootstrap_script = module.bootstrap.script

  iam_inline_policy_documents = {
    ebs-volume-attach = data.aws_iam_policy_document.ebs-volume-attach.json
  }
  security_group_ids = [aws_security_group.tigerbeetle.id]

  root_volume_size = 20
}

data "aws_iam_policy_document" "ebs-volume-attach" {
  statement {
    actions = ["ec2:AttachVolume", "ec2:DetachVolume"]
    resources = [
      "arn:aws:ec2:${local.region}:*:instance/*",
      "arn:aws:ec2:${local.region}:*:volume/*",
    ]
    condition {
      variable = "aws:ResourceTag/env"
      test     = "StringEquals"
      values   = [local.env]
    }
    condition {
      variable = "aws:ResourceTag/service"
      test     = "StringEquals"
      values   = [local.service]
    }
  }

  statement {
    actions   = ["ec2:DescribeVolumeStatus", "ec2:DescribeVolumes"]
    resources = ["*"]
  }
}

resource "aws_security_group" "tigerbeetle" {
  vpc_id = module.instance.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "tigerbeetle_cluster" {
  security_group_id = aws_security_group.tigerbeetle.id

  description = "TigerBeetle cluster intercommunication"

  ip_protocol = "tcp"
  from_port   = local.tigerbeetle_port
  to_port     = local.tigerbeetle_port
  cidr_ipv4   = module.instance.vpc_cidr_blocks[0]
}

resource "aws_ebs_volume" "tigerbeetle" {
  count             = length(module.instance.availability_zones)
  availability_zone = module.instance.availability_zones[count.index]

  type = "gp2"
  size = local.env == "prod" ? 64 : 32

  tags = {
    Name    = "conduit-tigerbeetle"
    env     = local.env
    service = local.service
    replica = count.index
  }
}

locals {
  settings_s3_key = "${local.service}/files/${local.env}/etc/nixos/modules.d/tigerbeetle/tigerbeetle-settings.json"
  settings_json = jsonencode({
    env  = local.env
    port = local.tigerbeetle_port
    ips  = module.instance.private_ips
    # Distinct cluster ids from Athena's (100/200) so the estates never collide.
    cluster_id = {
      staging_conduit = 300
      prod_conduit    = 400
    }["${local.env}_conduit"]
    volume_ids = {
      for index, ip in module.instance.private_ips :
      ip => aws_ebs_volume.tigerbeetle[index].id
    }
    replica_ids = {
      for index, ip in module.instance.private_ips :
      ip => aws_ebs_volume.tigerbeetle[index].tags.replica
    }
  })
}

resource "aws_s3_object" "settings" {
  bucket  = module.bootstrap.s3_bucket.bucket
  key     = local.settings_s3_key
  content = local.settings_json
  etag    = md5(local.settings_json)
}
