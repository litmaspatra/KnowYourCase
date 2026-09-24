using System.Buffers.Binary;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

static class Program
{
    private static readonly Guid ServiceUuid = new("6f61f3b2-0d74-4a2e-b327-7b690730ad31");

    static async Task<int> Main()
    {
        Console.Title = "BlueSpeaker Bridge";
        Console.WriteLine("BlueSpeaker Bridge");
        Console.WriteLine("==================");
        Console.WriteLine("1. Pair your phone in Windows Bluetooth settings.");
        Console.WriteLine("2. Open BlueSpeaker on the phone and tap Start.");
        Console.WriteLine("3. Select CABLE Input (VB-Audio Virtual Cable) as the Windows output.");
        Console.WriteLine();

        using var enumerator = new MMDeviceEnumerator();
        var renderDevices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);
        var cable = renderDevices.FirstOrDefault(d =>
            d.FriendlyName.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase) ||
            d.FriendlyName.Contains("VB-Audio Virtual Cable", StringComparison.OrdinalIgnoreCase));

        if (cable is null)
        {
            Console.Error.WriteLine("ERROR: VB-CABLE playback endpoint was not found.");
            Console.Error.WriteLine("Install VB-CABLE, then run BlueSpeaker Bridge again.");
            return 2;
        }

        Console.WriteLine($"Audio endpoint: {cable.FriendlyName}");
        Console.WriteLine("Searching paired Bluetooth devices for the BlueSpeaker service...");

        BluetoothClient? bt = null;
        foreach (var device in new BluetoothClient().DiscoverDevices())
        {
            if (!device.Authenticated) continue;
            Console.Write($"Trying {device.DeviceName} ({device.DeviceAddress})... ");
            try
            {
                var candidate = new BluetoothClient();
                candidate.Connect(device.DeviceAddress, ServiceUuid);
                bt = candidate;
                Console.WriteLine("connected.");
                break;
            }
            catch
            {
                Console.WriteLine("not BlueSpeaker.");
            }
        }

        if (bt is null)
        {
            Console.Error.WriteLine();
            Console.Error.WriteLine("ERROR: Could not connect to BlueSpeaker.");
            Console.Error.WriteLine("Make sure the phone is paired with Windows and the app says 'Waiting for computer'.");
            return 3;
        }

        using (bt)
        using (var stream = bt.GetStream())
        using (var capture = new WasapiLoopbackCapture(cable))
        {
            var gate = new SemaphoreSlim(1, 1);
            Exception? sendError = null;

            capture.DataAvailable += (_, e) =>
            {
                if (sendError is not null) return;
                try
                {
                    gate.Wait();
                    var wf = capture.WaveFormat;
                    short formatCode = GetFormatCode(wf);
                    Span<byte> header = stackalloc byte[16];
                    header[0] = (byte)'B'; header[1] = (byte)'S'; header[2] = (byte)'P'; header[3] = (byte)'K';
                    BinaryPrimitives.WriteInt32LittleEndian(header.Slice(4, 4), wf.SampleRate);
                    BinaryPrimitives.WriteInt16LittleEndian(header.Slice(8, 2), (short)wf.Channels);
                    BinaryPrimitives.WriteInt16LittleEndian(header.Slice(10, 2), formatCode);
                    BinaryPrimitives.WriteInt32LittleEndian(header.Slice(12, 4), e.BytesRecorded);
                    stream.Write(header);
                    stream.Write(e.Buffer, 0, e.BytesRecorded);
                }
                catch (Exception ex)
                {
                    sendError = ex;
                }
                finally
                {
                    if (gate.CurrentCount == 0) gate.Release();
                }
            };

            capture.StartRecording();
            Console.WriteLine();
            Console.WriteLine($"Streaming {capture.WaveFormat.SampleRate} Hz, {capture.WaveFormat.Channels} ch, {capture.WaveFormat.BitsPerSample}-bit.");
            Console.WriteLine("BlueSpeaker is active. Press ENTER to stop.");

            while (!Console.KeyAvailable && sendError is null)
                await Task.Delay(250);

            if (Console.KeyAvailable) Console.ReadLine();
            capture.StopRecording();

            if (sendError is not null)
            {
                Console.Error.WriteLine($"Bluetooth streaming failed: {sendError.Message}");
                return 4;
            }
        }

        return 0;
    }

    private static short GetFormatCode(WaveFormat wf)
    {
        if (wf.Encoding == WaveFormatEncoding.IeeeFloat) return 1;
        if (wf.Encoding == WaveFormatEncoding.Pcm)
        {
            return wf.BitsPerSample switch { 24 => 2, 32 => 3, _ => 0 };
        }

        if (wf.Encoding == WaveFormatEncoding.Extensible && wf is WaveFormatExtensible ext)
        {
            if (ext.SubFormat == AudioSubtypes.MEDIASUBTYPE_IEEE_FLOAT) return 1;
            if (ext.SubFormat == AudioSubtypes.MEDIASUBTYPE_PCM)
                return wf.BitsPerSample switch { 24 => 2, 32 => 3, _ => 0 };
        }

        throw new NotSupportedException($"Unsupported capture format: {wf.Encoding}, {wf.BitsPerSample} bit");
    }
}
