# Permission boundary for any operator touching the WORM records bucket (doc 17 §6). The boundary is
# scoped to the conduit-records.* buckets only; the actual bucket lives in terraform/conduit-records.
data "aws_iam_policy_document" "s3-records-boundary-operator" {
  statement {
    actions = ["s3:*"]
    resources = [
      "arn:aws:s3:::${local.service}-records.*.hypervolt",
      "arn:aws:s3:::${local.service}-records.*.hypervolt/*",
    ]
  }
}
