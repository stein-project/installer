/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The Stein registry (Stein-Project/registry, served at {@link Urls#REGISTRY}): the files the
 * project publishes for its programs to read at run time. The installer reads {@code stein.json}
 * and the official mods' indexes ({@code mods/<id>.json}) from it.
 *
 * <p>Every file comes with an ed25519 signature next to it ({@code <file>.sig}, the base64 of the
 * raw signature over the file's exact bytes) and is only believed when it matches {@link
 * #PUBLIC_KEY}: the registry says which mods go into the player's folder, and a hijacked account or
 * a tampered copy on the way must not be able to. Java only has ed25519 from 15 on; on an older
 * Java the check fails like a bad signature and the caller falls back to the installer's own
 * release file, which is what this installer read before the registry existed.
 */
final class Registry {

    /** The registry's public key (ed25519, the raw 32 bytes in base64). */
    static final String PUBLIC_KEY = "exxXasq5SsodR84fDi9gVEs3oRfXXaPVgTn/eK47TUc=";

    /** The DER header of an ed25519 SubjectPublicKeyInfo, before the raw key. */
    private static final byte[] SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Registry() {}

    /** Is this address a file of the registry (and so must come signed)? */
    static boolean isRegistry(String url) {
        return url.startsWith(Urls.REGISTRY);
    }

    /**
     * Downloads a registry file and its signature; throws when either does not arrive or the
     * signature does not match.
     */
    static byte[] fetchSigned(String url) throws IOException {
        byte[] body = Mods.download(url, null);
        byte[] sig = Mods.download(url + ".sig", null);
        if (!verify(body, new String(sig, StandardCharsets.US_ASCII))) {
            throw new IOException("no valid signature at " + url);
        }
        return body;
    }

    /** Does the signature (base64 of the raw ed25519 signature) match the bytes and our key? */
    static boolean verify(byte[] data, String signature) {
        try {
            byte[] raw = Base64.getDecoder().decode(PUBLIC_KEY);
            byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
            System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
            PublicKey key =
                    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (Exception e) {
            // Includes NoSuchAlgorithmException on Java 8 to 14: treated as not verified.
            return false;
        }
    }
}
