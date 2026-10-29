# 0009. Firebase Installations may use the system CAs

- Status: Accepted
- Date: 2026-10-29

## Context

The spec's "Security > Transport" bullet says the app's network security config trusts only ISRG
Root X1 and X2, and "App updates" says the app "can only talk to our server (ADR 0003)". The
"Wake-up" row of the decisions table uses FCM, and the Firebase Installations SDK runs inside our
process: it calls `https://firebaseinstallations.googleapis.com` to create the installation id and
auth token that FCM registration needs. That host's certificate chain ends at Google Trust Services
(GTS Root R4 / GlobalSign), not ISRG, so with ISRG-only anchors the TLS handshake fails. On a real
phone this showed as `Firebase Installations Service is unavailable` in logcat and a device that
enrolled but never registered an FCM token. The same request succeeds from a PC with the same API
key, package and signing certificate, so the Firebase configuration is fine. The FCM token request
itself goes through Google Play services in another process and is not affected by our config.

## Decision

`<base-config>` stays as it is: ISRG Root X1/X2 only, no cleartext. One `<domain-config>` for exactly
`firebaseinstallations.googleapis.com` (`includeSubdomains="false"`) trusts the system CAs
(`<certificates src="system" />`) and still forbids cleartext. No other host gets this. Our own server
stays ISRG-only. The debug config carries the same entry. A unit test pins both files to this shape.

## Consequences

- A compromised or coerced public CA could intercept Firebase Installations traffic. That traffic is
  the installation id and its auth token, used only for FCM registration; it carries no family data.
  Pages are signed and encrypted end to end and reach the phone through Google Play services anyway.
- If a future Firebase SDK version calls another host from our process, the symptom is the same: a
  token that never registers. Look in logcat for `Firebase Installations Service is unavailable` or
  TLS errors (`SSLHandshakeException`, `Trust anchor for certification path not found`), find the host,
  and extend this ADR's list deliberately rather than loosening `<base-config>`.
- Non-essential Firebase hosts (telemetry and similar) are deliberately left blocked.
