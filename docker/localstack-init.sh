#!/bin/sh
# Create the Conduit records bucket on LocalStack startup, mirroring the prod WORM bucket
# (terraform/conduit-records) as closely as LocalStack supports: versioning enabled.
awslocal s3api create-bucket --bucket conduit-records-local --region eu-west-1 \
  --create-bucket-configuration LocationConstraint=eu-west-1 || true
awslocal s3api put-bucket-versioning --bucket conduit-records-local \
  --versioning-configuration Status=Enabled || true
echo "localstack: conduit-records-local bucket ready"
