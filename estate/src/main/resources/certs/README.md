# Demo certificates (not secrets)

Generated with `keytool` for the local demo estate only. Password for every store: `changeit`.

| File | What it is |
|---|---|
| `truststore.p12` | The demo CA certificate. Trusted by the regulator (to check gateway client certs) and by the gateway (to check the regulator's server cert). |
| `regulator.p12` | Server key + cert for the mock regulator ARM. SAN: `regulator-arm`, `localhost`, `127.0.0.1`. Valid 10 years. |
| `gateway-client.p12` | The gateway's client cert (`CN=reg-gateway`). Valid 10 years. |
| `gateway-client-expired.p12` | Same subject, but valid only 2025-01-01 → 2025-01-31. Used by the `cert-expiry` fault. |

These keys protect nothing real. Never reuse them outside this demo.
