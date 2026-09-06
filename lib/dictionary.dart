/// Dart port of the shared dictionary correction contract.
///
/// Same three load-bearing rules as the C# (Windows) and Swift (macOS)
/// implementations and the Kotlin port used by the Whispr AI keyboard:
///   1. Longest match first.
///   2. Whole matches only — lookaround fences on letters/digits, not \b.
///   3. Glued words still match — gaps are optional whitespace or hyphens.
library;

import 'package:unorm_dart/unorm_dart.dart' as unorm;

enum EntryKind { term, correction }

class DictionaryEntry {
  final String id;
  final EntryKind kind;
  final String write;
  final String hear;
  final bool isEnabled;

  const DictionaryEntry({
    required this.id,
    required this.kind,
    required this.write,
    this.hear = '',
    this.isEnabled = true,
  });

  DictionaryEntry.term(String word)
      : this(
          id: DateTime.now().microsecondsSinceEpoch.toString(),
          kind: EntryKind.term,
          write: word,
        );

  DictionaryEntry.correction(String hear, String write)
      : this(
          id: DateTime.now().microsecondsSinceEpoch.toString(),
          kind: EntryKind.correction,
          hear: hear,
          write: write,
        );

  /// Plain-text serialization, identical to the desktop format:
  /// "hear -> write" for corrections, bare text for terms, "# off: " prefix
  /// marks a disabled entry.
  String toFileLine() {
    final body = kind == EntryKind.correction ? '$hear -> $write' : write;
    return isEnabled ? body : '# off: $body';
  }
}

class AppliedCorrection {
  final String from;
  final String to;
  final int count;
  const AppliedCorrection(this.from, this.to, this.count);
}

class _Rule {
  final RegExp pattern;
  final String replacement;
  const _Rule(this.pattern, this.replacement);
}

/// How many phrases to hand a speech engine as context. Deliberately small:
/// long context makes these models drift and invent text on quiet audio.
const int biasLimit = 40;

final RegExp _separatorSplit = RegExp(r'[ \-\t]+');

/// Normalize to NFC before matching, as the other implementations do.
/// Without this, "café" arriving in NFD never matches an NFC trigger —
/// the exact case the shared vector suite covers.
String _nfc(String s) => unorm.nfc(s);

/// Compiles the enabled correction entries into an ordered rule set —
/// longest trigger first, a stable sort that keeps file order on ties.
List<_Rule> _compileRules(Iterable<DictionaryEntry> entries) {
  final rules = <_Rule>[];
  final usable = entries
      .where((e) => e.isEnabled && e.kind == EntryKind.correction)
      .where((e) => e.hear.trim().isNotEmpty)
      .toList()
    ..sort((a, b) => b.hear.length.compareTo(a.hear.length));

  for (final e in usable) {
    final parts = _nfc(e.hear)
        .trim()
        .split(_separatorSplit)
        .where((p) => p.isNotEmpty)
        .map(RegExp.escape)
        .toList();
    if (parts.isEmpty) continue;
    final body = parts.join(r'[\s\-]*');
    final pattern = RegExp(
      '(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])',
      caseSensitive: false,
      unicode: true,
    );
    rules.add(_Rule(pattern, e.write));
  }
  return rules;
}

/// Applies every rule in order. Returns the rewritten text plus one entry
/// per rule that fired — recording what the engine actually produced, which
/// can differ from the trigger in case or spacing ("CloudCode" matched by
/// "cloud code").
({String text, List<AppliedCorrection> applied}) applyCorrections(
  String text,
  Iterable<DictionaryEntry> entries,
) {
  final rules = _compileRules(entries);
  if (rules.isEmpty || text.isEmpty) return (text: text, applied: const []);

  var result = _nfc(text);
  final applied = <AppliedCorrection>[];

  for (final rule in rules) {
    final matches = rule.pattern.allMatches(result).toList();
    if (matches.isEmpty) continue;
    final heard = matches.first.group(0) ?? rule.replacement;
    result = result.replaceAllMapped(
      rule.pattern,
      (_) => rule.replacement, // strictly literal — never a $1 substitution
    );
    applied.add(AppliedCorrection(heard, rule.replacement, matches.length));
  }
  return (text: result, applied: applied);
}

/// The correct spellings — Term words and the write side of corrections —
/// capped at [biasLimit], de-duplicated case-insensitively, in file order.
List<String> biasPhrases(Iterable<DictionaryEntry> entries) {
  final seen = <String>{};
  final phrases = <String>[];
  for (final e in entries.where((e) => e.isEnabled)) {
    final phrase = e.write.trim();
    if (phrase.isEmpty) continue;
    if (!seen.add(phrase.toLowerCase())) continue;
    phrases.add(phrase);
    if (phrases.length == biasLimit) break;
  }
  return phrases;
}

/// Ordinary English words that would fire constantly if used as a whole
/// trigger. Kept identical to the C#/Swift/Kotlin lists.
const Set<String> _commonWords = {
  'a', 'about', 'all', 'also', 'and', 'any', 'are', 'as', 'at', 'back', 'be',
  'because', 'but', 'by', 'call', 'can', 'case', 'check', 'class', 'close',
  'cloud', 'code', 'come', 'could', 'data', 'day', 'did', 'do', 'does', 'down',
  'each', 'even', 'file', 'find', 'first', 'for', 'from', 'get', 'give', 'go',
  'good', 'great', 'group', 'had', 'has', 'have', 'he', 'her', 'here', 'him',
  'his', 'how', 'if', 'in', 'into', 'is', 'it', 'its', 'just', 'key', 'know',
  'like', 'line', 'list', 'look', 'make', 'man', 'many', 'may', 'me', 'more',
  'most', 'my', 'need', 'new', 'no', 'not', 'now', 'number', 'of', 'off', 'on',
  'one', 'only', 'open', 'or', 'other', 'our', 'out', 'over', 'page', 'part',
  'people', 'point', 'put', 'read', 'right', 'run', 'said', 'same', 'say',
  'see', 'set', 'she', 'should', 'show', 'side', 'so', 'some', 'state',
  'still', 'such', 'take', 'team', 'test', 'than', 'that', 'the', 'their',
  'them', 'then', 'there', 'these', 'they', 'thing', 'think', 'this', 'time',
  'to', 'two', 'type', 'up', 'us', 'use', 'user', 'very', 'want', 'was', 'way',
  'we', 'well', 'were', 'what', 'when', 'where', 'which', 'who', 'will',
  'with', 'word', 'work', 'would', 'year', 'you', 'your',
};

/// Warnings for an entry that looks likely to fire on unintended text.
/// Never blocks — it is the user's dictionary.
List<String> checkWarnings(DictionaryEntry entry) {
  if (entry.kind != EntryKind.correction) return const [];
  final trigger = entry.hear.trim();
  if (trigger.isEmpty) return const [];

  final warnings = <String>[];
  final words =
      trigger.split(_separatorSplit).where((w) => w.isNotEmpty).toList();

  if (words.length == 1) {
    final only = words[0];
    if (_commonWords.contains(only.toLowerCase())) {
      warnings.add(
        '“$trigger” is an ordinary word. This will rewrite every use of it, '
        'not just the ones you mean. Consider a longer phrase.',
      );
    } else if (only.length <= 3) {
      warnings.add(
        '“$trigger” is very short and will match often. Consider a longer phrase.',
      );
    }
  }
  if (entry.write.trim().toLowerCase() == trigger.toLowerCase()) {
    warnings.add(
      'This rewrites “$trigger” to itself, so it will never change anything.',
    );
  }
  return warnings;
}
