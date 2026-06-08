# The WORM document store (doc 17 §6): finalised legal artefacts (invoices, credit notes, …) the
# DocumentService writes via S3DocumentStorage. Versioning + object-lock make every finalised document
# immutable for the statutory retention period; corrections are new documents, never overwrites.
resource "aws_s3_bucket" "records" {
  bucket              = "${local.service}.${local.env}.${local.region}.${data.aws_iam_account_alias.current.account_alias}"
  object_lock_enabled = true
}

resource "aws_s3_bucket_versioning" "records" {
  bucket = aws_s3_bucket.records.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "records" {
  bucket = aws_s3_bucket.records.id

  rule {
    default_retention {
      # Non-prod buckets allow retention bypass (GOVERNANCE); prod is COMPLIANCE (no bypass, ever).
      mode  = local.env == "prod" ? "COMPLIANCE" : "GOVERNANCE"
      years = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.records]
}

resource "aws_s3_bucket_public_access_block" "records" {
  bucket = aws_s3_bucket.records.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "records" {
  bucket = aws_s3_bucket.records.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
