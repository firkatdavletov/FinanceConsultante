#!/bin/sh
set -eu

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"
: "${BACKUP_AGE_RECIPIENT:?BACKUP_AGE_RECIPIENT is required}"

endpoint="${AWS_ENDPOINT_URL:-${ENDPOINT:-}}"
bucket="${AWS_S3_BUCKET_NAME:-${BUCKET:-}}"
prefix="${BACKUP_PREFIX:-financeconsultante}"
retention_days="${BACKUP_RETENTION_DAYS:-14}"

: "${endpoint:?AWS_ENDPOINT_URL or ENDPOINT is required}"
: "${bucket:?AWS_S3_BUCKET_NAME or BUCKET is required}"

case "$retention_days" in
    ''|*[!0-9]*)
        echo "BACKUP_FAILED stage=configuration reason=invalid_retention" >&2
        exit 1
        ;;
esac

export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}"

stamp="$(date -u +%Y%m%d-%H%M%S)"
dump_file="/tmp/financeconsultante-${stamp}.dump"
encrypted_file="${dump_file}.age"
listing_file="/tmp/financeconsultante-${stamp}.objects"
object_key="${prefix}/$(basename "$encrypted_file")"

cleanup() {
    rm -f "$dump_file" "$encrypted_file" "$listing_file"
}
trap cleanup EXIT HUP INT TERM

echo "BACKUP_STARTED timestamp=${stamp}"
if ! pg_dump "$DATABASE_URL" --format=custom --no-owner --no-acl --file="$dump_file"; then
    echo "BACKUP_FAILED stage=pg_dump" >&2
    exit 1
fi

if ! age --recipient "$BACKUP_AGE_RECIPIENT" --output "$encrypted_file" "$dump_file"; then
    echo "BACKUP_FAILED stage=encryption" >&2
    exit 1
fi
rm -f "$dump_file"

if ! aws s3 cp "$encrypted_file" "s3://${bucket}/${object_key}" \
    --endpoint-url "$endpoint" --only-show-errors; then
    echo "BACKUP_FAILED stage=upload" >&2
    exit 1
fi
echo "BACKUP_UPLOADED key=${object_key}"

cutoff_epoch="$(date -u -d "${retention_days} days ago" +%s)"
if ! aws s3api list-objects-v2 --bucket "$bucket" --prefix "${prefix}/" \
    --endpoint-url "$endpoint" --query 'Contents[].[Key,LastModified]' --output text > "$listing_file"; then
    echo "BACKUP_FAILED stage=retention" >&2
    exit 1
fi
while read -r key modified; do
    [ -n "${key:-}" ] || continue
    [ "$key" != "None" ] || continue
    modified_epoch="$(date -u -d "$modified" +%s)"
    if [ "$modified_epoch" -lt "$cutoff_epoch" ]; then
        aws s3 rm "s3://${bucket}/${key}" --endpoint-url "$endpoint" --only-show-errors
        echo "BACKUP_EXPIRED key=${key}"
    fi
done < "$listing_file"

echo "BACKUP_COMPLETED timestamp=${stamp} retention_days=${retention_days}"
