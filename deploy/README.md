# EC2 Ubuntu deployment

The frontend always requests relative `/api` URLs. In local development, Vite
proxies that path to Spring Boot. On EC2, Nginx serves the static frontend and
proxies the same path to Spring Boot. No EC2 IP address or frontend API build
variable is required.

## Build artifacts

Build the backend with Java 21 and Maven:

```bash
cd Backend
mvn -q test
mvn -q package
```

Copy `target/parcel-routing-0.0.1-SNAPSHOT.jar` to
`/opt/parcel-routing/parcel-routing.jar` on the server. Build the frontend:

```bash
cd Frontend
npm ci
npm run build
```

Copy the contents of `Frontend/dist/` to `/var/www/parcel-routing/`.

## Server configuration

1. Install Java 21, PostgreSQL, and Nginx on Ubuntu. Create a non-login
   `parcelrouting` service user that can read `/opt/parcel-routing`.
2. Create the database and a least-privilege PostgreSQL user. Keep PostgreSQL
   bound to loopback/private networking; do not create a public security-group
   or firewall rule for port 5432.
3. Copy `parcel-routing.env.example` to
   `/etc/parcel-routing/parcel-routing.env`, replace every placeholder with
   strong values, then run `sudo chmod 600 /etc/parcel-routing/parcel-routing.env`.
   The example binds Spring Boot to `127.0.0.1`, leaving Nginx as the only
   public HTTP entry point.
4. Copy `systemd/parcel-routing.service` to
   `/etc/systemd/system/parcel-routing.service`, then run:

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now parcel-routing
   sudo systemctl status parcel-routing
   ```

5. Copy `nginx/parcel-routing.conf` to
   `/etc/nginx/sites-available/parcel-routing`, enable it with a symlink in
   `sites-enabled`, remove the default site if it conflicts, then verify and
   reload:

   ```bash
   sudo nginx -t
   sudo systemctl reload nginx
   ```

6. Configure TLS before exposing the application publicly (for example, with
   Certbot and a domain name). HTTP Basic credentials must not be sent over
   plain HTTP. Allow only ports 22, 80, and 443 as needed in the EC2 security
   group/UFW; do not allow 5432 or 8080 from the internet.

Flyway runs automatically at application startup. The application does not
need a production Spring profile: datasource URL, database credentials,
application credentials, and server binding are supplied by the environment.

## Local development

Start local PostgreSQL with a database named `parcel_routing`, then set
`PARCEL_DB_USERNAME` and `PARCEL_DB_PASSWORD`. The datasource URL defaults to
`jdbc:postgresql://localhost:5432/parcel_routing`; override `PARCEL_DB_URL`
only when needed. Run the backend from `Backend` with `mvn spring-boot:run` and
the frontend from `Frontend` with `npm run dev`. Vite proxies `/api` to
`http://localhost:8080` only during development.
