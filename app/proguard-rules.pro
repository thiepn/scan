# Scan keeps domain data explicit and relies on generated Room code.
# ML Kit and AndroidX ship consumer rules for their reflective internals.

# PdfBox-Android declares JPEG-2000 support as an optional compileOnly
# dependency. Scan does not decode or encode JPX in its PDF paths.
-dontwarn com.gemalto.jp2.**
