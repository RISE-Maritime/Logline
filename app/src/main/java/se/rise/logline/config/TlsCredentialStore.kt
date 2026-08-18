package se.rise.logline.config

import android.content.Context
import android.net.Uri
import android.util.Log
import se.rise.logline.keelson.TlsPaths
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "TlsCredentialStore"

/** The three PEM files an mTLS connection to the cloud router needs. */
enum class TlsCredential(val fileName: String, val label: String) {
    ROOT_CA("root_ca.pem", "Root CA certificate"),
    CLIENT_CERTIFICATE("client_cert.pem", "Client certificate"),
    CLIENT_KEY("client_key.pem", "Client key"),
}

/** What the UI shows for one credential: whether it is there, and enough to tell *which* one. */
data class TlsCredentialState(
    val credential: TlsCredential,
    val present: Boolean,
    val summary: String? = null,
)

/**
 * Imported TLS material, kept in app-private storage.
 *
 * Deliberately not bundled in the APK: this key authenticates to the shared fleet bus, and a debug
 * build gets passed around. `filesDir` is the security boundary — never `getExternalFilesDir`, which
 * any app with storage access can read.
 */
class TlsCredentialStore(private val appContext: Context) {

    private val dir: File get() = File(appContext.filesDir, "tls").apply { mkdirs() }

    fun file(credential: TlsCredential): File = File(dir, credential.fileName)

    fun paths(): TlsPaths = TlsPaths(
        rootCa = pathIfPresent(TlsCredential.ROOT_CA),
        clientCertificate = pathIfPresent(TlsCredential.CLIENT_CERTIFICATE),
        clientKey = pathIfPresent(TlsCredential.CLIENT_KEY),
    )

    /** Copies a picked document into app-private storage. */
    fun import(credential: TlsCredential, uri: Uri) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            file(credential).outputStream().use { input.copyTo(it) }
        } ?: error("Could not read ${credential.label} from $uri")
    }

    fun clear(credential: TlsCredential) {
        file(credential).delete()
    }

    fun state(credential: TlsCredential): TlsCredentialState {
        val file = file(credential)
        if (!file.exists() || file.length() == 0L) {
            return TlsCredentialState(credential, present = false)
        }
        return TlsCredentialState(credential, present = true, summary = summarise(credential, file))
    }

    private fun pathIfPresent(credential: TlsCredential): String? =
        file(credential).takeIf { it.exists() && it.length() > 0L }?.absolutePath

    /**
     * Subject and expiry for a certificate — an expired client certificate otherwise fails as an
     * opaque handshake error, and this is the only place it can be seen before that happens.
     *
     * A private key is not parsed: there is nothing safe or useful to show, so it reports size only.
     */
    private fun summarise(credential: TlsCredential, file: File): String? {
        if (credential == TlsCredential.CLIENT_KEY) return "${file.length()} bytes"
        return try {
            val certificate = file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            val expires = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(certificate.notAfter)
            val subject = certificate.subjectX500Principal.name
            val expired = if (certificate.notAfter.before(java.util.Date())) " — EXPIRED" else ""
            "$subject, expires $expires$expired"
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse ${credential.fileName}", t)
            "unreadable — is this a PEM certificate?"
        }
    }
}
