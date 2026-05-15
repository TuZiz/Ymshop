# Local runtime plugin jars

The project no longer requires local `PlaceholderAPI` or `PlayerPoints` jars to compile. Those integrations are accessed reflectively and must be installed as normal server plugins at runtime when enabled.

Optional jars placed in this directory are only copied by the `runServer` task into `run/plugins` for local testing. Do not commit downloaded plugin jars or shaded server plugin dependencies.

Expected runtime plugins when those features are used:

- Vault 1.7.x plus a Vault-compatible economy provider
- PlayerPoints 3.3.x
- PlaceholderAPI 2.11.x
- Folia/Paper server jars for local test servers as needed
