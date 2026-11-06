using Murmur.Core;
using Shouldly;
using Xunit;

namespace Murmur.CoreTests;

public sealed class BilingualGrammarCorrectorTests
{
    private readonly BilingualGrammarCorrector _corrector = BilingualGrammarCorrector.Instance;

    [Fact]
    public void English_filler_words_and_hesitations_are_removed()
    {
        var input = "Um I went to the store and uh bought milk";
        var result = _corrector.Correct(input);
        result.ShouldBe("I went to the store and bought milk.");
    }

    [Fact]
    public void English_repeated_stutter_words_are_deduplicated()
    {
        var input = "I went when I'm talking the the words are written fast";
        var result = _corrector.Correct(input);
        result.ShouldBe("I went when I'm talking the words are written fast.");
    }

    [Fact]
    public void English_sentence_is_capitalized_and_period_added()
    {
        var input = "hello world this is a test";
        var result = _corrector.Correct(input);
        result.ShouldBe("Hello world this is a test.");
    }

    [Fact]
    public void English_standalone_i_is_capitalized()
    {
        var input = "when i speak i see words";
        var result = _corrector.Correct(input);
        result.ShouldBe("When I speak I see words.");
    }

    [Fact]
    public void English_questions_receive_question_marks()
    {
        var input = "how do you make this project run faster";
        var result = _corrector.Correct(input);
        result.ShouldBe("How do you make this project run faster?");
    }

    [Fact]
    public void Arabic_fillers_are_removed()
    {
        var input = "أنا يعني ذهبت إلى السوق";
        var result = _corrector.Correct(input);
        result.ShouldBe("أنا ذهبت إلى السوق.");
    }

    [Fact]
    public void Arabic_repeated_stutter_words_are_deduplicated()
    {
        var input = "تحدثت في في المؤتمر اليوم";
        var result = _corrector.Correct(input);
        result.ShouldBe("تحدثت في المؤتمر اليوم.");
    }

    [Fact]
    public void Arabic_punctuation_is_converted_and_spaced()
    {
        var input = "مرحبا , كيف حالك اليوم";
        var result = _corrector.Correct(input);
        // "كيف" is an Arabic question starter
        result.ShouldBe("مرحبا، كيف حالك اليوم؟");
    }

    [Fact]
    public void Arabic_question_starters_receive_arabic_question_mark()
    {
        var input = "هل يعمل البرنامج بشكل جيد";
        var result = _corrector.Correct(input);
        result.ShouldBe("هل يعمل البرنامج بشكل جيد؟");
    }

    [Fact]
    public void Egyptian_arabic_question_starters_receive_arabic_question_mark()
    {
        var input = "ازاي انزل التطبيق على الموبايل";
        var result = _corrector.Correct(input);
        result.ShouldBe("ازاي انزل التطبيق على الموبايل؟");
    }

    [Fact]
    public void Stray_dots_between_words_are_removed()
    {
        var input = "انا. عاوز. اكتب. رساله";
        var result = _corrector.Correct(input);
        result.ShouldBe("انا عاوز اكتب رساله.");
    }

    [Fact]
    public void Empty_or_whitespace_returns_empty()
    {
        _corrector.Correct("   ").ShouldBe(string.Empty);
        _corrector.Correct(null).ShouldBe(string.Empty);
    }
}
