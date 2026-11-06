using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Murmur.App.Controls;
using Murmur.App.Design;
using Murmur.Core;
using Murmur.Speech;

namespace Murmur.App.Views;

/// <summary>Settings: the hotkey and the model.</summary>
public sealed class SettingsWindow : Window
{
    /// <summary>
    /// The keys offered, in recommendation order.
    /// </summary>
    /// <remarks>
    /// Right Alt is included but listed last and carries a warning: on German, Polish, UK,
    /// Nordic and most Latin-American layouts it is AltGr, and binding push-to-talk there
    /// breaks typing <c>@</c>, <c>€</c>, <c>\</c> and <c>|</c>.
    /// </remarks>
    private static readonly (int Key, string Label, string? Warning)[] Keys =
    [
        (0x11, "ANY CTRL", null),
        (0xA2, "LEFT CTRL", null),
        (0xA3, "RIGHT CTRL", null),
        (0xA1, "RIGHT SHIFT", null),
        (0x14, "CAPS LOCK", null),
        (0x7C, "F13", null),
        (0xA5, "RIGHT ALT", "Right Alt is AltGr on many European layouts — binding it here "
                          + "will interfere with typing @, €, \\ and |."),
    ];

    private readonly AppSettings _settings;
    private readonly StackPanel _keyRow;
    private readonly TextBlock _keyWarning;

    /// <summary>Builds the settings window.</summary>
    public SettingsWindow(AppSettings settings)
    {
        _settings = settings;

        Title = "Whispr AI Settings";
        Width = 540;
        SizeToContent = SizeToContent.Height;
        CanResize = false;
        Background = Tokens.Brushes.Chassis;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        _keyRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = Tokens.Space.Snug,
        };

        _keyWarning = new TextBlock
        {
            FontFamily = Tokens.Fonts.Grotesque,
            FontSize = Tokens.Fonts.Label,
            Foreground = new SolidColorBrush(Tokens.Colors.MeterAmber),
            TextWrapping = TextWrapping.Wrap,
            IsVisible = false,
        };

        foreach (var (key, label, warning) in Keys)
        {
            var button = new TransportKey { Content = label, EngagedColor = Tokens.Colors.Ink };
            button.Click += (_, _) => SelectKey(key, warning);
            _keyRow.Children.Add(button);
        }

        Content = BuildContent();
        SelectKey(_settings.Data.PushToTalkKey, WarningFor(_settings.Data.PushToTalkKey));
    }

    private static string? WarningFor(int key) =>
        Keys.FirstOrDefault(k => k.Key == key).Warning;

    private StackPanel BuildContent() => new StackPanel
    {
        Margin = new Thickness(Tokens.Space.Panel),
        Spacing = Tokens.Space.Wide,
        Children =
        {
            Section("DEVICE & ACCELERATION", BuildHardwareSection()),

            Section("PUSH TO TALK", new StackPanel
            {
                Spacing = Tokens.Space.Snug,
                Children =
                {
                    _keyRow,
                    _keyWarning,
                    Note("Touch or hold this key anywhere to dictate. In Smart mode, touching (tapping) the "
                       + "Control key starts recording; touching it again stops and transcribes."),
                },
            }),

            Section("SPEECH MODEL", BuildModelSection()),

            Section("BEHAVIOUR", new StackPanel
            {
                Spacing = Tokens.Space.Snug,
                Children =
                {
                    Toggle("Correct grammar, format punctuation & clean fillers", _settings.Data.EnableGrammarCorrection,
                        v => Save(_settings.Data with { EnableGrammarCorrection = v })),
                    Toggle("Type transcripts into the focused app", _settings.Data.InjectText,
                        v => Save(_settings.Data with { InjectText = v })),
                    Toggle("Keep a transcript history", _settings.Data.KeepHistory,
                        v => Save(_settings.Data with { KeepHistory = v })),
                },
            }),
        },
    };

    private static StackPanel BuildHardwareSection()
    {
        var dev = DeviceCapabilities.Current;
        var gpuText = dev.HasGpu ? $"{dev.GpuName} (Provider: {dev.PreferredProvider})" : "CPU Execution (No discrete GPU detected)";
        var tierText = dev.HasGpu
            ? "GPU acceleration detected: 'WhisperFlow' (Best Model) is recommended."
            : "CPU mode detected: 'Default Model' (~39 MB lightweight) is recommended.";

        return new StackPanel
        {
            Spacing = Tokens.Space.Snug,
            Children =
            {
                new TextBlock
                {
                    Text = $"{dev.OperatingSystem} · {dev.ProcessorCount} Cores · {dev.TotalMemoryMb} MB RAM",
                    FontFamily = Tokens.Fonts.Grotesque,
                    FontSize = Tokens.Fonts.Body,
                    Foreground = Tokens.Brushes.Ink,
                },
                new TextBlock
                {
                    Text = gpuText,
                    FontFamily = Tokens.Fonts.Grotesque,
                    FontSize = Tokens.Fonts.Label,
                    Foreground = dev.HasGpu ? new SolidColorBrush(Tokens.Colors.MeterGreen) : new SolidColorBrush(Tokens.Colors.InkSecondary),
                },
                Note(tierText),
            },
        };
    }

    private StackPanel BuildModelSection()
    {
        var located = SpeechModelLocator.Locate(_settings.Data.ModelPreference);
        var found = located is not null;

        var status = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = Tokens.Space.Snug,
            Children =
            {
                new Lamp
                {
                    IsLit = found,
                    LampColor = found ? Tokens.Colors.MeterGreen : Tokens.Colors.MeterAmber,
                    VerticalAlignment = VerticalAlignment.Center,
                },
                new TextBlock
                {
                    Text = found ? $"{located!.Value.Kind} ready" : "Model not installed",
                    FontFamily = Tokens.Fonts.Grotesque,
                    FontSize = Tokens.Fonts.Body,
                    Foreground = Tokens.Brushes.Ink,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            },
        };

        var tierRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = Tokens.Space.Snug,
        };

        var tiers = new (string Value, string Label)[]
        {
            ("auto", "AUTO (SMART)"),
            ("best", "WHISPERFLOW (GPU)"),
            ("default", "DEFAULT (CPU)"),
        };

        foreach (var (val, lbl) in tiers)
        {
            var btn = new TransportKey
            {
                Content = lbl,
                IsEngaged = string.Equals(_settings.Data.ModelPreference, val, StringComparison.OrdinalIgnoreCase),
                EngagedColor = Tokens.Colors.Ink,
            };
            btn.Click += (_, _) =>
            {
                Save(_settings.Data with { ModelPreference = val });
                foreach (TransportKey k in tierRow.Children)
                {
                    k.IsEngaged = k.Content?.ToString() == lbl;
                }
            };
            tierRow.Children.Add(btn);
        }

        var detail = found
            ? Note($"Loaded from {located!.Value.Directory}")
            : Note("Download the fast, lightweight Whisper model (~39 MB) or high-accuracy WhisperFlow (~150-240 MB) "
                 + "for bilingual English & Arabic dictation. See docs/WHISPERFLOW_GUIDE.md. Expected in:\n"
                 + string.Join("\n", SpeechModelLocator.DefaultSearchPaths().Take(5)));

        return new StackPanel { Spacing = Tokens.Space.Snug, Children = { status, tierRow, detail } };
    }

    private void SelectKey(int key, string? warning)
    {
        for (var i = 0; i < Keys.Length; i++)
        {
            ((TransportKey)_keyRow.Children[i]).IsEngaged = Keys[i].Key == key;
        }

        _keyWarning.Text = warning ?? string.Empty;
        _keyWarning.IsVisible = warning is not null;

        if (_settings.Data.PushToTalkKey != key) Save(_settings.Data with { PushToTalkKey = key });
    }

    private void Save(SettingsData data) => _settings.Update(data);

    private static BrushedPanel Section(string label, Control content) => new BrushedPanel
    {
        Child = new StackPanel
        {
            Margin = new Thickness(Tokens.Space.Roomy),
            Spacing = Tokens.Space.Base,
            Children = { new Silkscreen { Text = label, IsLarge = true }, content },
        },
    };

    private static TextBlock Note(string text) => new()
    {
        Text = text,
        FontFamily = Tokens.Fonts.Grotesque,
        FontSize = Tokens.Fonts.Label,
        Foreground = new SolidColorBrush(Tokens.Colors.InkSecondary),
        TextWrapping = TextWrapping.Wrap,
    };

    private static CheckBox Toggle(string label, bool value, Action<bool> onChange)
    {
        var box = new CheckBox
        {
            IsChecked = value,
            Content = new TextBlock
            {
                Text = label,
                FontFamily = Tokens.Fonts.Grotesque,
                FontSize = Tokens.Fonts.Body,
                Foreground = Tokens.Brushes.Ink,
            },
        };

        box.IsCheckedChanged += (_, _) => onChange(box.IsChecked ?? false);
        return box;
    }
}
