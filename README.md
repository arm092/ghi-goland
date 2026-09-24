# Ghi for GoLand

GoLand support for the [Ghi programming language](https://github.com/arm092/ghi). The plugin recognizes `.ghi` files and provides syntax highlighting, navigation, completion, parameter hints, rename support, formatting, compiler diagnostics, build and run actions, and debugging with GoLand's bundled Delve.

## Requirements

- GoLand 2026.2.x (platform build 262) with its bundled Go plugin.
- The [Ghi compiler](https://github.com/arm092/ghi/releases) for build, run, formatting, diagnostics, and debugging actions.

## Install

The [Ghi listing on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34508-ghi) was submitted on September 25, 2026 and is awaiting moderation. It is not yet available for installation from Marketplace. After approval, open **Settings → Plugins → Marketplace**, search for **Ghi**, and install it.

Until then, download the [v0.1.0 ZIP](https://github.com/arm092/ghi-goland/releases/download/v0.1.0/ghi-goland-0.1.0.zip) and use **Settings → Plugins → Install Plugin from Disk**. Configure the compiler executable and project directory under **Settings → Languages & Frameworks → Ghi**.

## Build and test

The repository includes a Gradle wrapper. Use a Java 25 runtime and run:

```sh
./gradlew test buildPlugin verifyPlugin
```

On Windows, use `gradlew.bat`. You can set `-PlocalIde=/path/to/GoLand` to build and verify against an installed IDE without downloading one. Full integration tests use `GHI_TEST_COMPILER` for the compiler executable, `GHI_TEST_GO_ROOT` for the Go installation, and `GOMODCACHE` for the Go module cache.

The installable ZIP is written to `build/distributions/`.

## License

MIT. See [LICENSE](LICENSE). The plugin archive also includes the license notice.
