Any csv files in this directory, which match the location and schema for csv files in metadata.zip
will be loaded as resources in preference to the files in metadata.zip.

E.g. if this directory contains "1/formats.csv", then that will override the formats for NANPA
ranges.

Obviously if csv contents depend on each other it is necessary to keep them synchronised (e.g. if
format IDs change).

This does NOT require setting the "overlay directory" (via --dir) and should only be used for
patching the data in fairly permanent ways.