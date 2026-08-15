# Security policy

## Scope

This is an offline-first bike computer extension. It has a small attack surface:

- It makes one outbound HTTPS request to `api.open-meteo.com`, routed through the
  Karoo system service, sending only a coarse latitude and longitude.
- It stores settings, cached weather and ride state locally via DataStore.
- It collects no analytics, has no accounts, and talks to no other server.

## Reporting a vulnerability

Please report privately via
[GitHub Security Advisories](https://github.com/timpara/karoo-sweat/security/advisories/new)
rather than a public issue.

Expect an acknowledgement within a week. As a hobby project there is no formal SLA,
but anything affecting user data or enabling code execution will be treated as
urgent.

## Privacy note

Your position is sent to Open-Meteo in order to fetch local temperature and humidity.
Coordinates are rounded to four decimal places in the request. If you would rather
send nothing at all, set the temperature source to the device sensor and configure a
fallback humidity; the extension will then make no network requests.
