# Credentials come from the admin's environment (AWS_PROFILE, SSO, or similar), never from files
# in this repository.
provider "aws" {
  region = local.region

  default_tags {
    tags = {
      Project   = "ptt"
      ManagedBy = "opentofu"
      Stack     = "bootstrap"
    }
  }
}
