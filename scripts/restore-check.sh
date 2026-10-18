#!/usr/bin/env bash
# Проверка восстановления из бэкапа (`D-101`, `PLAN` O02).
#
#   ./scripts/restore-check.sh [каталог бэкапа]
#
# Без аргумента берётся последний комплект из каталога бэкапов. Скрипт восстанавливает дамп
# в отдельную пустую базу `<db>_restore_check`, сверяет число строк в доменных таблицах
# с боевой базой и удаляет проверочную базу. Боевая база не трогается: проверка не имеет
# права портить то, что проверяет.
#
# Проверочная база — не данные игры, поэтому удаляется целиком. Физическое удаление
# доменных строк запрещено (`D-010`), но это правило про игры, а не про временную копию.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

backup_root="${CHATTERGAME_BACKUP_DIR:-./backups}"
backup_dir="${1:-$(ls -1d "$backup_root"/*/ 2>/dev/null | sort | tail -1)}"

if [ -z "${backup_dir:-}" ] || [ ! -d "$backup_dir" ]; then
  echo "FAIL: каталог бэкапа не найден: ${backup_dir:-<пусто>}" >&2
  exit 2
fi

db_url="${CHATTERGAME_DB_URL:-jdbc:postgresql://localhost:5432/chattergame}"
db_user="${CHATTERGAME_DB_USER:-chattergame}"
db_password="${CHATTERGAME_DB_PASSWORD:-chattergame}"

host_port_db="${db_url#jdbc:postgresql://}"
db_host_port="${host_port_db%%/*}"
db_name="${host_port_db#*/}"
db_host="${db_host_port%%:*}"
db_port="${db_host_port#*:}"
[ "$db_port" = "$db_host" ] && db_port=5432

check_db="${db_name}_restore_check"
export PGPASSWORD="$db_password"

psql() { command psql --host="$db_host" --port="$db_port" --username="$db_user" "$@"; }

fail=0

for tool in pg_restore psql sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: нет $tool в PATH" >&2; exit 1; }
done

# Та же проверка, что в `backup.sh`: восстановление идёт клиентом серверной мажорной
# версии (`D-092`). Иначе `pg_restore` падает на настройке из дампа, и проверка копии
# краснеет не из-за копии.
client_major=$(pg_restore --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')
server_major=$(psql -d postgres -tAc "SHOW server_version_num" 2>/dev/null)
server_major=$(( ${server_major:-0} / 10000 ))
if [ "$client_major" != "$server_major" ]; then
  echo "FAIL: pg_restore $client_major, сервер $server_major — нужны одной мажорной версии (D-092)" >&2
  exit 1
fi

echo "== комплект $backup_dir"
if [ -f "$backup_dir/SHA256SUMS" ]; then
  ( cd "$backup_dir" && sha256sum --check --quiet SHA256SUMS ) \
    && echo "  ok: контрольные суммы сходятся" \
    || { echo "FAIL: контрольные суммы не сходятся"; fail=1; }
fi

dump="$backup_dir/chattergame.dump"
if [ ! -f "$dump" ]; then
  echo "FAIL: в комплекте нет chattergame.dump" >&2
  exit 1
fi

echo "== чистая база $check_db"
psql -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $check_db" >/dev/null \
  || { echo "FAIL: старая проверочная база не удалена"; exit 1; }
psql -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $check_db" >/dev/null \
  || { echo "FAIL: проверочная база не создана"; exit 1; }

cleanup() {
  psql -d postgres -c "DROP DATABASE IF EXISTS $check_db" >/dev/null 2>&1
}
trap cleanup EXIT

pg_restore --host="$db_host" --port="$db_port" --username="$db_user" \
  --dbname="$check_db" --no-owner --no-privileges --exit-on-error "$dump" \
  && echo "  ok: дамп восстановлен" \
  || { echo "FAIL: pg_restore не выполнился"; exit 1; }

# Таблицы сравнения — те, потеря которых означает потерю проекта: мир, журнал, история
# переписки и видимость. Служебные (сессии, токены) сюда не входят: они живут часами.
#
# Эталон — `counts.txt` комплекта, а не боевая база: между бэкапом и проверкой проходят
# часы, и «в боевой больше» — норма. Сверка с живой базой оставила бы проверку зелёной
# только при остановленном приложении, то есть никогда.
tables="app_user game game_event game_stream message inbox_entry chat character membership note item storage"

echo "== сверка числа строк"
if [ -f "$backup_dir/counts.txt" ]; then
  source_counts="$backup_dir/counts.txt"
  echo "  эталон: counts.txt комплекта"
else
  source_counts=""
  echo "  ВНИМАНИЕ: в комплекте нет counts.txt, сверка идёт с боевой базой;"
  echo "  при работающем приложении расхождение ожидаемо"
fi

for table in $tables; do
  if [ -n "$source_counts" ]; then
    source_count=$(awk -v t="$table" '$1 == t { print $2 }' "$source_counts")
  else
    source_count=$(psql -d "$db_name" -tAc "SELECT count(*) FROM $table" 2>/dev/null)
  fi
  restored_count=$(psql -d "$check_db" -tAc "SELECT count(*) FROM $table" 2>/dev/null)
  if [ -z "$source_count" ] || [ -z "$restored_count" ]; then
    echo "FAIL: $table — таблица не прочитана (эталон: '${source_count}', копия: '${restored_count}')"
    fail=1
  elif [ "$source_count" != "$restored_count" ]; then
    echo "FAIL: $table — в эталоне $source_count, в копии $restored_count"
    fail=1
  else
    echo "  ok: $table — $source_count"
  fi
done

# Медиа проверяются отдельно: в дампе их нет, они лежат в tar-архиве.
if [ -f "$backup_dir/media.tar.gz" ]; then
  if tar -tzf "$backup_dir/media.tar.gz" >/dev/null 2>&1; then
    echo "  ok: архив медиа читается"
  else
    echo "FAIL: архив медиа повреждён"
    fail=1
  fi
else
  echo "  ok: архива медиа нет (медиа не загружали)"
fi

if [ "$fail" -eq 0 ]; then
  echo "RESTORE CHECK PASSED: $backup_dir"
else
  echo "RESTORE CHECK FAILED: $backup_dir"
fi
exit "$fail"
