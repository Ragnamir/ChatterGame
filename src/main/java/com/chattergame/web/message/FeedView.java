package com.chattergame.web.message;

import com.chattergame.message.MessageQueryService;

import java.util.List;

/** Страница ленты и метки, которые клиент держит между запросами (`D-032`, `D-083`). */
public record FeedView(List<MessageView> items, long lastSeq, long readSeq, String chatKind) {

    public static FeedView of(MessageQueryService.FeedPage page) {
        return new FeedView(
                page.items().stream().map(MessageView::of).toList(),
                page.lastSeq(),
                page.readSeq(),
                page.chatKind());
    }
}
