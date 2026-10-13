plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.chattergame"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Служебный source set: инструменты командной строки для гейта. Не попадает в продовый jar.
val tools = sourceSets.create("tools") {
    java.srcDir("src/tools/java")
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    runtimeClasspath += output + compileClasspath + configurations["runtimeClasspath"]
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Реалтайм (`D-035`, `D-073`): Spring WebSocket без STOMP, собственные JSON-события.
    // Зависимость нужна этапу E09, а не «на будущее»: сокет — его предмет.
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.liquibase:liquibase-core")
    // Argon2id для паролей (AGENTS: «Пароли: Argon2id»). Берём кодировщик из
    // spring-security-crypto, а не свой: рукописная проверка хешей — источник дыр.
    // Полный spring-boot-starter-security не подключаем: аутентификация своя,
    // серверная сессия вместо фильтр-цепочки Spring Security.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // WebP в whitelist `REQUIREMENTS §19` требует настоящего декодирования (`D-076`), а JDK
    // `ImageIO` его не читает вовсе: проверено — `webp readable: false`. Плагин закрывает
    // ровно этот формат (`D-105`); остальные три читает JDK.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
    // Область драйвера поднята из runtime в compile: `PostgresNotifyFanout` слушает канал
    // `LISTEN/NOTIFY` и разбирает уведомления через `PGConnection` (`D-084`). Тот же драйвер,
    // не новая зависимость — меняется только видимость на компиляции.
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Тесты переиспользуют служебный LiquibaseRunner, чтобы миграции применялись
    // ровно тем же кодом, что и в гейте.
    testImplementation(tools.output)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// Пересоздание тестовой базы: миграционные тесты осмысленны только на чистой базе.
tasks.register<JavaExec>("resetDb") {
    group = "verification"
    description = "Пересоздаёт БД из -Pdb.url / -Pdb.user / -Pdb.password"
    classpath = tools.runtimeClasspath
    mainClass = "com.chattergame.tools.DatabaseResetter"
    // Свойства читаются лениво: без -Pdb.* задача не выполняется, и обычная
    // сборка не падает на этапе конфигурации.
    val dbUrl = providers.gradleProperty("db.url")
    val dbUser = providers.gradleProperty("db.user")
    val dbPassword = providers.gradleProperty("db.password")
    onlyIf { dbUrl.isPresent && dbUser.isPresent && dbPassword.isPresent }
    argumentProviders.add {
        listOf(dbUrl.get(), dbUser.get(), dbPassword.get())
    }
}

// Печать списка таблиц: секция `migrations` гейта сверяет его с контрактом.
tasks.register<JavaExec>("printSchema") {
    group = "verification"
    description = "Печатает доменные таблицы БД из -Pdb.url / -Pdb.user / -Pdb.password"
    classpath = tools.runtimeClasspath
    mainClass = "com.chattergame.tools.SchemaReporter"
    // Свойства читаются лениво: без -Pdb.* задача не выполняется, и обычная
    // сборка не падает на этапе конфигурации.
    val dbUrl = providers.gradleProperty("db.url")
    val dbUser = providers.gradleProperty("db.user")
    val dbPassword = providers.gradleProperty("db.password")
    onlyIf { dbUrl.isPresent && dbUser.isPresent && dbPassword.isPresent }
    argumentProviders.add {
        listOf(dbUrl.get(), dbUser.get(), dbPassword.get())
    }
}

// Применение миграций к произвольной БД без поднятия приложения: используется гейтом
// и разработчиком для проверки changelog на чистой базе.
tasks.register<JavaExec>("liquibaseUpdate") {
    group = "verification"
    description = "Применяет Liquibase changelog к БД из -Pdb.url / -Pdb.user / -Pdb.password"
    classpath = tools.runtimeClasspath
    mainClass = "com.chattergame.tools.LiquibaseRunner"
    // Свойства читаются лениво: без -Pdb.* задача не выполняется, и обычная
    // сборка не падает на этапе конфигурации.
    val dbUrl = providers.gradleProperty("db.url")
    val dbUser = providers.gradleProperty("db.user")
    val dbPassword = providers.gradleProperty("db.password")
    onlyIf { dbUrl.isPresent && dbUser.isPresent && dbPassword.isPresent }
    argumentProviders.add {
        listOf(dbUrl.get(), dbUser.get(), dbPassword.get())
    }
}
