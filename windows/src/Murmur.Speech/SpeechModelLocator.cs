using Murmur.Abstractions;

namespace Murmur.Speech;

/// <summary>
/// Locates installed speech models and constructs the appropriate transcriber.
/// Checks device capabilities (GPU vs CPU) and preferences:
/// - If GPU is available: automatically selects the best model (WhisperFlow).
/// - If CPU: uses the default lightweight model (Whisper Tiny/Base).
/// Falls back gracefully between model tiers and to Parakeet.
/// </summary>
public static class SpeechModelLocator
{
    /// <summary>
    /// Searches standard directories for an installed speech model based on hardware and preference.
    /// </summary>
    /// <param name="preference">"auto" (GPU -> Best, CPU -> Default), "best" (WhisperFlow), or "default" (Whisper Tiny/Base).</param>
    public static (string Directory, string Kind)? Locate(string preference = "auto")
    {
        var wantBest = preference.Equals("best", StringComparison.OrdinalIgnoreCase) ||
                       (preference.Equals("auto", StringComparison.OrdinalIgnoreCase) && DeviceCapabilities.Current.HasGpu);

        var paths = WhisperTranscriber.DefaultSearchPaths().Where(WhisperTranscriber.IsComplete).Distinct().ToList();

        if (wantBest)
        {
            // Look for WhisperFlow first
            var flow = paths.FirstOrDefault(WhisperTranscriber.IsWhisperFlow);
            if (flow is not null)
                return (flow, "WhisperFlow (Best / High Accuracy)");

            // Fallback to default Whisper
            var def = paths.FirstOrDefault();
            if (def is not null)
                return (def, "Whisper Default (Lightweight)");
        }
        else
        {
            // Look for default lightweight Whisper first
            var def = paths.FirstOrDefault(p => !WhisperTranscriber.IsWhisperFlow(p));
            if (def is not null)
                return (def, "Whisper Default (Lightweight)");

            // Fallback to WhisperFlow if installed
            var flow = paths.FirstOrDefault();
            if (flow is not null)
                return (flow, "WhisperFlow (High Accuracy)");
        }

        var parakeet = ParakeetTranscriber.Locate();
        if (parakeet is not null) return (parakeet, "Parakeet (English)");

        return null;
    }

    /// <summary>
    /// Creates a transcriber for the specified directory or best located model.
    /// </summary>
    /// <param name="modelDirectory">Explicit model directory or null to auto-locate.</param>
    /// <param name="language">Language code ("auto", "en", "ar").</param>
    /// <param name="executionProvider">"auto" (DirectML/CUDA on GPU, CPU on CPU), "cpu", or "gpu".</param>
    /// <param name="modelPreference">"auto", "best", or "default".</param>
    public static ITranscriber Create(
        string? modelDirectory,
        string language = "auto",
        string executionProvider = "auto",
        string modelPreference = "auto")
    {
        var dir = modelDirectory ?? Locate(modelPreference)?.Directory;
        if (dir is null) return new UnavailableTranscriber();

        if (WhisperTranscriber.IsComplete(dir))
        {
            return new WhisperTranscriber(dir, language, executionProvider);
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
