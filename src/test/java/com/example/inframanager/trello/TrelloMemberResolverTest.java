package com.example.inframanager.trello;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сопоставление автора пул-реквеста с участником доски. HTTP здесь неинтересен —
 * интересно, кого мы соглашаемся опознать, а кого нет.
 */
class TrelloMemberResolverTest {

    private static final String BOARD = "board-1";

    private final TrelloClient client = mock(TrelloClient.class);

    @Test
    void findsTheSamePersonWrittenInCyrillicWithAPatronymic() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("Красовский Григорий Игоревич")))
                .containsExactly("id-grigory");
    }

    @Test
    void findsThePersonBehindALoginBuiltFromTheirName() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("g.krasovsky"))).containsExactly("id-grigory");
    }

    @Test
    void toleratesTheWayPeopleSpellTheirOwnNameInLatin() {
        // «ц» против «ts», «х» против «kh», «ий» против «y» — один и тот же человек.
        TrelloMemberResolver resolver = resolver(Map.of(), member("mike", "Mihail Kuznecov"));

        assertThat(resolver.memberIds(BOARD, List.of("Кузнецов Михаил"))).containsExactly("id-mike");
    }

    @Test
    void matchesAMemberWhoLeftOnlyAFirstNameInTheirProfile() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory"));

        assertThat(resolver.memberIds(BOARD, List.of("Григорий Красовский"))).containsExactly("id-grigory");
    }

    @Test
    void refusesToGuessWhenOnlyTheSurnameIsShared() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("Красовский Пётр Иванович"))).isEmpty();
    }

    @Test
    void leavesTheCardUnassignedWhenNobodyOnTheBoardIsTheAuthor() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("i.ivanov", "Иванов Иван"))).isEmpty();
    }

    @Test
    void assignsNobodyWhenTheNameFitsTwoMembers() {
        // Два аккаунта одного человека — тоже неоднозначность: молча выбрать первый
        // значит наполовину назначать карточки на заброшенную учётную запись.
        TrelloMemberResolver resolver = resolver(Map.of(),
                member("grigory", "Grigory Krasovsky"),
                member("gkrasovsky", "Григорий Красовский"));

        assertThat(resolver.memberIds(BOARD, List.of("Красовский Григорий"))).isEmpty();
    }

    @Test
    void anExplicitMappingSettlesAnAmbiguousName() {
        TrelloMemberResolver resolver = resolver(Map.of("Красовский Григорий", "gkrasovsky"),
                member("grigory", "Grigory Krasovsky"),
                member("gkrasovsky", "Григорий Красовский"));

        assertThat(resolver.memberIds(BOARD, List.of("Красовский Григорий")))
                .containsExactly("id-gkrasovsky");
    }

    @Test
    void anExplicitMappingAcceptsAMemberId() {
        TrelloMemberResolver resolver = resolver(Map.of("nickname", "id-grigory"),
                member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("nickname"))).containsExactly("id-grigory");
    }

    @Test
    void aMappingPointingAtNobodyFallsBackToTheName() {
        // Опечатка в конфигурации не должна отменять то, что и так работало бы само.
        TrelloMemberResolver resolver = resolver(Map.of("g.krasovsky", "gone-from-the-board"),
                member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("g.krasovsky"))).containsExactly("id-grigory");
    }

    @Test
    void prefersAnExactUsernameOverANameThatLooksSimilar() {
        TrelloMemberResolver resolver = resolver(Map.of(),
                member("krasovsky", "Someone Else"),
                member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, List.of("krasovsky"))).containsExactly("id-krasovsky");
    }

    @Test
    void readsTheBoardRosterOncePerCachePeriod() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        resolver.memberIds(BOARD, List.of("Григорий Красовский"));
        resolver.memberIds(BOARD, List.of("Иванов Иван"));

        verify(client, times(1)).boardMembers(eq(BOARD), any(), any());
    }

    @Test
    void anUnreadableBoardLeavesTheCardUnassignedInsteadOfFailing() {
        when(client.boardMembers(eq(BOARD), any(), any())).thenThrow(new IllegalStateException("boom"));
        TrelloMemberResolver resolver = new TrelloMemberResolver(client, properties(Map.of()));

        assertThat(resolver.memberIds(BOARD, List.of("Григорий Красовский"))).isEmpty();
    }

    @Test
    void toleratesMissingInput() {
        TrelloMemberResolver resolver = resolver(Map.of(), member("grigory", "Grigory Krasovsky"));

        assertThat(resolver.memberIds(BOARD, null)).isEmpty();
        assertThat(resolver.memberIds(BOARD, List.of())).isEmpty();
        assertThat(resolver.memberIds(BOARD, java.util.Arrays.asList(null, "  "))).isEmpty();
    }

    private TrelloMemberResolver resolver(Map<String, String> members, TrelloClient.TrelloMember... roster) {
        when(client.boardMembers(eq(BOARD), any(), any())).thenReturn(List.of(roster));
        return new TrelloMemberResolver(client, properties(members));
    }

    private static TrelloClient.TrelloMember member(String username, String fullName) {
        return new TrelloClient.TrelloMember("id-" + username, username, fullName);
    }

    private static TrelloProperties properties(Map<String, String> members) {
        return new TrelloProperties(true, "https://api.trello.com", "key", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMinutes(10), members, List.of(),
                new TrelloProperties.Reconciliation(false, Duration.ofMinutes(15),
                        TrelloProperties.Reconciliation.OnDrift.LOG,
                        TrelloProperties.Reconciliation.OnMissing.LOG));
    }
}
