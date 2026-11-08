# BSC USDT Recharge Verifier

The Android client submits a transaction hash to `wallet.deposit`. The PHP API creates a pending row in both `recharge_records` and `wallet_transactions`. Run exactly one verifier service; it polls pending rows every 30 seconds and credits `wallet_assets` only after validating the BSC receipt and confirmation count.

## Requirements

- Python 3.10 or newer
- PHP CLI available as `php` (or set `PHP_CLI` to its executable path)
- MySQL access using the credentials in `config.php`
- A trusted BSC mainnet JSON-RPC endpoint
- `requests` and `PyMySQL` from `requirements-recharge.txt`

Install dependencies and run a single check:

```sh
python3 -m pip install -r requirements-recharge.txt
python3 recharge_verifier.py --once
```

Configure the service environment:

- `FISS_BSC_RPC_URL` or `BSC_RPC`: optional trusted BSC mainnet JSON-RPC HTTPS endpoint; defaults to `https://bsc-rpc.publicnode.com`.
- `FISS_BSC_USDT_CONTRACT_ADDRESS`: optional; defaults to BSC USDT `0x55d398326f99059ff775485246999027b3197955`.
- `FISS_BSC_USDT_DEPOSIT_ADDRESS`: optional fallback if `app_settings.bsc_usdt_deposit_address` and `config.php` do not provide it.
- `FISS_BSC_CONFIRMATIONS`: required confirmation count, defaults to `12`.
- `FISS_BSC_USDT_DECIMALS`: token decimals, defaults to `18`.
- `PHP_CLI`: optional PHP executable path if it is not named `php` on PATH.

The receiving address is read from `app_settings.bsc_usdt_deposit_address`, with the environment and `config.php` as fallbacks. Set the setting through the existing application configuration before accepting deposits. Keep database and RPC credentials out of logs and source control.

## Validation and Credit

The verifier validates the transaction hash, successful receipt, transaction signer and USDT `Transfer` sender against the wallet address snapshotted at submission, the USDT contract, the receiving address snapshotted at submission, exact token amount, transaction age (at most 10 minutes), and confirmation count. Pending confirmations are retried. Invalid or expired transactions are marked failed and audited. Successful credit, recharge status, wallet transaction status, and audit entry are committed atomically.

Run the Python process under systemd or another supervisor so it restarts after errors. Do not run `wallet-deposit-worker.php` alongside this verifier; the PHP worker is deprecated and intentionally disabled.
