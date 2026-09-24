# Ghi for GoLand

GoLand support for the [Ghi programming language](https://github.com/arm092/ghi). The plugin recognizes `.ghi` files and provides syntax highlighting, navigation, completion, parameter hints, rename support, formatting, compiler diagnostics, build and run actions, and debugging with GoLand's bundled Delve.

## Requirements

- GoLand 2025.1 or newer (platform build 251+) with its bundled Go plugin.
- [Ghi compiler v0.2.2 or newer](https://github.com/arm092/ghi/releases) for build, run, formatting, diagnostics, and debugging actions.

GoLand 2025.1 bundles a Delve version whose Go version check rejects the Go 1.26 binaries required by Ghi. The plugin disables that check for its debug action; debugging was tested on GoLand 2025.1 and 2026.2.

## Install

The [Ghi listing on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34508-ghi) was submitted on September 25, 2026 and is awaiting moderation. It is not yet available for installation from Marketplace. After approval, open **Settings → Plugins → Marketplace**, search for **Ghi**, and install it.

Until then, download the [v0.1.2 ZIP](https://github.com/arm092/ghi-goland/releases/download/v0.1.2/ghi-goland-0.1.2.zip) and use **Settings → Plugins → Install Plugin from Disk**. Configure the compiler executable and project directory under **Settings → Languages & Frameworks → Ghi**.

## Editor diagnostics and completion

Saved files use the compiler's project check. For an unsaved buffer, the plugin sends the editor text to `ghi check --stdin --filename` and leaves the file on disk untouched. The buffer must belong to an existing production `.ghi` file in the configured project; new files without a disk path and excluded test files are outside this mode. Diagnostics are discarded if the buffer changes while the check runs.

Member completion follows explicitly declared types and simple constructor, function call, field, method call, and local initializer chains. It does not infer types for compound expressions or arbitrary control flow.

Plain `enum Direction { North, South, }` cases have the distinct `Direction` type. Explicit `string`, `int`, or `bool` backed enum cases use that backing type. The plugin highlights enum declarations and cases and supports case navigation and completion; the compiler checks invalid declarations and assignments to immutable cases.

## Build and test

The repository includes a Gradle wrapper. Use a Java 21 runtime and run:

```sh
./gradlew test buildPlugin verifyPlugin
```

On Windows, use `gradlew.bat`. You can set `-PlocalIde=/path/to/GoLand` to build and verify against an installed IDE without downloading one. Full integration tests use `GHI_TEST_COMPILER` for the compiler executable, `GHI_TEST_GO_ROOT` for the Go installation, and `GOMODCACHE` for the Go module cache.

The installable ZIP is written to `build/distributions/`.

## License

MIT. See [LICENSE](LICENSE). The plugin archive also includes the license notice.
