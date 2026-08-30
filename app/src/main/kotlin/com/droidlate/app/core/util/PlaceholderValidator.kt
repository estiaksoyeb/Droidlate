package com.droidlate.app.core.util

import java.util.regex.Pattern

data class ValidationWarning(
    val type: WarningType,
    val message: String
)

enum class WarningType {
    MISSING_PLACEHOLDER,
    EXTRA_PLACEHOLDER,
    HTML_TAG_MISMATCH,
    UNESCAPED_APOSTROPHE
}

object PlaceholderValidator {

    private val PLACEHOLDER_PATTERN = Pattern.compile("""%(?:\d+\$)?[-+ 0,(#]*\d*(?:\.\d+)?[a-zA-Z%]""")
    private val HTML_TAG_PATTERN = Pattern.compile("""<(/?[a-zA-Z0-9]+)[^>]*>""")

    /**
     * Validates whether translation string preserves all placeholders and HTML tags from source string.
     */
    fun validate(source: String, translation: String): List<ValidationWarning> {
        if (translation.isBlank()) return emptyList()

        val warnings = mutableListOf<ValidationWarning>()

        // 1. Check placeholders like %s, %1$d, %2$f, etc. using occurrence frequencies
        val srcPlaceholders = extractMatches(PLACEHOLDER_PATTERN, source)
        val tgtPlaceholders = extractMatches(PLACEHOLDER_PATTERN, translation)

        val srcPhCounts = srcPlaceholders.groupingBy { it }.eachCount()
        val tgtPhCounts = tgtPlaceholders.groupingBy { it }.eachCount()

        for ((ph, srcCount) in srcPhCounts) {
            val tgtCount = tgtPhCounts[ph] ?: 0
            if (tgtCount < srcCount) {
                val diff = srcCount - tgtCount
                warnings.add(
                    ValidationWarning(
                        type = WarningType.MISSING_PLACEHOLDER,
                        message = if (diff > 1) "Missing $diff occurrences of placeholder: $ph" else "Missing placeholder: $ph"
                    )
                )
            }
        }

        for ((ph, tgtCount) in tgtPhCounts) {
            val srcCount = srcPhCounts[ph] ?: 0
            if (tgtCount > srcCount) {
                warnings.add(
                    ValidationWarning(
                        type = WarningType.EXTRA_PLACEHOLDER,
                        message = "Unexpected placeholder: $ph"
                    )
                )
            }
        }

        // 2. Check HTML tags using occurrence frequencies
        val srcTags = extractMatches(HTML_TAG_PATTERN, source)
        val tgtTags = extractMatches(HTML_TAG_PATTERN, translation)

        val srcTagCounts = srcTags.groupingBy { it }.eachCount()
        val tgtTagCounts = tgtTags.groupingBy { it }.eachCount()

        for ((tag, srcCount) in srcTagCounts) {
            val tgtCount = tgtTagCounts[tag] ?: 0
            if (tgtCount < srcCount) {
                warnings.add(
                    ValidationWarning(
                        type = WarningType.HTML_TAG_MISMATCH,
                        message = "Missing HTML tag: $tag"
                    )
                )
            }
        }

        for ((tag, tgtCount) in tgtTagCounts) {
            val srcCount = srcTagCounts[tag] ?: 0
            if (tgtCount > srcCount) {
                warnings.add(
                    ValidationWarning(
                        type = WarningType.HTML_TAG_MISMATCH,
                        message = "Extra HTML tag: $tag"
                    )
                )
            }
        }

        // 3. Check unescaped single quotes/apostrophes in Android XML strings
        if (translation.contains("'") && !translation.contains("\\'") && !translation.startsWith("\"")) {
            warnings.add(
                ValidationWarning(
                    type = WarningType.UNESCAPED_APOSTROPHE,
                    message = "Apostrophe (') may require escaping (\\') in Android XML"
                )
            )
        }

        return warnings
    }

    private fun extractMatches(pattern: Pattern, text: String): List<String> {
        val matcher = pattern.matcher(text)
        val results = mutableListOf<String>()
        while (matcher.find()) {
            results.add(matcher.group())
        }
        return results
    }
}
