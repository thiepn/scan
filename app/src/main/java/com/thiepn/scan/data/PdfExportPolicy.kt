package com.thiepn.scan.data

object PdfExportPolicy {
    fun requiresRasterization(page: PageEntity): Boolean =
        !CropQuadCodec.decode(page.cropQuad).isFullFrame() ||
            !PageVisualRecipeCodec.decode(page.visualRecipe).isOriginal() ||
            !PageCleanupRecipeCodec.decode(page.cleanupRecipe).isEmpty() ||
            !PageTextEditRecipeCodec.decode(page.textEditRecipe).isEmpty() ||
            !PageMarkupRecipeCodec.decode(page.markupRecipe).isEmpty()
}
