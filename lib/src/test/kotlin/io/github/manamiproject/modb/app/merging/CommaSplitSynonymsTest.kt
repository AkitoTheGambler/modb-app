package io.github.manamiproject.modb.app.merging

import io.github.manamiproject.modb.core.anime.AnimeRaw
import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import kotlin.test.Test

internal class CommaSplitSynonymsTest {

    private fun anime(title: String, vararg synonyms: String) = AnimeRaw(
        _sources = hashSetOf(URI("https://myanimelist.net/anime/54722")),
        _title = title,
        _synonyms = synonyms.toHashSet(),
    )

    @Test
    fun `drops the pieces of a title whose every piece is listed`() {
        // given
        val anime = anime(
            "Mahou Shoujo ni Akogarete",
            "I Admire Magical Girls, and...",
            "I Admire Magical Girls",
            "and...",
            "Gushing over Magical Girls",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "I Admire Magical Girls, and...",
            "Gushing over Magical Girls",
        )
    }

    @Test
    fun `drops the pieces of the main title but keeps the main title`() {
        // given
        val anime = anime(
            "15-sai, Kyou kara Dousei Hajimemasu.",
            "15-sai",
            "Kyou kara Dousei Hajimemasu.",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.title).isEqualTo("15-sai, Kyou kara Dousei Hajimemasu.")
        assertThat(result.synonyms).isEmpty()
    }

    @Test
    fun `keeps the pieces when not all of them are listed`() {
        // given
        val anime = anime(
            "Ame to Shoujo to Watashi no Tegami",
            "The Rain, the Little Girl, and My Letter",
            "The Rain",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "The Rain, the Little Girl, and My Letter",
            "The Rain",
        )
    }

    @Test
    fun `keeps synonyms which were listed separately and never joined`() {
        // given
        val anime = anime(
            "Black Jack",
            "BJ",
            "BMan",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder("BJ", "BMan")
    }

    @Test
    fun `keeps every piece when one of them is the main title`() {
        // given
        val anime = anime(
            "Make a Girl",
            "Make a Girl, Un Amor Artificial",
            "Un amor artificial",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "Make a Girl, Un Amor Artificial",
            "Un amor artificial",
        )
    }

    @Test
    fun `keeps every piece when one of them nearly is the main title`() {
        // given
        val anime = anime(
            "Haikyuu!! To the Top Part 2",
            "Haikyu!! TO THE TOP 2nd-cour, Haikyu!! TO THE TOP Part 2",
            "Haikyu!! To the Top 2nd-cour",
            "Haikyu!! To the Top Part 2",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "Haikyu!! TO THE TOP 2nd-cour, Haikyu!! TO THE TOP Part 2",
            "Haikyu!! To the Top 2nd-cour",
            "Haikyu!! To the Top Part 2",
        )
    }

    @Test
    fun `keeps every piece when all of them open with the same two words`() {
        // given
        val anime = anime(
            "Wonder Egg Priority: Watashi no Priority",
            "Wonder Egg Priority Episode 13, Wonder Egg Priority Special",
            "Wonder Egg Priority Episode 13",
            "Wonder Egg Priority Special",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "Wonder Egg Priority Episode 13, Wonder Egg Priority Special",
            "Wonder Egg Priority Episode 13",
            "Wonder Egg Priority Special",
        )
    }

    @Test
    fun `matches pieces regardless of case, apostrophe style and closing dots`() {
        // given
        val anime = anime(
            "100-man no Inochi no Ue ni Ore wa Tatteiru",
            "I\u2019m standing on 1,000,000 lives.",
            "I\u2019m Standing on 1",
            "000",
            "000 Lives",
            "I'm Standing on a Million Lives",
        )

        // when
        val result = CommaSplitSynonyms.dropPieces(anime)

        // then
        assertThat(result.synonyms).containsExactlyInAnyOrder(
            "I\u2019m standing on 1,000,000 lives.",
            "I'm Standing on a Million Lives",
        )
    }
}
