package com.example.ptt.widget

/** The compact layout shows only the first word of the display name; the full name stays in descriptions. */
internal fun firstName(name: String) = name.trim().split(Regex("[\\s\\p{Z}]+")).first().ifEmpty { name }

/** The compact label of [name]: its first word, unless another listed person shares that first word. */
internal fun compactLabel(name: String, all: List<String>): String {
    val first = firstName(name)
    return if (all.count { firstName(it) == first } > 1) name else first
}
