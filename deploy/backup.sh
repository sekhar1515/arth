#!/bin/sh
# Runs inside the "backup" container: a compressed pg_dump every night, kept for 14 days in ./backups.
# Copy the backups off the server too (see deploy/DEPLOY.md).
set -u
while true; do
  stamp=$(date +%Y-%m-%d_%H%M)
  out="/backups/arth-$stamp.dump"
  if pg_dump -h db -U arth -d arth -Fc -f "$out.partial"; then
    mv "$out.partial" "$out"
    echo "backup ok: $out"
  else
    echo "backup FAILED at $stamp"
  fi
  # prune dumps older than 14 days (only files this script wrote)
  find /backups -maxdepth 1 -name 'arth-*.dump' -mtime +14 -exec rm -- {} +
  find /backups -maxdepth 1 -name 'arth-*.dump.partial' -mmin +120 -exec rm -- {} +
  sleep 86400
done
