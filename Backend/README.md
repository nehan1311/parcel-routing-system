# Parcel Routing Service

## Local development credentials

The development-only in-memory accounts read their passwords from these environment variables:

- `PARCEL_OPERATOR_PASSWORD` for the `operator` account (`OPERATOR`)
- `PARCEL_APPROVER_PASSWORD` for the `approver` account (`INSURANCE_APPROVER`)
- `PARCEL_ADMIN_PASSWORD` for the `admin` account (`ADMIN`)

Local-only fallback values are configured for the three account passwords so the service can be run locally without setting them. Do not use those accounts or fallback passwords outside local development; set the variables through the deployment environment instead. Passwords must never be logged or committed.

## Database configuration

Set both database credentials in the environment before starting the application:

- `PARCEL_DB_USERNAME`
- `PARCEL_DB_PASSWORD`

The application intentionally provides no datasource credential defaults. Configure the local PostgreSQL instance with these values rather than committing database credentials.
