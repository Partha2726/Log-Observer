# db/dev-migration/

Dev-only Flyway migrations (seed users). Only `application-dev.yml` adds this
folder to `spring.flyway.locations`, so they never run outside the `dev` profile.

- Seed data only; schema lives in `db/migration/`.
- No real credentials. Seeded passwords are disposable and documented.
- Versions share Flyway's sequence with `db/migration/`. Current: `V1_1`.
