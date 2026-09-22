package com.thiepn.scan.data

object PageRotation {
    fun normalize(degrees: Int): Int =
        ((degrees % 360) + 360) % 360

    fun clockwise(degrees: Int): Int =
        normalize(degrees + 90)
}
