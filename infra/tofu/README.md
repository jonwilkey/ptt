# infra/tofu/

OpenTofu configuration for the cloud resources: the Vultr VPS (Seattle), firewall group, reserved
IP, automatic backups, and the `ptt.example.com` records in an existing Route 53 hosted zone
(`example.com`). The real names are configured per deployment, never committed. State lives in
an encrypted S3 backend.

Filled in milestone 1. Commit `.terraform.lock.hcl`; never commit state or `*.tfvars`.
