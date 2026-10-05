# Ghi for GoLand

GoLand support for the [Ghi programming language](https://github.com/arm092/ghi). The plugin recognizes `.ghi` files and provides syntax highlighting, navigation, completion, parameter hints, rename support, formatting, compiler diagnostics, build and run actions, and debugging with GoLand's bundled Delve.

## Requirements

- GoLand 2025.1 or newer (platform build 251+) with its bundled Go plugin.
- [Ghi compiler v0.2.2 or newer](https://github.com/arm092/ghi/releases) for build, run, formatting, diagnostics, and debugging actions.

GoLand 2025.1 bundles a Delve version whose Go version check rejects the Go 1.26 binaries required by Ghi. The plugin disables that check for its debug action; debugging was tested on GoLand 2025.1 and 2026.2.

## Install

The plugin has a [JetBrains Marketplace listing](https://plugins.jetbrains.com/plugin/34508-ghi). Versions become available there after Marketplace review.

To install directly, download the [v0.1.8 ZIP](https://github.com/arm092/ghi-goland/releases/download/v0.1.8/ghi-goland-0.1.8.zip) and use **Settings → Plugins → Install Plugin from Disk**. Configure the compiler executable and project directory under **Settings → Languages & Frameworks → Ghi**.

## Editor diagnostics and completion

Saved files use the compiler's project check. For an unsaved buffer, the plugin sends the editor text to `ghi check --stdin --filename` and leaves the file on disk untouched. The buffer must belong to an existing production `.ghi` file in the configured project; new files without a disk path and excluded test files are outside this mode. Diagnostics are discarded if the buffer changes while the check runs.

Editor error messages retain indented continuation lines such as `have` and `want` argument lists. Console links support both `file:line` and `file:line:column` locations, including Windows paths with spaces. The plugin converts the compiler's UTF-8 byte columns to editor positions, so diagnostics and console links point to the right place after Unicode text. Source coordinates and names are supplied by the configured compiler.

Plugin v0.1.4 handles the richer CLI diagnostics introduced in Ghi v0.2.6. Saved-file editor messages omit source excerpts and carets while retaining type mismatch explanations. The console displays the full compiler output. Unsaved-buffer checks use the compiler's plain overlay output.

With Ghi v0.2.10+, ternary expressions receive compiler diagnostics in the editor. For an unparenthesized nested ternary, the plugin also shows a parentheses hint in unsaved buffers; saved-file checks retain the compiler's richer hint. The IDE does not choose which branch to parenthesize because that would change the expression's meaning.

Member completion follows explicitly declared types and simple constructor, function call, field, method call, and local initializer chains. It does not infer types for compound expressions or arbitrary control flow.

### Expression type hover

The unreleased development checkout adds Quick Documentation for checked expression types with a Ghi compiler supporting `analyze --json --types` (introduced in 0.2.12). The linked v0.1.8 ZIP does not include this feature. Enable **Show quick documentation on hover** in GoLand to see types on mouse hover. The type comes from the compiler's original-source byte ranges; the plugin maps them to the current editor text. It shows the narrowest checked expression under the cursor and leaves unsupported or ambiguous expressions without a type.

Type analysis runs in the background with a timeout and requires the configured project's Go toolchain and dependencies. An unsaved edit to the current existing production `.ghi` file is sent in memory without modifying the disk file. If another file in the project is unsaved, type hover is suppressed because the compiler would read an older version of that file from disk. Failed or outdated analysis does not produce a hover result. The type index is conservative: declarations, rewritten constructs and some first-line expressions may have no entry.

Plain `enum Direction { North, South, }` cases have the distinct `Direction` type. Explicit `string`, `int`, or `bool` backed enum cases use that backing type. The plugin highlights enum declarations and cases and supports case navigation and completion; the compiler checks invalid declarations and assignments to immutable cases.

## Debugging

Use **Debug Project** to build with Ghi source metadata and start GoLand's bundled Delve. Line breakpoints, stepping, receiver fields, inherited exception fields and caught exceptions are supported. Variables shadowed by the active binding are omitted, so repeated catch names show the current exception. Breakpoint conditions and expression evaluation are not supported yet; exceptions can be inspected at line breakpoints in throw and catch blocks.

The Request Journal integration check uses the actual HTTP service, a temporary SQLite database, GoLand's XDebugSession and real Delve. Verified with Ghi 0.2.6 on GoLand 2025.1.7.2 and 2026.2.3, it checks service arguments and receiver fields, step over, InvalidInput's inherited message/code and violations, NotFound's inherited message/code and stackTrace, and HTTP responses 201/422/404. It runs through the IntelliJ test framework; it does not verify the native IDE window layout or mouse interactions.

## Test coverage

With **Ghi 0.2.7 or newer**, use **Tools → Ghi → Test with Coverage**. The IDE saves open documents, runs the configured project's tests, and opens **Ghi Coverage** with covered/total statements and per-file percentages. Double-click a file to open it; **Clear coverage** removes the results and editor markers.

Green boxes mark executed statement starts; red boxes mark missed statement starts. Gutter bars are green for fully executed lines, red for missed lines and yellow for lines containing both. Several statements on one line count separately. This is statement coverage, not branch coverage; files without executable statements show `n/a`. Tests, runtime and dependency sources are excluded.

Results belong to the current IDE session. A new run or a source edit clears them. Failed, cancelled, superseded or source-changing runs do not publish results from an earlier profile. The IDE action runs once; use the compiler CLI for `--watch`.

Integration checks exercise the real compiler, successful/failed/cancelled runs, UTF-8 coordinates, per-file counts and editor markers through the IntelliJ test framework. They do not verify native window layout or mouse interactions.

## Race detection and benchmarks

With **Ghi 0.2.8 or newer**, use **Tools → Ghi → Test with Race Detection** to run `ghi test --race`, or **Tools → Ghi → Run Benchmarks** for native Go benchmark output. Configure the benchmark name regex, duration or iteration count (for example `1s` or `100x`), positive repetition count and optional allocation reporting in **Settings → Languages & Frameworks → Ghi**. The benchmark command passes `--bench`, `--benchtime`, `--count` and, when enabled, `--benchmem`. Each command opens a stoppable IDE console. Invalid settings are rejected before the process starts.

Race detection requires a Go installation with CGO and a supported C compiler. The plugin reports the compiler's error in the console when that toolchain is unavailable. Benchmark and race runs are separate from the coverage action; running them does not publish a coverage profile.

## Build and test

The repository includes a Gradle wrapper. Use a Java 21 runtime and run:

```sh
./gradlew test buildPlugin verifyPlugin
```

On Windows, use `gradlew.bat`. You can set `-PlocalIde=/path/to/GoLand` to build and verify against an installed IDE without downloading one. Full integration tests use `GHI_TEST_COMPILER` for the compiler executable, `GHI_TEST_GO_ROOT` for the Go installation, and `GOMODCACHE` for the Go module cache.

Set `GHI_TEST_REQUEST_JOURNAL` to a Request Journal checkout with its Mojave dependencies installed to enable `testLiveRequestJournalDebugger`. The test copies the consumer into a temporary directory and runs there with free local ports and a separate database.

The installable ZIP is written to `build/distributions/`.

## License

MIT. See [LICENSE](LICENSE). The plugin archive also includes the license notice.
