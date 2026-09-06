import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:whispr_ai/dictionary.dart';

/// Runs the shared behavioural contract — dictionary-test-vectors.json, the
/// same file the Windows and macOS CI suites run — through the Dart port.
void main() {
  final json = File('test/dictionary-test-vectors.json').readAsStringSync();
  final suite = jsonDecode(json) as Map<String, dynamic>;
  final cases = (suite['cases'] as List).cast<Map<String, dynamic>>();

  List<DictionaryEntry> entriesOf(Map<String, dynamic> c) =>
      (c['entries'] as List).cast<Map<String, dynamic>>().map((m) {
        final kind = (m['kind'] as String).toLowerCase() == 'correction'
            ? EntryKind.correction
            : EntryKind.term;
        return DictionaryEntry(
          id: m['write'] as String,
          kind: kind,
          write: m['write'] as String,
          hear: (m['hear'] as String?) ?? '',
          isEnabled: (m['isEnabled'] as bool?) ?? true,
        );
      }).toList();

  test('every case is uniquely named and the suite is not empty', () {
    expect(cases, isNotEmpty);
    final names = cases.map((c) => c['name'] as String).toSet();
    expect(names.length, cases.length, reason: 'duplicate case names');
  });

  for (final c in cases) {
    final name = c['name'] as String;
    test('vector: $name', () {
      final result =
          applyCorrections(c['input'] as String, entriesOf(c));
      expect(result.text, c['expected'] as String, reason: name);

      final expected = (c['expectedCorrections'] as List? ?? [])
          .cast<Map<String, dynamic>>();
      expect(result.applied.length, expected.length, reason: name);
      for (final e in expected) {
        final match = result.applied
            .where((a) => a.to == e['to'])
            .fold<int>(0, (s, a) => s + a.count);
        expect(match, e['count'] as int, reason: name);
      }
    });
  }

  test('bias list is capped and deduplicated', () {
    final entries = List.generate(100, (i) => DictionaryEntry.term('Word$i'))
      ..add(DictionaryEntry.term('Word0'));
    final phrases = biasPhrases(entries);
    expect(phrases.length, biasLimit);
    expect(phrases.map((p) => p.toLowerCase()).toSet().length, phrases.length);
  });

  test('disabled entries are excluded from biasing', () {
    final entries = [
      const DictionaryEntry(id: 'a', kind: EntryKind.term, write: 'Kept'),
      const DictionaryEntry(
          id: 'b', kind: EntryKind.term, write: 'Skipped', isEnabled: false),
    ];
    expect(biasPhrases(entries), ['Kept']);
  });

  test('an ordinary word used as a trigger is flagged', () {
    expect(
      checkWarnings(const DictionaryEntry(
          id: 'x', kind: EntryKind.correction, hear: 'cloud', write: 'Claude')),
      isNotEmpty,
    );
  });

  test('a distinctive phrase is not flagged', () {
    expect(
      checkWarnings(const DictionaryEntry(
          id: 'y',
          kind: EntryKind.correction,
          hear: 'clawed code',
          write: 'Claude Code')),
      isEmpty,
    );
  });
}
