# infra/tofu/

OpenTofu configuration for the cloud resources: the Vultr VPS (Seattle), firewall group, reserved
IP, automatic backups, and the `ptt.example.com` records in Route 53. State lives in an
encrypted S3 backend.

Filled in milestone 1. Commit `.terraform.lock.hcl`; never commit state or `*.tfvars`.
