# Scan keeps domain data explicit and relies on generated Room code.
# ML Kit and AndroidX ship consumer rules for their reflective internals.


# PdfBox-Android's JPEG-2000 adapter is optional. Scan does not decode or encode
# JPX streams in its searchable-PDF generation path.
-dontwarn com.gemalto.jp2.**
