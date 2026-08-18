## Agent skills

### Issue tracker

Issues live as GitHub Issues; skills use the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### System design

The accepted IM architecture, data model, protocols, security boundaries, delivery stages, and test targets live in `docs/design/im-system-technical-design.md`. Keep it aligned with `CONTEXT.md` and accepted ADRs.

### Development environment

Use JDK 21 through `scripts/dev-env.sh`, the checked-in Maven Wrapper (`./mvnw`), and the Docker Desktop Compose plugin. Do not rely on the system Java or a global Maven installation. See `docs/development-environment.md`.
