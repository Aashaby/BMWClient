package net.ccbluex.liquidbounce.config.gson.adapter

import com.google.gson.*
import net.ccbluex.liquidbounce.integration.theme.Background
import net.ccbluex.liquidbounce.integration.theme.ThemeMetadata
import java.lang.reflect.Type

/**
 * Accepts both current metadata and older theme metadata that omitted optional
 * arrays/fields. Old themes should never prevent the client from starting.
 */
object ThemeMetadataAdapter : JsonDeserializer<ThemeMetadata> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ThemeMetadata {
        val o = json.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw JsonParseException("Theme metadata must be an object")

        fun strings(name: String) =
            o.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                it.takeIf { e -> e.isJsonPrimitive }?.asString
            } ?: emptyList()

        val id = o.get("id")?.asString?.takeIf { it.isNotBlank() } ?: "legacy-theme"
        val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: id
        val version = o.get("version")?.asString ?: "legacy"

        val backgrounds = o.get("backgrounds")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            runCatching {
                val b = e.asJsonObject
                Background(
                    b.get("name")?.asString ?: return@runCatching null,
                    b.get("types")?.asJsonArray?.map { it.asString } ?: emptyList()
                )
            }.getOrNull()
        } ?: emptyList()

        val values = o.get("values")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }

        return ThemeMetadata(
            id = id,
            name = name,
            version = version,
            authors = strings("authors"),
            screens = strings("screens"),
            overlays = strings("overlays"),
            components = strings("components"),
            fonts = strings("fonts"),
            backgrounds = backgrounds,
            values = values
        )
    }
}
