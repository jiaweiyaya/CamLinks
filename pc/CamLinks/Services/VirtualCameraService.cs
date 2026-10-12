using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace CamLinks.Services
{
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    internal struct SharedImageMemoryHeader
    {
        public int Width;
        public int Height;
        public int Format;     // 0: ARGB32
        public int ResizeMode; // 0: Linear
        public int MirrorMode; // 0: None
        public int Timeout;    // 超时毫秒
    }

    public class VirtualCameraService : IDisposable
    {
        private const string MemoryName = "CamLinks_CaptureInstance0";
        private const int MaxBufferSize = 1920 * 1080 * 4 + 32;

        private MemoryMappedFile? _mmf;
        private MemoryMappedViewAccessor? _accessor;
        public bool IsRunning { get; private set; }

        public static bool EnsureDriverRegistered()
        {
            string driverPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "CamLinks.VirtualCam.dll");
            if (!File.Exists(driverPath))
            {
                driverPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Drivers", "CamLinks.VirtualCam.dll");
                if (!File.Exists(driverPath))
                {
                    return false;
                }
            }

            try
            {
                // 使用 Windows 内置的 regsvr32 /s 进行静默注册，不弹黑框、不弹提示
                var psi = new System.Diagnostics.ProcessStartInfo
                {
                    FileName = "regsvr32.exe",
                    Arguments = $"/s \"{driverPath}\"",
                    UseShellExecute = true,
                    Verb = "runas" // 请求 UAC 授权静默写入注册表
                };
                var process = System.Diagnostics.Process.Start(psi);
                process?.WaitForExit(3000);
                return true;
            }
            catch
            {
                return false;
            }
        }

        public void Start()
        {
            if (IsRunning) return;
            try
            {
                EnsureDriverRegistered();
                _mmf = MemoryMappedFile.CreateOrOpen(MemoryName, MaxBufferSize);
                _accessor = _mmf.CreateViewAccessor(0, MaxBufferSize);
                IsRunning = true;
            }
            catch
            {
                IsRunning = false;
            }
        }

        public async Task SendFrameAsync(byte[] jpegBytes)
        {
            if (!IsRunning || _accessor == null) return;

            try
            {
                using var stream = new InMemoryRandomAccessStream();
                using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
                {
                    writer.WriteBytes(jpegBytes);
                    await writer.StoreAsync().AsTask();
                }

                var decoder = await BitmapDecoder.CreateAsync(stream);
                var pixelData = await decoder.GetPixelDataAsync(
                    BitmapPixelFormat.Bgra8,
                    BitmapAlphaMode.Premultiplied,
                    new BitmapTransform(),
                    ExifOrientationMode.RespectExifOrientation,
                    ColorManagementMode.DoNotColorManage
                );

                byte[] pixels = pixelData.DetachPixelData();
                int width = (int)decoder.PixelWidth;
                int height = (int)decoder.PixelHeight;

                var header = new SharedImageMemoryHeader
                {
                    Width = width,
                    Height = height,
                    Format = 0,
                    ResizeMode = 0,
                    MirrorMode = 0,
                    Timeout = 1000
                };

                int headerSize = Marshal.SizeOf<SharedImageMemoryHeader>();
                _accessor.Write(0, ref header);
                _accessor.WriteArray(headerSize, pixels, 0, Math.Min(pixels.Length, MaxBufferSize - headerSize));
            }
            catch
            {
            }
        }

        public void Stop()
        {
            IsRunning = false;
            _accessor?.Dispose();
            _accessor = null;
            _mmf?.Dispose();
            _mmf = null;
        }

        public void Dispose()
        {
            Stop();
        }
    }
}