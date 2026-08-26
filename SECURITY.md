# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Email
**security@ohalo.co** with a description, the affected version/tag and, if
possible, steps to reproduce. We aim to acknowledge reports within 3 working
days.

## Scope

These workflows run inside your own Collibra instance and connect to your own
Data X-Ray instance using credentials that *you* configure as hidden workflow
configuration variables. They never send data to Ohalo. Relevant concerns
include: leakage of those credentials into logs or forms, unexpected writes or
deletions in Collibra, and injection via Data X-Ray-supplied names/values.

## Supported versions

Only the latest release on the Releases page is supported.
