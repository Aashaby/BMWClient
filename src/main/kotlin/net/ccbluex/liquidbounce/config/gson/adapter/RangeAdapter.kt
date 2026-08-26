/*
 * Backwards-compatible range adapter.
 */
package net.ccbluex.liquidbounce.config.gson.adapter

import com.google.gson.*
import java.lang.reflect.Type

object RangeAdapter : JsonSerializer<ClosedRange<*>>, JsonDeserializer<ClosedRange<*>> {

    override fun serialize(src: ClosedRange<*>, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonObject().apply {
            add("from", context.serialize(src.start))
            add("to", context.serialize(src.endInclusive))
        }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ClosedRange<*> {
        fun numberPair(): Pair<Double, Double> {
            when {
                json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> {
                    val n = json.asDouble
                    return n to n
                }
                json.isJsonArray && json.asJsonArray.size() >= 2 -> {
                    return json.asJsonArray[0].asDouble to json.asJsonArray[1].asDouble
                }
                json.isJsonObject -> {
                    val obj = json.asJsonObject
                    val from = obj.get("from") ?: obj.get("min") ?: obj.get("start")
                    val to = obj.get("to") ?: obj.get("max") ?: obj.get("end")
                    if (from != null && to != null) return from.asDouble to to.asDouble
                }
            }
            throw JsonParseException("Invalid range format")
        }

        val (from, to) = numberPair()
        return when (typeOfT) {
            (0.0f..5.0f).javaClass -> from.toFloat()..to.toFloat()
            (0.0..5.0).javaClass -> from..to
            else -> throw JsonParseException("Unsupported range type: $typeOfT")
        }
    }
}
