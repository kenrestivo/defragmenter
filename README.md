# defragment

Tool for defragmenting ogg files saved as YY-MM-DD-show_name.ogg

## Why??

Liquidsoap/Airtime saving shows in ogg format timestamped with YY-MM-DD. If the DJ's connection goes out, the shows get chopped up into several files. 

The shows are saved with YY-MM-DD-show_name.ogg, so this processes those filenames, combines them based on date and show name, saves them to an output directory, and moves the original files to a backup dir.

It's usually run from a cron job in the middle of the night.

## Requirements
* vorbis-tools (ogg123, oggenc) version 1.4.0-6ubuntu1 or later, with this patch:
https://bugzilla.redhat.com/show_bug.cgi?id=1185558
or this one:
https://git.xiph.org/?p=vorbis-tools.git;a=commit;h=514116d7bea89dad9f1deb7617b2277b5e9115cd

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

That said, this is some truly awful code. It really needs to be redone or at least refactored.

## License

Copyright © 2014-2019 ken restivo <ken@restivo.org>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
