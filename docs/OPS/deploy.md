# Развёртывание: dev-контур и хостинг

Документ описывает, как поднять ChatterGame локально и как выложить его на хостинг:
что установить, какие параметры задать, через что они передаются и как проверить, что
всё дошло. Решения, на которые опирается текст: `D-072` (один инстанс ведёт много игр),
`D-079` (прод без SMTP не стартует), `D-092` (PostgreSQL 16), `D-093` (ключ LLM только
из окружения), `D-142` (абсолютные ссылки в письмах), `D-144` (SMTP-креденшалы только
из окружения), `D-147` (служебный контур `/ops` за токеном).

Почта и DNS описаны отдельно в `docs/OPS/email.md`, бэкап и восстановление —
в `docs/OPS/backup.md`. Здесь только запуск и параметры.

## 1. Из чего состоит продукт

```
браузер
  │  https://game.example.org
  ▼
nginx ── статика SPA (frontend/dist) ────────────────────────┐
  │                                                          │
  ├── /auth, /me, /games, /ops ──► ChatterGame :8080 ──► PostgreSQL 16
  │        (включая /games/{id}/ws)          │
  │                                          ├──► каталог медиа (диск)
  │                                          ├──► SMTP (письма)
  │                                          └──► LLM по ключу мастера
  └── /games/{id} при Accept: text/html ──► index.html (оболочка SPA)
```

Три вещи, которые нельзя менять произвольно:

- **Один origin.** Фронт и API живут на одном домене. Сессия — `HttpOnly` cookie
  (`SameSite=Lax`), сокет — handshake по cookie. Разные домены потребовали бы CORS
  с credentials и отдельной настройки Origin, а `HttpOnly` cookie всё равно не читается
  из JS. В разработке тот же контур даёт proxy Vite.
- **Один инстанс приложения.** `D-072`: один процесс ведёт произвольное число игр.
  Горизонтальное масштабирование без `chattergame.realtime.fanout=postgres` и привязки
  игры к инстансу потеряет сообщения.
- **Медиа отдаёт приложение, а не nginx.** Проверка участия стоит на каждом запросе
  (`D-075`), поэтому `alias` на каталог медиа в nginx был бы обходом прав.

Порты:

| Порт | Кто слушает | Доступ |
|---|---|---|
| 80, 443 | nginx | из интернета |
| 8080 | ChatterGame | только loopback (или внутренняя сеть контейнеров) |
| 5432 | PostgreSQL | только loopback (или внутренняя сеть контейнеров) |

## 2. Что установить

| Компонент | Версия | Зачем |
|---|---|---|
| JDK | 21 (Temurin или OpenJDK) | сборка и запуск бэкенда |
| Node.js + npm | 20+ и 10+ | сборка фронта (Vite 5) |
| Docker + Compose v2 | 24+ | локальный Postgres, тесты на Testcontainers |
| PostgreSQL | 16, сервер и клиент | база; клиент `pg_dump`/`pg_restore` строго 16 (`D-092`) |
| nginx | 1.24+ | статика SPA, TLS, reverse proxy, WebSocket |
| certbot | — | сертификат Let's Encrypt |

Клиент PostgreSQL старшей мажорной версии писать дампы нельзя: `pg_dump` 17 против
сервера 16 добавляет `SET transaction_timeout`, и копия перестаёт восстанавливаться.
`scripts/backup.sh` проверяет совпадение версий и падает, если они разошлись.

JVM: для 4–10 игроков на игру и нескольких игр достаточно 512 МиБ–1 ГиБ heap.
Ориентир для небольшого узла — `-XX:MaxRAMPercentage=75` при 1–2 ГиБ RAM у процесса.

## 3. Параметры: какие нужны и через что передаются

### 3.1. Три способа передачи

| Способ | Где применяется | Пример |
|---|---|---|
| Переменные окружения | основной способ для прода и dev | `CHATTERGAME_DB_PASSWORD=...` |
| Аргументы командной строки | разовый запуск, отладка | `--spring.profiles.active=prod` |
| `application-{profile}.yaml` | только несекретные умолчания | `src/main/resources/application-prod.yaml` |

Порядок старшинства: аргументы командной строки → переменные окружения →
`application-prod.yaml` → `application.yaml`. Секреты в YAML не хранятся: только
окружение (`D-079`, `D-093`, `D-144`).

Имена переменных выводятся из ключа Spring по правилам relaxed binding: точки заменяются
подчёркиваниями, всё приводится к верхнему регистру.

```
chattergame.media.max-file-bytes  →  CHATTERGAME_MEDIA_MAX_FILE_BYTES
spring.mail.host                  →  SPRING_MAIL_HOST
chattergame.session.cookie-secure →  CHATTERGAME_SESSION_COOKIE_SECURE
```

Списки задаются одной строкой через запятую — значение из окружения заменяет умолчание
целиком:

```
CHATTERGAME_REALTIME_ALLOWED_ORIGINS=https://game.example.org
```

Для вложенных структур, которым тесно в переменной, остаётся
`SPRING_APPLICATION_JSON`:

```bash
SPRING_APPLICATION_JSON='{"chattergame":{"realtime":{"allowed-origins":["https://game.example.org"]}}}'
```

Куда класть переменные:

| Контур | Место | Права |
|---|---|---|
| dev | `.env` рядом с `docker-compose.yml` (читает compose) | файл в `.gitignore` |
| VPS + systemd | `/etc/chattergame/chattergame.env`, `EnvironmentFile=` | `600 root:root` |
| Docker | `env_file:` в compose или секреты платформы | не в образе и не в репозитории |
| PaaS | панель Environment / Secrets | значения не дублировать в репозитории |

### 3.2. Минимальный набор для прода

Без этих значений прод либо не стартует, либо работает неправильно:

```bash
SPRING_PROFILES_ACTIVE=prod
CHATTERGAME_DB_URL=jdbc:postgresql://127.0.0.1:5432/chattergame
CHATTERGAME_DB_USER=chattergame
CHATTERGAME_DB_PASSWORD=<пароль>
CHATTERGAME_PUBLIC_URL=https://game.example.org
CHATTERGAME_SMTP_HOST=smtp.example.org
CHATTERGAME_MAIL_FROM=no-reply@example.org
CHATTERGAME_REALTIME_ALLOWED_ORIGINS=https://game.example.org
CHATTERGAME_OPS_TOKEN=<токен>
CHATTERGAME_MEDIA_ROOT=/var/lib/chattergame/media
```

`CHATTERGAME_LLM_SECRET_KEY` нужен, если мастер будет пользоваться LLM-опциями. Без него
приложение стартует, но сохранение ключа LLM вернёт ошибку.

### 3.3. Полная таблица

#### Ядро и профиль

| Переменная | Ключ | По умолчанию | Комментарий |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | `dev` | В проде обязательно `prod`. Иначе включатся автоподтверждение почты, письма в лог, cookie без `Secure` и локальные Origin |
| `SERVER_PORT` | `server.port` | `8080` | Порт приложения |
| `SERVER_ADDRESS` | `server.address` | все интерфейсы | В проде `127.0.0.1`: наружу смотрит только nginx |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` | `framework` в `prod` | Чтобы `getRemoteAddr()` видел клиента, а не nginx |
| `JAVA_TOOL_OPTIONS` | — | — | JVM-флаги, если запуск не через свой `ExecStart` |

#### База данных

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_DB_URL` | `jdbc:postgresql://localhost:5432/chattergame` | Именно JDBC-строка, не `postgres://` |
| `CHATTERGAME_DB_USER` | `chattergame` | Роль должна иметь право создавать таблицы: Liquibase применяет миграции при старте |
| `CHATTERGAME_DB_PASSWORD` | `chattergame` | Секрет; в dev допустимо умолчание, в проде — только из окружения |

#### Почта

| Переменная | Обязательна в проде | По умолчанию | Комментарий |
|---|---|---|---|
| `CHATTERGAME_SMTP_HOST` | да | — | Прод падает без него (`D-079`) |
| `CHATTERGAME_SMTP_PORT` | нет | `587` | Submission со STARTTLS |
| `CHATTERGAME_SMTP_USER` | нет | — | Пусто — релей без auth (`D-144`) |
| `CHATTERGAME_SMTP_PASSWORD` | нет | — | Пароль приложения, не пароль от ящика |
| `CHATTERGAME_SMTP_STARTTLS` | нет | `true` | `false` только для внутреннего релея |
| `CHATTERGAME_MAIL_FROM` | да | — | Адрес отправителя |
| `CHATTERGAME_PUBLIC_URL` | да | — | Абсолютная база ссылок в письмах (`D-142`), без завершающего `/` |

В `dev` почта не нужна: `chattergame.mail.auto-verify-email=true`, адрес подтверждается
при регистрации, а письма пишутся в лог.

#### Реалтайм

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_REALTIME_ALLOWED_ORIGINS` | в dev — `http://localhost:5173,http://localhost:8080`; в prod — пусто | Точное совпадение схемы и хоста, через запятую. Пусто означает «сокет не разрешён ниоткуда» (`D-073`), а не «всем» |
| `CHATTERGAME_REALTIME_HEARTBEAT_INTERVAL` | `20s` | Период сердцебиения |
| `CHATTERGAME_REALTIME_IDLE_TIMEOUT` | `60s` | Молчание, после которого сокет закрывается |
| `CHATTERGAME_REALTIME_FANOUT` | не задано (`in-jvm`) | `postgres` включает `LISTEN/NOTIFY` для нескольких инстансов (`D-084`) |

Оригин пишется без пути и без завершающего `/`: `https://game.example.org`, а не
`https://game.example.org/` и не `https://game.example.org/games`.

#### Безопасность и служебный контур

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_SESSION_COOKIE_SECURE` | `false`; в `prod` — `true` | В проде не выключать: cookie должна уходить только по https |
| `CHATTERGAME_OPS_TOKEN` | пусто | Пусто выключает `/ops` (`404`). Сравнение постоянное по времени (`D-147`) |
| `CHATTERGAME_LLM_SECRET_KEY` | пусто | Base64 ровно 32 байта: AES-GCM для ключей LLM (`D-093`). Смена ключа делает сохранённые ключи нерасшифровываемыми |

#### Медиа

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_MEDIA_ROOT` | `build/media` | В проде — постоянный каталог, иначе картинки исчезнут при пересборке |
| `CHATTERGAME_MEDIA_MAX_FILE_BYTES` | `5242880` (5 МиБ) | Потолок одного файла; та же переменная задаёт `spring.servlet.multipart.max-file-size` |
| `CHATTERGAME_MEDIA_MAX_REQUEST_BYTES` | `6291456` (6 МиБ) | Потолок multipart-запроса; поднимать вместе с предыдущим |
| `CHATTERGAME_MEDIA_MAX_GAME_BYTES` | `209715200` (200 МиБ) | Квота медиа на игру |
| `CHATTERGAME_MEDIA_MAX_SIDE_PIXELS` | `4096` | Потолок стороны изображения |
| `CHATTERGAME_MEDIA_MAX_CONCURRENT_UPLOADS` | `2` | Одновременные загрузки одного пользователя |
| `CHATTERGAME_MEDIA_ORPHAN_CRON` | `0 0 4 * * *` | Расписание орфан-сборки |
| `CHATTERGAME_MEDIA_ORPHAN_GRACE_HOURS` | `24` | Возраст, после которого ассет считается осиротевшим |
| `CHATTERGAME_MEDIA_ORPHAN_PURGE` | `false` | Оставить `false`, пока сухой прогон не проверен (`D-104`) |

#### LLM и заявки

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_LLM_TIMEOUT` | `PT60S` | Таймаут вызова модели |
| `CHATTERGAME_LLM_THREADS` | `2` | Потоки пула вызовов |
| `CHATTERGAME_LLM_QUEUE` | `16` | Очередь пула |
| `CHATTERGAME_REQUESTS_TTL` | `P14D` | Срок жизни заявки |

Адрес провайдера, модель и API-ключ задаёт мастер в интерфейсе: они лежат в
`llm_credential`, а не в окружении (`D-092`, `D-093`). Серверный параметр только один —
ключ шифрования.

#### Скрипты бэкапа

| Переменная | По умолчанию | Комментарий |
|---|---|---|
| `CHATTERGAME_BACKUP_DIR` | `./backups` | Куда складывать комплекты |
| `CHATTERGAME_BACKUP_RETENTION_DAYS` | `14` | Срок хранения (`D-101`) |
| `CHATTERGAME_MEDIA_ROOT` | `./data/media` | Тот же каталог, что у приложения |
| `CHATTERGAME_DB_URL`, `_USER`, `_PASSWORD` | локальные значения | Те же, что у приложения |

### 3.4. Секреты

Секреты — пароль БД, пароль SMTP, `CHATTERGAME_OPS_TOKEN`, `CHATTERGAME_LLM_SECRET_KEY`.
Они не попадают в репозиторий, образ, БД и логи (`D-079`, `D-093`, `D-144`). Генерация:

```bash
openssl rand -hex 32        # CHATTERGAME_OPS_TOKEN
openssl rand -base64 32     # CHATTERGAME_LLM_SECRET_KEY: ровно 32 байта
```

`CHATTERGAME_LLM_SECRET_KEY` сохранить отдельно от базы: в бэкап он не входит, и после
восстановления базы без него сохранённые ключи LLM не расшифровать. То же с паролем SMTP.

### 3.5. Что приложение проверяет само

| Ситуация | Поведение |
|---|---|
| `prod` без SMTP, `MAIL_FROM` или `PUBLIC_URL` | старт падает с понятным сообщением (`D-079`, `D-142`) |
| `CHATTERGAME_OPS_TOKEN` пуст | `/ops` отвечает `404` |
| `CHATTERGAME_REALTIME_ALLOWED_ORIGINS` пуст | WebSocket отклоняется: fail-closed (`D-073`) |
| `CHATTERGAME_LLM_SECRET_KEY` пуст или не 32 байта | старт проходит; сохранение ключа LLM возвращает ошибку |
| Версии `pg_dump` и сервера разошлись | `scripts/backup.sh` падает до создания комплекта |

## 4. Dev-контур

### 4.1. Подготовка

```bash
git clone <адрес-репозитория> ChatterGame
cd ChatterGame
```

Создать `.env` рядом с `docker-compose.yml` (файл в `.gitignore`):

```bash
cat > .env <<'EOF'
CHATTERGAME_DB_NAME=chattergame
CHATTERGAME_DB_USER=chattergame
CHATTERGAME_DB_PASSWORD=chattergame
CHATTERGAME_DB_PORT=5432
EOF
```

### 4.2. База

```bash
docker compose -p chattergame-dev --profile dev up -d
docker compose -p chattergame-dev ps
```

Проверка:

```bash
docker compose -p chattergame-dev exec postgres \
  psql -U chattergame -d chattergame -c '\dt'
```

Таблиц пока нет: их создаст Liquibase при первом старте приложения. Имя проекта
`-p chattergame-dev` важно: от него зависят имена тома и сети, поэтому данные dev
не пересекаются с prod на той же машине.

### 4.3. Бэкенд

```bash
./gradlew bootRun
```

Профиль `dev` включён по умолчанию (`spring.profiles.default: dev`). Что это значит:

- SMTP не нужен, письма уходят в лог (`LoggingMailSender`);
- адрес почты подтверждается автоматически при регистрации;
- cookie сессии без `Secure`, потому что dev живёт на `http`;
- Origin для сокета — `http://localhost:5173` и `http://localhost:8080`;
- медиа по умолчанию лежит в `build/media` и исчезает после `./gradlew clean`.

Чтобы медиа переживало пересборку:

```bash
CHATTERGAME_MEDIA_ROOT=./data/media ./gradlew bootRun
```

Приложение слушает `http://localhost:8080`. Проверка:

```bash
curl -i http://localhost:8080/me
# 401 и JSON с машинным кодом — это норма: сессии ещё нет
```

### 4.4. Фронтенд

```bash
cd frontend
npm ci
npm run dev
```

Открыть `http://localhost:5173`. Vite проксирует `/auth`, `/me`, `/games` и `/ws`
на `localhost:8080`, поэтому cookie и сокет работают как в проде, без CORS.

### 4.5. Первый сценарий

1. Зарегистрироваться на `/register`. В `dev` адрес подтверждён сразу.
2. Создать игру: создатель становится мастером.
3. Создать персонажа и локацию, войти в игру.
4. Проверить ленту, сокет и загрузку картинки.

Письма в `dev` не отправляются: `LoggingMailSender` пишет их в консоль `bootRun`.
Сброс пароля тоже даёт ссылку в логе.

### 4.6. Тесты и гейт

```bash
./gradlew test                 # тесты на реальном PostgreSQL 16
./scripts/gate.sh O03          # полный гейт этапа
./scripts/check-docs.sh        # согласованность документации
```

Тестовая база ищется по порядку: `CHATTERGAME_DB_*`, локальный Postgres из compose,
затем Testcontainers. Если базы нет вовсе, тесты на БД пропускаются — «зелёный» прогон
без базы ничего не значит, поэтому гейт отдельной строкой проверяет, что пропущенных
тестов нет.

Гейт прогоняет и фронтовые проверки, поэтому `npm ci` в `frontend/` должен быть выполнен
заранее (§4.4). Гейт пересоздаёт базу `chattergame_gate` на `localhost:5432`, поэтому роль
`chattergame` должна уметь создавать базы (в контейнере compose она суперпользователь).
Если Postgres внешний:

```bash
CHATTERGAME_GATE_DB_URL=jdbc:postgresql://db.example:5432/chattergame_gate \
CHATTERGAME_DB_USER=chattergame \
CHATTERGAME_DB_PASSWORD=... \
  ./scripts/gate.sh O03
```

### 4.7. Остановка и очистка

```bash
docker compose -p chattergame-dev down          # остановить, данные сохранить
docker compose -p chattergame-dev down -v       # остановить и удалить том с данными
```

`down -v` уничтожает базу dev. Для прода эта команда запрещена.

## 5. Сборка артефактов для хостинга

```bash
./gradlew clean bootJar
cd frontend && npm ci && npm run build && cd ..
```

Результат:

| Артефакт | Путь | Что это |
|---|---|---|
| Бэкенд | `build/libs/chattergame-0.1.0.jar` | исполняемый Spring Boot jar |
| Фронтенд | `frontend/dist/` | статика SPA, service worker, manifest |

`npm run build` включает проверку типов (`tsc --noEmit`), поэтому сборка падает на
ошибке типов, а не в браузере. `chattergame-0.1.0-plain.jar` не нужен: это jar без
зависимостей.

Проверить jar локально можно на dev-профиле:

```bash
java -jar build/libs/chattergame-0.1.0.jar
```

## 6. Хостинг: VPS с systemd и nginx

Ниже — проверенный порядок для одного сервера Linux. Контейнерный вариант — в §7.

### 6.1. DNS

A-запись (и AAAA, если есть IPv6) домена должна указывать на IP сервера до выпуска
сертификата. Почтовые записи SPF, DKIM и DMARC — по `docs/OPS/email.md` §2.

### 6.2. Пользователь и каталоги

```bash
sudo useradd --system --home /opt/chattergame --shell /usr/sbin/nologin chattergame
sudo install -d -o chattergame -g chattergame /opt/chattergame
sudo install -d -o chattergame -g chattergame /var/lib/chattergame/media
sudo install -d -o chattergame -g chattergame /var/backups/chattergame
sudo install -d -o root -g root -m 750 /etc/chattergame
```

Разложить артефакты:

```bash
sudo install -m 640 -o root -g chattergame \
  build/libs/chattergame-0.1.0.jar /opt/chattergame/app.jar
sudo rsync -a --delete frontend/dist/ /opt/chattergame/web/
sudo chown -R root:root /opt/chattergame/web
```

`--delete` безопасен только потому, что `/opt/chattergame/web` — выделенный каталог
только под статику.

### 6.3. PostgreSQL 16

```bash
sudo apt install postgresql-16 postgresql-client-16
sudo systemctl enable --now postgresql
```

Роль и база:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE chattergame LOGIN PASSWORD 'замените-на-секрет';
CREATE DATABASE chattergame OWNER chattergame;
SQL
```

Проверить, что сервер слушает только локальный адрес:

```bash
sudo ss -ltnp | grep 5432     # ожидается 127.0.0.1:5432 или [::1]:5432
```

Liquibase применяет миграции при старте приложения. Отдельный шаг «накатить схему»
не нужен и не должен появляться: порядок миграций — часть запуска. Роль обязана иметь
право создавать таблицы в своей базе.

### 6.4. Файл окружения

`/etc/chattergame/chattergame.env`, права `600 root:root`:

```bash
sudo tee /etc/chattergame/chattergame.env >/dev/null <<'EOF'
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

CHATTERGAME_DB_URL=jdbc:postgresql://127.0.0.1:5432/chattergame
CHATTERGAME_DB_USER=chattergame
CHATTERGAME_DB_PASSWORD=замените-на-секрет

CHATTERGAME_PUBLIC_URL=https://game.example.org
CHATTERGAME_SMTP_HOST=smtp.example.org
CHATTERGAME_SMTP_PORT=587
CHATTERGAME_SMTP_USER=no-reply@example.org
CHATTERGAME_SMTP_PASSWORD=замените-на-пароль-приложения
CHATTERGAME_SMTP_STARTTLS=true
CHATTERGAME_MAIL_FROM=no-reply@example.org

CHATTERGAME_REALTIME_ALLOWED_ORIGINS=https://game.example.org

CHATTERGAME_OPS_TOKEN=замените-на-токен
CHATTERGAME_LLM_SECRET_KEY=замените-на-ключ-base64
CHATTERGAME_MEDIA_ROOT=/var/lib/chattergame/media
CHATTERGAME_SESSION_COOKIE_SECURE=true
EOF
sudo chmod 600 /etc/chattergame/chattergame.env
```

Значения `замените-…` — не пароли по умолчанию, а напоминание: приложение стартует
с ними только если они совпадут с реальными настройками SMTP и БД.

Секреты сгенерировать командами из §3.4 и вписать в файл. Пробелов вокруг `=` быть
не должно: systemd читает файл как набор `KEY=VALUE`.

### 6.5. systemd

`/etc/systemd/system/chattergame.service`:

```ini
[Unit]
Description=ChatterGame
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=chattergame
Group=chattergame
WorkingDirectory=/opt/chattergame
EnvironmentFile=/etc/chattergame/chattergame.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/chattergame/app.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=30
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/chattergame
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Путь к `java` проверить: `readlink -f "$(command -v java)"`. Если JDK установлен
в `/usr/lib/jvm/...`, указать его явно.

Запуск:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now chattergame
sudo systemctl status chattergame
sudo journalctl -u chattergame -n 100 --no-pager
```

В логе старта должна быть строка вида `The following 1 profile is active: "prod"`.
Если там `dev`, значит `SPRING_PROFILES_ACTIVE` не дошёл: приложение работает
с автоподтверждением почты, без SMTP и с cookie без `Secure`.

### 6.6. nginx

Сначала общий конфиг WebSocket, `/etc/nginx/conf.d/websocket.conf`:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

Общие заголовки прокси, `/etc/nginx/snippets/chattergame-proxy.conf`:

```nginx
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
```

Сайт, `/etc/nginx/sites-available/chattergame`:

```nginx
server {
    listen 80;
    server_name game.example.org;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name game.example.org;

    ssl_certificate     /etc/letsencrypt/live/game.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/game.example.org/privkey.pem;

    root /opt/chattergame/web;
    index index.html;
    client_max_body_size 6m;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy same-origin always;

    # Оболочку и service worker не кэшировать: иначе клиент останется на старой версии.
    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    location = /sw.js {
        add_header Cache-Control "no-cache";
    }

    # Хэшированные файлы Vite неизменяемы.
    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /auth {
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/snippets/chattergame-proxy.conf;
    }

    location /me {
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/snippets/chattergame-proxy.conf;
    }

    # REST и WebSocket живут под одним префиксом. Переход браузера по /games/{id}
    # отдаётся оболочке SPA, запрос клиента и handshake — приложению.
    location /games {
        if ($http_accept ~* "text/html") {
            rewrite ^ /index.html last;
        }
        proxy_pass http://127.0.0.1:8080;
        include /etc/nginx/snippets/chattergame-proxy.conf;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    # Служебный контур наружу не нужен: мониторинг ходит на 127.0.0.1:8080.
    # Если доступ нужен снаружи, раскомментировать и ограничить адреса.
    # location /ops {
    #     allow 203.0.113.10;
    #     deny all;
    #     proxy_pass http://127.0.0.1:8080;
    #     include /etc/nginx/snippets/chattergame-proxy.conf;
    # }
}
```

Почему именно так:

- `client_max_body_size 6m` должен быть не меньше `CHATTERGAME_MEDIA_MAX_REQUEST_BYTES`.
  Если поднять потолок файла, поднять и здесь, и в `max-request-size`.
- `/games` проксируется целиком, включая `/games/{id}/ws`: отдельный `location` для
  сокета не нужен, но `Upgrade` и `Connection` обязательны, иначе handshake вернёт `400`.
- `proxy_read_timeout 3600s` не даёт nginx закрыть сокет между сообщениями. Само
  приложение закрывает мёртвый сокет по `idle-timeout` (`D-083`).
- Медиа не получает `alias`: файлы отдаёт приложение после проверки участия (`D-075`).
- `try_files` в `/` отдаёт `index.html` для `/login`, `/profile`, `/verify-email`,
  `/reset-password` — прямые переходы по ссылкам из писем работают.

Включить сайт:

```bash
sudo ln -s /etc/nginx/sites-available/chattergame /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

### 6.7. TLS

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d game.example.org
```

Certbot сам проверит домен и перепишет `listen`/сертификаты. После этого:

```bash
sudo nginx -t && sudo systemctl reload nginx
sudo systemctl list-timers | grep certbot   # продление живёт таймером
```

TLS обязателен не только из-за пароля: service worker и установка PWA работают
только на `https` (кроме `localhost`).

### 6.8. Первый запуск: проверки

```bash
# 1. Процесс и профиль
systemctl is-active chattergame
sudo journalctl -u chattergame -n 50 --no-pager | grep "profile is active"

# 2. Приложение отвечает через прокси, но без сессии — 401
curl -i https://game.example.org/me

# 3. Статика и оболочка SPA
curl -sI https://game.example.org/ | head -1
curl -s -H 'Accept: text/html' https://game.example.org/games/00000000-0000-0000-0000-000000000000 | head -5

# 4. Служебный контур: с токеном 200, без токена 401, при пустом токене 404
curl -s -H "X-Ops-Token: $CHATTERGAME_OPS_TOKEN" http://127.0.0.1:8080/ops/metrics
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/ops/metrics

# 5. Cookie помечена Secure
curl -si -X POST https://game.example.org/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"login":"...","password":"..."}' | grep -i set-cookie
```

Ожидается `Secure; HttpOnly; SameSite=Lax`. Если `Secure` нет — не подхватился профиль
`prod` или переопределён `CHATTERGAME_SESSION_COOKIE_SECURE`.

Проверки в браузере:

1. Регистрация и вход.
2. Создание игры, персонажа, локации.
3. Открыть DevTools → Network → WS: соединение `/games/{id}/ws` должно перейти
   в `101 Switching Protocols` и не рваться.
4. Загрузить картинку до 5 МиБ: она сохраняется и видна после перезапуска приложения.
5. Письмо подтверждения и сброса пароля приходит (чек-лист `docs/OPS/email.md` §3).

### 6.9. Обновление версии

```bash
# 1. Бэкап до любых действий
CHATTERGAME_DB_URL=... CHATTERGAME_DB_USER=... CHATTERGAME_DB_PASSWORD=... \
CHATTERGAME_MEDIA_ROOT=/var/lib/chattergame/media \
  ./scripts/backup.sh /var/backups/chattergame

# 2. Собрать новые артефакты
./gradlew clean bootJar
(cd frontend && npm ci && npm run build)

# 3. Заменить и перезапустить
sudo install -m 640 -o root -g chattergame build/libs/chattergame-0.1.0.jar /opt/chattergame/app.jar
sudo rsync -a --delete frontend/dist/ /opt/chattergame/web/
sudo systemctl restart chattergame

# 4. Проверить
systemctl is-active chattergame
curl -s -o /dev/null -w '%{http_code}\n' https://game.example.org/me
```

Liquibase применит новые changeset'ы при старте. Применённые файлы миграций не
редактируются никогда (`AGENTS.md`): исправление — новая запись. Откат jar на старую
версию безопасен только если новая миграция не меняла схему несовместимо; иначе
восстановление из бэкапа по `docs/OPS/backup.md`.

### 6.10. Бэкап, логи, мониторинг

```cron
15 3 * * * set -a; . /etc/chattergame/chattergame.env; set +a; cd /opt/chattergame && ./scripts/backup.sh /var/backups/chattergame >> /var/log/chattergame-backup.log 2>&1
```

Cron не наследует окружение systemd, поэтому файл окружения читается прямо в строке
задания. Без этого скрипт возьмёт умолчания `chattergame/chattergame` и не подключится
к базе.

Логи — в journald:

```bash
journalctl -u chattergame -f
journalctl -u chattergame --since "1 hour ago" | grep -i error
```

Ротацию journald настроить по общему правилу сервера (`SystemMaxUse=`), отдельный
logrotate не нужен. В логах нет текстов сообщений, адресов почты и токенов (`D-046`,
`D-143`), поэтому их можно хранить централизованно.

Мониторинг: `GET /ops/metrics` с заголовком `X-Ops-Token` (сокеты по играм, пул LLM,
размер журнала и его материализации). Проверка живости без токена — `GET /me`:
`401` означает, что приложение отвечает.

## 7. Контейнерный хостинг

Если платформа принимает только контейнер, приложение всё равно остаётся одним
процессом (`D-072`), а статику и TLS удобнее держать перед ним.

`Dockerfile` для бэкенда (собирается из готового jar, чтобы образ не тянул Gradle
и Node):

```dockerfile
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --home /app chattergame \
 && mkdir -p /var/lib/chattergame/media \
 && chown -R chattergame:chattergame /app /var/lib/chattergame

USER chattergame
WORKDIR /app
COPY build/libs/chattergame-0.1.0.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

`docker-compose.prod.yml` (Postgres можно заменить управляемой базой):

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: chattergame
      POSTGRES_USER: chattergame
      POSTGRES_PASSWORD: ${CHATTERGAME_DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    restart: unless-stopped

  app:
    build: .
    env_file: chattergame.env
    environment:
      CHATTERGAME_DB_URL: jdbc:postgresql://db:5432/chattergame
      CHATTERGAME_MEDIA_ROOT: /var/lib/chattergame/media
    volumes:
      - media:/var/lib/chattergame/media
    depends_on:
      - db
    restart: unless-stopped

  web:
    image: nginx:1.27-alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./frontend/dist:/usr/share/nginx/html:ro
      - ./deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./deploy/certs:/etc/nginx/certs:ro
    depends_on:
      - app
    restart: unless-stopped

volumes:
  pgdata:
  media:
```

В `nginx.conf` для контейнера upstream — `app:8080` вместо `127.0.0.1:8080`, остальные
правила из §6.6 те же. TLS на платформе может терминироваться до контейнера: тогда
`web` слушает только `80`, а `X-Forwarded-Proto` приходит от платформы. Приложение
всё равно должно видеть `https` в `CHATTERGAME_PUBLIC_URL`, иначе ссылки в письмах
будут вести на `http`.

Особенности платформ:

- **WebSocket.** Проверить, что платформа не буферизует и не обрывает соединения;
  таймаут простоя должен быть больше `CHATTERGAME_REALTIME_HEARTBEAT_INTERVAL`.
- **Один инстанс.** Не включать автомасштабирование. При нескольких репликах нужен
  `CHATTERGAME_REALTIME_FANOUT=postgres` и маршрутизация игры на один инстанс (`D-072`).
- **Диск.** Каталог медиа — постоянный том. Без него картинки исчезают при redeploy.
- **Секреты.** Только секреты платформы или `env_file`, не `ENV` в образе.
- **Порты.** Наружу — только `web`; `app` и `db` доступны внутри сети compose.

## 8. Чек-лист безопасности

- [ ] `SPRING_PROFILES_ACTIVE=prod`; в логе старта профиль `prod`.
- [ ] TLS на домене, HTTP перенаправляется на HTTPS.
- [ ] Cookie сессии `Secure; HttpOnly; SameSite=Lax`.
- [ ] `CHATTERGAME_REALTIME_ALLOWED_ORIGINS` — точный `https://`-домен, без пути.
- [ ] `CHATTERGAME_OPS_TOKEN` задан; `/ops` не опубликован в интернет без allowlist.
- [ ] `CHATTERGAME_LLM_SECRET_KEY` — 32 байта base64, хранится отдельно от бэкапа БД.
- [ ] Пароли БД и SMTP только в файле окружения с правами `600`.
- [ ] PostgreSQL слушает loopback; порт 5432 закрыт файрволом.
- [ ] Файрвол: открыты только 22, 80, 443.
- [ ] Каталог медиа — постоянный, права `chattergame`, `UMask=0077`.
- [ ] Бэкап по расписанию и проверка восстановления раз в неделю (`docs/OPS/backup.md`).
- [ ] `CHATTERGAME_MEDIA_ORPHAN_PURGE=false`, пока сухой прогон не проверен.

## 9. Если что-то не работает

| Симптом | Причина | Что сделать |
|---|---|---|
| Прод не стартует: `Профиль prod требует настроенный SMTP` | нет `CHATTERGAME_SMTP_HOST` | задать SMTP; это проверка `D-079`, а не сбой |
| Прод не стартует: `chattergame.mail.from` или `link-base` | нет `MAIL_FROM`/`PUBLIC_URL` | задать оба, URL без `/` на конце |
| Вход «проходит», следующий запрос — `401` | cookie `Secure`, а сайт открыт по `http` | включить HTTPS или в dev использовать профиль `dev` |
| Сокет закрывается сразу, в логах `403` | Origin не совпал | добавить точный `https://host` в `CHATTERGAME_REALTIME_ALLOWED_ORIGINS`, перезапустить |
| Сокет рвётся через минуту | nginx закрывает соединение | `proxy_read_timeout`, `Upgrade`/`Connection`, `proxy_buffering off` |
| `413` и `payload_too_large` при загрузке | файл больше `CHATTERGAME_MEDIA_MAX_FILE_BYTES` или `CHATTERGAME_MEDIA_MAX_REQUEST_BYTES` | поднять обе переменные |
| `413 Request Entity Too Large` без тела JSON | nginx отклоняет раньше приложения | поднять `client_max_body_size` |
| Картинки исчезли после обновления | медиа лежит в `build/media` или во временном слое | задать постоянный `CHATTERGAME_MEDIA_ROOT` |
| `500` при сохранении ключа LLM | нет или неверный `CHATTERGAME_LLM_SECRET_KEY` | base64 ровно 32 байта; после смены ключа ввести ключи LLM заново |
| `/ops` отвечает `404` | пустой `CHATTERGAME_OPS_TOKEN` | задать токен |
| `/ops` отвечает `401` | нет заголовка `X-Ops-Token` | передать заголовок |
| Liquibase: `checksum mismatch` | изменён применённый changeset | вернуть файл, исправление — новой миграцией |
| `relation ... does not exist` | миграции не применились | проверить права роли на создание таблиц и лог Liquibase |
| Письма не приходят | SMTP, SPF/DKIM/DMARC | `docs/OPS/email.md` §2–3 |
| PWA не устанавливается | нет HTTPS | TLS обязателен |
| `scripts/backup.sh`: версии клиента и сервера разошлись | `pg_dump` не 16 | установить `postgresql-client-16` |

## 10. Короткие чек-листы

**Dev**

1. `.env` создан, `docker compose -p chattergame-dev --profile dev up -d`.
2. `./gradlew bootRun`.
3. `cd frontend && npm ci && npm run dev`.
4. `http://localhost:5173`, регистрация, игра, сокет, картинка.
5. `./scripts/gate.sh O03` перед коммитом.

**Prod**

1. DNS указывает на сервер; PostgreSQL 16 создан, роль владеет базой.
2. `/etc/chattergame/chattergame.env` заполнен, права `600`.
3. `systemctl start chattergame`, в логе профиль `prod`.
4. nginx с TLS, `/me` отвечает `401`, статика отдаётся, `/games/{id}/ws` даёт `101`.
5. `/ops/metrics` с токеном отвечает `200`.
6. Письмо подтверждения дошло, `mail-tester.com` ≥ 9/10.
7. Бэкап в cron, проверка восстановления выполнена.
