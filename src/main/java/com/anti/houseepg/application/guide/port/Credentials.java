package com.anti.houseepg.application.guide.port;

/** How to authenticate against a source location, if at all. */
public record Credentials(String username, String password, String headerName, String headerValue) {

    public static Credentials none() {
        return new Credentials(null, null, null, null);
    }

    public boolean hasBasicAuth() {
        return username != null && !username.isBlank();
    }

    public boolean hasHeader() {
        return headerName != null && !headerName.isBlank();
    }
}
