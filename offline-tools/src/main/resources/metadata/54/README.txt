Due to significant differences in how metadata is processed between Libphonenumber, we cannot use
the formats in the original metadata for Argentina.

National numbers should be formatted with '15' after the area code:
* "(11) 15 1234-5678" - Buenos Aires.
* "(380) 15 123-4567" - La Rioja.
* "(2966) 15 12-3456" - Río Gallegos.

International MOBILE numbers should be formatted with '9' after the country code:
* "+54 9 11 1234-5678" - Buenos Aires.
* "+54 9 380 123-4567" - La Rioja.
* "+54 9 2966 12-3456" - Río Gallegos.

However, because the Argentinian ranges do not cleanly distinguish mobile vs fixed line numbers,
we format all geographic numbers as if they were mobile, based on likely usage.

Reference: https://en.wikipedia.org/wiki/Telephone_numbers_in_Argentina
