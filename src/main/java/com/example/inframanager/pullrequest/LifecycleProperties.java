package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Жизненный цикл карточки: какие репозитории зеркалятся на какую доску и какое событие
 * пул-реквеста кладёт карточку в какую колонку.
 *
 * <p>Живёт в конфигурации, а не в базе, потому что правится руками, а админки для
 * редактирования строк нет.
 */
@ConfigurationProperties("infra-manager.lifecycle")
public record LifecycleProperties(

        @DefaultValue List<RepoBoard> repos,

        /**
         * Ключ события Bitbucket → имя списка Trello. Событие, которого здесь нет, всё
         * равно обновит заголовок и описание карточки — просто не переместит её, а
         * именно так и должен вести себя {@code pr:modified}.
         *
         * <p>Список пар, а не map, потому что в ключах событий есть двоеточие, а
         * двоеточие недопустимо в имени property Spring. В виде map пришлось бы писать
         * в YAML {@code "[pr:opened]": Review} — выглядит как опечатка и молча перестаёт
         * связываться, стоит кому-нибудь это «исправить».
         */
        @DefaultValue List<EventList> eventToList,

        /**
         * Целевая ветка → имя метки, когда они не совпадают. В ЦСВ ветка {@code postgres}
         * по смыслу и есть тест: PR в неё — это PR в тест, и на доске он должен попадать
         * под тот же фильтр, что и {@code test} из остальных репозиториев.
         *
         * <p>Подменяется только метка. Заголовок и описание карточки называют ветку её
         * настоящим именем: метка нужна для фильтра, а не для того, чтобы скрыть, куда
         * на самом деле поедет изменение.
         *
         * <p>Список пар, а не map, по той же причине, что и {@code eventToList}: в именах
         * веток есть «/» и точки, а такой ключ property Spring разбирает по-своему.
         */
        @DefaultValue List<BranchLabel> branchLabels,

        /** Отправлять ли карточку с уже полученным апрувом обратно на ревью при новых коммитах. */
        @DefaultValue("true") boolean reopenOnNewCommits,

        /** Где создаётся карточка, если {@code pr:opened} не отображён на список. */
        @DefaultValue("Review") String defaultList) {

    /**
     * @param prefix короткое имя в начале заголовка карточки. Несколько репозиториев
     *               делят одну доску, а одна задача обычно порождает два пул-реквеста —
     *               фронт и бэк, — так что именно префикс разводит их карточки. Если не
     *               задан, берётся slug.
     */
    public record RepoBoard(String projectKey, String repoSlug, String trelloBoardId, String prefix) {

        public String displayPrefix() {
            return prefix == null || prefix.isBlank() ? repoSlug : prefix;
        }

        boolean matches(PullRequestRef ref) {
            return projectKey.equalsIgnoreCase(ref.projectKey())
                    && repoSlug.equalsIgnoreCase(ref.repoSlug());
        }
    }

    public record EventList(String event, String list) {
    }

    public record BranchLabel(String branch, String label) {
    }

    /** @return имя метки для целевой ветки: псевдоним, если он задан, иначе сама ветка */
    public String labelForBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return branch;
        }
        return branchLabels.stream()
                .filter(alias -> branch.equalsIgnoreCase(alias.branch()))
                .map(BranchLabel::label)
                .findFirst()
                .orElse(branch);
    }

    public Optional<RepoBoard> boardFor(PullRequestRef ref) {
        return repos.stream().filter(repo -> repo.matches(ref)).findFirst();
    }

    /** Куда попадает карточка, когда её только создали. */
    public String createInList() {
        return listNamed("pr:opened").orElse(defaultList);
    }

    /**
     * @return список, в который это событие перемещает карточку, либо empty — оставить на месте
     */
    public Optional<String> listFor(String eventKey) {
        if (reopenOnNewCommits && "pr:from_ref_updated".equals(eventKey)) {
            // Новые коммиты обесценивают апрув, поэтому карточка возвращается туда,
            // где лежал бы только что открытый PR.
            return listNamed("pr:opened");
        }
        return listNamed(eventKey);
    }

    private Optional<String> listNamed(String eventKey) {
        return eventToList.stream()
                .filter(mapping -> mapping.event().equals(eventKey))
                .map(EventList::list)
                .findFirst();
    }
}
