using Windows.ApplicationModel;
using Windows.ApplicationModel.Activation;
using Windows.UI.ViewManagement;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace PokeyellowXbox
{
    sealed partial class App : Application
    {
        public App()
        {
            InitializeComponent();
            // Xbox: no mouse-style cursor; the game reads the controller directly.
            RequiresPointerMode = ApplicationRequiresPointerMode.WhenRequested;
            Suspending += OnSuspending;
        }

        protected override void OnLaunched(LaunchActivatedEventArgs e)
        {
            // Use the whole TV picture instead of the default safe-area margins.
            ApplicationView.GetForCurrentView().SetDesiredBoundsMode(ApplicationViewBoundsMode.UseCoreWindow);

            var frame = Window.Current.Content as Frame;
            if (frame == null)
            {
                frame = new Frame();
                Window.Current.Content = frame;
            }
            if (frame.Content == null) frame.Navigate(typeof(MainPage), e.Arguments);
            Window.Current.Activate();
        }

        private async void OnSuspending(object sender, SuspendingEventArgs e)
        {
            var deferral = e.SuspendingOperation.GetDeferral();
            var frame = Window.Current.Content as Frame;
            var page = frame != null ? frame.Content as MainPage : null;
            if (page != null) await page.SaveNowAsync();
            deferral.Complete();
        }
    }
}
