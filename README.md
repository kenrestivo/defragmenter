# defragment

Tool for defragmenting ogg files saved as YY-MM-DD-show_name.ogg

## Why??

Liquidsoap/Airtime saving shows in ogg format timestamped with YY-MM-DD. If the DJ's connection goes out, the shows get chopped up into several files. 

The shows are saved with YY-MM-DD-show_name.ogg, so this processes those filenames, combines them based on date and show name, saves them to an output directory, and moves the original files to a backup dir.

It's usually run from a cron job in the middle of the night.

## Requirements
* vorbis-tools (ogg123, oggenc)

## Installation

	lein bin

Create the installation file.

## Usage

  ./defragment config.edn

## Options

TODO: document the config file options

```clojure

{:in-oggs-path "/some/temp/borkenoggs"
 :out-oggs-path "/some/temp/tmp/"
 :cmd-path "/usr/local/bin/thrashcat"
 :backup-dir "/home/backups"
 :out-commands-file "/some/temp/get-me"}
```

## Examples

...

### Bugs

Actually, none at the moment, I'm sure some will crop up at some point.



## License

Copyright © 2014 ken restivo <ken@restivo.org>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
