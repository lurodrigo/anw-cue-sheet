# anw-cue-sheet

A small application I made for my girlfriend to automate generating
cue sheets from EDL files. She often uses Audio Network, so she had the laborious task of 
browsing Audio Network to find out info about the tracks to fill the spreasheets.

This app takes one, many, or a entire dir of .edl files, searches for files whose name
begins with ANW, crawls ANW search to find ISBN, Author etc for each of them. It
stores all info in a durable atom (thanks [Enduro](https://github.com/alandipert/enduro)),
so tipically a track will only be crawled for once. 

## Usage

This app can be used from the REPL. The main functions are `cue-from-file`, `cue-from-files` and
`cue-from-dir`. They take a file path, many paths, or a dir path, respectively, and a xlsx template
to be filled. You can specify the columns and starting row. 

There's also a GUI, written with [cljfx](https://github.com/cljfx/cljfx), that's run as the main entrypoint.

## Libraries used

* [cljfx](https://github.com/cljfx/cljfx)
* [Diehard](https://github.com/sunng87/diehard)
* [Docjure](https://github.com/mjul/docjure)
* [Enduro](https://github.com/alandipert/enduro)
* [Etaoin](https://github.com/igrishaev/etaoin)
* [fs](https://github.com/clj-commons/fs)
* [Timbre](https://github.com/ptaoussanis/timbre)

## License

Distributed under the MIT License.

