package com.example.inframanager.pullrequest;

/**
 * Задача ревью: что просил поправить ревьюер и закрыто ли это.
 *
 * @param resolved закрыть задачу может и автор, и ревьюер — кем именно она закрыта,
 *                 Bitbucket в этом ответе не сообщает, да нам это и не нужно
 */
public record PrTask(long id, String text, boolean resolved) {
}
