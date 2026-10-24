# The Vultr API key is read from the VULTR_API_KEY environment variable (the provider's
# default). It is never written in a file in this repository.
provider "vultr" {}

# AWS credentials come from the environment: the ptt-tofu-plan / ptt-tofu-apply OIDC roles in CI,
# or the admin's own profile on a laptop.
# Route 53 is global; the region is fixed to the one used by the state bucket and KMS key.
provider "aws" {
  region = "us-west-2"

  default_tags {
    tags = {
      Project   = "ptt"
      ManagedBy = "opentofu"
      Stack     = "prod"
    }
  }
}
