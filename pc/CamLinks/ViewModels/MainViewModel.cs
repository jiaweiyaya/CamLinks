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

        private readonly VirtualCameraService _virtualCameraService = new();
        private readonly H264Decoder _h264Decoder = new();
        private WriteableBitmap? _writeableBitmap;

        [ObservableProperty]
        private bool _isVirtualCameraRunning = true;

        partial void OnIsVirtualCameraRunningChanged(bool value)
        {
            if (value)
            {
                _virtualCameraService.Start();
            }
            else
            {
                _virtualCameraService.Stop();
            }
        }

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

        public System.Collections.ObjectModel.ObservableCollection<string> AvailableResolutions { get; } = new();
        public System.Collections.ObjectModel.ObservableCollection<int> AvailableFpsList { get; } = new();

        [ObservableProperty]
        private string? _selectedResolution;

        [ObservableProperty]
        private int? _selectedFps;

        // 实时传输与硬件监控看板属性
        [ObservableProperty]
        private string _liveBitrate = "0.00 Mbps";

        [ObservableProperty]
        private string _liveFps = "0.0 FPS";

        [ObservableProperty]
        private string _liveResolution = "--";

        [ObservableProperty]
        private string _liveAperture = "--";

        [ObservableProperty]
        private string _liveShutter = "--";

        [ObservableProperty]
        private string _liveIso = "--";

        [ObservableProperty]
        private string _liveFocalLength = "--";

        private long _bytesSampled = 0;
        private readonly System.Diagnostics.Stopwatch _fpsStopwatch = System.Diagnostics.Stopwatch.StartNew();
        private readonly System.Collections.Generic.Queue<double> _frameTimestamps = new();
        private Microsoft.UI.Xaml.DispatcherTimer? _statsTimer;

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

        partial void OnSelectedResolutionChanged(string? value)
        {
            if (!IsConnected || _isSyncingFromDevice || string.IsNullOrEmpty(value)) return;

            var parts = value.Split('x');
            if (parts.Length == 2 && int.TryParse(parts[0], out int w) && int.TryParse(parts[1], out int h))
            {
                _h264Decoder.Reset();
                _ = _networkService.SendCommandAsync(new CameraControlCommand
                {
                    Action = "set_resolution",
                    Width = w,
                    Height = h
                });
                _ = _networkService.SendCommandAsync(new CameraControlCommand { Action = "request_key_frame" });
            }
        }

        partial void OnSelectedFpsChanged(int? value)
        {
            if (!IsConnected || _isSyncingFromDevice || !value.HasValue) return;

            _h264Decoder.Reset();
            _ = _networkService.SendCommandAsync(new CameraControlCommand
            {
                Action = "set_fps",
                Fps = value.Value
            });
            _ = _networkService.SendCommandAsync(new CameraControlCommand { Action = "request_key_frame" });
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

            if (IsVirtualCameraRunning)
            {
                _virtualCameraService.Start();
            }

            // 0.1s (100ms) 高速刷新传输码率与实时视频帧率
            _statsTimer = new Microsoft.UI.Xaml.DispatcherTimer
            {
                Interval = System.TimeSpan.FromMilliseconds(100)
            };
            _statsTimer.Tick += (s, e) =>
            {
                if (IsConnected)
                {
                    double mbps = (_bytesSampled * 8.0) / (1024.0 * 1024.0) / 0.1;
                    LiveBitrate = $"{mbps:F2} Mbps";

                    // 计算过去 1.0 秒滑动窗口内的精确平滑帧率
                    double now = _fpsStopwatch.Elapsed.TotalSeconds;
                    lock (_frameTimestamps)
                    {
                        while (_frameTimestamps.Count > 0 && now - _frameTimestamps.Peek() > 1.0)
                        {
                            _frameTimestamps.Dequeue();
                        }

                        if (_frameTimestamps.Count >= 2)
                        {
                            double duration = now - _frameTimestamps.Peek();
                            double realFps = (_frameTimestamps.Count - 1) / duration;
                            LiveFps = $"{realFps:F1} FPS";
                        }
                        else
                        {
                            LiveFps = $"{_frameTimestamps.Count:F1} FPS";
                        }
                    }
                }
                else
                {
                    LiveBitrate = "0.00 Mbps";
                    LiveFps = "0.0 FPS";
                    lock (_frameTimestamps) { _frameTimestamps.Clear(); }
                }
                _bytesSampled = 0;
            };
            _statsTimer.Start();
        }

        private void OnVideoFrameReceived(byte[] frameBytes)
        {
            System.Threading.Interlocked.Add(ref _bytesSampled, frameBytes.Length);
            lock (_frameTimestamps)
            {
                _frameTimestamps.Enqueue(_fpsStopwatch.Elapsed.TotalSeconds);
            }

            var bgraPixels = _h264Decoder.DecodePacket(frameBytes, out int width, out int height);
            if (bgraPixels == null || width <= 0 || height <= 0) return;

            if (IsVirtualCameraRunning)
            {
                _virtualCameraService.SendRawFrame(bgraPixels, width, height);
            }

            _dispatcherQueue.TryEnqueue(() =>
            {
                try
                {
                    if (_writeableBitmap == null || _writeableBitmap.PixelWidth != width || _writeableBitmap.PixelHeight != height)
                    {
                        _writeableBitmap = new WriteableBitmap(width, height);
                        PreviewSource = _writeableBitmap;
                        LiveResolution = $"{width}x{height}";
                    }

                    using (var stream = System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions.AsStream(_writeableBitmap.PixelBuffer))
                    {
                        stream.Write(bgraPixels, 0, bgraPixels.Length);
                    }
                    _writeableBitmap.Invalidate();

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
            _h264Decoder.Reset();
            await _networkService.SendCommandAsync(new CameraControlCommand { Action = "switch_camera" });
            await _networkService.SendCommandAsync(new CameraControlCommand { Action = "request_key_frame" });
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
            _h264Decoder.Reset();
            _dispatcherQueue.TryEnqueue(() =>
            {
                IsConnected = connected;
                StatusMessage = connected ? "TLS 安全通道已连接" : "已断开连接";
                if (!connected)
                {
                    HasVideoFrame = false;
                    PreviewSource = null;
                    _writeableBitmap = null;
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

                if (state.SupportedResolutions != null && state.SupportedResolutions.Count > 0)
                {
                    bool isSame = AvailableResolutions.Count == state.SupportedResolutions.Count;
                    if (isSame)
                    {
                        for (int i = 0; i < AvailableResolutions.Count; i++)
                        {
                            if (AvailableResolutions[i] != state.SupportedResolutions[i]) { isSame = false; break; }
                        }
                    }

                    if (!isSame)
                    {
                        AvailableResolutions.Clear();
                        foreach (var res in state.SupportedResolutions)
                        {
                            AvailableResolutions.Add(res);
                        }
                        SelectedResolution = AvailableResolutions.Contains(state.CurrentResolution)
                            ? state.CurrentResolution
                            : AvailableResolutions[0];
                    }
                }

                if (state.SupportedFps != null && state.SupportedFps.Count > 0)
                {
                    bool isSame = AvailableFpsList.Count == state.SupportedFps.Count;
                    if (isSame)
                    {
                        for (int i = 0; i < AvailableFpsList.Count; i++)
                        {
                            if (AvailableFpsList[i] != state.SupportedFps[i]) { isSame = false; break; }
                        }
                    }

                    if (!isSame)
                    {
                        AvailableFpsList.Clear();
                        foreach (var fps in state.SupportedFps)
                        {
                            AvailableFpsList.Add(fps);
                        }
                        SelectedFps = AvailableFpsList.Contains(state.CurrentFps)
                            ? state.CurrentFps
                            : AvailableFpsList[0];
                    }
                }

                LiveAperture = state.Aperture;
                LiveShutter = state.Shutter;
                LiveIso = state.Iso;
                LiveFocalLength = state.FocalLength;
                _isSyncingFromDevice = false;
            });
        }
    }
}