using Murmur.Abstractions;

namespace Murmur.Speech;

/// <summary>
/// A transcriber that reports the speech model is missing rather than throwing.
/// </summary>
public sealed class UnavailableTranscriber : ITranscriber
{
    /// <inheritdoc />
    public bool IsReady => false;

    /// <inheritdoc />
    public ValueTask<bool> LoadAsync(CancellationToken cancellationToken) => ValueTask.FromResult(false);

    /// <inheritdoc />
    public ValueTask<string> TranscribeAsync(
        ReadOnlyMemory<float> samples,
        IReadOnlyList<string> biasPhrases,
        CancellationToken cancellationToken) => ValueTask.FromResult(string.Empty);

    /// <inheritdoc />
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}
