using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.UI;
using Microsoft.Graphics.Canvas.UI.Xaml;
using PokeyellowCore;
using Windows.Foundation;
using Windows.Gaming.Input;
using Windows.Graphics.DirectX;
using Windows.Media;
using Windows.Media.Audio;
using Windows.Media.Render;
using Windows.Storage;
using Windows.System;
using Windows.UI.Core;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace PokeyellowXbox
{
    [ComImport]
    [Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    unsafe interface IMemoryBufferByteAccess
    {
        void GetBuffer(out byte* buffer, out uint capacity);
    }

    /// <summary>
    /// The game screen. Win2D's game loop calls Update ~59.7 times a second on a
    /// worker thread: it reads the controller, runs one emulated frame and queues
    /// the audio, which an AudioGraph pulls on its own thread.
    /// </summary>
    public sealed partial class MainPage : Page
    {
        private const string RomName = "pokeyellow.gbc";
        private const int FastForwardSpeed = 4;
        private const double StickThreshold = 0.5;
        private static readonly TimeSpan SaveCheckInterval = TimeSpan.FromSeconds(2);

        private GameBoy gb;
        private string saveName;
        private CanvasBitmap bitmap;
        private readonly byte[] pixels = new byte[GameBoy.Width * GameBoy.Height * 4];
        private readonly short[] audioScratch = new short[8192];
        private TimeSpan lastSaveCheck;
        private readonly SemaphoreSlim saveLock = new SemaphoreSlim(1, 1);

        // Keyboard state (for testing on a PC), written on the UI thread.
        private volatile int keyboardButtons;
        private volatile bool keyboardFastForward;

        // Audio: ring buffer of interleaved stereo floats.
        private AudioGraph graph;
        private AudioFrameInputNode inputNode;
        private readonly object audioLock = new object();
        private float[] ring = new float[1];
        private int ringRead, ringCount;
        private int maxQueuedFloats;

        public MainPage()
        {
            InitializeComponent();
            Screen.TargetElapsedTime = TimeSpan.FromTicks((long)(TimeSpan.TicksPerSecond / GameBoy.Fps));
            Screen.Paused = true;
        }

        protected override async void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            // The B button raises "back"; swallow it so it only reaches the game.
            Windows.UI.Core.SystemNavigationManager.GetForCurrentView().BackRequested += (s, a) => a.Handled = true;
            Window.Current.CoreWindow.KeyDown += OnKeyDown;
            Window.Current.CoreWindow.KeyUp += OnKeyUp;

            byte[] rom = await LoadRomAsync();
            if (rom == null)
            {
                ShowMessage("No ROM found.\nBuild pokeyellow.gbc with `make` before building the app, " +
                            "or copy it into the app's LocalState folder:\n" + ApplicationData.Current.LocalFolder.Path, false);
                return;
            }

            int sampleRate = await StartAudioAsync();
            try
            {
                var cart = new Cartridge(rom);
                saveName = (cart.Title.Length == 0 ? "game" : cart.Title.Replace(' ', '_')) + ".sav";
                var saveItem = await ApplicationData.Current.LocalFolder.TryGetItemAsync(saveName) as StorageFile;
                if (saveItem != null)
                {
                    var buffer = await FileIO.ReadBufferAsync(saveItem);
                    var data = new byte[buffer.Length];
                    Windows.Storage.Streams.DataReader.FromBuffer(buffer).ReadBytes(data);
                    cart.LoadRam(data);
                }
                gb = new GameBoy(cart, sampleRate);
            }
            catch (Exception ex)
            {
                ShowMessage("Could not load the ROM: " + ex.Message, false);
                return;
            }

            ShowMessage("A = A   B = B   ≡ Menu = START   ⧉ View = SELECT\nHold RT or RB to fast-forward", true);
            Screen.Paused = false;
        }

        private static async Task<byte[]> LoadRomAsync()
        {
            StorageFile file = null;
            try
            {
                file = await StorageFile.GetFileFromApplicationUriAsync(new Uri("ms-appx:///Assets/" + RomName));
            }
            catch (Exception)
            {
                file = await ApplicationData.Current.LocalFolder.TryGetItemAsync(RomName) as StorageFile;
            }
            if (file == null) return null;
            var buffer = await FileIO.ReadBufferAsync(file);
            var bytes = new byte[buffer.Length];
            Windows.Storage.Streams.DataReader.FromBuffer(buffer).ReadBytes(bytes);
            return bytes;
        }

        // ---- audio ----

        private async Task<int> StartAudioAsync()
        {
            const int fallbackRate = 48000;
            try
            {
                var settings = new AudioGraphSettings(AudioRenderCategory.GameMedia)
                {
                    QuantumSizeSelectionMode = QuantumSizeSelectionMode.LowestLatency
                };
                var result = await AudioGraph.CreateAsync(settings);
                if (result.Status != AudioGraphCreationStatus.Success) return fallbackRate;
                graph = result.Graph;
                var output = await graph.CreateDeviceOutputNodeAsync();
                if (output.Status != AudioDeviceNodeCreationStatus.Success) return fallbackRate;

                var props = graph.EncodingProperties;
                props.ChannelCount = 2;
                inputNode = graph.CreateFrameInputNode(props);
                inputNode.AddOutgoingConnection(output.DeviceOutputNode);
                inputNode.QuantumStarted += OnQuantumStarted;

                int rate = (int)props.SampleRate;
                ring = new float[rate]; // half a second of stereo
                // Keep at most ~4 video frames of audio queued to bound latency.
                maxQueuedFloats = (int)(rate / GameBoy.Fps) * 2 * 4;
                graph.Start();
                return rate;
            }
            catch (Exception)
            {
                return fallbackRate;
            }
        }

        private void QueueAudio(short[] samples, int count)
        {
            lock (audioLock)
            {
                for (int i = 0; i < count; i++)
                {
                    if (ringCount == ring.Length)
                    {
                        ringRead = (ringRead + 1) % ring.Length;
                        ringCount--;
                    }
                    ring[(ringRead + ringCount) % ring.Length] = samples[i] / 32768f;
                    ringCount++;
                }
                // Drop the oldest audio if we've drifted too far ahead (keep stereo pairs aligned).
                int excess = ringCount - maxQueuedFloats;
                if (excess > 0)
                {
                    excess &= ~1;
                    ringRead = (ringRead + excess) % ring.Length;
                    ringCount -= excess;
                }
            }
        }

        private unsafe void OnQuantumStarted(AudioFrameInputNode sender, FrameInputNodeQuantumStartedEventArgs args)
        {
            int samples = args.RequiredSamples;
            if (samples <= 0) return;
            int floats = samples * 2;
            var frame = new AudioFrame((uint)(floats * sizeof(float)));
            using (var buffer = frame.LockBuffer(AudioBufferAccessMode.Write))
            using (var reference = buffer.CreateReference())
            {
                byte* data;
                uint capacity;
                ((IMemoryBufferByteAccess)reference).GetBuffer(out data, out capacity);
                float* outp = (float*)data;
                lock (audioLock)
                {
                    int n = Math.Min(floats, ringCount);
                    for (int i = 0; i < n; i++)
                    {
                        outp[i] = ring[ringRead];
                        ringRead = (ringRead + 1) % ring.Length;
                    }
                    ringCount -= n;
                    for (int i = n; i < floats; i++) outp[i] = 0f; // underrun: silence
                }
            }
            sender.AddFrame(frame);
        }

        // ---- game loop (Win2D worker thread) ----

        private void Screen_CreateResources(CanvasAnimatedControl sender, CanvasCreateResourcesEventArgs args)
        {
            bitmap = CanvasBitmap.CreateFromBytes(sender, pixels, GameBoy.Width, GameBoy.Height,
                DirectXPixelFormat.B8G8R8A8UIntNormalized);
        }

        private void Screen_Update(ICanvasAnimatedControl sender, CanvasAnimatedUpdateEventArgs args)
        {
            var g = gb;
            if (g == null) return;

            bool fastForward;
            int buttons = ReadControllers(out fastForward) | keyboardButtons;
            fastForward |= keyboardFastForward;

            int frames = fastForward ? FastForwardSpeed : 1;
            for (int i = 0; i < frames; i++)
            {
                g.SetButtons(buttons);
                g.RunFrame();
            }

            if (fastForward || inputNode == null)
            {
                g.DiscardAudio();
            }
            else
            {
                int n = g.ReadAudio(audioScratch);
                if (n > 0) QueueAudio(audioScratch, n);
            }

            Buffer.BlockCopy(g.FrameBuffer, 0, pixels, 0, pixels.Length);

            var now = args.Timing.TotalTime;
            if (now - lastSaveCheck > SaveCheckInterval)
            {
                lastSaveCheck = now;
                if (g.Cartridge.HasBattery && g.Cartridge.ConsumeRamDirty())
                {
                    var _ = WriteSaveAsync((byte[])g.Cartridge.GetRam().Clone());
                }
            }
        }

        private void Screen_Draw(ICanvasAnimatedControl sender, CanvasAnimatedDrawEventArgs args)
        {
            if (bitmap == null || gb == null) return;
            bitmap.SetPixelBytes(pixels);

            // Largest whole-pixel scale that fits, centred, for crisp pixels.
            float dpiScale = sender.Dpi / 96f;
            double w = sender.Size.Width, h = sender.Size.Height;
            int scale = (int)Math.Min(w * dpiScale / GameBoy.Width, h * dpiScale / GameBoy.Height);
            double dw, dh;
            if (scale >= 1)
            {
                dw = GameBoy.Width * scale / dpiScale;
                dh = GameBoy.Height * scale / dpiScale;
            }
            else
            {
                double s = Math.Min(w / GameBoy.Width, h / GameBoy.Height);
                dw = GameBoy.Width * s;
                dh = GameBoy.Height * s;
            }
            var dest = new Rect((w - dw) / 2, (h - dh) / 2, dw, dh);
            args.DrawingSession.DrawImage(bitmap, dest, new Rect(0, 0, GameBoy.Width, GameBoy.Height),
                1f, CanvasImageInterpolation.NearestNeighbor);
        }

        /// <summary>Combined state of every connected controller.</summary>
        private static int ReadControllers(out bool fastForward)
        {
            int mask = 0;
            fastForward = false;
            foreach (var pad in Gamepad.Gamepads)
            {
                var r = pad.GetCurrentReading();
                var b = r.Buttons;
                if ((b & GamepadButtons.A) != 0) mask |= GameBoy.ButtonA;
                if ((b & GamepadButtons.B) != 0) mask |= GameBoy.ButtonB;
                if ((b & GamepadButtons.Menu) != 0) mask |= GameBoy.ButtonStart;
                if ((b & GamepadButtons.View) != 0) mask |= GameBoy.ButtonSelect;
                if ((b & GamepadButtons.DPadUp) != 0 || r.LeftThumbstickY > StickThreshold) mask |= GameBoy.ButtonUp;
                if ((b & GamepadButtons.DPadDown) != 0 || r.LeftThumbstickY < -StickThreshold) mask |= GameBoy.ButtonDown;
                if ((b & GamepadButtons.DPadLeft) != 0 || r.LeftThumbstickX < -StickThreshold) mask |= GameBoy.ButtonLeft;
                if ((b & GamepadButtons.DPadRight) != 0 || r.LeftThumbstickX > StickThreshold) mask |= GameBoy.ButtonRight;
                if ((b & GamepadButtons.RightShoulder) != 0 || r.RightTrigger > 0.5) fastForward = true;
            }
            return mask;
        }

        // ---- keyboard (PC) ----

        private static int MapKey(VirtualKey key)
        {
            switch (key)
            {
                case VirtualKey.Up: return GameBoy.ButtonUp;
                case VirtualKey.Down: return GameBoy.ButtonDown;
                case VirtualKey.Left: return GameBoy.ButtonLeft;
                case VirtualKey.Right: return GameBoy.ButtonRight;
                case VirtualKey.X: return GameBoy.ButtonA;
                case VirtualKey.Z: return GameBoy.ButtonB;
                case VirtualKey.Enter: return GameBoy.ButtonStart;
                case VirtualKey.Back: return GameBoy.ButtonSelect;
                default: return 0;
            }
        }

        private void OnKeyDown(CoreWindow sender, KeyEventArgs e)
        {
            if (e.VirtualKey == VirtualKey.Space) { keyboardFastForward = true; e.Handled = true; return; }
            int b = MapKey(e.VirtualKey);
            if (b != 0) { keyboardButtons |= b; e.Handled = true; }
        }

        private void OnKeyUp(CoreWindow sender, KeyEventArgs e)
        {
            if (e.VirtualKey == VirtualKey.Space) { keyboardFastForward = false; e.Handled = true; return; }
            int b = MapKey(e.VirtualKey);
            if (b != 0) { keyboardButtons &= ~b; e.Handled = true; }
        }

        // ---- saves ----

        /// <summary>Write the battery save immediately (called when the app is suspended).</summary>
        public Task SaveNowAsync()
        {
            var g = gb;
            if (g == null || !g.Cartridge.HasBattery) return Task.CompletedTask;
            return WriteSaveAsync((byte[])g.Cartridge.GetRam().Clone());
        }

        private async Task WriteSaveAsync(byte[] data)
        {
            if (saveName == null) return;
            await saveLock.WaitAsync();
            try
            {
                var file = await ApplicationData.Current.LocalFolder.CreateFileAsync(
                    saveName, CreationCollisionOption.ReplaceExisting);
                await FileIO.WriteBytesAsync(file, data);
            }
            catch (Exception)
            {
                // Keep playing; the next check will try again.
            }
            finally
            {
                saveLock.Release();
            }
        }

        // ---- UI ----

        private void ShowMessage(string text, bool autoHide)
        {
            Message.Text = text;
            MessageBox.Visibility = Visibility.Visible;
            if (autoHide)
            {
                var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
                timer.Tick += (s, e) =>
                {
                    MessageBox.Visibility = Visibility.Collapsed;
                    timer.Stop();
                };
                timer.Start();
            }
        }
    }
}
