package com.vba.robotscan

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Les photos prises pour une page web (un champ photo, input type="file" avec capture) : l'appareil photo écrit dans
 * cache/photos/photo-<instant>.jpg par l'adresse content://<paquet>.photos/photo-<instant>.jpg, puis la WebView lit la
 * photo par la même adresse. C'est ce que fait le FileProvider d'AndroidX, en quelques lignes : RobotScan n'a aucune
 * dépendance. Non exporté : seul l'appareil photo, à qui MainActivity donne la permission pour cette photo, y écrit.
 */
class PhotoProvider : ContentProvider() {

    private fun fichier(uri: Uri): File {
        val nom = uri.lastPathSegment ?: throw FileNotFoundException("photo sans nom")
        if (!Regex("^photo-\\d+\\.jpg$").matches(nom)) throw FileNotFoundException("photo inconnue")
        val ctx = context ?: throw FileNotFoundException("pas de contexte")
        return File(File(ctx.cacheDir, "photos"), nom)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fichier(uri)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    // La WebView demande le nom et la taille du fichier choisi
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fichier(uri)
        val colonnes: Array<String> = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val curseur = MatrixCursor(colonnes)
        val ligne = arrayOfNulls<Any>(colonnes.size)
        colonnes.forEachIndexed { i, c ->
            ligne[i] = when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        curseur.addRow(ligne)
        return curseur
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
