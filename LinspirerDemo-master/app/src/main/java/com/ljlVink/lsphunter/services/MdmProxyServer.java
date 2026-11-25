package com.ljlVink.lsphunter.services;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local HTTP CONNECT proxy advertised by {@link vpnService}.
 *
 * <p>The stock Linspirer HTTP client uses the system ProxySelector. Requests for its command
 * endpoint can therefore be redirected without changing the managed APK. Other destinations are
 * tunneled to their original address so private-server mode does not block normal traffic.</p>
 *
 * <p>The proxy transports TLS without decrypting it. The private endpoint must present a
 * certificate accepted by the device for the original Linspirer hostname.</p>
 */
public final class MdmProxyServer implements Closeable {
    private static final String TAG = "MdmProxyServer";
    private static final String MDM_HOST = "cloud.linspirer.com";
    private static final int MDM_PORT = 883;
    private static final int HEADER_LIMIT = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final vpnService vpnService;
    private final String privateHost;
    private final int privatePort;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> openSockets = Collections.synchronizedSet(new HashSet<>());

    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public MdmProxyServer(vpnService vpnService, String privateHost, int privatePort) {
        this.vpnService = vpnService;
        this.privateHost = normalizeHost(privateHost);
        this.privatePort = validatePort(privatePort);
        if (this.privateHost.isEmpty()) {
            throw new IllegalArgumentException("自定义 MDM 服务器地址不能为空");
        }
    }

    public synchronized int start() throws IOException {
        if (running) {
            return getPort();
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mdm-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Listening on 127.0.0.1:" + getPort()
                + ", redirecting " + MDM_HOST + ":" + MDM_PORT
                + " to " + privateHost + ":" + privatePort);
        return getPort();
    }

    public synchronized int getPort() {
        return listener == null ? -1 : listener.getLocalPort();
    }

    public String getPrivateEndpoint() {
        return privateHost + ":" + privatePort;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                openSockets.add(client);
                workers.execute(() -> handleClient(client));
            } catch (IOException error) {
                if (running) {
                    Log.w(TAG, "Accept failed", error);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30_000);
            byte[] requestHeader = readHeader(client.getInputStream());
            ProxyRequest request = ProxyRequest.parse(requestHeader);
            Target target = redirect(request.target);

            upstream = new Socket();
            if (!vpnService.protect(upstream)) {
                throw new IOException("Unable to protect upstream socket from VPN loop");
            }
            openSockets.add(upstream);
            upstream.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            upstream.setTcpNoDelay(true);
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);

            if (request.connect) {
                client.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
            } else {
                upstream.getOutputStream().write(requestHeader);
                upstream.getOutputStream().flush();
            }

            Log.d(TAG, request.target + " -> " + target);
            startRelay(client, upstream);
            client = null;
            upstream = null;
        } catch (Throwable error) {
            Log.w(TAG, "Proxy request failed", error);
            sendBadGateway(client);
        } finally {
            closeSocket(client);
            closeSocket(upstream);
        }
    }

    private Target redirect(Target original) {
        if (MDM_HOST.equalsIgnoreCase(original.host) && original.port == MDM_PORT) {
            return new Target(privateHost, privatePort);
        }
        return original;
    }

    private void startRelay(Socket first, Socket second) throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream firstIn = first.getInputStream();
        OutputStream firstOut = first.getOutputStream();
        InputStream secondIn = second.getInputStream();
        OutputStream secondOut = second.getOutputStream();
        workers.execute(() -> copy(firstIn, secondOut, first, second, closed));
        workers.execute(() -> copy(secondIn, firstOut, first, second, closed));
    }

    private void copy(
            InputStream input,
            OutputStream output,
            Socket first,
            Socket second,
            AtomicBoolean closed) {
        byte[] buffer = new byte[16 * 1024];
        try {
            int count;
            while (running && (count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeSocket(first);
                closeSocket(second);
            }
        }
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int state = 0;
        while (output.size() < HEADER_LIMIT) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("Connection closed before proxy header completed");
            }
            output.write(value);
            switch (state) {
                case 0:
                    state = value == '\r' ? 1 : 0;
                    break;
                case 1:
                    state = value == '\n' ? 2 : 0;
                    break;
                case 2:
                    state = value == '\r' ? 3 : 0;
                    break;
                case 3:
                    if (value == '\n') {
                        return output.toByteArray();
                    }
                    state = 0;
                    break;
                default:
                    state = 0;
            }
        }
        throw new IOException("Proxy request header exceeds " + HEADER_LIMIT + " bytes");
    }

    private static void sendBadGateway(Socket client) {
        if (client == null || client.isClosed()) {
            return;
        }
        try {
            client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException ignored) {
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        openSockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            listener = null;
        }
        List<Socket> sockets;
        synchronized (openSockets) {
            sockets = new ArrayList<>(openSockets);
            openSockets.clear();
        }
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
    }

    public static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return "";
        }
        try {
            if (candidate.contains("://")) {
                URI uri = URI.create(candidate);
                return uri.getHost() == null ? "" : uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        if (candidate.startsWith("[") && candidate.contains("]")) {
            return candidate.substring(1, candidate.indexOf(']'));
        }
        int colon = candidate.lastIndexOf(':');
        if (colon > 0 && candidate.indexOf(':') == colon) {
            candidate = candidate.substring(0, colon);
        }
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(0, slash);
        }
        return candidate.trim();
    }

    public static int validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        }
        return port;
    }

    private static final class ProxyRequest {
        final boolean connect;
        final Target target;

        ProxyRequest(boolean connect, Target target) {
            this.connect = connect;
            this.target = target;
        }

        static ProxyRequest parse(byte[] headerBytes) throws IOException {
            String header = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\\r\\n");
            if (lines.length == 0) {
                throw new IOException("Empty proxy request");
            }
            String[] requestLine = lines[0].split(" ", 3);
            if (requestLine.length < 2) {
                throw new IOException("Malformed proxy request line");
            }
            boolean connect = "CONNECT".equalsIgnoreCase(requestLine[0]);
            if (connect) {
                return new ProxyRequest(true, Target.parse(requestLine[1], 443));
            }

            try {
                URI uri = URI.create(requestLine[1]);
                if (uri.getHost() != null) {
                    int port = uri.getPort() >= 0 ? uri.getPort()
                            : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
                    return new ProxyRequest(false, new Target(uri.getHost(), port));
                }
            } catch (IllegalArgumentException ignored) {
            }

            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                    return new ProxyRequest(false, Target.parse(line.substring(5).trim(), 80));
                }
            }
            throw new IOException("Proxy request has no destination host");
        }
    }

    private static final class Target {
        final String host;
        final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = validatePort(port);
        }

        static Target parse(String authority, int defaultPort) throws IOException {
            String host = authority;
            int port = defaultPort;
            try {
                if (authority.startsWith("[")) {
                    int end = authority.indexOf(']');
                    if (end < 0) {
                        throw new IOException("Malformed IPv6 authority");
                    }
                    host = authority.substring(1, end);
                    if (authority.length() > end + 1 && authority.charAt(end + 1) == ':') {
                        port = Integer.parseInt(authority.substring(end + 2));
                    }
                } else {
                    int colon = authority.lastIndexOf(':');
                    if (colon > 0 && authority.indexOf(':') == colon) {
                        host = authority.substring(0, colon);
                        port = Integer.parseInt(authority.substring(colon + 1));
                    }
                }
                return new Target(host, port);
            } catch (IllegalArgumentException error) {
                throw new IOException("Malformed proxy destination: " + authority, error);
            }
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
