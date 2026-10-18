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
  E14)
    # Миграция этапа: колонка пожеланий и частичный индекс просрочки — то, без чего
    # ни задача 5, ни задача 6 не выполняются на уровне схемы.
    if [ -f src/main/resources/db/changelog/changes/009-e14.yaml ]; then
      echo "  ok: E14 — миграция этапа присутствует"
    else
      fail=1
      echo "FAIL: E14 требует changes/009-e14.yaml (пожелания по отыгрышу, индекс просрочки)"
    fi
    # Одно горло для трёх источников (`D-040`): машина применения живёт в сервисе заявок,
    # а не в контроллере, и словарь операций — в домене, а не в разборе запроса.
    if grep -rq 'class RequestService' src/main/java/com/chattergame/request \
      && grep -rq 'enum ChangeOperationType' src/main/java/com/chattergame/request; then
      echo "  ok: E14 — движок change set на месте"
    else
      fail=1
      echo "FAIL: E14 требует RequestService и словарь операций change set"
    fi
    # Применение идёт мутациями GameMutationService (`D-034`), а не своим SQL, и каждая
    # операция — своей транзакцией (`D-137`).
    if grep -rq 'mutations\.' src/main/java/com/chattergame/request/RequestService.java \
      && grep -rq 'applyCharacterFieldEdit' src/main/java/com/chattergame/request/RequestService.java; then
      echo "  ok: E14 — применение идёт через GameMutationService (D-034)"
    else
      fail=1
      echo "FAIL: E14 требует применения операций через GameMutationService (D-034)"
    fi
    # Откат читает прежнее значение из журнала (`D-138`), а не из таблицы, которую меняет.
    if grep -rq 'oldValueFromJournal' src/main/java/com/chattergame/request/RequestService.java \
      && grep -rq 'findByOperationId' src/main/java/com/chattergame/journal/GameEventRepository.java; then
      echo "  ok: E14 — откат берёт старое значение из журнала (D-138)"
    else
      fail=1
      echo "FAIL: E14 требует чтения прежнего значения из журнала при откате (D-138)"
    fi
    # Срок жизни истекает сам, а проверяет его планировщик, а не зашедший автор (`D-137`).
    if grep -rq '@Scheduled' src/main/java/com/chattergame/request; then
      echo "  ok: E14 — просрочка заявок проверяется планировщиком"
    else
      fail=1
      echo "FAIL: E14 требует планировщика просрочки заявок (D-137)"
    fi
    # Заявка игрока не меняет мир: решение начинается с проверки мастера.
    if grep -rq 'requireMaster' src/main/java/com/chattergame/request/RequestService.java; then
      echo "  ok: E14 — решение по заявке проверяет мастера"
    else
      fail=1
      echo "FAIL: E14 требует проверки мастера в решении по заявке"
    fi
    # Контракт этапа: маршруты заявок и схема Membership с пожеланиями описаны в OpenAPI.
    if grep -q '/games/{id}/requests:' src/main/resources/openapi/openapi.yaml \
      && grep -q '/games/{id}/requests/{requestId}/decision:' src/main/resources/openapi/openapi.yaml \
      && grep -q 'roleplayWishes' src/main/resources/openapi/openapi.yaml; then
      echo "  ok: E14 — маршруты заявок описаны в OpenAPI"
    else
      fail=1
      echo "FAIL: E14 требует описания маршрутов заявок и пожеланий в openapi.yaml"
    fi
    # Контроллеры заявок не пишут в доменные таблицы (`D-034`).
    if grep -rnE 'jdbc\.(update|execute)\(' src/main/java/com/chattergame/web/request >/dev/null 2>&1; then
      fail=1
      echo "FAIL: E14 — контроллер заявок пишет в БД напрямую вместо GameMutationService"
    else
      echo "  ok: E14 — контроллер заявок не пишет в доменные таблицы"
    fi
    ;;
  F01)
    # `PLAN F01`: установка, вход после перезапуска, список игр, кабинет. Проверки идут по
    # собранному фронту: настройка `VitePWA` может быть верной, а артефакт — без манифеста.
    if [ ! -f frontend/package.json ]; then
      fail=1
      echo "FAIL: F01 — нет frontend/package.json"
    elif [ ! -d frontend/node_modules ]; then
      fail=1
      echo "FAIL: F01 — зависимости фронта не установлены (cd frontend && npm ci)"
    else
      echo "  ok: F01 — каркас фронта на месте"
    fi
    if grep -q '"vite-plugin-pwa"' frontend/package.json \
      && grep -q 'VitePWA' frontend/vite.config.ts; then
      echo "  ok: F01 — PWA-плагин подключён (D-090)"
    else
      fail=1
      echo "FAIL: F01 требует vite-plugin-pwa в сборке (D-090)"
    fi
    # Контур доступа: guard на маршруте и cookie-сессия, а не токен в localStorage.
    if grep -rq 'RequireAuth' frontend/src && grep -rq "credentials: 'include'" frontend/src/api; then
      echo "  ok: F01 — маршруты под guard, сессия идёт cookie (D-079)"
    else
      fail=1
      echo "FAIL: F01 требует guard маршрутов и cookie-сессии"
    fi
    # `D-081`: строки только через словарь. Проверка — часть гейта, не только npm-скрипт.
    if (cd frontend && npm run --silent check:i18n); then
      echo "  ok: F01 — кириллица только в словаре (D-081)"
    else
      fail=1
      echo "FAIL: F01 — кириллические литералы вне словаря (D-081)"
    fi
    if (cd frontend && npm run --silent check:a11y); then
      echo "  ok: F01 — разметка доступна с клавиатуры"
    else
      fail=1
      echo "FAIL: F01 — разметка нарушает доступность"
    fi
    if (cd frontend && npm test --silent); then
      echo "  ok: F01 — тесты фронта пройдены"
    else
      fail=1
      echo "FAIL: F01 — тесты фронта провалены"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: F01 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: F01 — прод-сборка не собрана"
    fi
    if (cd frontend && npm run --silent check:pwa); then
      echo "  ok: F01 — установка PWA подтверждена артефактом"
    else
      fail=1
      echo "FAIL: F01 — артефакт не устанавливается как PWA"
    fi
    ;;
  F02)
    # `PLAN F02`: игровая комната. Проверки привязаны к механизмам, которые нельзя
    # подменить «похожим» экраном: окно виртуализации, бейджи, выбор персонажа,
    # три состояния перехода, офлайн-кэш и метрика переподключения.
    if [ ! -d frontend/node_modules ]; then
      fail=1
      echo "FAIL: F02 — зависимости фронта не установлены (cd frontend && npm ci)"
    else
      echo "  ok: F02 — зависимости фронта на месте"
    fi
    if [ -f frontend/tests/F02Room.test.ts ] \
      && grep -q 'renderWindow' frontend/src/components/VirtualFeed.tsx \
      && grep -q 'renderWindow' frontend/src/lib/virtual.ts; then
      echo "  ok: F02 — виртуализация ленты через окно рендера (D-082)"
    else
      fail=1
      echo "FAIL: F02 требует виртуализации ленты: renderWindow в VirtualFeed (D-082)"
    fi
    if grep -q 'unread' frontend/src/lib/room.ts \
      && grep -q 'onKeyDown' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'aria-current' frontend/src/pages/GameRoomPage.tsx; then
      echo "  ok: F02 — бейджи непрочитанного и клавиатурная навигация (D-085)"
    else
      fail=1
      echo "FAIL: F02 требует бейджей непрочитанного и клавиатурной навигации (D-085)"
    fi
    if grep -q 'loadFeed' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'saveFeed' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'ReconnectClock' frontend/src/pages/GameRoomPage.tsx; then
      echo "  ok: F02 — офлайн-кэш ленты и метрика переподключения"
    else
      fail=1
      echo "FAIL: F02 требует офлайн-чтения кэша и метрики времени до первого сообщения"
    fi
    if (cd frontend && node --test tests/F02Room.test.ts); then
      echo "  ok: F02 — тесты комнаты пройдены"
    else
      fail=1
      echo "FAIL: F02 — тесты комнаты провалены"
    fi
    if (cd frontend && npm run --silent check:i18n && npm run --silent check:a11y); then
      echo "  ok: F02 — словарь и доступность"
    else
      fail=1
      echo "FAIL: F02 — нарушены словарь или доступность"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: F02 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: F02 — прод-сборка не собрана"
    fi
    ;;
  F03)
    # `PLAN F03`: ход и ограничения в интерфейсе. Проверки привязаны к тому, что нельзя
    # подменить «похожим» экраном: причина отказа до отправки, бюджет по персонажу и
    # срок ограничения, а не только его текст.
    if [ ! -d frontend/node_modules ]; then
      fail=1
      echo "FAIL: F03 — зависимости фронта не установлены (cd frontend && npm ci)"
    else
      echo "  ok: F03 — зависимости фронта на месте"
    fi
    if [ -f frontend/src/lib/turn.ts ] \
      && grep -q 'blockedKindReasons' frontend/src/lib/turn.ts \
      && grep -q 'blockedKindReasons' frontend/src/pages/GameRoomPage.tsx; then
      echo "  ok: F03 — причины блокировки видов сообщений вынесены в проверяемый модуль"
    else
      fail=1
      echo "FAIL: F03 требует blockedKindReasons в frontend/src/lib/turn.ts"
    fi
    if grep -q 'turn.speechSpent' frontend/src/components/TurnPanel.tsx \
      && grep -q 'turn.actionSpent' frontend/src/components/TurnPanel.tsx \
      && grep -q 'turn.closed' frontend/src/components/TurnPanel.tsx; then
      echo "  ok: F03 — бюджет персонажа и «ход не открыт» видны до отправки"
    else
      fail=1
      echo "FAIL: F03 требует индикатор бюджета и состояния хода в TurnPanel"
    fi
    if grep -q 'formatRestrictionExpiry' frontend/src/components/RestrictionNotice.tsx \
      && grep -q 'formatRestrictionExpiry' frontend/src/components/MasterPanel.tsx \
      && grep -q 'expiresAt' frontend/src/components/MasterPanel.tsx; then
      echo "  ok: F03 — ограничение показано с причиной и сроком (D-064)"
    else
      fail=1
      echo "FAIL: F03 требует показа ограничения с причиной и сроком"
    fi
    if [ -f frontend/tests/F03Turn.test.ts ] \
      && (cd frontend && node --test tests/F03Turn.test.ts); then
      echo "  ok: F03 — тесты хода и ограничений пройдены"
    else
      fail=1
      echo "FAIL: F03 — тесты хода и ограничений провалены"
    fi
    if (cd frontend && npm run --silent check:i18n && npm run --silent check:a11y); then
      echo "  ok: F03 — словарь и доступность"
    else
      fail=1
      echo "FAIL: F03 — нарушены словарь или доступность"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: F03 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: F03 — прод-сборка не собрана"
    fi
    ;;
  F04)
    # `PLAN F04`: личные каналы и тетрадь. Проверки привязаны к решениям, которые нельзя
    # подменить «похожим» экраном: подсветка только по `matched_note_ids` (`D-052`),
    # вкладки тетради с разными правами (`D-063`), шёпот с приглашением (`D-038`).
    if [ ! -d frontend/node_modules ]; then
      fail=1
      echo "FAIL: F04 — зависимости фронта не установлены (cd frontend && npm ci)"
    else
      echo "  ok: F04 — зависимости фронта на месте"
    fi
    if [ -f frontend/src/lib/notebook.ts ] \
      && grep -q 'matchedNoteIds' frontend/src/lib/notebook.ts \
      && ! grep -qE 'body\.(includes|indexOf|search|match)' frontend/src/lib/notebook.ts; then
      echo "  ok: F04 — подсветка считается по matched_note_ids, а не поиском по тексту (D-052)"
    else
      fail=1
      echo "FAIL: F04 требует подсветку только по matchedNoteIds в frontend/src/lib/notebook.ts"
    fi
    if grep -q 'notebookTabs' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'OBSERVER' frontend/src/lib/notebook.ts \
      && grep -q 'canWriteNotes' frontend/src/components/Notebook.tsx; then
      echo "  ok: F04 — вкладки тетради разведены по роли: наблюдатель без заметок (D-063)"
    else
      fail=1
      echo "FAIL: F04 требует вкладки тетради и запрет заметок наблюдателю"
    fi
    if grep -q 'noteById' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'noteTitles' frontend/src/pages/GameRoomPage.tsx \
      && grep -q 'notesForMessage' frontend/src/components/VirtualFeed.tsx; then
      echo "  ok: F04 — тетрадь живёт на игре и участии, подсветка доходит до ленты"
    else
      fail=1
      echo "FAIL: F04 требует тетрадь на игру/участие и подсветку в ленте"
    fi
    if grep -q 'channel.accept' frontend/src/components/ChannelActions.tsx \
      && grep -q 'channel.createWhisper' frontend/src/components/ChannelActions.tsx \
      && grep -q 'channel.createRadio' frontend/src/components/ChannelActions.tsx; then
      echo "  ok: F04 — шёпот с приглашением и радиочаты в интерфейсе (D-038, D-039)"
    else
      fail=1
      echo "FAIL: F04 требует создания шёпота с приглашением и радиочата"
    fi
    if [ -f frontend/tests/F04Notebook.test.ts ] \
      && (cd frontend && node --test tests/F04Notebook.test.ts); then
      echo "  ok: F04 — тесты тетради и подсветки пройдены"
    else
      fail=1
      echo "FAIL: F04 — тесты тетради и подсветки провалены"
    fi
    if (cd frontend && npm run --silent check:i18n && npm run --silent check:a11y); then
      echo "  ok: F04 — словарь и доступность"
    else
      fail=1
      echo "FAIL: F04 — нарушены словарь или доступность"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: F04 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: F04 — прод-сборка не собрана"
    fi
    ;;
  F05)
    # `PLAN F05`: заявки и панель мастера для LLM. Проверки привязаны к решениям, которые
    # нельзя подменить «похожим» экраном: дифф по операциям, частичное одобрение, различие
    # классов отказа модели и скрытие опций без ключа (`D-042`, `D-043`).
    if [ ! -d frontend/node_modules ]; then
      fail=1
      echo "FAIL: F05 — зависимости фронта не установлены (cd frontend && npm ci)"
    else
      echo "  ok: F05 — зависимости фронта на месте"
    fi
    if [ -f src/main/resources/db/changelog/changes/010-f05.yaml ] \
      && grep -q '010-f05.yaml' src/main/resources/db/changelog/db.changelog-master.yaml; then
      echo "  ok: F05 — миграция этапа подключена"
    else
      fail=1
      echo "FAIL: F05 требует changes/010-f05.yaml в changelog"
    fi
    # Вызов модели не обёрнут транзакцией (`D-044`): иначе десятки секунд ожидания выбирают
    # пул Hikari до нуля.
    if grep -rq 'class LlmProposalService' src/main/java/com/chattergame/llm \
      && ! grep -q '@Transactional' src/main/java/com/chattergame/llm/LlmProposalService.java \
      && grep -rq 'LlmCallExecutor' src/main/java/com/chattergame/llm; then
      echo "  ok: F05 — вызов модели вне транзакции и на ограниченном пуле (D-044)"
    else
      fail=1
      echo "FAIL: F05 требует вызова модели вне транзакции (D-044)"
    fi
    # Классы отказа различимы машинным кодом, а не общим текстом (`D-043`).
    if grep -q 'llm_rate_limited' src/main/java/com/chattergame/llm/LlmErrorKind.java \
      && grep -q 'llm_timeout' src/main/java/com/chattergame/llm/LlmErrorKind.java \
      && grep -q 'llm_invalid_response' src/main/java/com/chattergame/llm/LlmErrorKind.java; then
      echo "  ok: F05 — классы отказа модели различимы (D-043)"
    else
      fail=1
      echo "FAIL: F05 требует различимых кодов отказа модели (D-043)"
    fi
    # Без активного ключа опции скрыты целиком, а не активны с ошибкой (`D-042`).
    if grep -q 'llm.unavailable' frontend/src/components/LlmPanel.tsx \
      && grep -q 'available' frontend/src/components/LlmPanel.tsx; then
      echo "  ok: F05 — панель модели скрыта без ключа (D-042)"
    else
      fail=1
      echo "FAIL: F05 требует скрытия опций модели без ключа (D-042)"
    fi
    # Частичное одобрение по операциям: решение передаёт номера, а не «одобрить всё».
    if grep -q 'pendingOrdinals' frontend/src/components/RequestPanel.tsx \
      && grep -q 'onDecide' frontend/src/components/RequestPanel.tsx \
      && grep -q 'diffOf' frontend/src/components/RequestPanel.tsx; then
      echo "  ok: F05 — очередь заявок с диффом и выбором операций"
    else
      fail=1
      echo "FAIL: F05 требует очереди заявок с диффом и выбором операций"
    fi
    # Обратимость — свойство вида операции, а не строка причины в представлении.
    if grep -q 'reversible' src/main/java/com/chattergame/web/request/RequestView.java \
      && grep -q 'reversible' src/main/resources/openapi/openapi.yaml; then
      echo "  ok: F05 — клиент узнаёт необратимость до отката (D-010)"
    else
      fail=1
      echo "FAIL: F05 требует признака reversible в контракте операции"
    fi
    if [ -f frontend/tests/F05Requests.test.ts ] \
      && (cd frontend && node --test tests/F05Requests.test.ts); then
      echo "  ok: F05 — тесты заявок и диффа пройдены"
    else
      fail=1
      echo "FAIL: F05 — тесты заявок и диффа провалены"
    fi
    if (cd frontend && npm run --silent check:i18n && npm run --silent check:a11y); then
      echo "  ok: F05 — словарь и доступность"
    else
      fail=1
      echo "FAIL: F05 — нарушены словарь или доступность"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: F05 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: F05 — прод-сборка не собрана"
    fi
    ;;

  O01)
    # `PLAN O01`: почта — подтверждение и сброс. Проверки привязаны к `D-079` и
    # `D-141…D-144`: хеш вместо токена, привязка ссылки к адресу, одинаковый ответ,
    # падение прода без SMTP и чек-лист DNS.
    if [ -f src/main/resources/db/changelog/changes/011-o01.yaml ] \
      && grep -q '011-o01.yaml' src/main/resources/db/changelog/db.changelog-master.yaml; then
      echo "  ok: O01 — миграция токена подтверждения подключена"
    else
      fail=1
      echo "FAIL: O01 требует changes/011-o01.yaml в changelog"
    fi
    if grep -q '011-o01.yaml' src/main/resources/contract/changelog-manifest.txt; then
      echo "  ok: O01 — отпечаток миграции внесён в манифест"
    else
      fail=1
      echo "FAIL: O01 требует отпечаток 011-o01.yaml в changelog-manifest.txt"
    fi
    # В БД лежит хеш, а не токен (`D-079`, `D-141`): иначе ссылка из письма читается из дампа.
    if grep -q 'token_hash' src/main/java/com/chattergame/user/EmailVerificationTokenRepository.java \
      && grep -q 'Tokens.hash' src/main/java/com/chattergame/user/EmailVerificationTokens.java; then
      echo "  ok: O01 — токен подтверждения хранится хешем (D-141)"
    else
      fail=1
      echo "FAIL: O01 требует хеш токена подтверждения (D-141)"
    fi
    # Ссылка адресована конкретному адресу: письмо на прежний адрес не подтверждает новый.
    if grep -q 'email_verification_token' src/main/resources/db/changelog/changes/011-o01.yaml \
      && grep -q 'stored.email().equals(user.email())' src/main/java/com/chattergame/user/EmailVerificationTokens.java; then
      echo "  ok: O01 — подтверждение привязано к адресу (D-141)"
    else
      fail=1
      echo "FAIL: O01 требует привязки подтверждения к адресу (D-141)"
    fi
    # Сброс отвечает одинаково на существующий и несуществующий адрес (`D-079`).
    if grep -q 'findByEmail(email)' src/main/java/com/chattergame/user/PasswordResetService.java \
      && ! grep -q 'notFound' src/main/java/com/chattergame/user/PasswordResetService.java; then
      echo "  ok: O01 — сброс не различает адреса по ответу (D-079)"
    else
      fail=1
      echo "FAIL: O01 требует анти-enumeration в сбросе пароля (D-079)"
    fi
    # Отправка идёт через один узел со счётчиками, без адреса и токена в логах (`D-143`).
    if grep -q 'class MailDelivery' src/main/java/com/chattergame/user/MailDelivery.java \
      && grep -q 'stats()' src/main/java/com/chattergame/user/MailDelivery.java \
      && ! grep -q 'log\.info(.*email' src/main/java/com/chattergame/user/MailDelivery.java; then
      echo "  ok: O01 — доставка считается по виду письма (D-143)"
    else
      fail=1
      echo "FAIL: O01 требует счётчиков доставки без адресов в логах (D-143)"
    fi
    # Прод без SMTP и без абсолютной ссылки не стартует (`D-079`, `D-142`).
    if grep -q 'spring.mail.host' src/main/java/com/chattergame/user/MailConfiguration.java \
      && grep -q 'chattergame.mail.link-base' src/main/java/com/chattergame/user/MailConfiguration.java; then
      echo "  ok: O01 — прод требует SMTP и публичный адрес (D-079, D-142)"
    else
      fail=1
      echo "FAIL: O01 требует падения прода без SMTP и публичного адреса"
    fi
    # Чек-лист DNS и порядок приёмки живут в документации, а не в голове оператора.
    if grep -q 'v=spf1' docs/OPS/email.md \
      && grep -q '_domainkey' docs/OPS/email.md \
      && grep -q 'v=DMARC1' docs/OPS/email.md \
      && grep -q 'mail-tester.com' docs/OPS/email.md; then
      echo "  ok: O01 — чек-лист SPF/DKIM/DMARC записан"
    else
      fail=1
      echo "FAIL: O01 требует чек-лист SPF/DKIM/DMARC в docs/OPS/email.md"
    fi
    if [ -f frontend/tests/O01AuthMail.test.ts ] \
      && (cd frontend && node --test tests/O01AuthMail.test.ts); then
      echo "  ok: O01 — тесты форм входа и сброса пройдены"
    else
      fail=1
      echo "FAIL: O01 — тесты форм входа и сброса провалены"
    fi
    if (cd frontend && npm run --silent check:i18n && npm run --silent check:a11y); then
      echo "  ok: O01 — словарь и доступность"
    else
      fail=1
      echo "FAIL: O01 — нарушены словарь или доступность"
    fi
    if (cd frontend && npm run --silent build); then
      echo "  ok: O01 — прод-сборка собрана"
    else
      fail=1
      echo "FAIL: O01 — прод-сборка не собрана"
    fi
    ;;

  O02)
    # `PLAN O02`: экспорт, архив и эксплуатационные операции. Проверки адресные: этап
    # принимается не по «файл существует», а по свойствам, которые нельзя подменить
    # похожим кодом — выгрузка только мастеру, снимок со всеми чатами, архив без удаления,
    # служебный контур за токеном.
    if [ -f src/main/java/com/chattergame/export/GameExportService.java ] \
      && [ -f src/main/java/com/chattergame/export/GameMarkdownRenderer.java ] \
      && [ -f src/main/java/com/chattergame/export/GameSnapshotRestorer.java ]; then
      echo "  ok: O02 — выгрузка и восстановление снимка на месте"
    else
      fail=1
      echo "FAIL: O02 требует экспорт, Markdown-рендер и восстановление снимка (D-061, D-146)"
    fi
    # Экспорт только мастеру (`D-021`): участник не должен получать закрытые истории
    # и личные диалоги, даже если у него есть право читать свой чат.
    if grep -q 'export_master_only' src/main/java/com/chattergame/export/GameExportService.java; then
      echo "  ok: O02 — выгрузка закрыта мастером (D-021)"
    else
      fail=1
      echo "FAIL: O02 требует отказа export_master_only в выгрузке (D-021)"
    fi
    # Снимок включает журнал и материализацию видимости: без них «восстановилось» означало бы
    # только текст, а resume и рассылка остались бы пустыми (`D-033`, `D-146`).
    if grep -q '"game_event"' src/main/java/com/chattergame/export/SnapshotTables.java \
      && grep -q '"inbox_entry"' src/main/java/com/chattergame/export/SnapshotTables.java; then
      echo "  ok: O02 — снимок несёт журнал и inbox_entry (D-146)"
    else
      fail=1
      echo "FAIL: O02 требует game_event и inbox_entry в снимке (D-146)"
    fi
    # Архив — единственный путь снять игру с глаз; удаления нет (`D-010`).
    if grep -q 'archiveGame' src/main/java/com/chattergame/web/game/GameController.java \
      && ! grep -rqE 'DELETE FROM game\b' src/main/java; then
      echo "  ok: O02 — игра архивируется, а не удаляется (D-010)"
    else
      fail=1
      echo "FAIL: O02 требует архивации без удаления игры (D-010)"
    fi
    # Служебный контур закрыт токеном из окружения и выключается пустым значением (`D-147`).
    if grep -q 'X-Ops-Token' src/main/java/com/chattergame/web/ops/OpsController.java \
      && grep -q 'MessageDigest.isEqual' src/main/java/com/chattergame/web/ops/OpsController.java \
      && grep -q 'CHATTERGAME_OPS_TOKEN' src/main/resources/application.yaml; then
      echo "  ok: O02 — /ops за токеном из окружения (D-147)"
    else
      fail=1
      echo "FAIL: O02 требует служебного токена /ops из окружения (D-147)"
    fi
    # Бэкап и проверка восстановления — скрипты и документ, а не устная традиция (`D-101`, `D-145`).
    if [ -x scripts/backup.sh ] && [ -x scripts/restore-check.sh ] \
      && grep -q 'pg_dump' scripts/backup.sh \
      && grep -q 'pg_restore' scripts/restore-check.sh \
      && grep -q 'CHATTERGAME_BACKUP_RETENTION_DAYS' scripts/backup.sh \
      && grep -q 'restore-check.sh' docs/OPS/backup.md; then
      echo "  ok: O02 — бэкап, срок хранения и проверка восстановления (D-101, D-145)"
    else
      fail=1
      echo "FAIL: O02 требует scripts/backup.sh, scripts/restore-check.sh и docs/OPS/backup.md"
    fi
    if find src/test/java -name 'O02*Test.java' | grep -q .; then
      echo "  ok: O02 — тесты этапа присутствуют"
    else
      fail=1
      echo "FAIL: O02 требует тестов этапа (O02*Test.java)"
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
