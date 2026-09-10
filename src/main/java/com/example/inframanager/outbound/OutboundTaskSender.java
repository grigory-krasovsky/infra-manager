package com.example.inframanager.outbound;

/**
 * Выполняет собственно вызов API для одной цели. Реализации появляются вместе со
 * своими фазами: Telegram в фазе 2, Trello в фазе 4.
 *
 * <p>Исключение означает сбой, который имеет смысл повторить. Если API вернул 429,
 * бросайте {@link com.example.inframanager.work.RetryAfterException}, чтобы воркер
 * выждал именно тот интервал, который запросил API, а не гадал.
 */
public interface OutboundTaskSender {

    OutboundTarget target();

    void send(OutboundTask task);
}
