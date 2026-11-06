using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace Murmur.Speech;

/// <summary>Hardware tier recommendation for speech recognition models.</summary>
public enum ModelTier
{
    /// <summary>
    /// Default lightweight model (Whisper Tiny ~39 MB / Base ~74 MB).
    /// Best for CPU-only systems, low memory, or battery saving.
    /// </summary>
    Default,

    /// <summary>
    /// Best high-performance model: WhisperFlow (Whisper Small / Flow ~150-240 MB).
    /// "Fast, but good" — recommended when GPU acceleration (CUDA or DirectML) is available.
    /// </summary>
    Best,
}

/// <summary>
/// Hardware capabilities and device specification of the host machine.
/// Automatically detects CPU cores, RAM, and GPU presence to select the optimal model tier.
/// </summary>
public sealed record DeviceCapabilities
{
    /// <summary>Operating system description.</summary>
    public string OperatingSystem { get; init; } = string.Empty;

    /// <summary>Process architecture (X64, Arm64).</summary>
    public string Architecture { get; init; } = string.Empty;

    /// <summary>Number of logical CPU cores.</summary>
    public int ProcessorCount { get; init; }

    /// <summary>Total system memory in megabytes.</summary>
    public long TotalMemoryMb { get; init; }

    /// <summary>Name of detected display/GPU adapter, or null.</summary>
    public string? GpuName { get; init; }

    /// <summary>Whether a capable GPU (NVIDIA, AMD, Intel Arc/Iris) is present.</summary>
    public bool HasGpu { get; init; }

    /// <summary>Preferred execution provider ("cuda", "directml", or "cpu").</summary>
    public string PreferredProvider { get; init; } = "cpu";

    /// <summary>Recommended model tier: Best (WhisperFlow) for GPU, Default for CPU.</summary>
    public ModelTier RecommendedTier { get; init; } = ModelTier.Default;

    /// <summary>Human-readable device summary.</summary>
    public string Summary { get; init; } = string.Empty;

    /// <summary>Cached device capabilities for the current machine.</summary>
    public static DeviceCapabilities Current { get; } = Detect();

    /// <summary>Detects host hardware and execution environment.</summary>
    public static DeviceCapabilities Detect()
    {
        var os = RuntimeInformation.OSDescription;
        var arch = RuntimeInformation.ProcessArchitecture.ToString();
        var cores = Environment.ProcessorCount;
        var memMb = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1024 * 1024);

        string? gpu = null;
        var hasGpu = false;
        var provider = "cpu";

        if (System.OperatingSystem.IsWindows())
        {
            gpu = QueryWindowsGpu();
            if (!string.IsNullOrWhiteSpace(gpu))
            {
                var lower = gpu.ToLowerInvariant();
                var isDiscreteOrCompute = lower.Contains("nvidia") || lower.Contains("geforce") ||
                                          lower.Contains("rtx") || lower.Contains("gtx") ||
                                          lower.Contains("radeon") || lower.Contains("arc") ||
                                          lower.Contains("iris");

                if (isDiscreteOrCompute || memMb >= 8192)
                {
                    hasGpu = true;
                    if (lower.Contains("nvidia") || File.Exists(Path.Combine(Environment.SystemDirectory, "nvcuda.dll")))
                    {
                        provider = "cuda";
                    }
                    else
                    {
                        provider = "directml";
                    }
                }
            }
        }
        else
        {
            // Non-Windows (Linux / macOS): check for GPU device nodes
            if (Directory.Exists("/dev") && Directory.GetFiles("/dev", "nvidia*").Length > 0)
            {
                hasGpu = true;
                gpu = "NVIDIA CUDA Device";
                provider = "cuda";
            }
        }

        var tier = hasGpu ? ModelTier.Best : ModelTier.Default;
        var tierLabel = hasGpu ? "WhisperFlow (Best / GPU)" : "Whisper Default (Lightweight / CPU)";
        var summary = $"{os} ({arch}) · {cores} Cores · {memMb} MB RAM" +
                      (gpu is not null ? $" · {gpu} (Provider: {provider})" : " · CPU Mode") +
                      $" · Recommended: {tierLabel}";

        return new DeviceCapabilities
        {
            OperatingSystem = os,
            Architecture = arch,
            ProcessorCount = cores,
            TotalMemoryMb = memMb,
            GpuName = gpu,
            HasGpu = hasGpu,
            PreferredProvider = provider,
            RecommendedTier = tier,
            Summary = summary,
        };
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DisplayDevice
    {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceKey;
    }

    [SupportedOSPlatform("windows")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplayDevices(string? lpDevice, uint iDevNum, ref DisplayDevice lpDisplayDevice, uint dwFlags);

    [SupportedOSPlatform("windows")]
    private static string? QueryWindowsGpu()
    {
        try
        {
            var d = new DisplayDevice();
            d.cb = Marshal.SizeOf(d);

            uint devNum = 0;
            while (EnumDisplayDevices(null, devNum, ref d, 0))
            {
                // DISPLAY_DEVICE_ATTACHED_TO_DESKTOP = 0x1
                if ((d.StateFlags & 0x1) != 0 && !string.IsNullOrWhiteSpace(d.DeviceString))
                {
                    return d.DeviceString.Trim();
                }
                devNum++;
            }
        }
        catch
        {
            // Silently fall back if display enumeration is restricted
        }
        return null;
    }
}
