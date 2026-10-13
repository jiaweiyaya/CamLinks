using System;
using System.Runtime.InteropServices;
using FFmpeg.AutoGen;

namespace CamLinks.Services
{
    public unsafe class H264Decoder : IDisposable
    {
        private readonly object _syncLock = new();
        private AVCodecContext* _codecContext;
        private AVFrame* _frame;
        private AVFrame* _rgbFrame;
        private AVPacket* _packet;
        private SwsContext* _swsContext;
        private byte* _rgbBuffer;
        private byte* _paddedInputBuffer;
        private int _paddedInputCapacity;
        private int _currentWidth;
        private int _currentHeight;
        private int _currentFormat = -1;
        private bool _hasReceivedKeyframe;

        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern bool SetDllDirectory(string lpPathName);

        private static void InitializeFFmpeg()
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string[] candidatePaths = new[]
            {
                baseDir,
                System.IO.Path.Combine(baseDir, "FFmpegBinaries"),
                System.IO.Path.Combine(baseDir, "..", "..", "..", "FFmpegBinaries"),
                Environment.CurrentDirectory
            };

            string? targetPath = null;
            foreach (var path in candidatePaths)
            {
                if (System.IO.Directory.Exists(path) && System.IO.Directory.GetFiles(path, "avcodec*.dll").Length > 0)
                {
                    targetPath = System.IO.Path.GetFullPath(path);
                    break;
                }
            }

            if (string.IsNullOrEmpty(targetPath))
            {
                throw new System.IO.FileNotFoundException($"Cannot find FFmpeg native DLLs (avcodec*.dll) in {baseDir} or its subfolders.");
            }

            SetDllDirectory(targetPath);
            ffmpeg.RootPath = targetPath;
        }

        public H264Decoder()
        {
            InitializeFFmpeg();
            InitCodecContext();
            _frame = ffmpeg.av_frame_alloc();
            _rgbFrame = ffmpeg.av_frame_alloc();
            _packet = ffmpeg.av_packet_alloc();
        }

        private void InitCodecContext()
        {
            var codec = ffmpeg.avcodec_find_decoder(AVCodecID.AV_CODEC_ID_H264);
            if (codec == null)
            {
                throw new InvalidOperationException("H.264 decoder not found in FFmpeg libraries");
            }

            _codecContext = ffmpeg.avcodec_alloc_context3(codec);
            _codecContext->flags |= ffmpeg.AV_CODEC_FLAG_LOW_DELAY;
            _codecContext->flags2 |= ffmpeg.AV_CODEC_FLAG2_FAST;
            _codecContext->thread_count = 1;

            ffmpeg.avcodec_open2(_codecContext, codec, null);
        }

        public void Reset()
        {
            lock (_syncLock)
            {
                _hasReceivedKeyframe = false;
                if (_codecContext != null)
                {
                    var ctx = _codecContext;
                    ffmpeg.avcodec_free_context(&ctx);
                    _codecContext = null;
                }
                if (_swsContext != null)
                {
                    ffmpeg.sws_freeContext(_swsContext);
                    _swsContext = null;
                }
                _currentWidth = 0;
                _currentHeight = 0;
                _currentFormat = -1;
                InitCodecContext();
            }
        }

        public byte[]? DecodePacket(byte[] data, out int width, out int height)
        {
            width = 0;
            height = 0;
            if (data == null || data.Length == 0) return null;

            lock (_syncLock)
            {
                if (_codecContext == null) return null;

                // 切换流时拦截残缺 P 帧，直到遇到完整关键帧才放行，彻底消除过渡期花屏
                if (!_hasReceivedKeyframe)
                {
                    if (!IsKeyframe(data)) return null;
                    _hasReceivedKeyframe = true;
                }

                // 分配并填充带有安全对齐 padding 的缓冲区，防止 SIMD 越界访问造成 AccessViolation
                int requiredSize = data.Length + ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE;
                if (requiredSize > _paddedInputCapacity)
                {
                    if (_paddedInputBuffer != null) ffmpeg.av_free(_paddedInputBuffer);
                    _paddedInputBuffer = (byte*)ffmpeg.av_malloc((ulong)requiredSize);
                    _paddedInputCapacity = requiredSize;
                }

                Marshal.Copy(data, 0, (IntPtr)_paddedInputBuffer, data.Length);
                for (int i = 0; i < ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE; i++)
                {
                    _paddedInputBuffer[data.Length + i] = 0;
                }

                _packet->data = _paddedInputBuffer;
                _packet->size = data.Length;

                int ret = ffmpeg.avcodec_send_packet(_codecContext, _packet);
                if (ret < 0) return null;

                ret = ffmpeg.avcodec_receive_frame(_codecContext, _frame);
                if (ret == 0)
                {
                    if (_frame->format < 0) return null;
                    width = _frame->width;
                    height = _frame->height;
                    return ConvertToBgra(_frame, width, height);
                }
            }
            return null;
        }

        private static bool IsKeyframe(byte[] data)
        {
            for (int i = 0; i < Math.Min(data.Length - 4, 128); i++)
            {
                if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && data[i + 3] == 1)))
                {
                    int offset = data[i + 2] == 1 ? i + 3 : i + 4;
                    int nalType = data[offset] & 0x1F;
                    if (nalType == 5 || nalType == 7)
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        private byte[] ConvertToBgra(AVFrame* srcFrame, int w, int h)
        {
            if (_currentWidth != w || _currentHeight != h || _currentFormat != srcFrame->format || _swsContext == null)
            {
                if (_swsContext != null) ffmpeg.sws_freeContext(_swsContext);
                if (_rgbBuffer != null) Marshal.FreeHGlobal((IntPtr)_rgbBuffer);

                _swsContext = ffmpeg.sws_getContext(
                    w, h, (AVPixelFormat)srcFrame->format,
                    w, h, AVPixelFormat.AV_PIX_FMT_BGRA,
                    (int)SwsFlags.SWS_FAST_BILINEAR, null, null, null);

                int bufferSize = ffmpeg.av_image_get_buffer_size(AVPixelFormat.AV_PIX_FMT_BGRA, w, h, 1);
                _rgbBuffer = (byte*)Marshal.AllocHGlobal(bufferSize);

                byte_ptrArray4 dstData = new byte_ptrArray4();
                int_array4 dstLinesize = new int_array4();
                ffmpeg.av_image_fill_arrays(ref dstData, ref dstLinesize, _rgbBuffer, AVPixelFormat.AV_PIX_FMT_BGRA, w, h, 1);
                _rgbFrame->data[0] = dstData[0];
                _rgbFrame->linesize[0] = dstLinesize[0];

                _currentWidth = w;
                _currentHeight = h;
                _currentFormat = srcFrame->format;
            }

            ffmpeg.sws_scale(
                _swsContext,
                srcFrame->data,
                srcFrame->linesize,
                0,
                h,
                _rgbFrame->data,
                _rgbFrame->linesize);

            int totalBytes = w * h * 4;
            byte[] managedArray = new byte[totalBytes];
            Marshal.Copy((IntPtr)_rgbFrame->data[0], managedArray, 0, totalBytes);
            return managedArray;
        }

        public void Dispose()
        {
            lock (_syncLock)
            {
                if (_paddedInputBuffer != null)
                {
                    ffmpeg.av_free(_paddedInputBuffer);
                    _paddedInputBuffer = null;
                }
                if (_swsContext != null) ffmpeg.sws_freeContext(_swsContext);
                if (_rgbBuffer != null) Marshal.FreeHGlobal((IntPtr)_rgbBuffer);
                if (_codecContext != null)
            {
                var ctx = _codecContext;
                ffmpeg.avcodec_free_context(&ctx);
            }
            if (_frame != null)
            {
                var f = _frame;
                ffmpeg.av_frame_free(&f);
            }
            if (_rgbFrame != null)
            {
                var f = _rgbFrame;
                ffmpeg.av_frame_free(&f);
            }
            if (_packet != null)
            {
                var p = _packet;
                ffmpeg.av_packet_free(&p);
            }
            }
        }
    }
}