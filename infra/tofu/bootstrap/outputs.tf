output "state_bucket_name" {
  description = "S3 bucket holding OpenTofu state (bootstrap/ and prod/ keys)."
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
  description = "IAM role for `tofu plan` in CI. Store as the GitHub Actions variable AWS_TOFU_PLAN_ROLE_ARN."
  value       = aws_iam_role.plan.arn
}

output "apply_role_arn" {
  description = "IAM role for `tofu apply` in CI (production environment only). Store as the GitHub Actions variable AWS_TOFU_APPLY_ROLE_ARN."
  value       = aws_iam_role.apply.arn
}
