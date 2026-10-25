package com.example.ptt.crypto

import java.io.File

/** Loads the shared `testdata/protocol-vectors.json` (path from the `ptt.protocolVectors` system property). */
object VectorFile {
    private val root: JsonValue.Obj by lazy {
        val path = requireNotNull(System.getProperty("ptt.protocolVectors")) { "ptt.protocolVectors is not set" }
        StrictJson.parse(File(path).readBytes()).asObject("vectors")
    }

    fun section(name: String): List<JsonValue.Obj> = root.array(name).map { it.asObject(name) }

    fun b64(v: JsonValue.Obj, key: String): ByteArray = B64u.decode(v.string(key))
}
