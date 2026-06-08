module "vpc" {
  source = "git::ssh://git@gitlab.com/hypervolt/infrastructure/aws-vpc-remote.git"
  name   = "core"
  env    = local.env
}

data "aws_subnet" "private" {
  count = length(module.vpc.aws_subnet_private_ids)
  id    = element(module.vpc.aws_subnet_private_ids, local.identifier_hash + count.index)
}

# The RDS security group: the conduit-api / conduit-consumer ASGs add ingress rules referencing this
# (by name "conduit-rds") so only Conduit services can reach the database.
resource "aws_security_group" "main" {
  vpc_id = one(toset(data.aws_subnet.private[*].vpc_id))
  name   = "${local.service}-rds"

  tags = {
    Name = "${local.env}-${local.service}-rds"
  }

  lifecycle {
    create_before_destroy = true
  }
}

data "aws_security_group" "bastion" {
  vpc_id = one(toset(data.aws_subnet.private[*].vpc_id))
  name   = "bastion"
}

resource "aws_vpc_security_group_ingress_rule" "bastion" {
  security_group_id = aws_security_group.main.id

  from_port   = local.postgresql_port
  to_port     = local.postgresql_port
  ip_protocol = "tcp"

  referenced_security_group_id = data.aws_security_group.bastion.id

  tags = {
    Name     = "${local.env}-${local.service}-rds-bastion"
    protocol = "tcp/postgresql"
  }
}
