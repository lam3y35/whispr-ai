using System.Text;

namespace Murmur.Core;

/// <summary>
/// A tiny append-only log at %LOCALAPPDATA%\WhisprAI\app.log.
/// </summary>
/// <remarks>
/// The app has no console and a GUI exception the user never sees is undiagnosable —
/// the mic failure that reached a real user first proved that. Lives in the
/// platform-neutral core so DictationEngine can write to it; file IO here is no
/// different from DictionaryFile.
/// </remarks>
public static class AppLog
{
    private static readonly object Gate = new();

    private static readonly string FilePath = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "WhisprAI", "app.log");

    /// <summary>Where the log lives, so the UI can point the user at it.</summary>
    public static string Location => FilePath;

    /// <summary>Appends an informational line.</summary>
    public static void Info(string message) => Write("INFO ", message);

    /// <summary>Appends an error line.</summary>
    public static void Error(string message) => Write("ERROR", message);

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(FilePath)!);
                File.AppendAllText(
                    FilePath,
                    $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} {level} {message}{Environment.NewLine}",
                    Encoding.UTF8);
            }
        }
        catch
        {
            // Logging must never take the app down.
        }
    }
}
