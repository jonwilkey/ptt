terraform {
  # 1.10+ is needed for S3-native state locking (use_lockfile). CI runs 1.13.2.
  required_version = "~> 1.13"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }

  # Partial configuration: the bucket name is deployment-specific, so it is not committed. Pass it
  # with `tofu init -backend-config=../backend.hcl` (copy ../backend.hcl.example).
  #
  # Bootstrap creates the bucket this backend points at, so the very first apply runs with local
  # state: copy local_backend_override.tf.example to local_backend_override.tf (an override file
  # replaces this backend with "local"), apply, then delete the override and run
  # `tofu init -migrate-state -backend-config=../backend.hcl`. See docs/runbooks/infra-bootstrap.md.
  #
  # This module's state is deliberately NOT encrypted by OpenTofu (no `encryption` block): it
  # holds no secrets, only admins can read it, and S3 encrypts it at rest (SSE-S3). Encrypting it
  # with the KMS key this same module manages would make the stack unrecoverable if that key were
  # ever disabled or scheduled for deletion. See docs/adr/0002-kms-for-opentofu-state-encryption.md.
  backend "s3" {
    key          = "bootstrap/terraform.tfstate"
    region       = "us-west-2"
    encrypt      = true
    use_lockfile = true
  }
}
