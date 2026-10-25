# Upload limit deployment — 2026-10-26

The SPX production upload request limit was increased from 60 MiB to 500 MiB.

## Applied configuration

- `/etc/nginx/sites-available/default`: all three existing SPX server blocks now use `client_max_body_size 500m;`.
- `/etc/php/8.3/fpm/conf.d/99-custom.ini`: `upload_max_filesize = 500M` and `post_max_size = 512M`. Other settings were retained.
- Original files were backed up to `/root/spx-backups/upload-500mb-20261025T221334Z` before the change. The directory timestamp is UTC.
- Both configurations passed syntax validation, then PHP-FPM and Nginx were reloaded.

The sibling configuration files are reusable fragments, not complete site configurations. Include `nginx-upload-limit.conf` inside each relevant `server` block, or update that block's existing directive in place. Avoid duplicate directives. Merge the PHP values into the existing FPM custom INI file without discarding unrelated settings. Check any lower overrides in `location` blocks and FPM pool configuration.

```sh
nginx -t
php-fpm8.3 -t
# Reload only after both validation commands succeed.
systemctl reload php8.3-fpm
systemctl reload nginx
```

## Verified results

- Effective Nginx configuration: three `500m` limits.
- Effective PHP-FPM configuration: upload `500M`, POST `512M`.
- Nginx, PHP-FPM and the SPX WebSocket service remained active.
- Public API health endpoint returned HTTP 200 and application code 0.
- A local HTTPS request-header probe with `Expect: 100-continue` received `100 Continue` for 524288000 bytes, and HTTP 413 for 524288001 bytes. No request body was uploaded in this probe.
- A complete 500 MB file upload and iOS device upload were not performed as part of this configuration change.

Nginx limits the entire request body, including multipart form overhead. A file of exactly 500 MiB should use the existing chunked upload flow. The legacy single attachment endpoint retains its separate 50 MiB limit; avatar and image limits remain unchanged. Existing chunked uploads retain their 1 GiB total-file limit.
