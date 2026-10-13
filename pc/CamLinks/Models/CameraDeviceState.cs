using System.Text.Json.Serialization;

namespace CamLinks.Models
{
    public class CameraDeviceState
    {
        [JsonPropertyName("is_torch_on")]
        public bool IsTorchOn { get; set; }

        [JsonPropertyName("lens_facing")]
        public int LensFacing { get; set; }

        [JsonPropertyName("exposure_compensation")]
        public int ExposureCompensation { get; set; }

        [JsonPropertyName("min_exposure")]
        public int MinExposure { get; set; }

        [JsonPropertyName("max_exposure")]
        public int MaxExposure { get; set; }

        [JsonPropertyName("zoom_ratio")]
        public float ZoomRatio { get; set; } = 1.0f;

        [JsonPropertyName("bitrate")]
        public int Bitrate { get; set; } = 4000;

        [JsonPropertyName("current_resolution")]
        public string CurrentResolution { get; set; } = "1920x1080";

        [JsonPropertyName("current_fps")]
        public int CurrentFps { get; set; } = 30;

        [JsonPropertyName("supported_resolutions")]
        public System.Collections.Generic.List<string>? SupportedResolutions { get; set; }

        [JsonPropertyName("supported_fps")]
        public System.Collections.Generic.List<int>? SupportedFps { get; set; }

        [JsonPropertyName("aperture")]
        public string Aperture { get; set; } = "f/1.8";

        [JsonPropertyName("shutter")]
        public string Shutter { get; set; } = "1/100s";

        [JsonPropertyName("iso")]
        public string Iso { get; set; } = "ISO 100";

        [JsonPropertyName("focal_length")]
        public string FocalLength { get; set; } = "26mm";
    }
}