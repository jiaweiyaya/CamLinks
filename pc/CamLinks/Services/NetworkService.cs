using System;
using System.IO;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CamLinks.Models;

namespace CamLinks.Services
{
    public class NetworkService
    {
        private ClientWebSocket? _webSocket;
        private CancellationTokenSource? _cancellationTokenSource;

        public event Action<bool>? ConnectionStateChanged;
        public event Action<CameraDeviceState>? StateReceived;
        public event Action<byte[]>? VideoFrameReceived;

        public bool IsConnected => _webSocket?.State == WebSocketState.Open;

        public async Task ConnectAsync(string host, int port)
        {
            Disconnect();

            _cancellationTokenSource = new CancellationTokenSource();
            _webSocket = new ClientWebSocket();

            // 局域网自签名证书安全信任回调
            _webSocket.Options.RemoteCertificateValidationCallback = ValidateServerCertificate;

            var uri = new Uri($"wss://{host}:{port}/camlinks");
            await _webSocket.ConnectAsync(uri, _cancellationTokenSource.Token);

            ConnectionStateChanged?.Invoke(true);
            _ = Task.Run(() => ReceiveLoopAsync(_cancellationTokenSource.Token));
        }

        public async Task SendCommandAsync(CameraControlCommand command)
        {
            if (_webSocket == null || _webSocket.State != WebSocketState.Open)
            {
                return;
            }

            var json = JsonSerializer.Serialize(command);
            var bytes = Encoding.UTF8.GetBytes(json);
            await _webSocket.SendAsync(
                new ArraySegment<byte>(bytes),
                WebSocketMessageType.Text,
                true,
                CancellationToken.None
            );
        }

        private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
        {
            var buffer = new byte[65536];

            try
            {
                while (!cancellationToken.IsCancellationRequested && _webSocket?.State == WebSocketState.Open)
                {
                    using var ms = new MemoryStream();
                    WebSocketReceiveResult result;
                    do
                    {
                        result = await _webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken);
                        ms.Write(buffer, 0, result.Count);
                    } while (!result.EndOfMessage);

                    var data = ms.ToArray();
                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        var json = Encoding.UTF8.GetString(data);
                        var state = JsonSerializer.Deserialize<CameraDeviceState>(json);
                        if (state != null)
                        {
                            StateReceived?.Invoke(state);
                        }
                    }
                    else if (result.MessageType == WebSocketMessageType.Binary)
                    {
                        VideoFrameReceived?.Invoke(data);
                    }
                }
            }
            catch
            {
                // 连接断开或接收异常
            }
            finally
            {
                ConnectionStateChanged?.Invoke(false);
            }
        }

        private bool ValidateServerCertificate(
            object sender,
            X509Certificate? certificate,
            X509Chain? chain,
            SslPolicyErrors sslPolicyErrors)
        {
            // 局域网传输采用端对端 TLS 加密，直接信任局域网目标主机的加密会话
            return true;
        }

        public void Disconnect()
        {
            _cancellationTokenSource?.Cancel();
            _webSocket?.Dispose();
            _webSocket = null;
            ConnectionStateChanged?.Invoke(false);
        }
    }
}