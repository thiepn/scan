# Scan keeps domain data explicit and relies on generated Room code.
# ML Kit and AndroidX ship consumer rules for their reflective internals.


# PdfBox-Android's JPEG-2000 adapter is optional. Scan does not decode or encode
# JPX streams in its searchable-PDF generation path.
-dontwarn com.gemalto.jp2.**


# PdfBox-Android declares JPEG-2000 support as an optional compileOnly dependency.
# Scan does not decode/encode JPX in its generated searchable-PDF path.
-dontwarn com.gemalto.jp2.**
