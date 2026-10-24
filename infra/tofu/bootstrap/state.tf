# State storage for every OpenTofu root module in this repository:
#   bootstrap/terraform.tfstate  this module (admins only; CI roles cannot read it)
#   prod/terraform.tfstate       infra/tofu/prod, encrypted by OpenTofu with the KMS key below

# Hardcoded on purpose and repeated, with the same values, in versions.tf (backend) and in
# prod/versions.tf (backend and KMS key provider). Change all of them together.
locals {
  region          = "us-west-2"
  state_kms_alias = "alias/ptt-tofu-state"
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

# ---- S3 state bucket -----------------------------------------------------------------------

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3 rather than SSE-KMS: prod state is already encrypted client-side by OpenTofu with the
# KMS key, and keeping the S3 layer independent of that key means the bootstrap state (which is
# not OpenTofu-encrypted) stays readable even if the key is disabled. See ADR 0002.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Each lock/unlock leaves a delete marker on the .tflock object; clean them up once their
  # noncurrent versions have expired.
  rule {
    id     = "remove-expired-delete-markers"
    status = "Enabled"

    filter {}

    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

data "aws_iam_policy_document" "state_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid     = "DenyOutdatedTLS"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "NumericLessThan"
      variable = "s3:TlsVersion"
      values   = ["1.2"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_bucket.json

  # The public access block must exist first, or S3 may evaluate the policy against the
  # account defaults.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

# ---- KMS key for OpenTofu state and plan encryption ----------------------------------------

# Key policy: the standard "enable IAM policies" statement only. Access is then granted by the
# identity policies on the CI roles (github_oidc.tf) and on the admins' own IAM principals.
data "aws_iam_policy_document" "state_key" {
  statement {
    sid       = "EnableIAMPolicies"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "state" {
  description             = "OpenTofu state and plan encryption for ptt (infra/tofu/prod)"
  key_usage               = "ENCRYPT_DECRYPT"
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.state_key.json

  # Automatic rotation is off on purpose: the key only wraps OpenTofu's per-run data keys, its
  # material never leaves KMS, and each rotation adds $1/month (up to $3/month total). See ADR 0002.
  enable_key_rotation = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_kms_alias" "state" {
  name          = local.state_kms_alias
  target_key_id = aws_kms_key.state.key_id
}
