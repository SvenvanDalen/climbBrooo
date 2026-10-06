package nl.paree.climbpro.testsupport;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * Makes every HTTP connection in a test fail at once: the default proxy selector returns a
 * proxy on a closed local port, so OkHttp and HttpURLConnection get "connection refused"
 * instead of reaching a real API.
 */
public final class NoNetwork {

    private static ProxySelector previous;

    private NoNetwork() {}

    public static synchronized void install() {
        if (previous != null) return;
        previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return Collections.singletonList(new Proxy(Proxy.Type.HTTP,
                        new java.net.InetSocketAddress("127.0.0.1", 9)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // Expected: nothing listens there.
            }
        });
    }

    public static synchronized void uninstall() {
        if (previous == null) return;
        ProxySelector.setDefault(previous);
        previous = null;
    }
}
