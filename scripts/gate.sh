#!/usr/bin/env bash
# Гейт этапа: проверки из PLAN §1. Каждая секция печатается отдельно, чтобы провал был
# адресным.
#
#   ./scripts/gate.sh E01
#
# Тестовая база берётся из окружения (по умолчанию — локальный Postgres из compose).
# База пересоздаётся: секция `migrations` проверяет применение changelog с нуля.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

stage="${1:-}"
case "$stage" in
  E[0-9][0-9] | F[0-9][0-9] | O[0-9][0-9]) ;;
  *)
    echo "Использование: ./scripts/gate.sh <STAGE>, например E01" >&2
    exit 2
    ;;
esac

db_url="${CHATTERGAME_GATE_DB_URL:-jdbc:postgresql://localhost:5432/chattergame_gate}"
db_user="${CHATTERGAME_DB_USER:-chattergame}"
db_password="${CHATTERGAME_DB_PASSWORD:-chattergame}"

fail=0
step() { printf '\n=== %s\n' "$1"; }
record() {
  local code=$1
  local label=$2
  if [ "$code" -ne 0 ]; then
    fail=1
    echo "FAIL: $label"
  else
    echo "  ok: $label"
  fi
}
gradle() { ./gradlew --console=plain "$@"; }

step "документация"
bash scripts/check-docs.sh
record $? "согласованность документации"

step "migrations: чистая база $db_url"
gradle -q resetDb -Pdb.url="$db_url" -Pdb.user="$db_user" -Pdb.password="$db_password"
record $? "база пересоздана"

gradle -q liquibaseUpdate -Pdb.url="$db_url" -Pdb.user="$db_user" -Pdb.password="$db_password"
record $? "changelog применён на чистой базе"

gradle -q liquibaseUpdate -Pdb.url="$db_url" -Pdb.user="$db_user" -Pdb.password="$db_password"
record $? "повторный запуск Liquibase"

gradle -q printSchema -Pdb.url="$db_url" -Pdb.user="$db_user" -Pdb.password="$db_password" \
  > build/schema-actual.txt 2>/dev/null
record $? "список таблиц прочитан"

if diff -u src/main/resources/contract/expected-tables.txt build/schema-actual.txt; then
  echo "  ok: список таблиц совпадает с контрактом"
else
  fail=1
  echo "FAIL: список таблиц разошёлся с src/main/resources/contract/expected-tables.txt"
fi

step "build: компиляция и unit-тесты"
gradle clean build -x test
record $? "сборка без интеграционных тестов"

step "unit_and_integration: реальный Postgres"
CHATTERGAME_DB_URL="$db_url" \
CHATTERGAME_DB_USER="$db_user" \
CHATTERGAME_DB_PASSWORD="$db_password" \
  gradle test
record $? "тесты этапа $stage"

step "contract: OpenAPI и схема событий"
for pair in "src/main/resources/openapi/openapi.yaml:src/test/resources/contract/openapi.snapshot.yaml" \
            "src/main/resources/contract/events.schema.json:src/test/resources/contract/events.schema.snapshot.json"; do
  current=${pair%%:*}
  snapshot=${pair#*:}
  if diff -q "$current" "$snapshot" >/dev/null; then
    echo "  ok: $current совпадает со снапшотом"
  else
    fail=1
    echo "FAIL: $current разошёлся со снапшотом $snapshot"
    diff -u "$snapshot" "$current" | head -20
  fi
done

# Пропущенные тесты — красный гейт: зелёный этап не может быть получен молчаливым skipping.
skipped=$(find build/test-results/test -name '*.xml' -exec grep -c '<skipped' {} + 2>/dev/null \
  | awk -F: '{ sum += $NF } END { print sum + 0 }')
if [ "$skipped" -gt 0 ]; then
  fail=1
  echo "FAIL: пропущено тестов: $skipped"
  find build/test-results/test -name '*.xml' -exec grep -l '<skipped' {} \; 2>/dev/null | head -5
else
  echo "  ok: пропущенных тестов нет"
fi

step "invariants и stage_checks"
# Тесты этапа со своим префиксом (`AGENTS`, протокол п. 3) — проверка общая для всех `E`,
# потому что она одна и та же и её отсутствие даёт ложный зелёный: `stage_checks` молчит
# для этапа без своего случая, тесты прошлых этапов проходят, и гейт подтверждает этап,
# которого нет. Проверено: до этой правки `./scripts/gate.sh E06` печатал `GATE E06 PASSED`
# при полном отсутствии кода и тестов E06. Для дорожек `F` и `O` правило не применяется:
# их тесты не обязаны быть JUnit-классами в `src/test/java`.
if [[ "$stage" =~ ^E[0-9][0-9]$ ]]; then
  if find src/test/java -name "${stage}*Test.java" | grep -q .; then
    echo "  ok: $stage — тесты этапа присутствуют"
  else
    fail=1
    echo "FAIL: нет тестов этапа $stage (${stage}*Test.java): этап не написан"
  fi
fi

case "$stage" in
  E01)
    violations=$(grep -rlE '@(RestController|Controller|GetMapping|PostMapping|MessageMapping)' \
      src/main/java 2>/dev/null || true)
    if [ -n "$violations" ]; then
      fail=1
      echo "FAIL: E01 — каркас без домена, веб-слой вне объёма:"
      echo "$violations"
    else
      echo "  ok: E01 — веб-слой отсутствует"
    fi
    ;;
  E02)
    if grep -rqE '@RestController' src/main/java; then
      echo "  ok: E02 — веб-слой аутентификации на месте"
    else
      fail=1
      echo "FAIL: E02 требует контроллеры регистрации, входа и /me"
    fi
    ;;
  E03)
    if grep -rqE '@RestController' src/main/java/com/chattergame/web/game; then
      echo "  ok: E03 — веб-слой игр на месте"
    else
      fail=1
      echo "FAIL: E03 требует контроллер создания и чтения игры"
    fi
    # PLAN E03: «Запрещено: WebSocket, сообщения, персонажи». Проверка адресная, чтобы
    # преждевременный реалтайм не проехал вместе с этапом.
    forbidden=$(grep -rlE '@MessageMapping|WebSocketHandler|EnableWebSocket|class Character|@Entity.*Character' \
      src/main/java 2>/dev/null || true)
    if [ -n "$forbidden" ]; then
      fail=1
      echo "FAIL: E03 — WebSocket, сообщения и персонажи вне объёма этапа:"
      echo "$forbidden"
    else
      echo "  ok: E03 — WebSocket, сообщения и персонажи отсутствуют"
    fi
    # Единственное писательское горло (D-034): прямой изменяющий SQL из контроллеров запрещён.
    if grep -rnE 'jdbc\.(update|execute)\(' src/main/java/com/chattergame/web >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E03 — контроллер пишет в БД напрямую вместо GameMutationService"
    else
      echo "  ok: E03 — контроллеры не пишут в доменные таблицы"
    fi
    ;;
  E05)
    if grep -rqE '@RestController' src/main/java/com/chattergame/web/media; then
      echo "  ok: E05 — веб-слой медиа на месте"
    else
      fail=1
      echo "FAIL: E05 требует эндпоинты загрузки и отдачи ассетов"
    fi
    # REQUIREMENTS §19: медиа отдаётся приложением, потому что только оно проверяет участие
    # на каждый запрос (D-075). Каталог статики отдал бы файл мимо этой проверки.
    static_media=$(find src/main/resources -maxdepth 2 -path '*static*' 2>/dev/null || true)
    if [ -n "$static_media" ]; then
      fail=1
      echo "FAIL: E05 — медиа не должно отдаваться веб-сервером статикой:"
      echo "$static_media"
    else
      echo "  ok: E05 — медиа отдаётся приложением, статики нет"
    fi
    ;;
  E06)
    if grep -rqE '@RestController' src/main/java/com/chattergame/web/chat; then
      echo "  ok: E06 — веб-слой персонажей на месте"
    else
      fail=1
      echo "FAIL: E06 требует эндпоинты персонажей, присутствия и скрытности"
    fi
    # PLAN E06: реальное время — предмет E09, а «мгновенное обновление» этапа идёт событиями.
    # Проверка адресная, чтобы реалтайм не проехал вместе с этапом, как и на E03.
    realtime=$(grep -rlE '@MessageMapping|WebSocketHandler|EnableWebSocket' \
      src/main/java 2>/dev/null || true)
    if [ -n "$realtime" ]; then
      fail=1
      echo "FAIL: E06 — WebSocket и сообщения вне объёма этапа:"
      echo "$realtime"
    else
      echo "  ok: E06 — реалтайм отсутствует, обновление идёт событиями"
    fi
    # Присутствие и скрытность — состояния персонажа, а не поля графа: план E06 запрещает
    # заводить под них отдельные таблицы, контракт знает `presence` (`D-103`, `expected-tables.txt`).
    if [ -f src/main/resources/db/changelog/changes/003-e06.yaml ]; then
      echo "  ok: E06 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E06 требует changes/003-e06.yaml (присутствие и его ограничения)"
    fi
    ;;
  E07)
    # Предмет этапа — гонка за строкой в Postgres, поэтому проверки адресные: REST-слой здесь
    # ничего не доказывает (и по PLAN E07 не обязателен), а вот блокировка строки, количество
    # и вместимость обязаны быть в коде, а не на словах.
    if [ -f src/main/resources/db/changelog/changes/004-e07.yaml ]; then
      echo "  ok: E07 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E07 требует changes/004-e07.yaml (тип, количество и картинка хранилища)"
    fi
    if grep -rq 'FOR UPDATE' src/main/java/com/chattergame/item/ItemRepository.java; then
      echo "  ok: E07 — предмет берётся с блокировкой строки (D-015)"
    else
      fail=1
      echo "FAIL: E07 требует SELECT ... FOR UPDATE на предмете (D-015)"
    fi
    if grep -rq 'chk_item_quantity_positive' src/main/resources/db/changelog/changes/004-e07.yaml; then
      echo "  ok: E07 — количество не меньше единицы держит база"
    else
      fail=1
      echo "FAIL: E07 требует ограничение количества на уровне схемы (D-114)"
    fi
    # Стопка отделяется новой строкой, а не обнулением исходной (`D-010`, `D-114`).
    if grep -rqE 'DELETE FROM (item|item_placement|storage)' src/main/java; then
      fail=1
      echo "FAIL: E07 — удаление предметов и хранилищ запрещено (D-010)"
    else
      echo "  ok: E07 — предметы и хранилища не удаляются"
    fi
    ;;
  E08)
    # Системное сообщение — проекция журнала, а не строка `message` (`D-031`): проверка
    # адресная, потому что соблазн «просто записать системку» убирает вторую правду
    # о событии и расходится с `inbox_entry`, который ссылается на `message_id`.
    if [ -f src/main/resources/db/changelog/changes/005-e08.yaml ]; then
      echo "  ok: E08 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E08 требует changes/005-e08.yaml (выборка активных запретов)"
    fi
    # Права проверяются на каждое событие (`D-065`): точка входа обязана быть, иначе
    # матрица живёт только в тестах.
    if grep -rq 'assertCanPost' src/main/java/com/chattergame/message; then
      echo "  ok: E08 — assertCanPost вызывается в пути записи"
    else
      fail=1
      echo "FAIL: E08 требует применения assertCanPost в пути записи сообщения (D-065)"
    fi
    # Read-путь один: лента берётся из `inbox_entry` (`D-030`, `D-086`), а не отбирается
    # по условиям на чтении. Правило проверяется и AST-тестом, здесь — адресом провала.
    if grep -rq 'inbox_entry' src/main/java/com/chattergame/message/MessageRepository.java; then
      echo "  ok: E08 — лента читается через inbox_entry (D-030)"
    else
      fail=1
      echo "FAIL: E08 требует чтения ленты через inbox_entry (D-030)"
    fi
    # Отзыв и правка сообщений отсутствуют как API (`D-008`): ни удаления, ни UPDATE.
    if grep -rnE 'DELETE FROM message|UPDATE message' src/main/java; then
      fail=1
      echo "FAIL: E08 — отзыв и правка сообщений запрещены (D-008)"
    else
      echo "  ok: E08 — сообщения не удаляются и не правятся (D-008)"
    fi
    if grep -rq '@RestController' src/main/java/com/chattergame/web/message; then
      echo "  ok: E08 — веб-слой сообщений на месте"
    else
      fail=1
      echo "FAIL: E08 требует эндпоинты сообщений, ленты и ограничений"
    fi
    ;;
  *)
    echo "  ok: stage_checks $stage — проверки живут в тестах этапа"
    ;;
esac

if [ "$fail" -eq 0 ]; then
  echo
  echo "GATE $stage PASSED"
else
  echo
  echo "GATE $stage FAILED"
fi
exit "$fail"
