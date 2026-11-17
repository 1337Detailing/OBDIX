package com.obdix.app.core

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    companion object {
        fun parse(tag: String): ReleaseVersion? {
            val parts = tag.removePrefix("v").split(".")
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 || it > 999 }) return null
            return ReleaseVersion(numbers[0], numbers[1], numbers[2])
        }
    }
    fun code(): Int = major * 1_000_000 + minor * 1_000 + patch
    override fun compareTo(other: ReleaseVersion): Int = code().compareTo(other.code())
}
