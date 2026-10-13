using System.Text.Json.Serialization;

namespace CamLinks.Models
{
    public class CameraControlCommand
    {
        [JsonPropertyName("action")]
        public string Action { get; set; } = string.Empty;

        [JsonPropertyName("int_value")]
        public int? IntValue { get; set; }

        [JsonPropertyName("float_value")]
        public float? FloatValue { get; set; }

        [JsonPropertyName("bool_value")]
        public bool? BoolValue { get; set; }

        [JsonPropertyName("x")]
        public float? X { get; set; }

        [JsonPropertyName("y")]
        public float? Y { get; set; }

        [JsonPropertyName("width")]
        public int? Width { get; set; }

        [JsonPropertyName("height")]
        public int? Height { get; set; }

        [JsonPropertyName("fps")]
        public int? Fps { get; set; }
    }
}