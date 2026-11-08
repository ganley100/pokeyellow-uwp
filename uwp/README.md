# Pokémon Yellow for Xbox (UWP)

A UWP app for Xbox One / Series consoles in **Developer Mode** (and Windows
PCs) that runs the `pokeyellow.gbc` built from this repository.

- `GameBoyCore/` – the Game Boy Color emulator in C# (a port of the Android
  core in `android/core`). It's shared as source.
- `GameBoyCore.Tests/` – xUnit tests for the core; they run on any OS with `dotnet test`.
- `PokeyellowXbox/` – the UWP app: Win2D for crisp integer-scaled video,
  AudioGraph for sound, `Windows.Gaming.Input` for controllers.

## Getting the package

**From CI:** the *Xbox UWP package* workflow (`.github/workflows/xbox-uwp.yml`)
builds the ROM, runs the tests, then builds and signs an x64 package on Windows.
Download the `pokeyellow-xbox-package` artifact. It contains:

- `PokeyellowXbox_1.0.0.0_x64_Test/PokeyellowXbox_1.0.0.0_x64.msix` – the game
- `.../Dependencies/x64/*.appx` – framework packages (VCLibs, .NET Native)
- `PokeyellowXbox.cer` – the throwaway certificate it was signed with

**Locally (Windows):** run `make` in the repository root, then open
`uwp/PokeyellowXbox.sln` in Visual Studio 2022 with the *Universal Windows
Platform development* workload. Select **Release | x64** and build. Use
*Project → Publish → Create App Packages* for a sideload package, or deploy
straight to an Xbox in Dev Mode as a *Remote Machine*.

## Installing on Xbox

1. Put the console in Developer Mode: install the *Xbox Dev Mode* app from the
   Store, register as a Microsoft developer (one-time fee), and follow the
   activation steps.
2. In Dev Home, turn on **Remote Access** and note the Device Portal address
   (for example `https://192.168.1.50:11443`).
3. Open that address in a PC browser and go to *Home → Add*. Choose the
   `.msix`, tick *Allow me to select optional packages*, and add every `.appx`
   from `Dependencies/x64`. Then install.
4. In Dev Home, select the app, press the View/Menu button → *View details*
   and set **App type** to **Game**. That gives it game-level resources.
5. Launch **Pokémon Yellow** from Dev Home.

## Controls

| Controller             | Game Boy |
|------------------------|----------|
| D-pad / left stick     | D-pad    |
| A / B                  | A / B    |
| Menu (≡)               | START    |
| View (⧉)               | SELECT   |
| RT or RB (hold)        | Fast-forward |

On a PC keyboard: arrows, X = A, Z = B, Enter = START, Backspace = SELECT,
hold Space to fast-forward.

## Saving

The in-game *SAVE* writes cartridge RAM to the app's `LocalState` folder. It's
written every couple of seconds and when the app is suspended. If no ROM was
bundled, the app also looks for `pokeyellow.gbc` in that folder.
