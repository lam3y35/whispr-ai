package dev.whispr.whispr_ai

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

enum class EntryKind { TERM, CORRECTION }

/** Characters that can separate the words of a trigger phrase. */
val SEPARATORS = charArrayOf(' ', '-', '\t')

/** One thing the dictionary knows. Mirrors DictionaryEntry (C#) / DictionaryEntry (Swift). */
data class DictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: EntryKind,
    val write: String,
    val hear: String = "",
    val isEnabled: Boolean = true,
) {
    companion object {
        fun term(word: String) = DictionaryEntry(kind = EntryKind.TERM, write = word)
        fun correction(hear: String, write: String) =
            DictionaryEntry(kind = EntryKind.CORRECTION, hear = hear, write = write)
    }

    /** Plain-text serialization: "hear -> write" for corrections, bare text for terms. */
    fun toFileLine(): String {
        val body = if (kind == EntryKind.CORRECTION) "$hear -> $write" else write
        return if (isEnabled) body else "# off: $body"
    }
}

/** One correction that actually fired. */
data class AppliedCorrection(val from: String, val to: String, val count: Int)

/**
 * Rewrites transcribed text using the dictionary's correction pairs.
 *
 * A faithful port of DictionaryCorrector.cs / DictionaryCorrector.swift. The three
 * load-bearing rules are unchanged:
 *  1. Longest match first — "Claude Code" is applied before "Claude".
 *  2. Whole matches only — fences are lookarounds on letters/digits, not \b, so a rule
 *     for "cloud code" can never touch "Cloudflare".
 *  3. Glued words still match — the gap between parts is optional whitespace or hyphens,
 *     so "CloudCode" and "Cloud-Code" are caught alongside the spaced form.
 */
object DictionaryCorrector {

    class Rule(val pattern: Pattern, val replacement: String)

    /** How many phrases to hand the speech engine as context. Kept small on purpose. */
    const val BIAS_LIMIT = 40


    fun normalizeNfc(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC)

    fun makePattern(trigger: String): Pattern? {
        val parts = normalizeNfc(trigger).trim()
            .split(*SEPARATORS)
            .filter { it.isNotEmpty() }
            .map { Pattern.quote(it) }
        if (parts.isEmpty()) return null
        val body = parts.joinToString("[\\s\\-]*")
        return try {
            // CASE_INSENSITIVE alone folds ASCII only; UNICODE_CASE gives the full
            // Unicode folding the C# CultureInvariant matching provides.
            Pattern.compile(
                "(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Compiles the enabled correction entries into an ordered rule set. */
    fun compileRules(entries: List<DictionaryEntry>): List<Rule> =
        entries
            .filter { it.isEnabled && it.kind == EntryKind.CORRECTION && it.hear.isNotBlank() }
            .sortedByDescending { it.hear.length } // stable sort: file order kept on ties
            .mapNotNull { e ->
                makePattern(e.hear)?.let { Rule(it, e.write) }
            }

    /**
     * Applies every rule in order. Returns the rewritten text plus one entry per rule
     * that fired, recording what the engine actually produced (which may differ in
     * case or spacing from the trigger).
     */
    fun apply(text: String, rules: List<Rule>): Pair<String, List<AppliedCorrection>> {
        if (rules.isEmpty() || text.isEmpty()) return text to emptyList()
        var result = normalizeNfc(text)
        val applied = mutableListOf<AppliedCorrection>()

        for (rule in rules) {
            val m = rule.pattern.matcher(result)
            var count = 0
            var heard: String? = null
            val sb = StringBuffer()
            while (m.find()) {
                if (heard == null) heard = m.group()
                count++
                // matcher.quoteReplacement keeps the user's text strictly literal —
                // "$1" and friends in a replacement must never become substitutions.
                m.appendReplacement(sb, Matcher_quoteReplacement(rule.replacement))
            }
            if (count == 0) continue
            m.appendTail(sb)
            result = sb.toString()
            applied.add(AppliedCorrection(heard ?: rule.replacement, rule.replacement, count))
        }
        return result to applied
    }

    // java.util.regex.Matcher.quoteReplacement is public; this shim exists only so the
    // call site reads cleanly without importing Matcher everywhere.
    private fun Matcher_quoteReplacement(s: String): String =
        java.util.regex.Matcher.quoteReplacement(s)

    /**
     * The correct spellings — Term words and the write side of corrections — capped at
     * [BIAS_LIMIT], de-duplicated case-insensitively, in file order.
     */
    fun biasPhrases(entries: List<DictionaryEntry>): List<String> {
        val seen = HashSet<String>()
        val phrases = mutableListOf<String>()
        for (entry in entries.filter { it.isEnabled }) {
            val phrase = entry.write.trim()
            if (phrase.isEmpty()) continue
            val key = phrase.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            phrases.add(phrase)
            if (phrases.size == BIAS_LIMIT) break
        }
        return phrases
    }
}

/**
 * A reason an entry looks likely to fire on text it wasn't meant to. Never blocks —
 * it is the user's dictionary. The common-word list is kept identical to the other
 * two platforms so all three warn about the same things.
 */
object DictionaryWarning {
    private val COMMON = setOf(
        "a", "about", "all", "also", "and", "any", "are", "as", "at", "back", "be", "because",
        "but", "by", "call", "can", "case", "check", "class", "close", "cloud", "code", "come",
        "could", "data", "day", "did", "do", "does", "down", "each", "even", "file", "find",
        "first", "for", "from", "get", "give", "go", "good", "great", "group", "had", "has",
        "have", "he", "her", "here", "him", "his", "how", "if", "in", "into", "is", "it",
        "its", "just", "key", "know", "like", "line", "list", "look", "make", "man", "many",
        "may", "me", "more", "most", "my", "need", "new", "no", "not", "now", "number", "of",
        "off", "on", "one", "only", "open", "or", "other", "our", "out", "over", "page",
        "part", "people", "point", "put", "read", "right", "run", "said", "same", "say",
        "see", "set", "she", "should", "show", "side", "so", "some", "state", "still", "such",
        "take", "team", "test", "than", "that", "the", "their", "them", "then", "there",
        "these", "they", "thing", "think", "this", "time", "to", "two", "type", "up", "us",
        "use", "user", "very", "want", "was", "way", "we", "well", "were", "what", "when",
        "where", "which", "who", "will", "with", "word", "work", "would", "year", "you",
        "your",
    )

    fun check(entry: DictionaryEntry): List<String> {
        if (entry.kind != EntryKind.CORRECTION) return emptyList()
        val trigger = entry.hear.trim()
        if (trigger.isEmpty()) return emptyList()

        val warnings = mutableListOf<String>()
        val words = trigger.split(*SEPARATORS).filter { it.isNotEmpty() }

        if (words.size == 1) {
            val only = words[0]
            when {
                COMMON.contains(only.lowercase(Locale.ROOT)) -> warnings.add(
                    "\u201c$trigger\u201d is an ordinary word. This will rewrite every use of it, " +
                        "not just the ones you mean. Consider a longer phrase."
                )
                only.length <= 3 -> warnings.add(
                    "\u201c$trigger\u201d is very short and will match often. Consider a longer phrase."
                )
            }
        }
        if (entry.write.trim().equals(trigger, ignoreCase = true)) {
            warnings.add(
                "This rewrites \u201c$trigger\u201d to itself, so it will never change anything."
            )
        }
        return warnings
    }
}
