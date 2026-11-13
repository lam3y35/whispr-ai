// Bilingual grammar and punctuation corrector for English and Arabic.

final RegExp _enFillers = RegExp(r'\b(um|uh|uhm|er|ah|erm)\b[,\s]*', caseSensitive: false);
final RegExp _arFillers = RegExp(r'(^|\s)(يعني|أمم|اممم|آآآ|ااه|إيه|هاه|يعنى)(\s|$|(?=[،.؟!]))');
final RegExp _standaloneI = RegExp(r"(^|\s)i(?=\s|[.,!?;']|$)");
final RegExp _iContractions = RegExp(r"\bi('(?:m|ve|ll|d))\b", caseSensitive: false);
final RegExp _enSpacesPunct = RegExp(r'\s+([.,!?;:])');
final RegExp _enSentenceStart = RegExp(r'([.!?]\s+)([a-z])');
final RegExp _unicodeWordChar = RegExp(r'[\p{L}\p{N}]', unicode: true);
final RegExp _arSpacesPunct = RegExp(r'\s+([،؛؟.!])');
final RegExp _arPunctSpacing = RegExp(r'([،؛؟.!])(?=[\p{L}\p{N}])', unicode: true);
final RegExp _multipleSpaces = RegExp(r'\s+');

const Set<String> _enQuestionStarters = {
  'who', 'what', 'where', 'when', 'why', 'how',
  'is', 'are', 'was', 'were', 'can', 'could', 'should', 'would',
  'do', 'does', 'did', 'will', 'have', 'has', 'am'
};

const Set<String> _enAuxiliaries = {
  'do', 'does', 'did', 'is', 'are', 'was', 'were', 'can', 'could', 'should', 'would',
  'will', 'have', 'has', 'am'
};

const Set<String> _arQuestionStarters = {
  'هل', 'ماذا', 'ما', 'كيف', 'متى', 'أين', 'لماذا', 'كم', 'من', 'أي', 'أ',
  'ازاي', 'ليه', 'فين', 'إيه', 'ايه', 'مين', 'كام', 'شو', 'وين', 'علاش', 'شلون', 'شنو', 'ايش'
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

String _dedupeRepeatedWords(String text) {
  final words = text.split(RegExp(r'\s+'));
  final result = <String>[];
  for (final w in words) {
    if (result.isNotEmpty &&
        result.last.toLowerCase() == w.toLowerCase() &&
        w.toLowerCase() != 'had') {
      continue;
    }
    result.add(w);
  }
  return result.join(' ');
}

const Set<String> _enDiscourseLeads = {
  'so', 'ok', 'okay', 'well', 'and', 'but', 'then', 'also', 'hey', 'now',
  'yeah', 'oh', 'actually', 'please'
};

const Set<String> _enSubjects = {
  'you', 'i', 'we', 'they', 'he', 'she', 'it', 'there', 'this', 'that',
  'these', 'those', 'anyone', 'anybody', 'everyone', 'everybody', 'someone',
  'somebody'
};

/// After "do"/"have" these read as imperatives ("do it", "have this ready").
const Set<String> _enImperativeObjects = {'it', 'this', 'that', 'these', 'those'};

const Set<String> _enWhFollowers = {'time', 'many', 'much', 'long', 'old', 'far', 'about', 'else', 'kind', 'type'};

bool _isEnQuestion(String text) {
  final clauses = text.split(RegExp(r'[,.!;:]'));
  for (final c in clauses) {
    final words = c.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).map((w) => w.toLowerCase()).toList();
    while (words.length > 1 && _enDiscourseLeads.contains(words.first)) {
      words.removeAt(0);
    }
    if (words.length < 2) continue;
    final first = words[0];
    final second = words[1];
    if (first == 'how' || first == 'why') return true;
    if (const {'who', 'what', 'where', 'when', 'which'}.contains(first)) {
      if (_enAuxiliaries.contains(second) || _enWhFollowers.contains(second)) return true;
      continue;
    }
    if (_enAuxiliaries.contains(first) && _enSubjects.contains(second)) {
      if ((first == 'do' || first == 'have') && _enImperativeObjects.contains(second)) continue;
      return true;
    }
  }
  return false;
}

bool _isArQuestion(String text) {
  final clauses = text.split(RegExp(r'[،؛؟.!,;]'));
  for (final c in clauses) {
    final words = c.trim().split(RegExp(r'\s+'));
    if (words.isEmpty || words.first.isEmpty) continue;
    final first = words.first.replaceAll(RegExp(r'[،؛؟.!,;]'), '');
    if (_arQuestionStarters.contains(first)) {
      return true;
    }
  }
  return false;
}

/// Corrects grammar, removes fillers, and formats punctuation.
String correctBilingualGrammar(String? raw, [String language = 'auto']) {
  if (raw == null || raw.trim().isEmpty) return '';

  var text = raw.trim();
  final detectedArabic = isArabic(text);
  final hasLatin = text.contains(RegExp(r'[a-zA-Z]'));
  final isAr = detectedArabic ? true : (hasLatin ? false : language == 'ar');

  // 1. Remove filler words
  text = isAr
      ? text.replaceAllMapped(_arFillers, (m) => '${m.group(1)}${m.group(3)}')
      : text.replaceAll(_enFillers, ' ');

  // 2. Remove repeated stutter words
  text = _dedupeRepeatedWords(text);

  // 3. Clean spaces
  text = text.replaceAll(_multipleSpaces, ' ').trim();
  if (text.isEmpty) return '';

  // 4. Language-specific formatting
  return isAr ? _correctArabic(text) : _correctEnglish(text);
}

String _correctEnglish(String text) {
  var t = text;
  t = t.replaceAllMapped(_standaloneI, (m) => '${m.group(1)}I');
  t = t.replaceAllMapped(_iContractions, (m) => 'I${m.group(1)}');
  t = t.replaceAllMapped(_enSpacesPunct, (m) => m.group(1)!);

  if (t.isNotEmpty && t[0].toLowerCase() != t[0].toUpperCase()) {
    t = t[0].toUpperCase() + t.substring(1);
  }

  t = t.replaceAllMapped(_enSentenceStart, (m) => '${m.group(1)}${m.group(2)!.toUpperCase()}');

  final lastChar = t[t.length - 1];
  if (_unicodeWordChar.hasMatch(lastChar)) {
    t = _isEnQuestion(t) ? '$t?' : '$t.';
  }

  return t;
}

String _correctArabic(String text) {
  var t = text
      .replaceAll(',', '،')
      .replaceAll(';', '؛')
      .replaceAll('?', '؟');

  // 1. Remove stray dots between words (system speech recognizers fragment Arabic phrases with dots)
  t = t.replaceAll(RegExp(r'(?<=[\p{L}\p{N}])\s*\.\s*(?=[\p{L}\p{N}])', unicode: true), ' ');
  t = t.replaceAll(RegExp(r'\.{2,}'), ' ');

  t = t.replaceAllMapped(_arSpacesPunct, (m) => m.group(1)!);
  t = t.replaceAllMapped(_arPunctSpacing, (m) => '${m.group(1)} ');

  final lastChar = t[t.length - 1];
  if (_unicodeWordChar.hasMatch(lastChar)) {
    t = _isArQuestion(t) ? '$t؟' : '$t.';
  }

  return t;
}
