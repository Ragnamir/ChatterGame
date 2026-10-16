package com.chattergame.request;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик автоотклонения просроченных заявок (`PLAN` E14, задача 6).
 *
 * <p>Отдельный компонент по технической причине с доменным смыслом: прогон обязан быть
 * транзакцией, а вызов собственного `@Transactional`-метода изнутри класса прокси обходит —
 * транзакция молча не открывается. Вызов снаружи, через бин {@link RequestService}, делает
 * её настоящей. Заодно здесь стоит ответ на вопрос «кто проверяет срок»: планировщик, а не
 * зашедший в игру автор — просрочка наступает и без него.
 */
@Component
public class RequestExpiryScheduler {

    private final RequestService requests;

    public RequestExpiryScheduler(RequestService requests) {
        this.requests = requests;
    }

    /**
     * Прогон просроченных. Срок — свойство заявки, а не хода: `D-060` удалил таймаут
     * <em>хода</em>, и к автоотклонению заявки он не относится — мир по времени не меняется,
     * истекает лишь ожидание ответа мастеру.
     */
    @Scheduled(fixedDelayString = "${chattergame.requests.expire-delay:60000}")
    public void expireDue() {
        requests.expireDue();
    }
}
