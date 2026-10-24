# Deployment-specific values (repository, bucket, domain) have no defaults on purpose: this
# repository is public, so real values live only in a git-ignored terraform.tfvars.

variable "github_repository" {
  description = "GitHub repository (owner/name) whose Actions workflows may assume the OpenTofu roles."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name."
  }
}

variable "github_apply_environment" {
  description = "GitHub environment whose jobs may assume the apply role. It must require approval by an admin."
  type        = string
  default     = "production"
}

variable "state_bucket_name" {
  description = "Globally unique name of the S3 bucket holding OpenTofu state. Use the same value as `bucket` in infra/tofu/backend.hcl and the TOFU_STATE_BUCKET Actions variable."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name."
  }
}

variable "region" {
  description = "AWS region for the state bucket and the KMS key. Must match `region` in both backend blocks and the KMS key provider in prod/versions.tf."
  type        = string
  default     = "us-west-2"
}

variable "zone_name" {
  description = "Existing public Route 53 hosted zone that holds the server's records, e.g. example.com. Read only; never modified as a whole."
  type        = string
}

variable "record_name" {
  description = "Fully qualified record name the apply role may change (A and AAAA only), e.g. ptt.example.com. Must equal prod's record_name."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.record_name))
    error_message = "record_name must be a lower-case fully qualified domain name without a trailing dot."
  }
}

variable "state_kms_alias" {
  description = "Alias of the KMS key used by OpenTofu state encryption. prod/versions.tf refers to it by this name."
  type        = string
  default     = "alias/ptt-tofu-state"

  validation {
    condition     = startswith(var.state_kms_alias, "alias/")
    error_message = "state_kms_alias must start with \"alias/\"."
  }
}

variable "create_github_oidc_provider" {
  description = "Create the account's GitHub Actions OIDC provider. Set false if the AWS account already has one for token.actions.githubusercontent.com (only one may exist per account); it is then looked up instead."
  type        = bool
  default     = true
}
