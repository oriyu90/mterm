package dev.studiorizi.mterm.core.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** JSON encoding for [SessionEntity.commandJson] (argv list <-> String). */
object CommandJson {
    private val listSerializer = ListSerializer(String.serializer())

    fun encode(command: List<String>): String = Json.encodeToString(listSerializer, command)

    fun decode(commandJson: String): List<String> = Json.decodeFromString(listSerializer, commandJson)
}
