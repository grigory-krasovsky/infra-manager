package com.example.inframanager.pullrequest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Тот кусок REST API Bitbucket, который нужен пути с поллингом.
 *
 * <p>Используется, только когда вебхуки до нас не доходят, — а это обычная ситуация,
 * если сервис работает вне сети, где живёт Bitbucket.
 */
public interface BitbucketClient {

    @GetExchange("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests")
    PullRequestPage pullRequests(@PathVariable String projectKey,
                                 @PathVariable String repoSlug,
                                 @RequestParam("state") String state,
                                 @RequestParam("order") String order,
                                 @RequestParam("limit") int limit);

    /**
     * Задачи пул-реквеста — в терминах Bitbucket «блокирующие комментарии».
     *
     * <p>Отдельным запросом, потому что в списке пул-реквестов их нет ни в каком виде.
     * Возвращаются все, и закрытые тоже: фильтр {@code states} эндпоинт молча
     * игнорирует, зато у каждой задачи есть собственное состояние.
     */
    @GetExchange("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/blocker-comments")
    BlockerComments blockerComments(@PathVariable String projectKey,
                                    @PathVariable String repoSlug,
                                    @PathVariable long prId,
                                    @RequestParam("limit") int limit);

    /**
     * Результат пробного мержа: мешают ли конфликты влить пул-реквест.
     *
     * <p>Он же есть и в списке пул-реквестов ({@code properties.mergeResult}), и там он
     * бесплатен, — но Bitbucket считает мерж лениво, и пока пул-реквест никто не
     * открывал, в списке лежит ответ о старом коде. Этот запрос отвечает о нынешнем и
     * заодно заставляет мерж пересчитать, так что в следующем списке ответ будет свежим.
     */
    @GetExchange("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/merge")
    MergeStatus mergeStatus(@PathVariable String projectKey,
                            @PathVariable String repoSlug,
                            @PathVariable long prId);

    /**
     * Элементы имеют ту же форму, что Bitbucket кладёт в {@code pullRequest} тела
     * вебхука, — именно это позволяет поллеру отдавать их тому же обработчику.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestPage(List<BitbucketPrEvent.PullRequest> values) {
    }

    /**
     * Берём только {@code conflicted}. Рядом приходят {@code canMerge} и {@code vetoes} —
     * запреты merge check'ов: недобор аппрувов, незакрытые задачи, выставленный
     * {@code NEEDS_WORK}. Всё это доска уже показывает колонкой и чек-листом, и
     * повторять то же самое значком в заголовке значило бы сказать одно дважды.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeStatus(Boolean conflicted) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlockerComments(List<BlockerComment> values) {

        public List<BlockerComment> tasks() {
            return values == null ? List.of() : values;
        }
    }

    /** @param state {@code OPEN} или {@code RESOLVED} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlockerComment(long id, String text, String state) {

        public boolean isResolved() {
            return "RESOLVED".equalsIgnoreCase(state);
        }
    }
}
