package dev.whispr.whispr_ai

import java.util.Locale

/**
 * On-device bilingual grammar, punctuation, and filler word corrector
 * for English and Arabic dictation.
 */
object BilingualGrammarCorrector {

    private val enFillers = Regex("\\b(um|uh|uhm|er|ah|erm)\\b[,\\s]*", RegexOption.IGNORE_CASE)
    private val arFillers = Regex("(^|\\s)(يعني|أمم|اممم|آآآ|ااه|إيه|هاه|يعنى)(\\s|$|(?=[،.؟!]))")
    private val repeatedWords = Regex("\\b([\\p{L}\\p{N}]+)\\s+\\1\\b", RegexOption.IGNORE_CASE)
    private val standaloneI = Regex("(^|\\s)i(\\s|[.,!?;']|$)")
    private val iContractions = Regex("\\bi('(?:m|ve|ll|d))\\b")

    private val enQuestionStarters = hashSetOf(
        "who", "what", "where", "when", "why", "how",
        "is", "are", "was", "were", "can", "could", "should", "would",
        "do", "does", "did", "will", "have", "has", "am"
    )

    private val enAuxiliaries = hashSetOf(
        "do", "does", "did", "is", "are", "was", "were", "can", "could", "should", "would",
        "will", "have", "has", "am"
    )

    private val arQuestionStarters = hashSetOf(
        "هل", "ماذا", "ما", "كيف", "متى", "أين", "لماذا", "كم", "من", "أي", "أ"
    )

    fun isArabic(text: String): Boolean {
        var arCount = 0
        var enCount = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF) {
                arCount++
            } else if (ch in 'a'..'z' || ch in 'A'..'Z') {
                enCount++
            }
        }
        return arCount > enCount || (arCount > 0 && enCount == 0)
    }

    fun correct(raw: String?, langPreference: String = "auto"): String {
        if (raw.isNullOrBlank()) return ""

        var text = raw.trim()
        val isAr = if (langPreference == "ar") true else if (langPreference == "en") false else isArabic(text)

        // 1. Remove filler words
        text = if (isAr) arFillers.replace(text, "$1$3") else enFillers.replace(text, " ")

        // 2. Remove stutter / duplicated words
        text = repeatedWords.replace(text, "$1")

        // 3. Clean spacing
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""

        // 4. Grammar and punctuation rules
        return if (isAr) correctArabic(text) else correctEnglish(text)
    }

    private fun correctEnglish(text: String): String {
        var t = text
        // Capitalize standalone 'I' and 'I'm', 'I've'
        t = standaloneI.replace(t) { m -> m.groupValues[1] + "I" + m.groupValues[2] }
        t = iContractions.replace(t) { m -> "I" + m.groupValues[1] }

        // Normalize spacing before punctuation
        t = t.replace(Regex("\\s+([.,!?;:])"), "$1")

        // Capitalize first character
        if (t.isNotEmpty() && t[0].isLowerCase()) {
            t = t[0].uppercaseChar() + t.substring(1)
        }

        // Capitalize after terminal punctuation
        t = t.replace(Regex("([.!?]\\s+)([a-z])")) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase(Locale.ROOT)
        }

        // Add terminal punctuation
        val lastChar = t.last()
        if (!lastChar.isLetterOrDigit()) return t

        return if (isEnglishQuestion(t)) "$t?" else "$t."
    }

    private fun isEnglishQuestion(text: String): Boolean {
        val clauses = text.split(Regex("[,.!;:]"))
        for (clause in clauses) {
            val words = clause.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val first = words[0].lowercase(Locale.ROOT)
            if (first == "when" || first == "where" || first == "if") {
                if (words.size > 1 && enAuxiliaries.contains(words[1].lowercase(Locale.ROOT))) {
                    return true
                }
            } else if (enQuestionStarters.contains(first)) {
                return true
            }
        }
        return false
    }

    private fun correctArabic(text: String): String {
        var t = text
            .replace(',', '،')
            .replace(';', '؛')
            .replace('?', '؟')

        // Normalize spacing around Arabic punctuation
        t = t.replace(Regex("\\s+([،؛؟.\\!])"), "$1")
        t = t.replace(Regex("([،؛؟.\\!])(?=[\\p{L}\\p{N}])"), "$1 ")

        val lastChar = t.last()
        if (!lastChar.isLetterOrDigit()) return t

        return if (isArabicQuestion(t)) "$t؟" else "$t."
    }

    private fun isArabicQuestion(text: String): Boolean {
        val clauses = text.split(Regex("[،؛؟.!,;]"))
        for (clause in clauses) {
            val words = clause.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val first = words[0].replace(Regex("[،؛؟.!,;]"), "")
            if (arQuestionStarters.contains(first)) {
                return true
            }
        }
        return false
    }
}
