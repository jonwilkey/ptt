output "state_bucket_name" {
  description = "S3 bucket holding OpenTofu state (bootstrap/ and prod/ keys). Store as the GitHub Actions repository secret TOFU_STATE_BUCKET."
  value       = aws_s3_bucket.state.bucket
}

output "state_kms_key_arn" {
  description = "ARN of the KMS key used for OpenTofu state and plan encryption."
  value       = aws_kms_key.state.arn
}

output "state_kms_alias" {
  description = "Alias of the state encryption key, as referenced by prod/versions.tf."
  value       = aws_kms_alias.state.name
}

output "plan_role_arn" {
  description = "IAM role for `tofu plan` in CI. Store as the GitHub Actions repository secret AWS_TOFU_PLAN_ROLE_ARN (a secret, not a variable: it contains the account ID)."
  value       = aws_iam_role.plan.arn
}

output "apply_role_arn" {
  description = "IAM role for `tofu apply` in CI (production environment only). Store as the secret AWS_TOFU_APPLY_ROLE_ARN in the GitHub environment \"production\"."
  value       = aws_iam_role.apply.arn
}
