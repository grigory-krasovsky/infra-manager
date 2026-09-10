package com.example.inframanager.event;

/** Системы, присылающие нам вебхуки. */
public enum EventSource {

    /** События pull request'ов; управляют карточками Trello. */
    BITBUCKET,

    /** Результаты деплоя; управляют уведомлениями в Telegram. */
    BAMBOO
}
