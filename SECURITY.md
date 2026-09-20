# Security policy

## Supported versions

Trace has not had a release yet. Once releases begin, the latest release line is the supported one;
this file will say so explicitly.

## Reporting a vulnerability

Email **info@gravaxis.in** with the details. Please include:

* what an attacker can do, and what access they need to do it;
* the Trace version, server software and version (`/version`), and Java version;
* a reproduction, ideally the smallest one you can manage.

Please do not open a public issue for a vulnerability, and please do not test against servers you
do not own.

What to expect: an acknowledgement, an assessment, and a fix or an explanation of why something is
not a vulnerability. No response-time promise is made here that the project cannot currently keep —
this is a young project with a small maintainer count.

## Scope notes

Trace stores player identifiers and, when an operator switches those features on, chat messages,
commands and IP addresses. Data-handling behaviour that leaks more than the configuration implies
is in scope. So is anything that lets a player without the relevant permission read or destroy
history, or use a rollback to duplicate items.

Out of scope: vulnerabilities in Paper, Folia, Minecraft itself, or third-party plugins — report
those to their maintainers.
