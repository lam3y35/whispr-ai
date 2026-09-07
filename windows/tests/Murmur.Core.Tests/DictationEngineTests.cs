using System.Runtime.CompilerServices;
using Murmur.Abstractions;
using Murmur.Core;
using Murmur.Dictionary;
using Murmur.Testing;
using NetArchTest.Rules;
using Shouldly;
using Xunit;

namespace Murmur.CoreTests;

/// <summary>
/// Exercises the entire dictation path with fakes.
/// </summary>
/// <remarks>
/// This is the substitute for a Windows machine. Everything here runs on any platform, so a
/// regression in the state machine, the chunking, or the correction pass is caught on the
/// developer's own machine rather than discovered by a user on Windows.
/// </remarks>
public sealed class DictationEngineTests
{
    private static DictationEngine Build(
        IAudioCapture capture,
        FakeHotkeySource hotkey,
        FakeTranscriber transcriber,
        RecordingTextInjector injector,
        params DictionaryEntry[] dictionary) =>
        new(capture, hotkey, transcriber, injector, () => dictionary, new FakeClock());

    /// <summary>Presses, waits for capture to drain, then releases.</summary>
    private static async Task DictateAsync(FakeHotkeySource hotkey, DictationEngine engine)
    {
        hotkey.Press();
        for (var i = 0; i < 2000 && engine.State != DictationState.Recording; i++) await Task.Yield();
        for (var i = 0; i < 20000 && engine.Level == 0; i++) await Task.Yield();

        hotkey.Release();
        for (var i = 0; i < 20000 && engine.State != DictationState.Idle; i++) await Task.Yield();
    }

    [Fact]
    public async Task Speech_is_transcribed_corrected_and_injected()
    {
        var hotkey = new FakeHotkeySource();
        var transcriber = new FakeTranscriber("I use cloud code every day");
        var injector = new RecordingTextInjector();

        await using var engine = Build(
            FakeAudioCapture.Tone(1.0), hotkey, transcriber, injector,
            DictionaryEntry.Correction("cloud code", "Claude Code"));

        DictationResult? completed = null;
        engine.Completed += (_, r) => completed = r;

        await DictateAsync(hotkey, engine);

        injector.Injected.ShouldHaveSingleItem();
        injector.Injected[0].ShouldBe("I use Claude Code every day");

        completed.ShouldNotBeNull();
        completed.Corrections.ShouldHaveSingleItem();
        completed.Corrections[0].To.ShouldBe("Claude Code");
    }

    [Fact]
    public async Task Silence_injects_nothing()
    {
        var hotkey = new FakeHotkeySource();
        var injector = new RecordingTextInjector();

        // An engine that heard nothing returns empty — and empty must never be typed.
        await using var engine = Build(
            FakeAudioCapture.Silence(0.5), hotkey, new FakeTranscriber(""), injector);

        hotkey.Press();
        for (var i = 0; i < 2000 && engine.State != DictationState.Recording; i++) await Task.Yield();
        hotkey.Release();
        for (var i = 0; i < 20000 && engine.State != DictationState.Idle; i++) await Task.Yield();

        injector.Injected.ShouldBeEmpty();
    }

    [Fact]
    public async Task Release_without_press_is_ignored()
    {
        var hotkey = new FakeHotkeySource();
        var injector = new RecordingTextInjector();
        await using var engine = Build(
            FakeAudioCapture.Tone(0.2), hotkey, new FakeTranscriber("hello"), injector);

        hotkey.Release();
        for (var i = 0; i < 500; i++) await Task.Yield();

        engine.State.ShouldBe(DictationState.Idle);
        injector.Injected.ShouldBeEmpty();
    }

    [Fact]
    public async Task Dictionary_terms_are_offered_to_the_engine_as_bias()
    {
        var hotkey = new FakeHotkeySource();
        var transcriber = new FakeTranscriber("anything");

        await using var engine = Build(
            FakeAudioCapture.Tone(0.4), hotkey, transcriber, new RecordingTextInjector(),
            DictionaryEntry.Term("Anthropic"),
            DictionaryEntry.Correction("cloud code", "Claude Code"));

        await DictateAsync(hotkey, engine);

        // Both the plain term and the *write* side of the correction get biased — the whole
        // point is to nudge the recogniser toward the correct spelling.
        transcriber.LastBias.ShouldContain("Anthropic");
        transcriber.LastBias.ShouldContain("Claude Code");
    }

    [Fact]
    public async Task State_returns_to_idle_after_a_dictation()
    {
        var hotkey = new FakeHotkeySource();
        await using var engine = Build(
            FakeAudioCapture.Tone(0.3), hotkey, new FakeTranscriber("done"), new RecordingTextInjector());

        engine.State.ShouldBe(DictationState.Idle);
        await DictateAsync(hotkey, engine);
        engine.State.ShouldBe(DictationState.Idle);
        engine.Level.ShouldBe(0);
    }

    /// <summary>
    /// The boundary is enforced by the compiler via CA1416, but this fails louder and names
    /// the reason: anything reachable from Core must run in CI on any platform.
    /// </summary>
    [Fact]
    public void Core_does_not_depend_on_any_platform_project()
    {
        var result = Types.InAssembly(typeof(DictationEngine).Assembly)
            .That().ResideInNamespace("Murmur.Core")
            .ShouldNot().HaveDependencyOn("Murmur.Platform")
            .GetResult();

        result.IsSuccessful.ShouldBeTrue(
            "Murmur.Core must stay platform-neutral: " +
            string.Join(", ", result.FailingTypeNames ?? []));
    }
}

/// <summary>
/// The fault path: a capture that will not open must surface as a fault, not vanish.
/// </summary>
/// <remarks>
/// The original fire-and-forget bug shipped because nothing exercised a failing capture —
/// every test used a healthy one. These tests hold the replacement behaviour in place:
/// Faulted fires, the engine lands back in Idle, the fault text is set, and the engine
/// still works for the next press.
/// </remarks>
/// <summary>
/// Serial execution for tests that redirect AppLog: the redirect is process-global, so
/// two parallel tests would each point the log at their own file and interleave writes
/// into each other's assertion target.
/// </summary>
[CollectionDefinition(nameof(UsesTheRealAppLog), DisableParallelization = true)]
public sealed class UsesTheRealAppLog;

/// <summary>
/// Redirects AppLog to a temp file per test so runs never pollute the user's real app.log.
/// </summary>
[Collection(nameof(UsesTheRealAppLog))]
public abstract class LogIsolatedTest : IDisposable
{
    private readonly string _logPath = System.IO.Path.Combine(
        System.IO.Path.GetTempPath(),
        $"whispr-test-{Guid.NewGuid():N}.log");

    /// <summary>Initializes log isolation for the derived test class.</summary>
    protected LogIsolatedTest() => AppLog.UseLocationForTests(_logPath);

    /// <summary>The redirected log file for assertions.</summary>
    protected string LogPath => _logPath;

    /// <inheritdoc />
    public void Dispose()
    {
        AppLog.UseLocationForTests(null);
        try { File.Delete(_logPath); } catch { /* best effort */ }
        GC.SuppressFinalize(this);
    }
}

public sealed class FaultPathTests : LogIsolatedTest
{
    /// <summary>Faults on the first N capture attempts, then behaves like a steady tone.</summary>
    private sealed class FlakyAudioCapture : IAudioCapture
    {
        private int _faultsRemaining = 1;

        /// <summary>How many capture attempts still start by throwing.</summary>
        public int FaultsRemaining { get => _faultsRemaining; set => _faultsRemaining = value; }

        public bool IsCapturing { get; private set; }

        public async IAsyncEnumerable<AudioChunk> CaptureAsync(
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            IsCapturing = true;
            try
            {
                if (_faultsRemaining > 0)
                {
                    _faultsRemaining--;
                    await Task.Yield();
                    throw new InvalidOperationException("the microphone could not be opened");
                }

                // Healthy mode: half a second of a loud constant, so Level reads non-zero
                // and the normal dictate-wait loops make progress.
                var chunk = new float[AudioChunk.SampleRate / 20];
                Array.Fill(chunk, 0.5f);
                for (var i = 0; i < 10; i++)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    yield return new AudioChunk(chunk);
                    await Task.Yield();
                }
            }
            finally
            {
                IsCapturing = false;
            }
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private static DictationEngine Build(IAudioCapture capture)
    {
        var injector = new RecordingTextInjector();
        return new DictationEngine(
            capture, new FakeHotkeySource(), new FakeTranscriber("recovered"), injector,
            () => Array.Empty<DictionaryEntry>(), new FakeClock());
    }

    [Fact]
    public async Task A_faulting_capture_fires_Faulted_and_resets_to_idle()
    {
        var hotkey = new FakeHotkeySource();
        var injector = new RecordingTextInjector();
        await using var engine = new DictationEngine(
            new FlakyAudioCapture(), hotkey, new FakeTranscriber("never"), injector,
            () => Array.Empty<DictionaryEntry>(), new FakeClock());

        var faulted = new TaskCompletionSource();
        engine.Faulted += (_, _) => faulted.TrySetResult();

        hotkey.Press();
        await faulted.Task.WaitAsync(TimeSpan.FromSeconds(10));

        engine.State.ShouldBe(DictationState.Idle);
        engine.LastFault.ShouldNotBeNull();
        engine.LastFault.ShouldContain("microphone");
        injector.Injected.ShouldBeEmpty();

        // The fault must be in the log file, not just in memory — the log is what a
        // user can send you when the UI is not enough. Polled: the write is sequential
        // with the event, but the read races a concurrent appender on Windows share modes.
        ShouldContainInLogEventually("dictation fault in BeginAsync");
    }

    private void ShouldContainInLogEventually(string marker)
    {
        for (var i = 0; ; i++)
        {
            string text;
            try { text = File.ReadAllText(LogPath); }
            catch (IOException) when (i < 50)
            {
                // A concurrent append holds the file; retry briefly.
                Thread.Sleep(20);
                continue;
            }

            if (text.Contains(marker)) return;
            if (i >= 50)
            {
                throw new InvalidOperationException(
                    $"Log never contained '{marker}'. Log was:{Environment.NewLine}{text}");
            }
            Thread.Sleep(20);
        }
    }

    [Fact]
    public async Task The_engine_recovers_and_dictates_after_a_fault()
    {
        var hotkey = new FakeHotkeySource();
        var injector = new RecordingTextInjector();
        var capture = new FlakyAudioCapture();
        await using var engine = new DictationEngine(
            capture, hotkey, new FakeTranscriber("recovered"), injector,
            () => Array.Empty<DictionaryEntry>(), new FakeClock());

        var faulted = new TaskCompletionSource();
        engine.Faulted += (_, _) => faulted.TrySetResult();

        hotkey.Press();
        await faulted.Task.WaitAsync(TimeSpan.FromSeconds(10));
        engine.State.ShouldBe(DictationState.Idle);

        hotkey.Release();
        for (var i = 0; i < 2000 && engine.State != DictationState.Idle; i++) await Task.Yield();

        // The very next press must behave like nothing ever failed.
        hotkey.Press();
        for (var i = 0; i < 2000 && engine.State != DictationState.Recording; i++) await Task.Yield();
        for (var i = 0; i < 20000 && engine.Level == 0; i++) await Task.Yield();
        hotkey.Release();
        for (var i = 0; i < 20000 && engine.State != DictationState.Idle; i++) await Task.Yield();

        injector.Injected.ShouldHaveSingleItem();
        injector.Injected[0].ShouldBe("recovered");
        engine.LastFault.ShouldBeNull();
    }

    [Fact]
    public async Task A_silent_microphone_surfaces_a_fault_instead_of_vanishing()
    {
        // The exact failure a real user hit: WASAPI happily delivers exact zeros when
        // Windows blocks the mic, every layer above looked healthy, and the session
        // vanished with no text, no fault and no log line.
        var hotkey = new FakeHotkeySource();
        var injector = new RecordingTextInjector();
        await using var engine = new DictationEngine(
            FakeAudioCapture.Silence(0.5), hotkey, new FakeTranscriber("never"), injector,
            () => Array.Empty<DictionaryEntry>(), new FakeClock());

        var faulted = new TaskCompletionSource();
        engine.Faulted += (_, _) => faulted.TrySetResult();

        hotkey.Press();
        for (var i = 0; i < 2000 && engine.State != DictationState.Recording; i++) await Task.Yield();
        hotkey.Release();
        await faulted.Task.WaitAsync(TimeSpan.FromSeconds(10));

        engine.State.ShouldBe(DictationState.Idle);
        engine.LastFault.ShouldNotBeNull();
        engine.LastFault.ShouldContain("silence");
        injector.Injected.ShouldBeEmpty();
        ShouldContainInLogEventually("digital silence");
    }
}

/// <summary>Chunking behaviour around the encoder's hard limit.</summary>
public sealed class AudioSegmenterTests
{
    private static ReadOnlyMemory<float> Seconds(int n) =>
        new float[n * AudioChunk.SampleRate];

    [Fact]
    public void Short_audio_is_one_segment_and_is_not_copied()
    {
        var audio = Seconds(5);
        var pieces = AudioSegmenter.Split(audio);

        pieces.ShouldHaveSingleItem();
        pieces[0].Length.ShouldBe(audio.Length);
    }

    [Fact]
    public void Audio_at_the_limit_is_still_one_segment()
    {
        AudioSegmenter.Split(Seconds(AudioSegmenter.MaxSegmentSeconds)).ShouldHaveSingleItem();
    }

    [Fact]
    public void Long_audio_is_split_and_every_piece_is_under_the_limit()
    {
        var pieces = AudioSegmenter.Split(Seconds(200));

        pieces.Count.ShouldBeGreaterThan(1);
        foreach (var piece in pieces)
        {
            piece.Length.ShouldBeLessThanOrEqualTo(
                AudioSegmenter.MaxSegmentSeconds * AudioChunk.SampleRate);
            piece.Length.ShouldBeGreaterThan(0);
        }
    }

    [Fact]
    public void Splitting_loses_no_samples()
    {
        var pieces = AudioSegmenter.Split(Seconds(200));
        pieces.Sum(p => p.Length).ShouldBe(200 * AudioChunk.SampleRate);
    }

    /// <summary>
    /// 410 seconds is past the point where the encoder's position table overflows and
    /// inference throws rather than degrading. Nothing may reach it.
    /// </summary>
    [Fact]
    public void Nothing_ever_reaches_the_encoder_ceiling()
    {
        var pieces = AudioSegmenter.Split(Seconds(AudioSegmenter.EncoderCeilingSeconds + 100));

        foreach (var piece in pieces)
        {
            var seconds = (double)piece.Length / AudioChunk.SampleRate;
            seconds.ShouldBeLessThan(AudioSegmenter.EncoderCeilingSeconds);
        }
    }

    [Fact]
    public void A_cut_lands_in_the_quiet_part_rather_than_mid_word()
    {
        // Loud throughout, with one silent second placed inside the search window that
        // precedes the ideal cut point. The cut should be drawn to it.
        var total = AudioSegmenter.MaxSegmentSeconds * 2 * AudioChunk.SampleRate;
        var samples = new float[total];
        Array.Fill(samples, 0.5f);

        var quietStart = (AudioSegmenter.MaxSegmentSeconds - 3) * AudioChunk.SampleRate;
        Array.Clear(samples, quietStart, AudioChunk.SampleRate);

        var pieces = AudioSegmenter.Split(samples);
        var firstCut = pieces[0].Length;

        firstCut.ShouldBeGreaterThanOrEqualTo(quietStart);
        firstCut.ShouldBeLessThanOrEqualTo(quietStart + AudioChunk.SampleRate);
    }
}

/// <summary>
/// The quiet-mic failure mode: a user's voice arrived at a tenth of the level that
/// once transcribed fine, and the model returned nothing. Gain must rescue that.
/// </summary>
public sealed class AudioGainTests
{
    private static float Rms(float[] samples)
    {
        var sum = 0d;
        foreach (var s in samples) sum += (double)s * s;
        return MathF.Sqrt((float)(sum / samples.Length));
    }

    [Fact]
    public void A_quiet_recording_is_boosted_to_near_target()
    {
        // A tenth of target, matching the measured failure: peak ~0.01, rms ~0.002.
        var quiet = new float[16000];
        for (var i = 0; i < quiet.Length; i++) quiet[i] = 0.003f * MathF.Sin(2 * MathF.PI * 220 * i / 16000);

        var boosted = AudioGain.Normalize(quiet);

        // The uncapped gain would be ~56x; the cap limits it to MaxGain. Assert the cap
        // is applied exactly and the level lands an order of magnitude higher.
        Rms(boosted).ShouldBe(Rms(quiet) * AudioGain.MaxGain, 0.001f);
        Rms(boosted).ShouldBeGreaterThan(Rms(quiet) * 4);
    }

    [Fact]
    public void A_healthy_recording_passes_through_untouched()
    {
        var healthy = new float[16000];
        for (var i = 0; i < healthy.Length; i++) healthy[i] = 0.3f * MathF.Sin(2 * MathF.PI * 220 * i / 16000);

        AudioGain.Normalize(healthy).ShouldBe(healthy);
    }

    [Fact]
    public void A_boost_that_would_clip_is_capped_to_headroom()
    {
        // Peak already at ceiling with a low rms: gain must not push it over 0.99.
        var spiky = new float[16000];
        for (var i = 0; i < spiky.Length; i++) spiky[i] = 0.001f;
        spiky[100] = 0.95f;

        var boosted = AudioGain.Normalize(spiky);

        foreach (var sample in boosted) MathF.Abs(sample).ShouldBeLessThanOrEqualTo(0.99f);
    }

    [Fact]
    public void Digital_silence_is_returned_unchanged()
    {
        var silence = new float[16000];
        AudioGain.Normalize(silence).ShouldBe(silence);
    }

    [Fact]
    public void An_empty_recording_is_returned_unchanged()
    {
        AudioGain.Normalize([]).ShouldBe([]);
    }
}
