# Metabase StarRocks Driver

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Metabase](https://img.shields.io/badge/Metabase-v0.50+-blue.svg)](https://www.metabase.com/)
[![StarRocks](https://img.shields.io/badge/StarRocks-v3.2+-green.svg)](https://www.starrocks.io/)

A community Metabase driver for [StarRocks](https://www.starrocks.io/) that fixes MySQL protocol compatibility issues and adds proper multi-catalog support.

## Why This Driver?

Metabase's built-in MySQL driver doesn't work properly with StarRocks because:

1. **`SHOW GRANTS FOR CURRENT_USER` is unsupported** — StarRocks uses a different privilege system than MySQL, causing metadata sync to fail with:
   ```
   No viable statement for input 'SHOW GRANTS FOR CURRENT_USER'
   ```

2. **Multi-catalog support** — StarRocks external catalogs (Hive, Iceberg, etc.) require `catalog.database` format which the MySQL driver doesn't handle well.

This driver extends Metabase's `sql-jdbc` driver directly, bypassing the problematic MySQL-specific code while maintaining full query compatibility.

## Installation

### Option 1: Download Pre-built JAR

Download the latest `starrocks.metabase-driver-vX.X.X.jar` from the [Releases](../../releases) page.

### Option 2: Build from Source

```bash
# Install Clojure CLI (macOS)
brew install clojure/tools/clojure

# Clone and build
git clone https://github.com/Carbon-Arc/metabase-starrocks-driver.git
cd metabase-starrocks-driver
clojure -T:build uber

# Output: target/starrocks.metabase-driver.jar
```

### Deploy the Driver

Copy the JAR to your Metabase plugins directory:

```bash
# Docker
docker cp starrocks.metabase-driver.jar metabase:/plugins/

# Local installation
cp starrocks.metabase-driver.jar /path/to/metabase/plugins/

# Kubernetes
kubectl cp starrocks.metabase-driver.jar <namespace>/<pod>:/plugins/
```

Then restart Metabase to load the driver.

## Requirements

- **Metabase**: v0.50+ (tested with v0.57)
- **StarRocks**: v3.2+ (for external catalog support)
- **Java**: JDK 11-21 (for building from source)

## Configuration

1. Go to **Admin → Databases → Add Database**
2. Select **"StarRocks"** from the database type dropdown
3. Configure the connection:

| Field | Description | Example |
|-------|-------------|---------|
| Host | StarRocks FE hostname | `starrocks-fe.example.com` |
| Port | MySQL protocol port | `9030` |
| Catalog | StarRocks catalog name | `default_catalog` |
| Database | Database within catalog (optional) | `my_database` |
| Username | StarRocks user | `admin` |
| Password | User password | `••••••••` |
| Use a secure connection (SSL) | Encrypt the MySQL protocol connection | Enabled when FE requires SSL |
| Server SSL certificate chain | CA / server PEM chain for trust | Required when FE uses a private or self-signed CA |

### SSL

StarRocks supports SSL on the MySQL protocol port (default `9030`) starting from **v3.4.1**. Configure the FE node first — see the [StarRocks SSL documentation](https://docs.starrocks.io/docs/administration/user_privs/ssl_authentication/) (`ssl_keystore_location`, `ssl_keystore_password`, `ssl_key_password` in `fe.conf`).

1. Enable **Use a secure connection (SSL)** in the database connection form (off by default; no SSL settings apply when unchecked).
2. Choose **SSL mode** (three levels, only shown when SSL is enabled):
   - **VERIFY_CA** (default): encrypt + verify the certificate chain (paste CA PEM for private / Aliyun CAs).
   - **VERIFY_IDENTITY**: encrypt + verify certificate and hostname (public CA in JVM trust store).
   - **REQUIRED**: encrypt only, no certificate validation (only if your security policy allows).
3. Optionally paste the CA / certificate chain (PEM) into **Server SSL certificate chain**.
4. **Additional JDBC options** can still override `sslMode` when SSL is enabled. With SSL off, SSL-related keys in additional options are ignored.

> **Note**: StarRocks v3.2+ is required for catalog support; SSL on the MySQL port requires v3.4.1+.

### Catalog Examples

- **Internal catalog**: `default_catalog`
- **Hive catalog**: `hive_catalog`
- **Iceberg/Polaris catalog**: `iceberg_catalog`

> **Tip**: Leave the **Database** field empty to see all databases in the catalog.

## Limitations

- Foreign key relationships are not supported (StarRocks limitation)
- Some advanced MySQL features may not be available

## Project Structure

```
metabase-starrocks-driver/
├── deps.edn                         # Dependencies & build config
├── build.clj                        # Build script
├── src/metabase/driver/
│   └── starrocks.clj                # Driver implementation
├── resources/
│   ├── metabase-plugin.yaml         # Plugin manifest
│   └── metabase_driver/starrocks/
│       └── icon.svg                 # Driver icon
└── docs/
    └── metabase-infrastructure.md   # Deployment notes
```

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

1. Clone the repository
2. Install [Clojure CLI tools](https://clojure.org/guides/install_clojure)
3. Make your changes in `src/metabase/driver/starrocks.clj`
4. Build and test: `clojure -T:build uber`

## Releases

This project uses [semantic versioning](https://semver.org/). Releases are automated via GitHub Actions.

To create a new release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

This triggers a workflow that builds the JAR and creates a GitHub release with the artifact attached (e.g., `starrocks.metabase-driver-v1.0.0.jar`).

## Troubleshooting

### Connection Failed

- Verify StarRocks FE is accessible on the MySQL protocol port (default: 9030)
- Check firewall rules allow connections from Metabase
- Ensure the user has appropriate permissions

### Tables Not Showing

- Verify the catalog name is correct
- Check that the user has `SELECT` privileges on the tables
- Try leaving the Database field empty to scan all databases

### SSL Connection Failed

- StarRocks FE must have SSL configured in `fe.conf` (requires StarRocks v3.4.1+)
- Enable **Use a secure connection (SSL)** when the FE requires encrypted connections
- **PKIX / certificate_unknown**: paste the FE CA or full certificate chain (PEM) into **Server SSL certificate chain**, or import that CA into the Metabase JVM truststore
- If hostname verification fails with a valid CA, try `sslMode=VERIFY_CA` in additional JDBC options (verifies the chain but is less strict on hostname than `VERIFY_IDENTITY`)

### Sync Errors

- This driver bypasses `SHOW GRANTS` — if you see grant-related errors, ensure you're using this driver (not MySQL)

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Related Projects

- [Metabase](https://github.com/metabase/metabase) — The open source BI tool
- [StarRocks](https://github.com/StarRocks/starrocks) — The analytics database
