using System;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CamLinks.Models;
using CamLinks.Services;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;

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

        private bool _isSyncingFromDevice;
        private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcherQueue = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();

        [ObservableProperty]
        private int _bitrate = 4000; // 单位 kbps

        [ObservableProperty]
        private bool _isVirtualCameraRunning;

        [ObservableProperty]
        [NotifyPropertyChangedFor(nameof(CanScan))]
        [NotifyPropertyChangedFor(nameof(CanToggleConnect))]
        [NotifyPropertyChangedFor(nameof(ScanProgressVisibility))]
        [NotifyPropertyChangedFor(nameof(ScanIconVisibility))]
        private bool _isScanning;

        [ObservableProperty]
        [NotifyPropertyChangedFor(nameof(CanScan))]
        [NotifyPropertyChangedFor(nameof(CanToggleConnect))]
        [NotifyPropertyChangedFor(nameof(ConnectProgressVisibility))]
        [NotifyPropertyChangedFor(nameof(ConnectIconVisibility))]
        private bool _isConnecting;

        public bool CanScan => !IsScanning && !IsConnecting && !IsConnected;
        public bool CanToggleConnect => !IsConnecting && !IsScanning;

        public Microsoft.UI.Xaml.Visibility ScanProgressVisibility =>
            IsScanning ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
        public Microsoft.UI.Xaml.Visibility ScanIconVisibility =>
            IsScanning ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;

        public Microsoft.UI.Xaml.Visibility ConnectProgressVisibility =>
            IsConnecting ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
        public Microsoft.UI.Xaml.Visibility ConnectIconVisibility =>
            IsConnecting ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;

        [ObservableProperty]
        private double _bitrateInputValue = 4.0; // 默认 4 Mbps

        [ObservableProperty]
        private int _selectedBitrateUnitIndex = 1; // 0: kbps, 1: Mbps

        [ObservableProperty]
        private ImageSource? _previewSource;

        [ObservableProperty]
        [NotifyPropertyChangedFor(nameof(VideoPlaceholderVisibility))]
        private bool _hasVideoFrame;

        public Microsoft.UI.Xaml.Visibility VideoPlaceholderVisibility =>
            (IsConnected && HasVideoFrame) ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;

        partial void OnExposureChanged(int value)
        {
            if (IsConnected && !_isSyncingFromDevice)
            {
                _ = _networkService.SendCommandAsync(new CameraControlCommand
                {
                    Action = "set_exposure",
                    IntValue = value
                });
            }
        }

        partial void OnSelectedBitrateUnitIndexChanged(int value)
        {
            // 切换单位时自动换算当前输入框的值
            if (value == 1)
            {
                BitrateInputValue = System.Math.Round(BitrateInputValue / 1000.0, 2);
            }
            else
            {
                BitrateInputValue = System.Math.Round(BitrateInputValue * 1000.0);
            }
            ApplyBitrate();
        }

        public void ApplyBitrate()
        {
            bool isMbps = SelectedBitrateUnitIndex == 1;
            double minAllowed = isMbps ? 0.02 : 20.0;
            double maxAllowed = isMbps ? 400.0 : 400000.0;

            if (BitrateInputValue < minAllowed)
            {
                BitrateInputValue = minAllowed;
            }
            else if (BitrateInputValue > maxAllowed)
            {
                BitrateInputValue = maxAllowed;
            }

            int totalKbps = isMbps ? (int)System.Math.Round(BitrateInputValue * 1000.0) : (int)System.Math.Round(BitrateInputValue);
            totalKbps = System.Math.Clamp(totalKbps, 20, 400000);
            Bitrate = totalKbps;

            if (IsConnected)
            {
                _ = _networkService.SendCommandAsync(new CameraControlCommand
                {
                    Action = "set_bitrate",
                    IntValue = totalKbps
                });
            }
        }

        public MainViewModel()
        {
            _networkService.ConnectionStateChanged += OnConnectionStateChanged;
            _networkService.StateReceived += OnDeviceStateReceived;
            _networkService.VideoFrameReceived += OnVideoFrameReceived;
        }

        private void OnVideoFrameReceived(byte[] frameBytes)
        {
            _dispatcherQueue.TryEnqueue(async () =>
            {
                try
                {
                    using var stream = new InMemoryRandomAccessStream();
                    using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
                    {
                        writer.WriteBytes(frameBytes);
                        await writer.StoreAsync().AsTask();
                    }
                    var bitmap = new BitmapImage();
                    await bitmap.SetSourceAsync(stream).AsTask();
                    PreviewSource = bitmap;
                    if (!HasVideoFrame)
                    {
                        HasVideoFrame = true;
                    }
                }
                catch { }
            });
        }

        [RelayCommand]
        public async Task ScanDeviceAsync()
        {
            if (IsScanning || IsConnected) return;

            IsScanning = true;
            StatusMessage = "正在扫描局域网中的 CamLinks 设备...";
            var foundIp = await _networkService.DiscoverDeviceAsync();
            IsScanning = false;

            if (!string.IsNullOrEmpty(foundIp))
            {
                TargetIp = foundIp;
                StatusMessage = $"已发现手机 [{foundIp}]，正在建立连接...";
                await ToggleConnectionAsync();
            }
            else
            {
                StatusMessage = "未发现手机，请确保手机与电脑在同一 Wi-Fi 下且已打开 App";
            }
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
                IsConnecting = true;
                StatusMessage = "正在建立 TLS 加密连接...";
                try
                {
                    await _networkService.ConnectAsync(TargetIp, TargetPort);
                }
                catch (System.Exception ex)
                {
                    StatusMessage = $"连接失败: {ex.Message}";
                }
                finally
                {
                    IsConnecting = false;
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
            _dispatcherQueue.TryEnqueue(() =>
            {
                IsConnected = connected;
                StatusMessage = connected ? "TLS 安全通道已连接" : "已断开连接";
                if (!connected)
                {
                    HasVideoFrame = false;
                    PreviewSource = null;
                }
            });
        }

        private void OnDeviceStateReceived(CameraDeviceState state)
        {
            _dispatcherQueue.TryEnqueue(() =>
            {
                _isSyncingFromDevice = true;
                IsTorchOn = state.IsTorchOn;
                Exposure = state.ExposureCompensation;
                MinExposure = state.MinExposure;
                MaxExposure = state.MaxExposure;
                Bitrate = state.Bitrate;
                _isSyncingFromDevice = false;
            });
        }
    }
}