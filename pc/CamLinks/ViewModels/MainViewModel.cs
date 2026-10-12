using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CamLinks.Models;
using CamLinks.Services;

namespace CamLinks.ViewModels
{
    public partial class MainViewModel : ObservableObject
    {
        private readonly NetworkService _networkService = new();

        [ObservableProperty]
        private string _targetIp = "192.168.1.100";

        [ObservableProperty]
        private int _targetPort = 8443;

        [ObservableProperty]
        [NotifyPropertyChangedFor(nameof(ConnectionButtonText))]
        [NotifyPropertyChangedFor(nameof(VideoPlaceholderVisibility))]
        private bool _isConnected;

        public string ConnectionButtonText => IsConnected ? "断开" : "连接";

        public Microsoft.UI.Xaml.Visibility VideoPlaceholderVisibility =>
            IsConnected ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;

        [ObservableProperty]
        private string _statusMessage = "未连接";

        [ObservableProperty]
        private bool _isTorchOn;

        [ObservableProperty]
        private int _exposure = 0;

        [ObservableProperty]
        private int _minExposure = -4;

        [ObservableProperty]
        private int _maxExposure = 4;

        [ObservableProperty]
        private int _bitrate = 4000; // 单位 kbps

        [ObservableProperty]
        private bool _isVirtualCameraRunning;

        public MainViewModel()
        {
            _networkService.ConnectionStateChanged += OnConnectionStateChanged;
            _networkService.StateReceived += OnDeviceStateReceived;
        }

        [RelayCommand]
        public async Task ToggleConnectionAsync()
        {
            if (IsConnected)
            {
                _networkService.Disconnect();
            }
            else
            {
                StatusMessage = "正在建立 TLS 加密连接...";
                try
                {
                    await _networkService.ConnectAsync(TargetIp, TargetPort);
                }
                catch
                {
                    StatusMessage = "连接失败，请检查手机 IP 与端口";
                }
            }
        }

        [RelayCommand]
        public async Task SwitchCameraAsync()
        {
            await _networkService.SendCommandAsync(new CameraControlCommand { Action = "switch_camera" });
        }

        [RelayCommand]
        public async Task ToggleTorchAsync()
        {
            IsTorchOn = !IsTorchOn;
            await _networkService.SendCommandAsync(new CameraControlCommand
            {
                Action = "set_torch",
                BoolValue = IsTorchOn
            });
        }

        [RelayCommand]
        public async Task CapturePhotoAsync()
        {
            await _networkService.SendCommandAsync(new CameraControlCommand { Action = "capture_photo" });
        }

        [RelayCommand]
        public async Task UpdateExposureAsync(int value)
        {
            Exposure = value;
            await _networkService.SendCommandAsync(new CameraControlCommand
            {
                Action = "set_exposure",
                IntValue = value
            });
        }

        [RelayCommand]
        public async Task UpdateBitrateAsync(int value)
        {
            Bitrate = value;
            await _networkService.SendCommandAsync(new CameraControlCommand
            {
                Action = "set_bitrate",
                IntValue = value
            });
        }

        private void OnConnectionStateChanged(bool connected)
        {
            IsConnected = connected;
            StatusMessage = connected ? "TLS 安全通道已连接" : "已断开连接";
        }

        private void OnDeviceStateReceived(CameraDeviceState state)
        {
            IsTorchOn = state.IsTorchOn;
            Exposure = state.ExposureCompensation;
            MinExposure = state.MinExposure;
            MaxExposure = state.MaxExposure;
            Bitrate = state.Bitrate;
        }
    }
}