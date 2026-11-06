using System.Text;
using System.Text.RegularExpressions;

namespace Murmur.Core;

/// <summary>
/// Corrects grammar, normalizes punctuation, capitalizes sentences, and removes disfluencies/fillers
/// for bilingual English and Arabic dictation.
/// </summary>
public class BilingualGrammarCorrector
{
    private static readonly Lazy<BilingualGrammarCorrector> s_instance = new(() => new BilingualGrammarCorrector());

    /// <summary>Singleton instance of the bilingual grammar corrector.</summary>
    public static BilingualGrammarCorrector Instance => s_instance.Value;

    // English filler words and hesitations
    private static readonly Regex EnFillers = new(
        @"\b(um|uh|uhm|er|ah|erm)\b[,\s]*",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    // Arabic filler words and hesitations
    private static readonly Regex ArFillers = new(
        @"(^|\s)(يعني|أمم|اممم|آآآ|ااه|إيه|هاه|يعنى)(\s|$|(?=[،.؟!]))",
        RegexOptions.Compiled);

    // Repeated words from stuttering/hesitation (e.g. "the the", "في في")
    private static readonly Regex RepeatedWords = new(
        @"\b([\p{L}\p{N}]+)\s+\1\b",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    // English standalone 'i' capitalization
    private static readonly Regex StandaloneI = new(
        @"(^|\s)i(\s|[.,!?;']|$)",
        RegexOptions.Compiled);

    private static readonly Regex IContractions = new(
        @"\bi('(?:m|ve|ll|d))\b",
        RegexOptions.Compiled);

    private static readonly HashSet<string> EnQuestionStarters = new(StringComparer.OrdinalIgnoreCase)
    {
        "who", "what", "where", "when", "why", "how",
        "is", "are", "was", "were", "can", "could", "should", "would",
        "do", "does", "did", "will", "have", "has", "am"
    };

    private static readonly HashSet<string> EnAuxiliaries = new(StringComparer.OrdinalIgnoreCase)
    {
        "do", "does", "did", "is", "are", "was", "were", "can", "could", "should", "would",
        "will", "have", "has", "am"
    };

    private static readonly HashSet<string> ArQuestionStarters = new()
    {
        "هل", "ماذا", "ما", "كيف", "متى", "أين", "لماذا", "كم", "من", "أي", "أ",
        "ازاي", "ليه", "فين", "إيه", "ايه", "مين", "كام", "شو", "وين", "علاش", "شلون", "شنو", "ايش"
    };

    private static readonly char[] EnClauseDelimiters = [',', '.', '!', ';', ':'];
    private static readonly char[] ArClauseDelimiters = ['،', '؛', '؟', '.', '!', ',', ';'];
    private static readonly char[] PunctuationToTrim = ['،', '؛', '؟', '.', '!', ',', ';'];

    /// <summary>Whether the text predominantly contains Arabic characters.</summary>
    public static bool IsArabic(string text)
    {
        var arCount = 0;
        var enCount = 0;
        foreach (var ch in text)
        {
            if (ch is >= '\u0600' and <= '\u06FF' or >= '\u0750' and <= '\u077F' or >= '\uFB50' and <= '\uFDFF' or >= '\uFE70' and <= '\uFEFF')
                arCount++;
            else if (char.IsAsciiLetter(ch))
                enCount++;
        }
        return arCount > enCount || (arCount > 0 && enCount == 0);
    }

    /// <summary>Applies bilingual grammar, punctuation, and filler correction to the text.</summary>
    [System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1822:Mark members as static", Justification = "Instance member preserved for dependency injection and mocking")]
    public virtual string Correct(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return string.Empty;

        var text = raw.Trim();
        var isArabic = IsArabic(text);

        // 1. Remove filler words & hesitations
        text = isArabic ? CleanArabicFillers(text) : CleanEnglishFillers(text);

        // 2. Remove duplicate stutter words ("the the" -> "the", "في في" -> "في")
        text = RepeatedWords.Replace(text, "$1");

        // 3. Normalize multiple spaces
        text = Regex.Replace(text, @"\s+", " ").Trim();
        if (text.Length == 0) return string.Empty;

        // 4. Language-specific grammar & punctuation rules
        return isArabic ? CorrectArabic(text) : CorrectEnglish(text);
    }

    private static string CleanEnglishFillers(string text)
    {
        var cleaned = EnFillers.Replace(text, " ");
        return cleaned;
    }

    private static string CleanArabicFillers(string text)
    {
        var cleaned = ArFillers.Replace(text, "$1$3");
        return cleaned;
    }

    private static string CorrectEnglish(string text)
    {
        // Capitalize standalone 'I' and 'I'm', 'I've', etc.
        text = StandaloneI.Replace(text, m => m.Groups[1].Value + "I" + m.Groups[2].Value);
        text = IContractions.Replace(text, m => "I" + m.Groups[1].Value);

        // Normalize spacing before punctuation
        text = Regex.Replace(text, @"\s+([.,!?;:])", "$1");

        // Capitalize the first letter of the overall text
        if (char.IsLower(text[0]))
        {
            text = char.ToUpperInvariant(text[0]) + text[1..];
        }

        // Capitalize after sentence-ending punctuation (.!?)
        text = Regex.Replace(text, @"([.!?]\s+)([a-z])", m =>
            m.Groups[1].Value + char.ToUpperInvariant(m.Groups[2].Value[0]));

        // Check if sentence starts with a question and lacks terminal punctuation
        var lastChar = text[^1];
        if (!char.IsPunctuation(lastChar))
        {
            text += IsEnglishQuestion(text) ? "?" : ".";
        }

        return text;
    }

    private static bool IsEnglishQuestion(string text)
    {
        var clauses = text.Split(EnClauseDelimiters, StringSplitOptions.RemoveEmptyEntries);
        foreach (var clause in clauses)
        {
            var words = clause.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (words.Length == 0) continue;
            var first = words[0];
            if (first.Equals("when", StringComparison.OrdinalIgnoreCase) ||
                first.Equals("where", StringComparison.OrdinalIgnoreCase) ||
                first.Equals("if", StringComparison.OrdinalIgnoreCase))
            {
                if (words.Length > 1 && EnAuxiliaries.Contains(words[1]))
                    return true;
            }
            else if (EnQuestionStarters.Contains(first))
            {
                return true;
            }
        }
        return false;
    }

    private static string CorrectArabic(string text)
    {
        // Convert Latin punctuation to Arabic equivalents
        text = text.Replace(',', '،')
                   .Replace(';', '؛')
                   .Replace('?', '؟');

        // Remove stray dots between words from system recognizer fragmentation
        text = Regex.Replace(text, @"(?<=[\p{L}\p{N}])\s*\.\s*(?=[\p{L}\p{N}])", " ");
        text = Regex.Replace(text, @"\.{2,}", " ");

        // Normalize spacing: no space before punctuation, single space after
        text = Regex.Replace(text, @"\s+([،؛؟.\!])", "$1");
        text = Regex.Replace(text, @"([،؛؟.\!])(?=[\p{L}\p{N}])", "$1 ");

        // Check if text ends with punctuation; if not, determine if question or period
        var lastChar = text[^1];
        if (!char.IsPunctuation(lastChar))
        {
            text += IsArabicQuestion(text) ? "؟" : ".";
        }

        return text;
    }

    private static bool IsArabicQuestion(string text)
    {
        var clauses = text.Split(ArClauseDelimiters, StringSplitOptions.RemoveEmptyEntries);
        foreach (var clause in clauses)
        {
            var words = clause.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (words.Length == 0) continue;
            var first = words[0].Trim(PunctuationToTrim);
            if (ArQuestionStarters.Contains(first))
                return true;
        }
        return false;
    }
}
