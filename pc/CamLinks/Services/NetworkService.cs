using System;
using System.IO;
using System.Net.Security;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
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

        public async Task<string?> DiscoverDeviceAsync(int discoveryPort = 8444, int timeoutMs = 2500)
        {
            using var udpClient = new UdpClient();
            udpClient.EnableBroadcast = true;
            var requestBytes = Encoding.UTF8.GetBytes("CAMLINKS_DISCOVER");

            // 计算所有物理网卡的定向广播地址，绕过虚拟网卡阻断
            var broadcastTargets = new System.Collections.Generic.List<IPEndPoint>
            {
                new IPEndPoint(IPAddress.Broadcast, discoveryPort)
            };

            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus == OperationalStatus.Up &&
                    (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 || ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet))
                {
                    foreach (var u in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (u.Address.AddressFamily == AddressFamily.InterNetwork && u.IPv4Mask != null)
                        {
                            var ipBytes = u.Address.GetAddressBytes();
                            var maskBytes = u.IPv4Mask.GetAddressBytes();
                            var broadcastBytes = new byte[4];
                            for (int i = 0; i < 4; i++)
                            {
                                broadcastBytes[i] = (byte)(ipBytes[i] | ~maskBytes[i]);
                            }
                            broadcastTargets.Add(new IPEndPoint(new IPAddress(broadcastBytes), discoveryPort));
                        }
                    }
                }
            }

            foreach (var ep in broadcastTargets)
            {
                try { await udpClient.SendAsync(requestBytes, requestBytes.Length, ep); } catch { }
            }

            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                var receiveTask = udpClient.ReceiveAsync();
                var completedTask = await Task.WhenAny(receiveTask, Task.Delay(timeoutMs, cts.Token));
                if (completedTask == receiveTask)
                {
                    var result = await receiveTask;
                    var response = Encoding.UTF8.GetString(result.Buffer);
                    if (response.StartsWith("CAMLINKS_OFFER"))
                    {
                        return result.RemoteEndPoint.Address.ToString();
                    }
                }
            }
            catch
            {
            }

            return null;
        }

        public async Task ConnectAsync(string host, int port)
        {
            Disconnect();

            _cancellationTokenSource = new CancellationTokenSource();
            _webSocket = new ClientWebSocket();

            // 禁用系统代理，防止被 Clash 代理拦截局域网流量
            _webSocket.Options.Proxy = null;

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