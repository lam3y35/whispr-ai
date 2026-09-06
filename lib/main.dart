import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'dictionary.dart';

void main() => runApp(const WhisprAiApp());

/// Design tokens — the sober end of the 1980s field recorder.
/// Warm off-black face, cream ink, one red accent, amber for level.
abstract final class Tokens {
  static const face = Color(0xFF1C1B18);
  static const panel = Color(0xFF2A2823);
  static const panelEdge = Color(0xFF3A372F);
  static const ink = Color(0xFFEDE6D6);
  static const muted = Color(0xFF8F8A7C);
  static const recRed = Color(0xFFC0392B);
  static const recLit = Color(0xFFE74C3C);
  static const amber = Color(0xFFD9A441);
  static const mono = 'monospace';
}

const bridge = MethodChannel('dev.whispr.whispr_ai/bridge');

class WhisprAiApp extends StatelessWidget {
  const WhisprAiApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Whispr AI',
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Tokens.face,
        colorScheme: const ColorScheme.dark(
          surface: Tokens.face,
          primary: Tokens.recRed,
          secondary: Tokens.amber,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Tokens.face,
          foregroundColor: Tokens.ink,
          elevation: 0,
        ),
      ),
      home: const HomeScreen(),
    );
  }
}

class HistoryEntryView {
  final DateTime ts;
  final String text;
  final List<Map<String, dynamic>> corrections;
  HistoryEntryView(this.ts, this.text, this.corrections);

  static HistoryEntryView? tryParse(String raw) {
    try {
      final j = jsonDecode(raw) as Map<String, dynamic>;
      return HistoryEntryView(
        DateTime.fromMillisecondsSinceEpoch(j['ts'] as int),
        j['text'] as String? ?? '',
        (j['corrections'] as List<dynamic>? ?? [])
            .whereType<Map<String, dynamic>>()
            .toList(),
      );
    } catch (_) {
      return null;
    }
  }
}

class DictionaryEntryView {
  String id;
  String kind; // 'term' | 'correction'
  String write;
  String hear;
  bool isEnabled;
  DictionaryEntryView(this.id, this.kind, this.write, this.hear, this.isEnabled);

  Map<String, dynamic> toMap() =>
      {'id': id, 'kind': kind, 'write': write, 'hear': hear, 'isEnabled': isEnabled};
}

Future<List<DictionaryEntryView>> loadDictionary() async {
  if (!_isAndroid) return [];
  final raw = await bridge.invokeMethod('getDictionary') as List<dynamic>;
  return raw.whereType<Map>().map((m) {
    final map = Map<String, dynamic>.from(m);
    return DictionaryEntryView(map['id'] as String, map['kind'] as String,
        map['write'] as String, map['hear'] as String? ?? '', map['isEnabled'] as bool);
  }).toList();
}

bool get _isAndroid => !Platform.isWindows && !Platform.isMacOS && !Platform.isLinux;

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String? _lastText;
  bool _recording = false;
  double _level = 0;

  @override
  void initState() {
    super.initState();
    bridge.setMethodCallHandler((call) async {
      if (call.method == 'level' && mounted) {
        setState(() => _level = (call.arguments as num).toDouble());
      } else if (call.method == 'permissionGranted') {
        _record();
      }
    });
    _loadLast();
  }

  Future<void> _loadLast() async {
    if (!_isAndroid) return;
    final hist = await bridge.invokeMethod('getHistory') as List<dynamic>;
    if (hist.isEmpty) return;
    final e = HistoryEntryView.tryParse(hist.first as String);
    if (e != null && mounted) setState(() => _lastText = e.text);
  }

  Future<void> _record() async {
    if (_recording) return;
    if (!_isAndroid) {
      // No native handler on desktop: invoking the channel would throw
      // MissingPluginException. The desktop app is WhisprAI.exe.
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Dictation runs on Android. On Windows use WhisprAI.exe (hold Right Ctrl).')));
      return;
    }
    setState(() => _recording = true);
    try {
      final res = await bridge.invokeMethod('startDictation');
      // null means the mic permission was just granted and this call was
      // resolved early; the 'permissionGranted' callback re-triggers _record.
      if (res == null) return;
      final map = Map<String, dynamic>.from(res as Map);
      if (map['text'] != null && mounted) {
        setState(() => _lastText = map['text'] as String);
      } else if (map['error'] != null && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Dictation: ${map['error']}')),
        );
      }
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Dictation failed: ${e.message ?? e.code}')),
        );
      }
    } finally {
      if (mounted) setState(() { _recording = false; _level = 0; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WHISPR AI',
            style: TextStyle(letterSpacing: 4, fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            icon: const Icon(Icons.history),
            tooltip: 'History',
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const HistoryScreen())),
          ),
          IconButton(
            icon: const Icon(Icons.menu_book),
            tooltip: 'Dictionary',
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const DictionaryScreen())),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _Label('PUSH-TO-TALK DICTATION \u00b7 ON-DEVICE'),
            const SizedBox(height: 24),
            _RecordButton(recording: _recording, level: _level, onTap: _record),
            const SizedBox(height: 24),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Tokens.panel,
                border: Border.all(color: Tokens.panelEdge),
                borderRadius: BorderRadius.circular(6),
              ),
              constraints: const BoxConstraints(minHeight: 96),
              child: Text(
                _lastText ?? 'Your last dictation will appear here.',
                style: const TextStyle(color: Tokens.ink, fontSize: 16, height: 1.4),
              ),
            ),
            const Spacer(),
            if (_isAndroid) ...[
              const _Label('SETUP'),
              const SizedBox(height: 8),
              FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: Tokens.panel,
                  foregroundColor: Tokens.amber,
                  padding: const EdgeInsets.all(16),
                ),
                icon: const Icon(Icons.keyboard),
                label: const Text('Enable the Whispr AI keyboard'),
                onPressed: () => bridge.invokeMethod('openImeSettings'),
              ),
              const SizedBox(height: 8),
              const Text(
                'Then in any app: tap a text field, switch keyboards (globe / \u2325 space), '
                'tap the red button, speak, tap Insert.',
                style: TextStyle(color: Tokens.muted, fontSize: 12, height: 1.4),
              ),
            ] else
              const Text('Run this on Android for dictation.',
                  style: TextStyle(color: Tokens.muted)),
          ],
        ),
      ),
    );
  }
}

class _RecordButton extends StatelessWidget {
  final bool recording;
  final double level;
  final VoidCallback onTap;
  const _RecordButton({required this.recording, required this.level, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AspectRatio(
        aspectRatio: 3.2,
        child: Container(
          decoration: BoxDecoration(
            color: recording ? Tokens.recLit : Tokens.recRed,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
                color: recording ? Colors.white24 : Tokens.panelEdge, width: 2),
          ),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(recording ? '\u25cf LISTENING' : '\u25cf TAP TO DICTATE',
                    style: const TextStyle(
                        color: Tokens.ink,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 2)),
                const SizedBox(height: 8),
                SizedBox(
                  width: 120,
                  child: LinearProgressIndicator(
                    value: recording ? (level / 12).clamp(0.05, 1.0) : 0,
                    backgroundColor: Colors.black26,
                    color: Tokens.amber,
                    minHeight: 4,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Label extends StatelessWidget {
  final String text;
  const _Label(this.text);
  @override
  Widget build(BuildContext context) => Text(text.toUpperCase(),
      style: const TextStyle(
          color: Tokens.muted, fontSize: 11, letterSpacing: 2.5, fontWeight: FontWeight.w600));
}

class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key});
  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  List<HistoryEntryView> _entries = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (!_isAndroid) return;
    final hist = await bridge.invokeMethod('getHistory') as List<dynamic>;
    setState(() {
      _entries = hist
          .map((h) => HistoryEntryView.tryParse(h as String))
          .whereType<HistoryEntryView>()
          .toList();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('HISTORY', style: TextStyle(letterSpacing: 3)),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Clear history',
            onPressed: () async {
              await bridge.invokeMethod('clearHistory');
              setState(() => _entries = []);
            },
          ),
        ],
      ),
      body: _entries.isEmpty
          ? const Center(child: Text('Nothing dictated yet.', style: TextStyle(color: Tokens.muted)))
          : ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _entries.length,
              itemBuilder: (context, i) {
                final e = _entries[i];
                final corrected = e.corrections.fold(0, (s, c) => s + (c['count'] as int? ?? 0));
                return Card(
                  color: Tokens.panel,
                  margin: const EdgeInsets.symmetric(vertical: 4),
                  child: ListTile(
                    title: Text(e.text, style: const TextStyle(color: Tokens.ink)),
                    subtitle: Text(
                      '${e.ts.toLocal()}'.substring(0, 16) +
                          (corrected > 0 ? '  \u00b7 $corrected correction(s)' : ''),
                      style: const TextStyle(color: Tokens.muted, fontFamily: Tokens.mono, fontSize: 11),
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.copy, size: 18, color: Tokens.amber),
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: e.text));
                        ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Copied')));
                      },
                    ),
                  ),
                );
              },
            ),
    );
  }
}

class DictionaryScreen extends StatefulWidget {
  const DictionaryScreen({super.key});
  @override
  State<DictionaryScreen> createState() => _DictionaryScreenState();
}

class _DictionaryScreenState extends State<DictionaryScreen> {
  List<DictionaryEntryView> _entries = [];
  final _search = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final e = await loadDictionary();
    if (mounted) setState(() => _entries = e);
  }

  Future<void> _save() async {
    if (!_isAndroid) return; // persistence lives in the native layer
    await bridge.invokeMethod('saveDictionary', _entries.map((e) => e.toMap()).toList());
  }

  Future<void> _addEntry() async {
    final hearCtrl = TextEditingController();
    final writeCtrl = TextEditingController();
    var isCorrection = true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialog) => AlertDialog(
          backgroundColor: Tokens.panel,
          title: const Text('Add entry', style: TextStyle(color: Tokens.ink)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: true, label: Text('Correction')),
                  ButtonSegment(value: false, label: Text('Term')),
                ],
                selected: {isCorrection},
                onSelectionChanged: (s) => setDialog(() => isCorrection = s.first),
              ),
              const SizedBox(height: 12),
              if (isCorrection)
                TextField(
                  controller: hearCtrl,
                  decoration: const InputDecoration(
                      labelText: 'When you hear (mishearing)',
                      hintText: 'cloud code'),
                ),
              const SizedBox(height: 8),
              TextField(
                controller: writeCtrl,
                decoration: const InputDecoration(
                    labelText: 'Write (correct text)', hintText: 'Claude Code'),
              ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Add')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final write = writeCtrl.text.trim();
    if (write.isEmpty) return;

    // Contract: warn when an entry looks likely to fire on text it wasn't
    // meant to. Never blocks — the user's dictionary, their call.
    final draft = DictionaryEntry.correction(hearCtrl.text.trim(), write);
    final warnings = checkWarnings(draft);
    if (warnings.isNotEmpty && mounted) {
      final proceed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          backgroundColor: Tokens.panel,
          title: const Text('Heads-up', style: TextStyle(color: Tokens.ink)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final w in warnings)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(w,
                      style: const TextStyle(color: Tokens.ink, fontSize: 13)),
                ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Add anyway')),
          ],
        ),
      );
      if (proceed != true) return;
    }

    setState(() {
      _entries.insert(
          0,
          DictionaryEntryView(
            DateTime.now().microsecondsSinceEpoch.toString(),
            isCorrection ? 'correction' : 'term',
            write,
            hearCtrl.text.trim(),
            true,
          ));
    });
    await _save();
  }

  @override
  Widget build(BuildContext context) {
    final q = _search.text.toLowerCase();
    final visible = _entries
        .where((e) =>
            q.isEmpty ||
            e.write.toLowerCase().contains(q) ||
            e.hear.toLowerCase().contains(q))
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('DICTIONARY', style: TextStyle(letterSpacing: 3)),
        actions: [
          IconButton(icon: const Icon(Icons.add), tooltip: 'Add entry', onPressed: _addEntry),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: TextField(
              controller: _search,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                hintText: 'Search the dictionary',
              ),
            ),
          ),
          Expanded(
            child: visible.isEmpty
                ? const Center(
                    child: Text('Empty. Add a correction like\n"cloud code" -> "Claude Code"',
                        textAlign: TextAlign.center, style: TextStyle(color: Tokens.muted)))
                : ListView.builder(
                    padding: const EdgeInsets.all(12),
                    itemCount: visible.length,
                    itemBuilder: (context, i) {
                      final e = visible[i];
                      final title = e.kind == 'correction'
                          ? '${e.hear}  \u2192  ${e.write}'
                          : e.write;
                      return Card(
                        color: Tokens.panel,
                        margin: const EdgeInsets.symmetric(vertical: 3),
                        child: ListTile(
                          leading: Icon(
                            e.kind == 'correction' ? Icons.swap_horiz : Icons.label,
                            color: e.isEnabled ? Tokens.amber : Tokens.muted,
                          ),
                          title: Text(title,
                              style: TextStyle(
                                  color: e.isEnabled ? Tokens.ink : Tokens.muted,
                                  fontFamily: Tokens.mono,
                                  fontSize: 14)),
                          onTap: () {
                            setState(() => e.isEnabled = !e.isEnabled);
                            _save();
                          },
                          onLongPress: () {
                            setState(() => _entries.remove(e));
                            _save();
                          },
                          trailing: const Icon(Icons.delete_outline, size: 18),
                          subtitle: const Text('tap: on/off \u00b7 long-press: delete',
                              style: TextStyle(color: Tokens.muted, fontSize: 10)),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
