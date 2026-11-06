import 'package:flutter_test/flutter_test.dart';
import 'package:whispr_ai/grammar.dart';

void main() {
  group('BilingualGrammarCorrector (Dart)', () {
    test('English filler words are removed', () {
      final res = correctBilingualGrammar('Um I went to the store and uh bought coffee');
      expect(res, 'I went to the store and bought coffee.');
    });

    test('English repeated stutter words are deduplicated', () {
      final res = correctBilingualGrammar('I went when I am talking the the words are written down fast');
      expect(res, 'I went when I am talking the words are written down fast.');
    });

    test('English standalone i is capitalized', () {
      final res = correctBilingualGrammar('when i speak i type fast');
      expect(res, 'When I speak I type fast.');
    });

    test('English question receives question mark', () {
      final res = correctBilingualGrammar('how can i use this model');
      expect(res, 'How can I use this model?');
    });

    test('Arabic filler words are removed', () {
      final res = correctBilingualGrammar('أنا يعني ذهبت إلى العمل');
      expect(res, 'أنا ذهبت إلى العمل.');
    });

    test('Arabic repeated words are deduplicated', () {
      final res = correctBilingualGrammar('تحدثت في في الاجتماع');
      expect(res, 'تحدثت في الاجتماع.');
    });

    test('Arabic question words receive arabic question mark', () {
      final res = correctBilingualGrammar('هل يعمل التطبيق بدون إنترنت');
      expect(res, 'هل يعمل التطبيق بدون إنترنت؟');
    });

    test('Arabic punctuation is localized and formatted', () {
      final res = correctBilingualGrammar('مرحبا , كيف حالك');
      expect(res, 'مرحبا، كيف حالك؟');
    });

    test('Egyptian Arabic question words receive question mark', () {
      final res = correctBilingualGrammar('ازاي انزل التطبيق على الموبايل');
      expect(res, 'ازاي انزل التطبيق على الموبايل؟');
    });

    test('Stray dots between words from system recognizers are removed', () {
      final res = correctBilingualGrammar('انا. عاوز. اكتب. رساله');
      expect(res, 'انا عاوز اكتب رساله.');
    });
  });
}
