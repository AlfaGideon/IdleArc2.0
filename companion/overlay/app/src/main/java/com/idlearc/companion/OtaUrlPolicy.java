package com.idlearc.companion;

import java.net.URI;
import java.util.Locale;

/** OTA requests must never reach arbitrary hosts or local services, even through redirects. */
final class OtaUrlPolicy {
    private OtaUrlPolicy() {}

    static boolean isAllowed(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("api.github.com") || host.equals("github.com")
                    || host.equals("raw.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("github-production-release-asset-2e65be.s3.amazonaws.com");
        } catch (Exception e) {
            return false;
        }
    }
}
