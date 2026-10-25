# Runbook: Firebase and FCM setup

Creates the Firebase project that delivers pushes, gives the server a least-privilege
service-account key, and verifies the whole path with `pttd admin test-push`. Also covers the
yearly key rotation (the spec's "FCM key leaked or expiring" row).

Do this once before the first real phone is enrolled, and again each year. Needs a Google
account that owns the project, `sops` and `age` on your workstation, and SSH access to the
server. Placeholders below (`example-ptt`, `com.example.ptt`, `fcm-sender`) are not real: use
your own names, and keep the real project ID, package name and every key out of this repository
(it is public).

## 1. Create the Firebase project

1. In the [Firebase console](https://console.firebase.google.com/), **Add project**. Name it
   (the project ID will look like `example-ptt`). Decline Google Analytics: the app needs none.
2. **Project settings > General > Your apps > Add app > Android**.
   - Package name: the app's application ID. The Android milestone fixes it; until then
     `com.example.ptt` is the placeholder, and it must match the real ID exactly or pushes
     will not reach the app.
   - SHA-1 fingerprint: optional here, but you need the **release** signing certificate's
     SHA-1 (and the debug one for development builds) for the API-key restriction in step 6.
     Get it with `keytool -list -v -keystore <keystore>`.
3. **Do not download `google-services.json` into this repository.** How the Android app gets its
   Firebase configuration is covered in the Android section below.

## 2. Create the service account and key

The server authenticates to the FCM HTTP v1 API with a service-account key. Give that account
only what sending needs.

1. In the [Google Cloud console](https://console.cloud.google.com/) (same project),
   **IAM & Admin > Service Accounts > Create service account**. Name it `fcm-sender`.
2. Grant the narrowest role that can send. The narrowest option is a **custom role** containing
   only the permission `cloudmessaging.messages.create` (**IAM & Admin > Roles > Create role**,
   then grant it to the account). If you prefer a predefined role, use **Firebase Cloud
   Messaging API Admin** (`roles/firebasecloudmessaging.admin`). Do **not** use the project's
   default Firebase Admin SDK service account or any Editor/Owner role.
3. Confirm the **Firebase Cloud Messaging API** is enabled for the project (**APIs & Services >
   Library**).
4. On the service account, **Keys > Add key > Create new key > JSON**. The file downloads to
   your workstation. This is the only copy of the secret: treat it like a password. If the
   console refuses, the organization policy `iam.disableServiceAccountKeyCreation` is probably
   enforced; an organization admin must allow key creation for this project (and can turn it
   back on afterwards).

## 3. Encrypt it into the repository

```sh
cd infra/secrets
# One age public key per admin plus the CI key (public keys, not the private identity files;
# the CI key is created in github-setup.md, "The CI age key").
sops --encrypt --input-type json --output-type json \
  --age age1exampleadminonepublickey,age1exampleadmintwopublickey,age1examplecipublickey \
  ~/Downloads/example-ptt-key.json > fcm-service-account.sops.json
shred -u ~/Downloads/example-ptt-key.json   # or delete it from the password-manager import
```

Check that the file contains only ciphertext: `grep -c 'PRIVATE KEY' fcm-service-account.sops.json`
must print `0`. Then commit it. Never commit the downloaded plaintext; a gitleaks job and the local
hooks reject private keys, but do not rely on them. Conventions are in
[infra/secrets/README.md](../../infra/secrets/README.md).

## 4. Deploy it

1. In `infra/ansible/inventory/group_vars/prod/main.yml` replace `app_secrets: []` with:

   ```yaml
   app_secrets:
     - {src: fcm-service-account.sops.json, dest: fcm-service-account.json}
   ```

   The `fcm-service-account.json` dest is what makes the app role write
   `PTTD_FCM_CREDENTIALS_FILE=/run/secrets/ptt/fcm-service-account.json` into the compose
   `.env`.
2. Open a PR with both changes and merge it after CI is green. The `infra` workflow runs the
   Ansible playbook on merge (or start it by hand with **Actions > infra > Run workflow**). The
   workflow decrypts the file with the CI age key, so the `SOPS_AGE_KEY` environment secret
   must exist first ([github-setup.md](github-setup.md), "The CI age key"), and the file must
   have been encrypted to the CI public key (step 3).
3. The deploy recreates `pttd` because its environment changed. Check that the startup log has
   no "FCM is not configured" warning:

   ```sh
   sudo docker compose -f /opt/ptt/compose.yaml logs --tail 50 pttd | grep -i fcm
   ```

## 5. Verify

With at least one enrolled phone that has registered an FCM token:

```sh
sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin devices   # find the device id
sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin test-push <device-id>
```

The phone should show a "Test push" notification within a few seconds. Errors:

| Message | Meaning |
| --- | --- |
| `FCM is not configured` | The secret or the `.env` line is missing; redo step 4 |
| `no FCM token` | The phone has not registered with FCM yet; open the app once |
| `revoked` / `no such device` | Wrong device id |
| `permanent fcm failure` ... | FCM rejected the send: wrong service account or project, API not enabled, or the app's package or Firebase config does not match the project |

A token that FCM reports as unregistered is cleared from the device automatically; the phone
registers a fresh one the next time it opens.

## 6. Restrict the Android API key

The `google-services.json` that ships in the app contains an API key. Restrict it so a copy
extracted from the APK is useless elsewhere (the spec's "Secrets" section):

1. Google Cloud console > **APIs & Services > Credentials**, open the project's **Android key**
   (auto-created by Firebase; named like "Android key (auto created by Firebase)").
2. **Application restrictions: Android apps.** Add the package name (`com.example.ptt`) with the
   **release** certificate's SHA-1 fingerprint. Add the debug fingerprint only if development
   builds need it, ideally on a separate key.
3. **API restrictions: Restrict key**, allowing only the APIs the app calls (Firebase Installations
   API and Firebase Cloud Messaging Registration API; add others only if registration fails
   without them).
4. Re-run step 5 from a phone with a release build.

## 7. Android app configuration

*Placeholder.* How the Android app gets its Firebase configuration values (the
`google-services` project number, application ID, and API key, and where they live without
being committed) is decided in the Android milestone and will be documented here when that PR
lands.

## Rotation (yearly, or on suspicion of a leak)

Rotate every year and immediately if the key file may have been copied.

1. Create a **new** JSON key for the same `fcm-sender` service account (step 2.4). Keep the old
   key until the new one works.
2. Replace the file's contents: `sops` the new JSON into `fcm-service-account.sops.json`
   (step 3, overwriting the old ciphertext) and commit it.
3. Merge and let the `infra` workflow deploy (step 4). `pttd` restarts with the new key because
   the secret file changed.
4. Verify with `pttd admin test-push` (step 5).
5. **Delete the old key** in Google Cloud console > Service Accounts > Keys. After a suspected
   leak, check **Logs Explorer** for unexpected `google.firebase.fcm` calls, and review the
   service account's activity.
6. Securely delete every plaintext copy of either key.

If the service account itself may be compromised (not just one key), delete the account, create
a new one with the same single role, and repeat steps 2 to 5.
