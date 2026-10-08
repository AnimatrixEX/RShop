package com.rshop.domain.genre

import androidx.annotation.StringRes
import com.rshop.R
import java.text.Normalizer

/**
 * The genres RShop recognises. [genreTerms] match the genre/category text a site gives;
 * [freeTerms] are stricter and are looked for in titles and descriptions (series names included).
 * Terms are regular expressions over accent-free lower-case text, matched as whole words
 * (an optional plural "s" is accepted).
 */
enum class Genre(val key: String, @StringRes val labelRes: Int, genreTerms: String, freeTerms: String) {
    Action("action", R.string.genre_action, "action|beat em up|hack and slash|brawler|baston", "beat em up|hack and slash"),
    Adventure("adventure", R.string.genre_adventure, "adventure|aventure|point and click|graphic adventure|text adventure|interactive fiction", "adventure|aventure|point and click"),
    Rpg(
        "rpg", R.string.genre_rpg,
        "rpg|jrpg|arpg|srpg|role play(?:ing)?|roleplaying|jeu de role|dungeon crawler",
        "rpg|jrpg|role playing|dungeon crawler|dragon quest|final fantasy|pokemon|persona|chrono|earthbound|ultima|diablo|breath of fire|phantasy star|tales of|xenoblade|kingdom hearts|secret of mana|fire emblem",
    ),
    Strategy("strategy", R.string.genre_strategy, "strateg(?:y|ie)|tactic(?:s|al)?|rts|4x|tower defen[cs]e|turn based|tbs|war ?game", "strategy|real time strategy|rts|tower defen[cs]e|turn based|wargame"),
    Simulation("simulation", R.string.genre_simulation, "simulation|simulator|sim|management|tycoon|life sim|flight sim|construction", "simulation|simulator|tycoon|management sim|city builder|farming"),
    Sports(
        "sports", R.string.genre_sports,
        "sports?|football|soccer|basketball|baseball|tennis|golf|hockey|rugby|boxing|skate(?:board(?:ing)?)?|snowboard(?:ing)?|wrestling|olympic|volleyball|bowling|billiards?",
        "football|soccer|basketball|baseball|tennis|golf|hockey|rugby|nba|nfl|nhl|fifa|pes|madden|volleyball|bowling|snowboard|skateboard|olympic|wrestling",
    ),
    Racing("racing", R.string.genre_racing, "racing|race|course|driving|rally|kart|motocross|f1|formula one", "racing|racer|kart|rally|grand prix|formula 1|motocross|need for speed|ridge racer|gran turismo|outrun|out run|burnout|forza"),
    Fighting(
        "fighting", R.string.genre_fighting,
        "fighting|fighter|versus|vs|combat",
        "fighting|fighter|street fighter|mortal kombat|tekken|king of fighters|soulcalibur|virtua fighter|smash bros|guilty gear|dead or alive|killer instinct|samurai shodown|darkstalkers",
    ),
    Shooter(
        "shooter", R.string.genre_shooter,
        "shooter|shoot em up|shmup|fps|tps|light ?gun|gun|run and gun|shooting",
        "shoot em up|shmup|first person shooter|fps|light ?gun|run and gun|shooter|call of duty|metal slug|contra|gradius|r type|doom",
    ),
    Platformer(
        "platformer", R.string.genre_platformer,
        "platform(?:er|s)?|plateforme|jump and run|jump n run|metroidvania|run n gun",
        "platformer|metroidvania|jump and run|super mario|sonic|donkey kong|crash bandicoot|mega man|rayman|kirby|castlevania",
    ),
    Puzzle("puzzle", R.string.genre_puzzle, "puzzle|logic|brain|reflexion|match 3|tetris|falling block", "puzzle|tetris|match 3|bust a move|lemmings|puyo|dr mario|minesweeper|sokoban"),
    Horror("horror", R.string.genre_horror, "horror|horreur|survival horror|zombie", "survival horror|horror|resident evil|silent hill|alone in the dark|fatal frame|clock tower|zombie"),
    Music("music", R.string.genre_music, "music|musique|rhythm|rythme|dance|karaoke", "rhythm|dance dance|guitar hero|karaoke|parappa|beatmania"),
    Arcade("arcade", R.string.genre_arcade, "arcade|pinball|flipper|coin op|pac man|pong", "pinball|arcade|pac man|pacman|galaga|space invaders"),
    CardsBoard(
        "cards-board", R.string.genre_cards_board,
        "cards?|board(?: game)?|card game|cartes|casino|mahjong|chess|echecs|poker|quiz|trivia|party|tabletop|dice|gambling|solitaire|bingo",
        "mahjong|chess|poker|blackjack|solitaire|casino|backgammon|trivia|quiz|monopoly|bingo|checkers|othello|shogi",
    ),
    Educational("educational", R.string.genre_educational, "educational?|education|educatif|learning|kids?|children", "educational"),
    VisualNovel("visual-novel", R.string.genre_visual_novel, "visual novel|vn|dating sim|dating|otome|interactive novel", "visual novel|dating sim|otome"),
    Survival("survival", R.string.genre_survival, "survival|craft(?:ing)?|sandbox|open world", "survival|crafting|sandbox|open world"),
    ;

    internal val genreRegex = wordsRegex(genreTerms)
    internal val freeRegex = wordsRegex(freeTerms)
}

private fun wordsRegex(terms: String) = Regex("(?<![a-z0-9])(?:$terms)s?(?![a-z0-9])")

/**
 * Turns whatever a site says about a game (genre/category text, title, description) into a short
 * list of tags: known genres by [Genre.key], plus the site's own category names that are not
 * generic noise. The result feeds the store filters and the category shelves.
 */
object GenreClassifier {

    fun classify(genre: String?, title: String, description: String?, platform: String?): List<String> {
        val tags = LinkedHashSet<String>()

        // 1. The site's own genre/category text, one token per category.
        val platformKey = platform?.let(::normalize)
        for (raw in genre.orEmpty().split(SPLIT)) {
            val token = normalize(raw)
            if (token.isEmpty() || token == platformKey) continue
            val known = Genre.entries.filter { it.genreRegex.containsMatchIn(token) }
            if (known.isNotEmpty()) {
                known.forEach { tags += it.key }
            } else if (isUsefulCustomTag(token)) {
                tags += titleCase(raw)
            }
        }

        // 2. The title: series and genre words are strong signals.
        val normalizedTitle = normalize(title)
        Genre.entries.filter { it.freeRegex.containsMatchIn(normalizedTitle) }.forEach { tags += it.key }

        // 3. The description: a genre must come up twice, or in its first sentence.
        description?.let { text ->
            val normalized = normalize(text)
            val head = normalized.take(HEAD_LENGTH)
            Genre.entries.forEach { g ->
                if (g.key in tags) return@forEach
                val hits = g.freeRegex.findAll(normalized).count()
                if (hits >= 2 || (hits == 1 && g.freeRegex.containsMatchIn(head))) tags += g.key
            }
        }
        return tags.take(MAX_TAGS)
    }

    private fun isUsefulCustomTag(token: String): Boolean =
        token.length in 3..28 && !NOISE.containsMatchIn(token) && !YEAR.containsMatchIn(token)

    private fun titleCase(raw: String): String =
        raw.trim().split(Regex("\\s+")).joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.titlecase() } }
            .replace(Regex("[|%_]"), "")

    /** Lower-case, accent-free, punctuation turned into spaces (apostrophes included: "beat 'em up"). */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private val SPLIT = Regex("[,;/|>&•·\\n]+| - ")
    private val NOISE = Regex("(?<![a-z0-9])(games?|roms?|downloads?|all|uncategori[sz]ed|misc|miscellaneous|other|others|new|popular|hacks?|translations?|homebrew|demo|free|best|top|usa|europe|japan|world|region)(?![a-z0-9])")
    private val YEAR = Regex("(19|20)\\d{2}")
    private const val HEAD_LENGTH = 200
    private const val MAX_TAGS = 5
}

/** Tags are stored in one column as "|rpg|Strategy|" so a single LIKE '%|rpg|%' filters on one tag. */
object TagCodec {
    fun encode(tags: List<String>): String? =
        tags.map { it.replace(Regex("[|%_]"), "") }.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.joinToString("|", "|", "|")

    fun decode(column: String?): List<String> = column?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()

    /** The LIKE pattern matching [tag] in an encoded column. */
    fun pattern(tag: String): String = "%|${tag.replace(Regex("[|%_]"), "")}|%"
}

@StringRes
fun genreLabelRes(tag: String): Int? = Genre.entries.firstOrNull { it.key == tag }?.labelRes
