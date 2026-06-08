locals {
  db_instance_name  = local.service
  identifier        = "${local.env}-${local.db_instance_name}"
  db_root_name      = replace(local.db_instance_name, "-", "_")
  db_root_user_name = "postgres"

  high_availability     = local.env == "prod"
  backup_retention_days = local.env == "prod" ? 35 : 7

  postgresql_port = 5432

  # Stable subnet (AZ) index from the identifier so allocations spread across subnets.
  identifier_hash = parseint(substr(md5(local.identifier), 0, 8), 16)
}

data "aws_secretsmanager_random_password" "root" {
  password_length     = 16
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret" "root-password" {
  name                    = "${local.env}/${local.service}-rds/${local.db_root_user_name}"
  recovery_window_in_days = 0

  tags = {
    Name = "${local.env}-${local.service}-rds-${local.db_root_user_name}"
  }
}

resource "aws_secretsmanager_secret_version" "root-password" {
  secret_id     = aws_secretsmanager_secret.root-password.id
  secret_string = data.aws_secretsmanager_random_password.root.random_password

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_db_subnet_group" "private" {
  description = "${local.identifier} subnet group"
  name_prefix = "${local.identifier}-"
  subnet_ids  = data.aws_subnet.private[*].id

  tags = {
    Name = local.identifier
  }

  lifecycle {
    create_before_destroy = true
  }
}

module "db" {
  source  = "terraform-aws-modules/rds/aws"
  version = "~> 5.9"

  identifier = local.identifier

  create_db_option_group    = false
  create_db_parameter_group = false

  engine         = "postgres"
  engine_version = "16"
  port           = local.postgresql_port

  instance_class    = local.env == "prod" ? "db.t4g.small" : "db.t4g.micro"
  storage_encrypted = true

  apply_immediately = local.env != "prod"

  allocated_storage = 20

  db_name                = local.db_root_name
  username               = local.db_root_user_name
  create_random_password = false
  password               = aws_secretsmanager_secret_version.root-password.secret_string

  create_db_subnet_group = false
  db_subnet_group_name   = aws_db_subnet_group.private.name

  multi_az          = local.high_availability
  availability_zone = local.high_availability ? null : data.aws_subnet.private[0].availability_zone

  vpc_security_group_ids = [aws_security_group.main.id]

  maintenance_window      = "Tue:04:00-Tue:06:00"
  backup_window           = "00:00-04:00"
  backup_retention_period = local.backup_retention_days

  snapshot_identifier = var.snapshot_identifier
  skip_final_snapshot = var.skip_final_snapshot

  ca_cert_identifier = "rds-ca-rsa2048-g1"
}

# The credentials secret the conduit-api / conduit-consumer runtime reads
# (<env>/conduit/rds-db-credentials/conduit.json). Year-1 uses the root user; split into a
# least-privilege app role (via the postgresql provider, as athena/rds/roles.tf does) when needed.
resource "aws_secretsmanager_secret" "app-credentials" {
  name                    = "${local.env}/${local.service}/rds-db-credentials/${local.service}.json"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "app-credentials" {
  secret_id = aws_secretsmanager_secret.app-credentials.id
  secret_string = jsonencode({
    dialect  = "postgresql"
    user     = local.db_root_user_name
    password = aws_secretsmanager_secret_version.root-password.secret_string
    address  = module.db.db_instance_address
    port     = module.db.db_instance_port
    database = local.db_root_name
  })
}
