# The parent zone already exists and is managed outside this repository. It is only read here;
# OpenTofu owns exactly two records in it: the server's A and AAAA records.

data "aws_route53_zone" "parent" {
  name         = var.zone_name
  private_zone = false
}

locals {
  fqdn = var.record_name
}

resource "aws_route53_record" "a" {
  zone_id = data.aws_route53_zone.parent.zone_id
  name    = local.fqdn
  type    = "A"
  ttl     = var.dns_ttl
  records = [vultr_reserved_ip.v4.subnet]

  lifecycle {
    precondition {
      condition     = endswith(local.fqdn, ".${trimsuffix(var.zone_name, ".")}")
      error_message = "record_name must be a name inside zone_name."
    }
  }
}

resource "aws_route53_record" "aaaa" {
  zone_id = data.aws_route53_zone.parent.zone_id
  name    = local.fqdn
  type    = "AAAA"
  ttl     = var.dns_ttl
  records = [vultr_instance.ptt.v6_main_ip]
}
