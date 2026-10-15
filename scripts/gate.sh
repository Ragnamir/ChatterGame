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
    # Отзыв и правка сообщений отсутствуют как API (`D-008`): ни удаления, ни правки
    # содержимого. Запись подсветки заметок (`UPDATE message SET matched_note_ids`) —
    # это не правка сообщения, а досчёт материала по `D-087`, и адрес провала назван
    # по содержимому, чтобы правило E08 не ловило законную запись E12.
    if grep -rnE 'DELETE FROM message|UPDATE message SET body' src/main/java; then
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
  E10)
    # Время — единственная механика этапа (`D-011`) и единственная, у которой есть
    # выключенное состояние: проверки адресные, потому что соблазн «закрыть ход по
    # таймауту» ломает именно то свойство, ради которого этап назван (`D-060`).
    timers=$(grep -rlE '@Scheduled|ScheduledExecutorService|java\.util\.Timer' \
      src/main/java/com/chattergame/turn 2>/dev/null || true)
    if [ -n "$timers" ]; then
      fail=1
      echo "FAIL: E10 — таймеров в ходе нет: ход не закрывается сам (D-060):"
      echo "$timers"
    else
      echo "  ok: E10 — ход не закрывается по времени"
    fi
    # Решение по времени обязано отсутствовать и как вычисление, а не только как таймер.
    if grep -rqE 'isBefore\(|isAfter\(|expires' src/main/java/com/chattergame/turn 2>/dev/null; then
      fail=1
      echo "FAIL: E10 — по времени не вычисляется ничего, включая «ожидание мастера» (D-060)"
    fi
    # Лимит суммарный по чатам (`D-013`): проверка «не потрачено» и запись «потрачено»
    # под одной блокировкой строки бюджета, иначе два чата дают двойной лимит.
    if grep -rq 'FOR UPDATE' src/main/java/com/chattergame/turn/TurnRepository.java; then
      echo "  ok: E10 — бюджет хода берётся с блокировкой строки (D-013)"
    else
      fail=1
      echo "FAIL: E10 требует SELECT ... FOR UPDATE на бюджете хода (D-013)"
    fi
    # Открытый ход читается под разделяемой блокировкой: закрытие и параллельная запись
    # не должны пересечься, иначе сообщение попало бы в уже закрытый ход.
    if grep -rq 'FOR SHARE' src/main/java/com/chattergame/turn/TurnRepository.java; then
      echo "  ok: E10 — открытый ход читается под разделяемой блокировкой"
    else
      fail=1
      echo "FAIL: E10 требует FOR SHARE на открытом ходе"
    fi
    # Смена хода — мутация мира: событие, а не тихий UPDATE (D-033, D-034).
    if grep -rq 'TURN_OPENED' src/main/java/com/chattergame/game/GameMutationService.java \
      && grep -rq 'TURN_CLOSED' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E10 — открытие и закрытие хода пишут события"
    else
      fail=1
      echo "FAIL: E10 требует TURN_OPENED/TURN_CLOSED в GameMutationService (D-033)"
    fi
    # Расход живёт в пути записи сообщения: отдельный маршрут обходился бы прямой записью.
    if grep -rq 'TurnService' src/main/java/com/chattergame/message/MessageService.java; then
      echo "  ok: E10 — путь записи сообщения расходует бюджет"
    else
      fail=1
      echo "FAIL: E10 требует расхода бюджета в пути записи сообщения (PLAN E10, п. 4–6)"
    fi
    # Личный чат с мастером исключён из бюджета (`D-013`): без этой клетки лимит
    # расползается на канал, который планом прямо исключён.
    if grep -rq 'MASTER_DM' src/main/java/com/chattergame/message/MessageService.java; then
      echo "  ok: E10 — личный чат с мастером исключён из бюджета (D-013)"
    else
      fail=1
      echo "FAIL: E10 требует исключения MASTER_DM из бюджета хода (D-013)"
    fi
    # Отдельной миграции этап не несёт: `turn` и `turn_budget` объявлены в baseline
    # вместе с индексами (прецедент `D-103`). Вторая запись была бы второй правдой.
    if grep -rq 'tableName: turn' src/main/resources/db/changelog/changes/001-baseline.yaml; then
      echo "  ok: E10 — таблицы хода объявлены в baseline"
    else
      fail=1
      echo "FAIL: E10 требует turn/turn_budget в 001-baseline.yaml"
    fi
    if ls src/main/resources/db/changelog/changes/*-e10.yaml >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E10 — схема хода уже в baseline, новой миграции быть не должно"
    else
      echo "  ok: E10 — новой миграции нет"
    fi
    # Веб-слой счётчика: чтение, открытие и закрытие.
    if grep -rqE '@RestController' src/main/java/com/chattergame/web/game/TurnController.java; then
      echo "  ok: E10 — веб-слой счётчика хода на месте"
    else
      fail=1
      echo "FAIL: E10 требует эндпоинты счётчика, открытия и закрытия хода"
    fi
    ;;
  E11)
    # Участие в закрытом канале — состояние строки `chat_member`, а не присутствие в локации
    # (`D-123`): проверки адресные, потому что соблазн «хранить участника радио в presence»
    # ломает ограничение «активное присутствие одно на персонажа» из `003-e06.yaml`.
    if [ -f src/main/resources/db/changelog/changes/006-e11.yaml ]; then
      echo "  ok: E11 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E11 требует changes/006-e11.yaml (участники закрытых каналов)"
    fi
    if grep -rq 'tableName: chat_member' src/main/resources/db/changelog/changes/006-e11.yaml; then
      echo "  ok: E11 — таблица участников закрытых каналов объявлена"
    else
      fail=1
      echo "FAIL: E11 требует таблицу chat_member"
    fi
    # Миграции этапа обязаны попасть в манифест отпечатков: без записи гейт не заметит
    # правку применённого changeset (E01, `AGENTS §1`).
    if grep -q '006-e11.yaml' src/main/resources/contract/changelog-manifest.txt; then
      echo "  ok: E11 — отпечаток миграции внесён в манифест"
    else
      fail=1
      echo "FAIL: E11 требует отпечаток 006-e11.yaml в changelog-manifest.txt"
    fi
    # Личный чат заводится входом в игру, а не действием мастера (`D-037`): точка входа
    # обязана жить в пути участия, иначе канал пришлось бы открывать руками.
    if grep -rq 'openMasterDm' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E11 — личный чат с мастером открывается входом в игру (D-037)"
    else
      fail=1
      echo "FAIL: E11 требует открытия личного чата при JOINED (D-037)"
    fi
    # Финальный чат создаётся завершением, а не отдельным вызовом мастера (§7.5, `D-033`).
    if grep -rq 'openFinalChat' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E11 — чат финального обсуждения открывается переходом FINISH"
    else
      fail=1
      echo "FAIL: E11 требует открытия финального чата на FINISH (REQUIREMENTS §7.5)"
    fi
    # Радио только речь и отключение мастером (`D-039`): и то и другое обязано быть
    # проверкой пути записи, а не пожеланием в документации.
    if grep -rq 'radio_speech_only' src/main/java/com/chattergame/message/MessageAccess.java \
      && grep -rq 'chat_disabled' src/main/java/com/chattergame/message/MessageAccess.java; then
      echo "  ok: E11 — радио ограничено речью и умеет отключаться (D-039)"
    else
      fail=1
      echo "FAIL: E11 требует ограничений радиочата в assertCanPost (D-039)"
    fi
    # Шёпот без согласия — приглашение, а не участие (`D-038`): строка `chat_member`
    # обязана различать эти состояния, иначе согласие превращается в формальность.
    if grep -rq 'ChatMemberState.INVITED' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E11 — шёпот различает приглашение и согласие (D-038)"
    else
      fail=1
      echo "FAIL: E11 требует состояния INVITED у шёпота (D-038)"
    fi
    # Наблюдателю закрытых каналов нет вовсе (`D-063`), а не «меньше строк»: проверка
    # адресная, потому что частичная выдача выглядела бы работающей.
    if grep -rq 'openToObservers' src/main/java/com/chattergame/chat/ChannelQueryService.java; then
      echo "  ok: E11 — наблюдатель получает только открытый финальный чат (D-063)"
    else
      fail=1
      echo "FAIL: E11 требует разграничения открытых каналов для наблюдателя (D-063)"
    fi
    # Веб-слой каналов: создание радио, шёпот по согласию, список и отключение.
    if grep -rq '"/channels"' src/main/java/com/chattergame/web/chat/ChatController.java \
      && grep -rq '"/radios"' src/main/java/com/chattergame/web/chat/ChatController.java \
      && grep -rq '"/whispers"' src/main/java/com/chattergame/web/chat/ChatController.java; then
      echo "  ok: E11 — веб-слой каналов на месте"
    else
      fail=1
      echo "FAIL: E11 требует эндпоинты каналов, радио и шёпота"
    fi
    # Контроллеры не пишут в доменные таблицы (`D-034`): любая мутация канала идёт через
    # GameMutationService, иначе журнал перестанет быть источником истины.
    if grep -rnE 'jdbc\.(update|execute)\(' src/main/java/com/chattergame/web/chat >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E11 — контроллер каналов пишет в БД напрямую вместо GameMutationService"
    else
      echo "  ok: E11 — контроллеры каналов не пишут в доменные таблицы"
    fi
    ;;
  E12)
    # Подсветка материализуется при записи и в досчёте (`D-050`, `D-087`), а заметка —
    # мета-слой, не объект мира: проверки адресные, потому что соблазн «посчитать совпадение
    # на чтении» незаметно превращает `D-030` в фильтрацию и разводит ленту с `inbox_entry`.
    if [ -f src/main/resources/db/changelog/changes/007-e12.yaml ]; then
      echo "  ok: E12 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E12 требует changes/007-e12.yaml (superseded-ключи и уникальность снимка)"
    fi
    if grep -q '007-e12.yaml' src/main/resources/contract/changelog-manifest.txt; then
      echo "  ok: E12 — отпечаток миграции внесён в манифест"
    else
      fail=1
      echo "FAIL: E12 требует отпечаток 007-e12.yaml в changelog-manifest.txt"
    fi
    # Ключ заметки при правке помечается, а не удаляется: `note_keyword` — доменная строка,
    # и физическое удаление запрещено глобальным инвариантом (`D-010`).
    if grep -rq 'superseded' src/main/resources/db/changelog/changes/007-e12.yaml; then
      echo "  ok: E12 — ключи заметок помечаются, а не удаляются (D-010)"
    else
      fail=1
      echo "FAIL: E12 требует пометки superseded вместо удаления ключей (D-010)"
    fi
    # Совпадение ключей считается при записи (`D-050`): без вызова в пути записи сообщения
    # подсветка появлялась бы только досчётом, то есть с задержкой и не для новых реплик.
    if grep -rq 'NoteMatchingService' src/main/java/com/chattergame/message/MessageService.java; then
      echo "  ok: E12 — подсветка считается при записи сообщения (D-050)"
    else
      fail=1
      echo "FAIL: E12 требует вычисления подсветки в пути записи сообщения (D-050)"
    fi
    # Материализованная подсветка читается из строки, а не пересчитывается на чтении (`D-050`).
    if grep -rq 'matchedNoteIds' src/main/java/com/chattergame/web/message/MessageView.java; then
      echo "  ok: E12 — подсветка отдаётся клиенту готовой"
    else
      fail=1
      echo "FAIL: E12 требует matchedNoteIds в ответе ленты (D-050)"
    fi
    # Режим точного совпадения для имён в модели с первого дня (`D-051`): без него тёзкин
    # падеж ловится леммой, и заметка светит на чужого человека.
    if grep -rq 'exactMatch' src/main/java/com/chattergame/note/KeywordMatcher.java; then
      echo "  ok: E12 — флаг точного совпадения для имён на месте (D-051)"
    else
      fail=1
      echo "FAIL: E12 требует флага точного совпадения для имён (D-051)"
    fi
    # Маркер владельца отдаётся подписью цели (`D-053`, `D-127`): без неё панель тетради
    # отличала бы записи своих персонажей вторым запросом за именами.
    if grep -rq 'targetLabel' src/main/java/com/chattergame/note \
      && grep -rq 'targetLabel' src/main/java/com/chattergame/web/note \
      && grep -q 'targetLabel' src/main/resources/openapi/openapi.yaml; then
      echo "  ok: E12 — маркер владельца отдаётся подписью цели (D-053, D-127)"
    else
      fail=1
      echo "FAIL: E12 требует подписи цели (targetLabel) в модели, ответе и контракте (D-127)"
    fi
    # Досчёт уходит из транзакции запроса и оставляет видимый признак (`D-087`): признак
    # живёт в `note.matching_pending`, а запуск — в пост-коммитном обработчике, а не в теле
    # транзакции, иначе игрок ждал бы пересчёт и `matching_pending` был бы всегда снят.
    if grep -rq 'matching_pending' src/main/java/com/chattergame/note \
      && grep -rq 'afterCommit' src/main/java/com/chattergame/note/NoteService.java; then
      echo "  ok: E12 — досчёт запускается после коммита и виден по признаку (D-087)"
    else
      fail=1
      echo "FAIL: E12 требует асинхронного досчёта с признаком matching_pending (D-087)"
    fi
    # Область досчёта ограничена `inbox_entry` субъекта: пересчёт по всей игре был бы
    # миллионами строк и обошёл бы материализованную видимость (`D-030`, `D-087`).
    if grep -rq 'inbox_entry' src/main/java/com/chattergame/message/MessageRepository.java; then
      echo "  ok: E12 — область досчёта идёт по inbox_entry (D-030, D-087)"
    else
      fail=1
      echo "FAIL: E12 требует ограничения досчёта материалом inbox_entry (D-087)"
    fi
    # Сохранённое сообщение — снимок, а не живая ссылка (`D-055`): читать имя из карточки
    # значило бы менять запись задним числом при правке персонажа мастером.
    if grep -rq 'speakerSnapshot' src/main/java/com/chattergame/note/SavedMessage.java; then
      echo "  ok: E12 — сохранённое сообщение хранит снимок говорящего (D-055)"
    else
      fail=1
      echo "FAIL: E12 требует снимка текста и говорящего у сохранённого сообщения (D-055)"
    fi
    # Мастер видит заметки игроков (`D-054`), наблюдателю заметок нет вовсе (`D-063`).
    if grep -rq 'MembershipRole.MASTER' src/main/java/com/chattergame/note/NoteService.java \
      && grep -rq 'notes_not_for_observer' src/main/java/com/chattergame/note/NoteService.java; then
      echo "  ok: E12 — видимость заметок: мастеру всё, наблюдателю ничего (D-054, D-063)"
    else
      fail=1
      echo "FAIL: E12 требует видимости заметок по D-054 и D-063"
    fi
    # Веб-слой: заметки и сохранённые сообщения.
    if grep -rq '"/notes"' src/main/java/com/chattergame/web/note/NoteController.java \
      && grep -rq '"/saved-messages"' src/main/java/com/chattergame/web/note/NoteController.java; then
      echo "  ok: E12 — веб-слой тетради на месте"
    else
      fail=1
      echo "FAIL: E12 требует эндпоинты заметок и сохранённых сообщений"
    fi
    # Контроллеры не пишут в доменные таблицы (`D-034`): тетрадь живёт в сервисах.
    if grep -rnE 'jdbc\.(update|execute)\(' src/main/java/com/chattergame/web/note >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E12 — контроллер тетради пишет в БД напрямую вместо сервиса"
    else
      echo "  ok: E12 — контроллер тетради не пишет в доменные таблицы"
    fi
    ;;
  E13)
    # Участие — это разница между «позвали» и «вошёл», и проверки адресные: соблазн
    # записать приглашение прямо в `character.occupant_membership_id` стирает эту разницу,
    # и заметить её тестами занятия было бы нечем (`D-130`).
    if [ -f src/main/resources/db/changelog/changes/008-e13.yaml ]; then
      echo "  ok: E13 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E13 требует changes/008-e13.yaml (резерв слота и признак досчёта)"
    fi
    if grep -q '008-e13.yaml' src/main/resources/contract/changelog-manifest.txt; then
      echo "  ok: E13 — отпечаток миграции внесён в манифест"
    else
      fail=1
      echo "FAIL: E13 требует отпечаток 008-e13.yaml в changelog-manifest.txt"
    fi
    # Бронь — не занятость (`D-130`): уникальность держит база, потому что проверка
    # «сначала прочитай, потом запиши» проигрывает гонку двум приглашениям.
    if grep -rq 'ux_membership_reserved_character' src/main/resources/db/changelog/changes/008-e13.yaml \
      && grep -rq 'reservedCharacterId' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E13 — приглашение бронирует слот, а не занимает его (D-130)"
    else
      fail=1
      echo "FAIL: E13 требует брони слота на приглашении и её уникальности (D-130)"
    fi
    # Локация открывается наблюдателю историей, а не типом чата (`D-129`). Признак обязан
    # жить в одном месте и спрашиваться и графом, и материалом видимости: разнеси его —
    # наблюдатель увидит чат в одном ответе и не увидит в другом.
    if grep -rq 'hasPlayableHistory' src/main/java/com/chattergame/character/PresenceRepository.java \
      && grep -rq 'hasPlayableHistory' src/main/java/com/chattergame/message/InboxMaterializer.java \
      && grep -rq 'hasPlayableHistory' src/main/java/com/chattergame/chat/ChatGraphService.java; then
      echo "  ok: E13 — наблюдателю открыта история, а не все локации (D-129)"
    else
      fail=1
      echo "FAIL: E13 требует признака истории присутствия в видимости и графе (D-129)"
    fi
    # Приглашение адресуется логином существующего аккаунта (`D-131`): несуществующий
    # логин обязан отказать до записи, иначе в составе завёлся бы призрак.
    if grep -rq 'unknown_login' src/main/java/com/chattergame/web/game/MembershipController.java \
      && grep -rq 'Logins.normalize' src/main/java/com/chattergame/web/game/MembershipController.java; then
      echo "  ok: E13 — приглашение адресуется логином существующего аккаунта (D-131)"
    else
      fail=1
      echo "FAIL: E13 требует отказа unknown_login на неизвестный логин (D-131)"
    fi
    # Снятие бана возвращает право входа, а не вход (`D-133`): без отдельного события
    # и статуса LEFT запрет снимался бы молча.
    if grep -rq 'MEMBERSHIP_UNBANNED' src/main/java/com/chattergame/game/GameMutationService.java \
      && grep -rq 'MEMBERSHIP_DECLINED' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E13 — отказ и снятие бана оставляют события (D-033, D-128)"
    else
      fail=1
      echo "FAIL: E13 требует событий отказа и снятия бана (D-128)"
    fi
    # Захват слота идёт под блокировкой строки персонажа (`D-100`): иначе двое прочитали бы
    # «свободен» и оба записали себя.
    if grep -rq 'lockById' src/main/java/com/chattergame/game/GameMutationService.java \
      && grep -rq 'FOR UPDATE' src/main/java/com/chattergame/character/CharacterRepository.java; then
      echo "  ok: E13 — слот занимается под блокировкой строки (D-100)"
    else
      fail=1
      echo "FAIL: E13 требует SELECT ... FOR UPDATE при занятии персонажа (D-100)"
    fi
    # Свободный вход открыт после READY и только в публичной игре (`D-132`).
    if grep -rq 'private_game' src/main/java/com/chattergame/game/GameMutationService.java \
      && grep -rq 'game_not_ready' src/main/java/com/chattergame/game/GameMutationService.java; then
      echo "  ok: E13 — свободный вход ограничен публичной и готовой игрой (D-132)"
    else
      fail=1
      echo "FAIL: E13 требует ограничений свободного входа (D-132)"
    fi
    # Веб-слой участия: приглашение, согласие, отказ, вход, выход, бан и снятие бана.
    # Пути собираются из `@RequestMapping` класса и метода, поэтому проверяются хвосты.
    if grep -q '"/games/{gameId}/memberships"' src/main/java/com/chattergame/web/game/MembershipController.java \
      && grep -q '"/invitations"' src/main/java/com/chattergame/web/game/MembershipController.java \
      && grep -q '"/join"' src/main/java/com/chattergame/web/game/MembershipController.java \
      && grep -q '"/ban"' src/main/java/com/chattergame/web/game/MembershipController.java \
      && grep -q '"/unban"' src/main/java/com/chattergame/web/game/MembershipController.java; then
      echo "  ok: E13 — веб-слой участия на месте"
    else
      fail=1
      echo "FAIL: E13 требует эндпоинты приглашения, входа, бана и снятия бана"
    fi
    # Контракт этапа: путь без описания — это эндпоинт, которого клиент не увидит, а
    # снапшот ловит только расхождение двух файлов, а не пропущенный маршрут.
    if grep -q '/games/{id}/memberships/join:' src/main/resources/openapi/openapi.yaml \
      && grep -q '/games/{id}/characters/{characterId}/occupation:' src/main/resources/openapi/openapi.yaml \
      && grep -q '/games/{id}/characters/{characterId}/move:' src/main/resources/openapi/openapi.yaml \
      && grep -q '    Membership:' src/main/resources/openapi/openapi.yaml; then
      echo "  ok: E13 — маршруты участия описаны в OpenAPI"
    else
      fail=1
      echo "FAIL: E13 требует описания маршрутов участия и схемы Membership в openapi.yaml"
    fi
    # Контроллеры не пишут в доменные таблицы (`D-034`): участие меняется только событиями.
    if grep -rnE 'jdbc\.(update|execute)\(' src/main/java/com/chattergame/web/game >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E13 — контроллер участия пишет в БД напрямую вместо GameMutationService"
    else
      echo "  ok: E13 — контроллер участия не пишет в доменные таблицы"
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
