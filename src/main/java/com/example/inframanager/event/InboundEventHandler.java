package com.example.inframanager.event;

/**
 * Превращает сохранённую доставку вебхука в то, что она означает: поставленное
 * в очередь сообщение Telegram, перенос карточки Trello. Реализации появляются
 * вместе со своими фазами: Bamboo в фазе 3, Bitbucket в фазе 4.
 *
 * <p>Исключение означает сбой, который имеет смысл повторить; воркер выдержит
 * паузу и сдастся после {@code maxAttempts} попыток.
 */
public interface InboundEventHandler {

    boolean supports(EventSource source);

    void handle(InboundEvent event);
}
