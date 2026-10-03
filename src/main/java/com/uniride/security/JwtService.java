package com.uniride.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    @Value("${uniride.jwt.secret}")
    private String secreto;

    @Value("${uniride.jwt.expiracion-ms}")
    private long expiracionMs;

    private SecretKey llave() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secreto));
    }

    public String generarToken(String correoInstitucional) {
        long ahora = System.currentTimeMillis();
        return Jwts.builder()
                .subject(correoInstitucional)
                .issuedAt(new Date(ahora))
                .expiration(new Date(ahora + expiracionMs))
                .signWith(llave())
                .compact();
    }

    public String extraerCorreo(String token) {
        return Jwts.parser()
                .verifyWith(llave())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
