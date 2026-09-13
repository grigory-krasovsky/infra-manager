package com.example.inframanager.deployment;

/**
 * Пул-реквест, которым был вызван деплой: номер, заголовок и адрес в Bitbucket.
 *
 * <p>Это не связь между потоками карточек и деплоев — карточку по нему никто не ищет.
 * Всё, что здесь есть, вычитано из сообщения merge-коммита, который Bamboo и так отдаёт
 * вместе со сборкой.
 *
 * @param url может быть null, если адрес Bitbucket не настроен — тогда остаётся один номер
 */
public record DeploymentPullRequest(long id, String title, String url) {
}
