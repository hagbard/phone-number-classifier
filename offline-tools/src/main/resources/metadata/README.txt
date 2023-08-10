Any csv files in this directory, which match the location and schema for csv files in metadata.zip
will be loaded as resources in preference to the files in metadata.zip.

E.g. if this directory contains "1/formats.csv", then that will override the formats for NANPA
ranges.

Obviously if csv contents depend on each other it is necessary to keep them synchronised (e.g. if
format IDs change).

This does NOT require setting the "overlay directory" (via --dir) and should only be used for
patching the data in fairly permanent ways.

Local modifications:
----
metadata.csv:
  * 241 (GA/Gabon) is listed as having a national prefix (https://issuetracker.google.com/issues/294760512).
  * 52 (MX/Mexico) remove all national prefixes ().

241/ (GA/Gabon):
----
ranges.csv:
  * Remove 0 prefixed 8-digit ranges which were just a copy of the 7 digit ranges with national prefix.
  * Remove 7-digit ranges which have been migrated away from.
formats.csv
  * Remove all but the 2/2/2/2 format and remove comments/rename IDs.

52/ (MX/Mexico):
----
ranges.csv
  * Remove 11-digit mobile numbers completely.
  * Change format ID names (no longer only "fixed").
formats.csv
  * Remove 11-digit formats and fix comments/IDs.
examples.csv
  * Remove 11-digit example number.


