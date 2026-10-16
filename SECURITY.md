# Security and privacy

Agent Demo runs on your machine and records a real browser. Treat it like a
local automation script, not a sandbox or a PII-redaction system.

## Trust boundaries

- Use trusted EDN. The EDN reader does not evaluate Clojure code, but the
  Kokoro launch command and browser executable can run local programs.
- Browser actions can change real application data. Use an authorized,
  disposable account and fixture, never production data by default.
- Kokoro speech requests are restricted to loopback HTTP origins without
  embedded credentials. Narration still reaches that local service.
- The `auth` command opens a separate visible browser without recording or
  connecting to your personal profile. It saves state only after manual login
  and human confirmation in a fresh context. State can include identity-provider
  sessions as well as app sessions; keep it private and delete it when no longer
  needed. Cancellation before saving leaves only an empty output directory.
- Browser authentication belongs in a private storage-state file outside
  Git. Password-field fills are blocked; this does not detect all sensitive
  fields, token-bearing URLs, login redirects, or page content.
- Capture and render manifests contain actions, narration, selectors, URLs,
  and absolute paths. Raw footage can include setup and private UI. Keep the
  entire output directory private, including failed runs.
- New output directories use 0700 on POSIX. On other filesystems, inspect
  inherited ACLs. Moving or copying artifacts can change their protection.
- Saved project copies omit browser configuration and the Kokoro command,
  but are not general-purpose sanitized exports.
- CLI errors suppress arbitrary exception messages and diagnostic payloads.
  Library callers must also avoid printing raw exceptions or browser logs.
- Review the final MP4 for names, emails, credentials, notifications, and
  private narration before publishing. There is no automatic redaction.

## Repository audit

The source/history review found no matches for common credential signatures,
email addresses, or personal home-directory paths in the 42 historical file
blobs present at the start of the review. One historical image was excluded
from the text scan; it was the previously reviewed fixture preview.

Git commit metadata includes the maintainer's author name and GitHub no-reply
email. Public repository and attachment URLs intentionally identify the
repository and its published demos. These are not authentication credentials.

This was a targeted code review and pattern scan, not a comprehensive secret
scanner, dependency vulnerability assessment, or penetration test. It does
not establish that all files, media, or future captures are free of PII.

## If sensitive data is published

Revoke or rotate exposed credentials first. Remove the public attachment or
release asset as well as any tracked file. Deleting a file in a later commit
does not remove it from history. Coordinate a history rewrite with repository
collaborators; do not force-push as an automatic cleanup step.

Do not report vulnerabilities with live credentials, private screenshots,
or raw capture manifests in a public issue. Use a private reporting channel
agreed with the maintainer.
