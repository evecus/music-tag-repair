package com.musictagrepair.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 简易 JSON 工具
 */
object JsonUtils {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 将 Map<String, Any?> 编码为 JSON 字符串（支持 String/Number/Boolean/null）
     */
    fun encodeMap(map: Map<String, Any?>): String {
        val obj = buildJsonObject {
            map.forEach { (k, v) ->
                when (v) {
                    null -> put(k, JsonNull)
                    is String -> put(k, v)
                    is Number -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
