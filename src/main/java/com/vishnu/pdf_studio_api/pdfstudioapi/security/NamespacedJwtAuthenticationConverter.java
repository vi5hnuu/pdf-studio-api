package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;

/**
 * Builds the authentication whose name is the <b>namespaced</b> user id, {@code alias:sub}.
 *
 * <p>Two auth instances mint user ids independently, so the same {@code sub} can exist in both.
 * Keying credit accounts on {@code sub} alone would merge two unrelated people's balances the first
 * time those ids happened to coincide. Prefixing with the issuer's alias makes that impossible.
 *
 * <p>Doing it here — rather than at each call site — means every consumer of
 * {@link org.springframework.security.core.Authentication#getName()}, including
 * {@link CurrentUser}, gets the namespaced value automatically and none of them can forget.
 */
public class NamespacedJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String alias;
    private final JwtGrantedAuthoritiesConverter authoritiesConverter;

    public NamespacedJwtAuthenticationConverter(String alias) {
        this.alias = alias;
        this.authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        this.authoritiesConverter.setAuthoritiesClaimName("roles");
        this.authoritiesConverter.setAuthorityPrefix(""); // roles already carry the ROLE_ prefix
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
        return new JwtAuthenticationToken(jwt, authorities, PrincipalKey.of(alias, jwt.getSubject()));
    }
}
