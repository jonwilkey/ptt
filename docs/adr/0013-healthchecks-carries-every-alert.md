# 0013. Healthchecks.io carries every alert, by email only

- Status: Accepted
- Date: 2026-11-02

## Context

The spec's Operations section has `pttd` ping Healthchecks.io (free tier), and "a missed ping
emails and texts both admins". It also asks for an email to both admins naming any phone that has
been silent for 48 hours or has a red checklist item, and the scheduled workflows need a way to
tell both admins they failed. The server has no mail setup.

Two facts constrain this:

- Healthchecks.io's free (Hobbyist) plan includes no SMS or WhatsApp credits; they start with the
  paid Business plan (<https://healthchecks.io/pricing/>), which would add about $20 a month
  to a system the spec costs at $6-7.
- Healthchecks.io's alert email includes the body of the check's last ping (its last 10 000
  bytes, `templates/emails/alert-body-html.html` in the healthchecks repository; each ping
  stores up to 100 kB, <https://healthchecks.io/docs/http_api/>).
  So a check pinged with `/fail` and a text body works as a mailer for short messages.

## Decision

Both admins accepted dropping SMS on 2026-11-02.

- Every alert goes through a Healthchecks.io check with two email integrations, one per admin.
  No SMS: email reaches both admins' phones, which is what the spec's "the app can't page anyone
  when its own server is down" needs.
- The phone heartbeat is a check (`ptt-phones`) that `pttd` pings every hour: success when every
  device is fine, `/fail` with a plain-text list of the devices that need attention otherwise.
  The server gets no SMTP client and no mail credentials.
- `weekly-maintenance` and `nightly-security` ping their own checks, because GitHub notifies only
  one person about a failed scheduled run.

The spec's Uptime bullet changes from "emails and texts" to "emails". Details:
`docs/operations.md`, "Monitoring".

## Consequences

- No new secret beyond the Healthchecks.io ping key, and no mail deliverability to maintain.
- Healthchecks.io emails only on state changes. Both admins are members of the project's team
  with daily email reminders turned on, so any check that stays down is mentioned once a day
  (without its ping body). For `ptt-phones` only, `pttd` forces a fresh alert when a new device
  or reason joins the list, by pinging success then `/fail`; the admins then get an "up" email
  just before the new "down" email.
- One outage of the server sends up to three "down" emails (`ptt-uptime`, `ptt-phones`,
  `ptt-fcm`, hours apart; four if it spans the 02:00 snapshot). Accepted.
- Person and device names leave the server in the alert body, to Healthchecks.io and the admins'
  mailboxes. Nothing about pages or conversations does.
- If text alerts are wanted later, a free Healthchecks.io integration (for example Signal,
  Telegram or ntfy) can be added to the checks without code changes.
