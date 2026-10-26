package com.hoshino.wenku8reader.data.local.webdav

import java.io.File
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    @Test
    fun `test upload list download and delete use standard DAV methods and auth`() {
        val server = MockWebServer()
        val bindAddress = InetAddress.getByName("127.0.0.1")
        val serverHostName = bindAddress.canonicalHostName
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(serverHostName)
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val uploadedName = AtomicReference<String>()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "PROPFIND" && request.requestUrl?.encodedPath == "/dav/root" ->
                    MockResponse().setResponseCode(207).setBody(collectionResponse("/dav/root/"))

                request.method == "MKCOL" -> MockResponse().setResponseCode(201)

                request.method == "PUT" -> {
                    uploadedName.set(request.requestUrl?.pathSegments?.last())
                    MockResponse().setResponseCode(201)
                }

                request.method == "PROPFIND" && request.requestUrl?.encodedPath == "/dav/root/Root/backups" -> {
                    val name = uploadedName.get() ?: error("PUT was not received before listing")
                    MockResponse().setResponseCode(207).setBody(
                        """
                            <?xml version="1.0" encoding="utf-8"?>
                            <d:multistatus xmlns:d="DAV:">
                              <d:response><d:href>/dav/root/Root/backups/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                              <d:response><d:href>/dav/root/Root/backups/$name</d:href><d:propstat><d:prop><d:getcontentlength>5</d:getcontentlength></d:prop></d:propstat></d:response>
                            </d:multistatus>
                        """.trimIndent(),
                    )
                }

                request.method == "GET" -> MockResponse().setBody("backup")
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(405)
            }
        }
        server.start(bindAddress, 0)
        val testFile = File.createTempFile("webdav-client-test-", ".json")
        try {
            testFile.writeText("backup", StandardCharsets.UTF_8)
            val destination = File.createTempFile("webdav-client-downloaded-", ".json")
            destination.delete()
            val credentials = WebDavCredentials(
                serverUrl = server.url("/dav/root").toString().trimEnd('/'),
                remoteDirectory = "Root/backups",
                username = "webdav-user",
                password = "webdav-password",
            )
            val client = WebDavClient(
                credentials,
                OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .build(),
            )

            client.testConnection()
            val uploaded = client.uploadSnapshot(testFile)
            val snapshots = client.listSnapshots()
            assertEquals(1, snapshots.size)
            assertEquals(uploaded.fileName, snapshots.single().fileName)
            assertEquals(5L, snapshots.single().sizeBytes)
            client.downloadSnapshot(snapshots.single(), destination)
            assertEquals("backup", destination.readText(StandardCharsets.UTF_8))
            client.deleteSnapshot(uploaded.fileName)

            val requests = List(7) { server.takeRequest() }
            assertEquals(listOf("PROPFIND", "MKCOL", "MKCOL", "PUT", "PROPFIND", "GET", "DELETE"), requests.map { it.method })
            assertTrue(requests.all { it.getHeader("Authorization") == Credentials.basic("webdav-user", "webdav-password") })
            assertEquals("1", requests[4].getHeader("Depth"))
            destination.delete()
        } finally {
            testFile.delete()
            server.shutdown()
        }
    }

    @Test
    fun `snapshot cleanup rejects unrelated remote files`() {
        assertEquals(null, WebDavClient.parseSnapshotTimestamp("README.md"))
        assertEquals(null, WebDavClient.parseSnapshotTimestamp("wenku8reader-backup-../other.json"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects cleartext WebDAV URL even when credentials are otherwise valid`() {
        WebDavClient(
            WebDavCredentials(
                serverUrl = "http://dav.example.test",
                remoteDirectory = "backups",
                username = "user",
                password = "password",
            ),
        )
    }

    private fun collectionResponse(href: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>"""
}
