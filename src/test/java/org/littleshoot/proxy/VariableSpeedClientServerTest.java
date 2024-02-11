package org.littleshoot.proxy;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;

/**
 * Tests cases where either the client or the server is slower than the other.
 * 
 * Ignored because this doesn't quite trigger OOME for some reason. It also
 * takes too long to include in normal tests.
 */
@Disabled
public final class VariableSpeedClientServerTest {
    private static final Logger log = LoggerFactory.getLogger(VariableSpeedClientServerTest.class);

    private static final int PORT = TestUtils.randomPort();
    private static final int PROXY_PORT = TestUtils.randomPort();
    private static final int CONTENT_LENGTH = 1000000000;

    @Test
    public void testServerFaster() throws Exception {
        doTest(PORT, PROXY_PORT, false);
    }

    @Test
    public void testServerSlower() throws Exception {
        doTest(PORT, PROXY_PORT, true);
    }

    private void doTest(int port, int proxyPort, boolean slowServer) throws Exception {
        startServer(port, slowServer);
        Thread.yield();
        DefaultHttpProxyServer.bootstrap().withPort(proxyPort).start();
        Thread.yield();
        Thread.sleep(400);
        try (CloseableHttpClient client = createProxiedHttpClient(proxyPort)) {

            log.info("------------------ Memory Usage At Beginning ------------------");
            TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();

            final HttpPost post = createHttpPost("http://127.0.0.1:" + port + "/");
            final HttpResponse response = client.execute(post);

            final HttpEntity entity = response.getEntity();
            final long cl = entity.getContentLength();
          assertThat(cl).isEqualTo(CONTENT_LENGTH);

            int bytesRead = 0;
            try (InputStream content = slowServer ? new ThrottledInputStream(entity.getContent(), 10 * 1000) : entity.getContent()) {
                final byte[] input = new byte[100000];
                int read = content.read(input);

                while (read != -1) {
                    bytesRead += read;
                    read = content.read(input);
                }
            }
          assertThat(bytesRead).isEqualTo(CONTENT_LENGTH);
            // final String body = IOUtils.toString(entity.getContent());
            EntityUtils.consume(entity);
            log.info("------------------ Memory Usage At Beginning ------------------");
            TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();
        }
    }

    private static HttpPost createHttpPost(String endpoint) {
        final HttpPost post = new HttpPost(endpoint);
        post.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
        post.setEntity(new InputStreamEntity(new InputStream() {
            private int remaining = CONTENT_LENGTH;

            @Override
            public int read() {
                if (remaining > 0) {
                    remaining -= 1;
                    return 77;
                }
                else {
                    return 0;
                }
            }

            @Override
            public int available() {
                return remaining;
            }
        }, CONTENT_LENGTH));
        return post;
    }

    private void startServer(final int port, final boolean slowReader) {
        final Thread t = new Thread(() -> {
            try {
                startServerOnThread(port, slowReader);
            } catch (IOException e) {
                log.error("Failed to start server on port {} (slowReader: {})", port, slowReader, e);
            }
        }, "Test-Server-Thread");
        t.setDaemon(true);
        t.start();
    }

    private void startServerOnThread(int port, boolean slowReader) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            server.setSoTimeout(100000);
            final Socket sock = server.accept();
            InputStream is = sock.getInputStream();
            if (slowReader) {
                is = new ThrottledInputStream(is, 10 * 1000);
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            while (br.read() != 0) {
            }
            try (final OutputStream os = sock.getOutputStream()) {
                final String responseHeaders =
                  "HTTP/1.1 200 OK\r\n" +
                  "Date: Sun, 20 Jan 2013 00:16:23 GMT\r\n" +
                  "Expires: -1\r\n" +
                  "Cache-Control: private, max-age=0\r\n" +
                  "Content-Type: text/html; charset=ISO-8859-1\r\n" +
                  "Server: gws\r\n" +
                  "Content-Length: " + CONTENT_LENGTH + "\r\n\r\n"; // ~10 gigs

                os.write(responseHeaders.getBytes(UTF_8));

                int bufferSize = 100000;
                final byte[] bytes = new byte[bufferSize];
                Arrays.fill(bytes, (byte) 77);
                int remainingBytes = CONTENT_LENGTH;

                while (remainingBytes > 0) {
                    int numberOfBytesToWrite = Math.min(remainingBytes, bufferSize);
                    os.write(bytes, 0, numberOfBytesToWrite);
                    remainingBytes -= numberOfBytesToWrite;
                }
            }
        }
    }
}
