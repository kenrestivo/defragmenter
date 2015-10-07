#!/usr/bin/python

import mutagen
import sys

# in production:
# /usr/lib/airtime/airtime_virtualenv/bin/python

file_info = mutagen.File(sys.argv[1], easy=True)
print getattr(file_info.info, "length", 0)

