# Legacy ACL

FastAPI strangler facade for legacy Train Ticket entrypoints. It owns no domain state: every command resolves context through owning service APIs, invokes the new bounded-context HTTP commands, and emits `LegacyCommandMapped` to `events:legacy-acl` for audit ingestion.
