/// Bilingual grammar and punctuation corrector for English and Arabic.
library;

final RegExp _enFillers = RegExp(r'\b(um|uh|uhm|er|ah|erm)\b[,\s]*', caseSensitive: false);
final RegExp _arFillers = RegExp(r'(^|\s)(يعني|أمم|اممم|آآآ|ااه|إيه|هاه|يعنى)(\s|$|(?=[،.؟!]))');
final RegExp _repeatedWords = RegExp(r'\b([\p{L}\p{N}]+)\s+\1\b', caseSensitive: false, unicode: true);
final RegExp _standaloneI = RegExp(r'(^|\s)i(\s|[.,!?;' r']|$)');
final RegExp _iContractions = RegExp(r"\bi('(?:m|ve|ll|d))\b", caseSensitive: false);

final Set<String> _enQuestionStarters = {
  'who', 'what', 'where', 'when', 'why', 'how',
  'is', 'are', 'was', 'were', 'can', 'could', 'should', 'would',
  'do', 'does', 'did', 'will', 'have', 'has', 'am'
};

final Set<String> _arQuestionStarters = {
  'هل', 'ماذا', 'ما', 'كيف', 'متى', 'أين', 'لماذا', 'كم', 'من', 'أي', 'أ'
};

/// Returns true if the string predominantly contains Arabic characters.
bool isArabic(String text) {
  var arCount = 0;
  var enCount = 0;
  for (final rune in text.runes) {
    if ((rune >= 0x0600 && rune <= 0x06FF) ||
        (rune >= 0x0750 && rune <= 0x077F) ||
        (rune >= 0xFB50 && rune <= 0xFDFF) ||
        (rune >= 0xFE70 && rune <= 0xFEFF)) {
      arCount++;
    } else if ((rune >= 65 && rune <= 90) || (rune >= 97 && rune <= 122)) {
      enCount++;
    }
  }
  return arCount > enCount || (arCount > 0 && enCount == 0);
}

/// Corrects grammar, removes fillers, and formats punctuation.
String correctBilingualGrammar(String? raw, [String language = 'auto']) {
  if (raw == null || raw.trim().isEmpty) return '';

  var text = raw.trim();
  final isAr = language == 'ar' ? true : (language == 'en' ? false : isArabic(text));

  // 1. Remove filler words
  text = isAr
      ? text.replaceAllMapped(_arFillers, (m) => '${m.group(1)}${m.group(3)}')
      : text.replaceAll(_enFillers, ' ');

  // 2. Remove repeated words
  text = text.replaceAllMapped(_repeatedWords, (m) => m.group(1)!);

  // 3. Clean spaces
  text = text.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (text.isEmpty) return '';

  // 4. Language-specific formatting
  return isAr ? _correctArabic(text) : _correctEnglish(text);
}

String _correctEnglish(String text) {
  var t = text;
  t = t.replaceAllMapped(_standaloneI, (m) => '${m.group(1)}I${m.group(2)}');
  t = t.replaceAllMapped(_iContractions, (m) => 'I${m.group(1)}');
  t = t.replaceAll(RegExp(r'\s+([.,!?;:])'), r'$1');

  if (t.isNotEmpty && t[0].toLowerCase() != t[0].toUpperCase()) {
    t = t[0].toUpperCase() + t.substring(1);
  }

  t = t.replaceAllMapped(RegExp(r'([.!?]\s+)([a-z])'), (m) => '${m.group(1)}${m.group(2)!.toUpperCase()}');

  final lastChar = t[t.length - 1];
  if (RegExp(r'[\p{L}\p{N}]', unicode: true).hasMatch(lastChar)) {
    final firstWord = t.split(' ').first.trim().toLowerCase();
    t = _enQuestionStarters.contains(firstWord) ? '$t?' : '$t.';
  }

  return t;
}

String _correctArabic(String text) {
  var t = text
      .replaceAll(',', '،')
      .replaceAll(';', '؛')
      .replaceAll('?', '؟');

  t = t.replaceAll(RegExp(r'\s+([،؛؟.\!])'), r'$1');
  t = t.replaceAllMapped(RegExp(r'([،؛؟.\!])(?=[\p{L}\p{N}])', unicode: true), (m) => '${m.group(1)} ');

  final lastChar = t[t.length - 1];
  if (RegExp(r'[\p{L}\p{N}]', unicode: true).hasMatch(lastChar)) {
    final firstWord = t.split(' ').first.trim();
    t = _arQuestionStarters.contains(firstWord) ? '$t؟' : '$t.';
  }

  return t;
}
