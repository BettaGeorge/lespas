package site.leos.apps.lespas.search

import android.graphics.Rect

data class Classification (
    val photoId: String,        // Photo ID in photo table
    //val albumId: String,        // Photo album ID in album table
    //val date: LocalDateTime,    // Photo taken date
    val type: Int,              // Face or object
    val classId: String,        // For object, it's object's id; for face, it's face's id
    val similarity: Float,      // For object, the higher the more similar; for face, the lower the more similar
    val location: Rect,         // Face or object location in photo
) {
    companion object {
        const val TABLE_NAME = "classifications"

        const val TYPE_FACE = 0
        const val TYPE_OBJECT = 1
    }
}