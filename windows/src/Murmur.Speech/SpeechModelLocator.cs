using Murmur.Abstractions;

namespace Murmur.Speech;

/// <summary>
/// Locates installed speech models and constructs the appropriate transcriber.
/// Prefers fast bilingual Whisper models (English &amp; Arabic) when available,
/// and falls back to Parakeet for backwards compatibility.
/// </summary>
public static class SpeechModelLocator
{
    /// <summary>
    /// Searches standard directories for an installed speech model.
    /// Prefers Whisper bilingual models first, then Parakeet.
    /// </summary>
    public static (string Directory, string Kind)? Locate()
    {
        var whisper = WhisperTranscriber.Locate();
        if (whisper is not null) return (whisper, "Whisper (Bilingual)");

        var parakeet = ParakeetTranscriber.Locate();
        if (parakeet is not null) return (parakeet, "Parakeet (English)");

        return null;
    }

    /// <summary>
    /// Creates a transcriber for the specified directory or best located model.
    /// </summary>
    public static ITranscriber Create(string? modelDirectory, string language = "auto")
    {
        var dir = modelDirectory ?? Locate()?.Directory;
        if (dir is null) return new UnavailableTranscriber();

        if (WhisperTranscriber.IsComplete(dir))
        {
            return new WhisperTranscriber(dir, language);
        }

        if (ParakeetTranscriber.IsComplete(dir))
        {
            return new ParakeetTranscriber(dir);
        }

        return new UnavailableTranscriber();
    }

    /// <summary>
    /// All directories searched for speech models, in recommendation order.
    /// </summary>
    public static IEnumerable<string> DefaultSearchPaths()
    {
        foreach (var p in WhisperTranscriber.DefaultSearchPaths()) yield return p;
        foreach (var p in ParakeetTranscriber.DefaultSearchPaths()) yield return p;
    }
}
