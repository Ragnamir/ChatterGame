#!/usr/bin/env bash
# Ежедневный бэкап ChatterGame (`D-101`, `PLAN` O02).
#
#   ./scripts/backup.sh [каталог]
#
# Делает две вещи и ничего больше: `pg_dump` базы и tar каталога медиа. Обе части кладутся
# в один каталог с датой, поэтому восстановление берёт комплект, а не «базу от вторника
# и медиа от среды». Хранение — 14 дней: этого хватает, чтобы заметить пропажу, и это
# ровно тот срок, который записан в решении.
#
# Скрипт не удаляет ничего, кроме своих же архивов старше срока хранения: чужие файлы
# в каталоге бэкапов не его дело.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

backup_root="${1:-${CHATTERGAME_BACKUP_DIR:-./backups}}"
retention_days="${CHATTERGAME_BACKUP_RETENTION_DAYS:-14}"

db_url="${CHATTERGAME_DB_URL:-jdbc:postgresql://localhost:5432/chattergame}"
db_user="${CHATTERGAME_DB_USER:-chattergame}"
db_password="${CHATTERGAME_DB_PASSWORD:-chattergame}"
media_root="${CHATTERGAME_MEDIA_ROOT:-./data/media}"

# JDBC-строка приводится к параметрам `pg_dump`: `jdbc:postgresql://host:port/db`.
host_port_db="${db_url#jdbc:postgresql://}"
db_host_port="${host_port_db%%/*}"
db_name="${host_port_db#*/}"
db_host="${db_host_port%%:*}"
db_port="${db_host_port#*:}"
[ "$db_port" = "$db_host" ] && db_port=5432

stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$backup_root/$stamp"
mkdir -p "$target" || exit 1

fail=0

for tool in pg_dump psql sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: нет $tool в PATH" >&2; exit 1; }
done

# Версия клиента обязана совпадать с сервером (`D-092`). Старший клиент пишет в дамп
# настройки, которых сервер не знает (`SET transaction_timeout` в `pg_dump` 17 против
# сервера 16), и копия перестаёт восстанавливаться — молча, до первой проверки.
# Младший клиент отказывается работать сам. Проверка стоит здесь, а не в документации:
# узнавать об этом после аварии поздно.
client_major=$(pg_dump --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')
server_major=$(PGPASSWORD="$db_password" psql --host="$db_host" --port="$db_port" \
  --username="$db_user" -d "$db_name" -tAc "SHOW server_version_num" 2>/dev/null)
server_major=$(( ${server_major:-0} / 10000 ))
if [ "$client_major" != "$server_major" ]; then
  echo "FAIL: pg_dump $client_major, сервер $server_major — нужны одной мажорной версии (D-092)" >&2
  rmdir "$target" 2>/dev/null
  exit 1
fi

echo "== база $db_name на $db_host:$db_port"
# Формат custom (`-Fc`): сжатый и восстанавливается `pg_restore` с выбором объектов,
# а не только целиком, как plain SQL.
PGPASSWORD="$db_password" pg_dump \
  --host="$db_host" --port="$db_port" --username="$db_user" \
  --format=custom --no-owner --no-privileges \
  --file="$target/chattergame.dump" "$db_name" \
  && echo "  ok: chattergame.dump" \
  || { echo "FAIL: pg_dump не выполнен"; fail=1; }

echo "== медиа $media_root"
if [ -d "$media_root" ]; then
  tar -czf "$target/media.tar.gz" -C "$media_root" . \
    && echo "  ok: media.tar.gz" \
    || { echo "FAIL: архив медиа не собран"; fail=1; }
else
  # Пустой каталог медиа — штатное состояние новой установки, а не ошибка бэкапа.
  echo "  ok: каталога медиа нет, архив не нужен"
fi

# Снимок числа строк на момент дампа. Проверка восстановления сверяет копию с этим
# снимком, а не с боевой базой: между бэкапом и проверкой проходят часы, и «в боевой
# больше» — норма, а не повреждение копии.
tables="app_user game game_event game_stream message inbox_entry chat character membership note item storage"
counts="$target/counts.txt"
: > "$counts"
for table in $tables; do
  count=$(PGPASSWORD="$db_password" psql \
    --host="$db_host" --port="$db_port" --username="$db_user" \
    -d "$db_name" -tAc "SELECT count(*) FROM $table" 2>/dev/null)
  if [ -z "$count" ]; then
    echo "FAIL: число строк $table не прочитано"
    fail=1
  else
    printf '%s %s\n' "$table" "$count" >> "$counts"
  fi
done
[ -s "$counts" ] && echo "  ok: counts.txt" || rm -f "$counts"

# Отпечатки: по ним проверка восстановления убеждается, что взяла тот же комплект.
( cd "$target" && sha256sum ./* > SHA256SUMS 2>/dev/null ) \
  && echo "  ok: SHA256SUMS" \
  || { echo "FAIL: контрольные суммы не посчитаны"; fail=1; }

echo "== хранение: старше $retention_days дней"
find "$backup_root" -mindepth 1 -maxdepth 1 -type d -mtime "+$retention_days" -print -exec rm -rf {} + \
  || fail=1

if [ "$fail" -eq 0 ]; then
  echo "BACKUP OK: $target"
else
  echo "BACKUP FAILED: $target"
fi
exit "$fail"
