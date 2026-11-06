using System.Text.Json;
using System.Text.Json.Serialization;

namespace Murmur.Core;

/// <summary>How the hotkey triggers recording.</summary>
public enum PushToTalkMode
{
    /// <summary>
    /// Smart dual mode: touching (quick tap &lt; 350ms) toggles recording ON until tapped again.
    /// Holding (&gt; 350ms) acts as hold-to-talk (records while held, stops on release).
    /// </summary>
    Smart,

    /// <summary>
    /// Touching the control button once starts recording; touching it again stops and transcribes.
    /// </summary>
    TapToToggle,

    /// <summary>
    /// Hold the key while speaking, release to stop.
    /// </summary>
    HoldToTalk,
}

/// <summary>User preferences.</summary>
public sealed record SettingsData
{
    /// <summary>
    /// Virtual-key code of the push-to-talk key.
    /// Defaults to Right Control (0xA3); users can select Any Control (0x11) or Left Control (0xA2).
    /// </summary>
    public int PushToTalkKey { get; init; } = 0xA3;

    /// <summary>Trigger mode: Smart (tap or hold), TapToToggle, or HoldToTalk.</summary>
    public PushToTalkMode PushToTalkMode { get; init; } = PushToTalkMode.Smart;

    /// <summary>Speech language: "auto" (bilingual auto-detect), "en", or "ar".</summary>
    public string Language { get; init; } = "auto";

    /// <summary>Whether to automatically correct grammar, format punctuation, and clean fillers.</summary>
    public bool EnableGrammarCorrection { get; init; } = true;

    /// <summary>Optional AI grammar endpoint (e.g. Ollama, Groq, or OpenAI-compatible URL).</summary>
    public string? AiGrammarEndpoint { get; init; }

    /// <summary>Optional API key for the AI grammar service.</summary>
    public string? AiGrammarApiKey { get; init; }

    /// <summary>Optional model name for the AI grammar service (e.g. llama3.2:1b, qwen2.5:1.5b).</summary>
    public string? AiGrammarModel { get; init; }

    /// <summary>Where the speech model lives, or null to search the default locations.</summary>
    public string? ModelDirectory { get; init; }

    /// <summary>Model preference: "auto" (GPU -> Best, CPU -> Default), "best" (WhisperFlow), or "default" (Whisper Tiny).</summary>
    public string ModelPreference { get; init; } = "auto";

    /// <summary>Execution provider: "auto" (DirectML/CUDA if GPU, else CPU), "cpu", or "gpu".</summary>
    public string ExecutionProvider { get; init; } = "auto";

    /// <summary>Whether to type the transcript into the focused app.</summary>
    public bool InjectText { get; init; } = true;

    /// <summary>Whether to keep a transcript history.</summary>
    public bool KeepHistory { get; init; } = true;
}

/// <summary>Settings, persisted as JSON.</summary>
public sealed class AppSettings
{
    private readonly string _path;

    /// <summary>Loads settings from <paramref name="path"/>, or defaults if absent.</summary>
    public AppSettings(string path)
    {
        _path = path;
        Data = Load(path);
    }

    /// <summary>The default location.</summary>
    public static string DefaultPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "WhisprAI", "settings.json");

    /// <summary>Current values.</summary>
    public SettingsData Data { get; private set; }

    /// <summary>Raised after a successful save.</summary>
    public event EventHandler? Changed;

    /// <summary>Replaces and persists the settings.</summary>
    public void Update(SettingsData data)
    {
        Data = data;

        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, JsonSerializer.Serialize(data, SettingsJsonContext.Default.SettingsData));

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static SettingsData Load(string path)
    {
        // Corrupt or unreadable settings must never stop the app launching — defaults are
        // always a working configuration.
        try
        {
            if (!File.Exists(path)) return new SettingsData();

            return JsonSerializer.Deserialize(File.ReadAllText(path), SettingsJsonContext.Default.SettingsData)
                   ?? new SettingsData();
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            return new SettingsData();
        }
    }
}

/// <summary>Source-generated JSON for settings.</summary>
[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(SettingsData))]
[JsonSerializable(typeof(PushToTalkMode))]
public sealed partial class SettingsJsonContext : JsonSerializerContext;
