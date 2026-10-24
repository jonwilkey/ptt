"""Prints a GitHub Actions ::add-mask:: command for every value in a YAML file.

    python3 -I .github/scripts/mask-yaml-values.py <file.yml>

Used for ANSIBLE_SITE_VARS (admin user names, SSH keys, the domain), whose values would
otherwise appear in Ansible's task output (loop labels, assert messages) in a public log.
GitHub does not reliably mask a multi-line secret line by line, so every string is masked
on its own, as whole lines and as the whitespace-separated tokens of each line (an SSH key
line yields the key type, the base64 key, and the comment). A mask command ends at the first
newline, so no masked value ever contains one.

Values shorter than 2 characters and SSH key-type tokens (ssh-ed25519, ...) are skipped,
because masking them would only garble the log; values of `name` fields (user names) are
always masked. Needs PyYAML (installed with ansible-core).
"""

import re
import sys

import yaml

KEY_TYPE = re.compile(r"^(ssh|ecdsa|sk)-[a-z0-9@.-]+$")


def values(node, key=None):
    """Yields (value, always) for every string in node; always is True under a `name` key."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield from values(v, k)
    elif isinstance(node, list):
        for v in node:
            yield from values(v, key)
    elif isinstance(node, str):
        always = key == "name"
        for line in node.splitlines():
            line = line.strip()
            if line:
                yield line, always
            for token in line.split():
                yield token, always


def main(path):
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict):
        sys.exit("The file must be a YAML mapping (the content of site.yml).")
    masks = set()
    for value, always in values(data):
        if "\n" in value or "\r" in value:
            continue
        if always or (len(value) >= 2 and not KEY_TYPE.match(value)):
            masks.add(value)
    for value in sorted(masks):
        print(f"::add-mask::{value}")


if __name__ == "__main__":
    main(sys.argv[1])
