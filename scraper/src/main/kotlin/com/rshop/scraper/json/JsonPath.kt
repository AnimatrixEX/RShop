package com.rshop.scraper.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tiny path language for picking values out of a JSON document, the JSON counterpart of
 * [com.rshop.scraper.config.FieldRule]:
 *
 * - `title`, `data.game.title`: object members;
 * - `images[0]`: one array element (negative counts from the end: `images[-1]`);
 * - `images[]`: every element of the array, `downloads[].url` the `url` of each element;
 * - an empty path or `$` is the document itself.
 *
 * A path that matches nothing yields no value; it never throws, so a rule list can fall through
 * to its next entry.
 */
class JsonPath private constructor(val source: String, private val steps: List<Step>) {

    private sealed interface Step {
        data class Member(val name: String) : Step
        data class Index(val index: Int) : Step
        data object Each : Step
    }

    /** Every value the path reaches; JSON nulls are dropped. */
    fun select(root: JsonElement?): List<JsonElement> {
        var current = listOfNotNull(root)
        for (step in steps) {
            current = current.flatMap { element ->
                when (step) {
                    is Step.Member -> listOfNotNull((element as? JsonObject)?.get(step.name))
                    is Step.Index -> (element as? JsonArray)?.let { array ->
                        val at = if (step.index < 0) array.size + step.index else step.index
                        listOfNotNull(array.getOrNull(at))
                    }.orEmpty()
                    Step.Each -> (element as? JsonArray)?.toList().orEmpty()
                }
            }
        }
        return current.filter { it !is JsonNull }
    }

    fun first(root: JsonElement?): JsonElement? = select(root).firstOrNull()

    companion object {
        private val INDEX = Regex("""\[(-?\d*)]""")

        /** Throws [IllegalArgumentException] when the path is malformed. */
        fun parse(path: String): JsonPath {
            val text = path.trim()
            if (text.isEmpty() || text == "$") return JsonPath(text, emptyList())
            val steps = mutableListOf<Step>()
            for (segment in text.removePrefix("$.").split('.')) {
                require(segment.isNotEmpty()) { "empty segment in path '$path'" }
                val bracket = segment.indexOf('[')
                val name = if (bracket < 0) segment else segment.substring(0, bracket)
                if (name.isNotEmpty()) steps += Step.Member(name)
                if (bracket >= 0) {
                    val rest = segment.substring(bracket)
                    val matches = INDEX.findAll(rest).toList()
                    require(matches.isNotEmpty() && matches.joinToString("") { it.value } == rest) { "bad index in path '$path'" }
                    for (match in matches) {
                        val digits = match.groupValues[1]
                        steps += if (digits.isEmpty()) Step.Each else Step.Index(digits.toIntOrNull() ?: throw IllegalArgumentException("bad index in path '$path'"))
                    }
                }
            }
            return JsonPath(text, steps)
        }
    }
}

/** Text of a JSON primitive; objects and arrays have none. */
internal fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Readable text of whatever a rule reached: a string or number as is, a boolean as such, an array of
 * values joined with ", " (genres), nothing for an object.
 */
internal fun JsonElement.display(): String? = when (this) {
    is JsonPrimitive -> text()
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.text() }.joinToString(", ").takeIf { it.isNotEmpty() }
    else -> null
}
