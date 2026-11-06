using Murmur.Abstractions;
using SherpaOnnx;

namespace Murmur.Speech;

/// <summary>
/// Fast, lightweight, bilingual (English &amp; Arabic) speech transcriber using OpenAI Whisper
/// quantized ONNX models running locally via sherpa-onnx.
/// </summary>
public sealed class WhisperTranscriber : ITranscriber
{
    private const int Threads = 4;
    private const int FeatureDim = 80; // Whisper requires 80 feature dimensions

    private readonly string _modelDirectory;
    private readonly string _language;
    private OfflineRecognizer? _recognizer;

    /// <summary>
    /// Constructs a Whisper transcriber pointing at a model directory.
    /// </summary>
    /// <param name="modelDirectory">Directory containing encoder, decoder, and tokens.</param>
    /// <param name="language">Language code: "auto" (or empty for auto-detect), "en", or "ar".</param>
    public WhisperTranscriber(string modelDirectory, string language = "auto")
    {
        _modelDirectory = modelDirectory;
        _language = language;
    }

    /// <summary>Default locations where Whisper models are searched for.</summary>
    public static IEnumerable<string> DefaultSearchPaths()
    {
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        yield return Path.Combine(localAppData, "WhisprAI", "models", "whisper-tiny");
        yield return Path.Combine(localAppData, "WhisprAI", "models", "whisper-base");
        yield return Path.Combine(localAppData, "WhisprAI", "models", "whisper");

        yield return Path.Combine(AppContext.BaseDirectory, "models", "whisper-tiny");
        yield return Path.Combine(AppContext.BaseDirectory, "models", "whisper-base");
        yield return Path.Combine(AppContext.BaseDirectory, "models", "whisper");
    }

    /// <summary>Finds the first complete Whisper model directory, or null.</summary>
    public static string? Locate() => DefaultSearchPaths().FirstOrDefault(IsComplete);

    /// <summary>Finds encoder file in directory.</summary>
    public static string? FindEncoder(string directory) =>
        Directory.Exists(directory)
            ? Directory.GetFiles(directory, "*encoder*.onnx").FirstOrDefault()
            : null;

    /// <summary>Finds decoder file in directory.</summary>
    public static string? FindDecoder(string directory) =>
        Directory.Exists(directory)
            ? Directory.GetFiles(directory, "*decoder*.onnx").FirstOrDefault()
            : null;

    /// <summary>Finds tokens file in directory.</summary>
    public static string? FindTokens(string directory) =>
        Directory.Exists(directory)
            ? Directory.GetFiles(directory, "*tokens.txt").FirstOrDefault()
            : null;

    /// <summary>Whether directory contains all required Whisper files.</summary>
    public static bool IsComplete(string directory) =>
        FindEncoder(directory) is not null &&
        FindDecoder(directory) is not null &&
        FindTokens(directory) is not null;

    /// <inheritdoc />
    public bool IsReady => _recognizer is not null;

    /// <inheritdoc />
    public ValueTask<bool> LoadAsync(CancellationToken cancellationToken)
    {
        if (_recognizer is not null) return ValueTask.FromResult(true);
        if (!IsComplete(_modelDirectory)) return ValueTask.FromResult(false);

        var encoder = FindEncoder(_modelDirectory)!;
        var decoder = FindDecoder(_modelDirectory)!;
        var tokens = FindTokens(_modelDirectory)!;

        var config = new OfflineRecognizerConfig();
        config.FeatConfig.SampleRate = AudioChunk.SampleRate;
        config.FeatConfig.FeatureDim = FeatureDim;

        config.ModelConfig.Whisper.Encoder = encoder;
        config.ModelConfig.Whisper.Decoder = decoder;
        config.ModelConfig.Whisper.Language = (_language == "auto" || string.IsNullOrWhiteSpace(_language)) ? "" : _language;
        config.ModelConfig.Whisper.Task = "transcribe";
        config.ModelConfig.Tokens = tokens;
        config.ModelConfig.NumThreads = Threads;
        config.ModelConfig.Provider = "cpu";
        config.DecodingMethod = "greedy_search";

        _recognizer = new OfflineRecognizer(config);
        return ValueTask.FromResult(true);
    }

    /// <inheritdoc />
    public ValueTask<string> TranscribeAsync(
        ReadOnlyMemory<float> samples,
        IReadOnlyList<string> biasPhrases,
        CancellationToken cancellationToken)
    {
        if (_recognizer is null || samples.Length == 0) return ValueTask.FromResult(string.Empty);

        cancellationToken.ThrowIfCancellationRequested();

        using var stream = _recognizer.CreateStream();
        stream.AcceptWaveform(AudioChunk.SampleRate, samples.ToArray());
        _recognizer.Decode(stream);

        return ValueTask.FromResult(stream.Result.Text?.Trim() ?? string.Empty);
    }

    /// <inheritdoc />
    public ValueTask DisposeAsync()
    {
        _recognizer?.Dispose();
        _recognizer = null;
        return ValueTask.CompletedTask;
    }
}
