package com.chattergame.user;

/**
 * Тексты писем (`O01`, `D-142`). Шаблоны живут в коде, а не в БД: писем два, они меняются
 * вместе с кодом и обязаны быть переводимы, а таблица шаблонов потребовала бы миграции
 * и админки ради двух строк.
 *
 * <p>Ссылка приходит параметром, а не собирается здесь: адрес приложения — настройка
 * окружения, и шаблон не должен знать, где живёт стенд.
 */
public final class MailTemplates {

    private MailTemplates() {
    }

    public record Message(String subject, String text) {
    }

    public static Message passwordReset(String link) {
        return new Message(
                "Сброс пароля ChatterGame",
                "Чтобы задать новый пароль, откройте ссылку:\n\n" + link
                        + "\n\nЕсли вы не запрашивали сброс, ничего делать не нужно.");
    }

    public static Message emailVerification(String link) {
        return new Message(
                "Подтверждение адреса ChatterGame",
                "Чтобы подтвердить адрес почты, откройте ссылку:\n\n" + link
                        + "\n\nСсылка действует сутки. Если вы не указывали этот адрес, "
                        + "просто не открывайте её.");
    }
}
