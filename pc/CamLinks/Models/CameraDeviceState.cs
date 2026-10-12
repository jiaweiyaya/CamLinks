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
    }
}