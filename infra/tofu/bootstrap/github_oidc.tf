# GitHub Actions authenticates to AWS with short-lived OIDC tokens; no AWS keys are stored
# anywhere. Two roles:
#   ptt-tofu-plan   any ref or PR of the repo: read prod state, take the lock, read Route 53.
#   ptt-tofu-apply  only jobs in the "production" environment (approval required): write prod
#                   state and change the ptt A/AAAA records. Nothing else in the account.

locals {
  github_oidc_host = "token.actions.githubusercontent.com"

  github_oidc_provider_arn = (
    var.create_github_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
    : data.aws_iam_openid_connect_provider.github[0].arn
  )

  prod_state_key = "prod/terraform.tfstate"
  prod_state_arn = "${aws_s3_bucket.state.arn}/${local.prod_state_key}"
  prod_lock_arn  = "${aws_s3_bucket.state.arn}/${local.prod_state_key}.tflock"

  zone_arn = "arn:${data.aws_partition.current.partition}:route53:::hostedzone/${data.aws_route53_zone.parent.zone_id}"

  # Route 53 condition keys compare against lower-case names without the trailing dot.
  record_name_normalized = trimsuffix(lower(var.record_name), ".")
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://${local.github_oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
  # No thumbprint_list: AWS validates GitHub's certificate against its own trusted CAs.
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1

  url = "https://${local.github_oidc_host}"
}

data "aws_route53_zone" "parent" {
  name         = var.zone_name
  private_zone = false
}

# ---- Trust policies ------------------------------------------------------------------------

data "aws_iam_policy_document" "plan_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "${local.github_oidc_host}:sub"
      values   = ["repo:${var.github_repository}:*"]
    }
  }
}

data "aws_iam_policy_document" "apply_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:sub"
      values   = ["repo:${var.github_repository}:environment:${var.github_apply_environment}"]
    }
  }
}

# ---- Permission policies -------------------------------------------------------------------

# Shared statements: lock handling, KMS for state/plan encryption, read-only Route 53.
data "aws_iam_policy_document" "common" {
  statement {
    sid       = "ListStateBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid       = "StateLockFile"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [local.prod_lock_arn]
  }

  statement {
    sid       = "StateEncryptionKey"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.state.arn]
  }

  statement {
    sid    = "ReadHostedZone"
    effect = "Allow"
    actions = [
      "route53:GetHostedZone",
      "route53:ListResourceRecordSets",
      "route53:ListTagsForResource",
    ]
    resources = [local.zone_arn]
  }

  statement {
    sid    = "ListHostedZones"
    effect = "Allow"
    # These actions do not support resource-level permissions.
    actions   = ["route53:ListHostedZones", "route53:ListHostedZonesByName"]
    resources = ["*"]
  }

  statement {
    sid       = "ReadChangeStatus"
    effect    = "Allow"
    actions   = ["route53:GetChange"]
    resources = ["arn:${data.aws_partition.current.partition}:route53:::change/*"]
  }
}

data "aws_iam_policy_document" "plan" {
  source_policy_documents = [data.aws_iam_policy_document.common.json]

  statement {
    sid       = "ReadState"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = [local.prod_state_arn]
  }
}

data "aws_iam_policy_document" "apply" {
  source_policy_documents = [data.aws_iam_policy_document.common.json]

  statement {
    sid       = "ReadWriteState"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = [local.prod_state_arn]
  }

  statement {
    sid       = "ChangePttRecordsOnly"
    effect    = "Allow"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [local.zone_arn]

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values   = [local.record_name_normalized]
    }

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsRecordTypes"
      values   = ["A", "AAAA"]
    }
  }
}

# ---- Roles ---------------------------------------------------------------------------------

resource "aws_iam_role" "plan" {
  name                 = "ptt-tofu-plan"
  description          = "GitHub Actions (${var.github_repository}, any ref): tofu plan for infra/tofu/prod"
  assume_role_policy   = data.aws_iam_policy_document.plan_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "plan" {
  name   = "tofu-plan"
  role   = aws_iam_role.plan.id
  policy = data.aws_iam_policy_document.plan.json
}

resource "aws_iam_role" "apply" {
  name                 = "ptt-tofu-apply"
  description          = "GitHub Actions (${var.github_repository}, environment ${var.github_apply_environment}): tofu apply for infra/tofu/prod"
  assume_role_policy   = data.aws_iam_policy_document.apply_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "apply" {
  name   = "tofu-apply"
  role   = aws_iam_role.apply.id
  policy = data.aws_iam_policy_document.apply.json
}
