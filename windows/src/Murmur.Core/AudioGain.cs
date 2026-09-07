namespace Murmur.Core;

/// <summary>
/// Boosts quiet recordings to a level a speech model can actually use.
/// </summary>
/// <remarks>
/// <para>
/// A real user's sessions transcribed fine at one input level and produced nothing at
/// a tenth of it — the model does not hear a faint murmur. Every dictation product
/// solves this with gain; without it, a user's microphone level slider becomes a
/// coin flip on whether dictation works at all.
/// </para>
/// <para>
/// Rules: RMS-targeted, boost-only (gain clamped to at least 1 — a healthy recording
/// must pass through untouched), capped at <see cref="MaxGain"/> so the noise floor is
/// not amplified into a hallucination invitation, and hard-clipped to just below full
/// scale. Digital silence is returned as-is: boosting it would only amplify dither.
/// </para>
/// </remarks>
public static class AudioGain
{
    /// <summary>RMS level a healthy voice recording lands near.</summary>
    public const float TargetRms = 0.12f;

    /// <summary>The most a quiet signal is ever amplified.</summary>
    public const float MaxGain = 16f;

    /// <summary>Output ceiling, leaving headroom below full scale.</summary>
    public const float PeakCeiling = 0.99f;

    /// <summary>
    /// Returns a copy of <paramref name="samples"/> with gain applied.
    /// </summary>
    public static float[] Normalize(ReadOnlySpan<float> samples)
    {
        var result = samples.ToArray();
        if (result.Length == 0) return result;

        var sumOfSquares = 0d;
        var peak = 0f;
        foreach (var sample in result)
        {
            sumOfSquares += (double)sample * sample;
            var magnitude = MathF.Abs(sample);
            if (magnitude > peak) peak = magnitude;
        }

        // Exactly zero means the OS handed us a dead pipe, not a quiet room; there is
        // nothing to recover and boosting would only amplify dither.
        if (peak == 0f) return result;

        var rms = MathF.Sqrt((float)(sumOfSquares / result.Length));
        if (rms == 0f) return result;

        var gain = Math.Clamp(TargetRms / rms, 1f, MaxGain);
        if (gain == 1f) return result;

        // A boost that would clip the loudest peak is capped to what fits.
        var headroom = PeakCeiling / peak;
        if (gain > headroom) gain = headroom;

        for (var i = 0; i < result.Length; i++) result[i] *= gain;
        return result;
    }
}
